#!/usr/bin/env python3
"""Fit a cheap, conservative, on-device Kokoro token-cost model.

The Android splitter cannot run misaki/espeak G2P, but it must never send a
chunk that Kokoro will truncate at 510 phoneme tokens. Character count is not a
usable proxy: measured 2026-10-12, English prose costs ~1.00 tokens/char while
CJK through the English pipeline costs ~13 and digit-dense text ~3.5.

Two design decisions matter, and both were forced by measurement:

1. **Per-class counts, not characters.** The cost model is linear over counts of
   lower/upper/digit/space/punct/symbol/CJK characters, plus counts of *numeric
   runs* and *acronym runs*, because spelling "1234567" costs far more than
   seven ordinary characters and the cost grows with the magnitude of the number.

2. **A length-dependent safety factor.** A single global multiplier must cover
   the worst sample in the corpus, which is a very short one; measured, that
   forced a 2.13x blanket factor and halved the intended chunk length. The ratio
   observed/predicted concentrates as text gets longer (law of large numbers),
   so the factor is modelled as `1 + A/sqrt(predicted)` with `A` calibrated on
   training samples only. Short texts still get a conservative factor, but the
   chunks actually emitted are long enough to be estimated tightly.

Ground truth is cached to disk so the model can be re-fitted without
re-phonemizing the corpus.

Usage:
    .tooling/kokoro-venv/bin/python tools/fit_token_model.py \
        --out docs/research/token_model.json \
        --cache docs/research/g2p_cache.jsonl
"""

from __future__ import annotations

import argparse
import json
import math
import random
import re
import sys
from pathlib import Path

try:
    import numpy as np
except ImportError:
    _require_venv("numpy")

# Feature order is part of the contract with the Java port (tools/gen_cost_model.py).
def _require_venv(what: str) -> None:
    """Turn an opaque ImportError into the command the reader should run.

    These tools need packages that live only in the project virtualenv. Running
    them with the system python3 gave a bare ModuleNotFoundError with no hint,
    which is a bad way to learn that a tool has a runtime dependency.
    """
    raise SystemExit(
        f"\nThis tool needs {what}, which lives in the project virtualenv.\n"
        f"Run it as:\n"
        f"  .tooling/kokoro-venv/bin/python {Path(__file__).relative_to(Path.cwd()) if Path.cwd() in Path(__file__).parents else __file__} ...\n")


FEATURES = [
    "const",        # intercept: fixed per-utterance overhead
    "n_lower",      # a-z
    "n_upper",      # A-Z
    "n_digit",      # 0-9
    "n_space",      # whitespace
    "n_punct",      # , . ! ? ; : ' " - ( ) [ ]
    "n_symbol",     # / \ @ # $ % ^ & * { } < > = + _ | ~ `
    "n_cjk",        # CJK ideographs, kana, hangul
    "n_other",      # everything else
    "n_number_run", # maximal runs of digits (with , and . inside)
    "n_upper_run",  # maximal runs of 2+ capitals (acronyms)
]

RE_LOWER = re.compile(r"[a-z]")
RE_UPPER = re.compile(r"[A-Z]")
RE_DIGIT = re.compile(r"[0-9]")
RE_SPACE = re.compile(r"\s")
RE_PUNCT = re.compile(r"[,\.!\?;:'\"\-\(\)\[\]]")
RE_SYMBOL = re.compile(r"[/\\@#\$%\^&\*\{\}<>=_\+~`\|]")
RE_CJK = re.compile(
    r"[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uac00-\ud7af]"
)
RE_NUMBER_RUN = re.compile(r"\d[\d,\.]*\d|\d")
RE_UPPER_RUN = re.compile(r"[A-Z]{2,}")

# The splitter never emits a chunk far below this, so calibration below it is
# not representative of production. Shorter texts keep the factor at this floor.
MIN_CALIBRATION_TOKENS = 80


def features(text: str) -> list:
    """Per-class counts. Must match KokoroCostModel.features() in Java."""
    counts = [
        len(RE_LOWER.findall(text)),
        len(RE_UPPER.findall(text)),
        len(RE_DIGIT.findall(text)),
        len(RE_SPACE.findall(text)),
        len(RE_PUNCT.findall(text)),
        len(RE_SYMBOL.findall(text)),
        len(RE_CJK.findall(text)),
    ]
    counts.append(max(0, len(text) - sum(counts)))
    counts.append(len(RE_NUMBER_RUN.findall(text)))
    counts.append(len(RE_UPPER_RUN.findall(text)))
    return [1.0] + [float(c) for c in counts]


def load_lines(path: Path) -> list:
    out = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            out.append(line)
    return out


SENT_SPLIT = re.compile(r"(?<=[.!?…])[\"'”’)\]]*\s+")


def make_samples(paths: list, rng: random.Random, per_line: int = 3) -> list:
    samples = []
    for path in paths:
        for line in load_lines(path):
            samples.append(line)
            pieces = [p for p in SENT_SPLIT.split(line) if p.strip()] or [line]
            for _ in range(per_line):
                i = rng.randrange(len(pieces))
                j = min(len(pieces), i + rng.randint(1, 4))
                span = " ".join(pieces[i:j]).strip()
                if len(span) >= 12:
                    samples.append(span)
            words = line.split()
            if len(words) > 8:
                for _ in range(per_line):
                    a = rng.randrange(len(words))
                    b = min(len(words), a + rng.randint(4, 40))
                    span = " ".join(words[a:b]).strip()
                    if len(span) >= 12:
                        samples.append(span)
    return samples


def load_cache(path: Path) -> dict:
    cache = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            cache[rec["t"]] = rec["n"]
    return cache


def save_cache(path: Path, cache: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for text, n in cache.items():
            fh.write(json.dumps({"t": text, "n": n}, ensure_ascii=False) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--cache", type=Path, default=Path("docs/research/g2p_cache.jsonl"))
    ap.add_argument("--corpus", type=Path, nargs="+", default=[
        Path("data/corpus_pride_prejudice.txt"), Path("data/corpus_mixed.txt")])
    ap.add_argument("--seed", type=int, default=20261012)
    ap.add_argument("--train-frac", type=float, default=0.7)
    ap.add_argument("--lang", default="a")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    samples = make_samples(args.corpus, rng)
    print(f"candidate samples: {len(samples)}", flush=True)

    cache = load_cache(args.cache)
    missing = [s for s in dict.fromkeys(samples) if s not in cache]
    print(f"cache hits: {len(cache)}  missing: {len(missing)}", flush=True)

    if missing:
        try:
            from kokoro import KPipeline
        except ImportError:
            _require_venv("kokoro")
        print(f"loading G2P (lang={args.lang}); phonemizing {len(missing)} new samples...",
              flush=True)
        pipeline = KPipeline(lang_code=args.lang, model=False)
        for i, s in enumerate(missing):
            try:
                ps, tk = pipeline.g2p(s)
                n = len(ps.strip()) if tk is None else len(KPipeline.tokens_to_ps(tk))
            except Exception:  # noqa: BLE001 - unphonemizable input is recorded as 0
                n = 0
            cache[s] = n
            if (i + 1) % 2000 == 0:
                print(f"  {i + 1}/{len(missing)}", flush=True)
                save_cache(args.cache, cache)
        save_cache(args.cache, cache)
        print(f"cache written: {args.cache}", flush=True)

    X, y, texts = [], [], []
    for s in dict.fromkeys(samples):
        n = cache.get(s, 0)
        if n <= 0:
            continue
        X.append(features(s))
        y.append(float(n))
        texts.append(s)
    X = np.asarray(X, dtype=float)
    y = np.asarray(y, dtype=float)
    print(f"usable samples: {len(y)}", flush=True)

    idx = np.arange(len(y))
    rng.shuffle(idx)
    cut = int(len(idx) * args.train_frac)
    tr, te = idx[:cut], idx[cut:]

    # Centred least-squares fit for the *shape* of the cost, then a length-aware
    # safety factor calibrated on the training split for *safety*.
    coef, *_ = np.linalg.lstsq(X[tr], y[tr], rcond=None)
    coef = np.clip(coef, 0.0, None)
    pred_tr = np.maximum(X[tr] @ coef, 1.0)
    ratio_tr = y[tr] / pred_tr

    calib = pred_tr >= MIN_CALIBRATION_TOKENS
    if calib.sum() < 50:
        calib = np.ones_like(pred_tr, dtype=bool)
    # A = max (r - 1) * sqrt(L)  =>  safety(L) = 1 + A/sqrt(L) >= r for all
    # training samples at their own L, by construction.
    A = float(np.max((ratio_tr[calib] - 1.0) * np.sqrt(pred_tr[calib])))
    A = max(A, 0.0)

    def safety_of(pred: np.ndarray) -> np.ndarray:
        return 1.0 + A / np.sqrt(np.maximum(pred, MIN_CALIBRATION_TOKENS))

    def estimate(Xm: np.ndarray) -> np.ndarray:
        p = np.maximum(Xm @ coef, 1.0)
        return p * safety_of(p)

    est_tr = estimate(X[tr])
    est_te = estimate(X[te])
    under_tr = y[tr] > est_tr
    under_te = y[te] > est_te

    # If a training sample still slips through (a very short one, where the
    # factor is floored), raise the floor until none do.
    iterations = 0
    while under_tr.any() and iterations < 60:
        extra = float(np.max(y[tr][under_tr] - est_tr[under_tr]))
        A += max(extra / math.sqrt(MIN_CALIBRATION_TOKENS), 0.05)
        est_tr = estimate(X[tr])
        est_te = estimate(X[te])
        under_tr = y[tr] > est_tr
        under_te = y[te] > est_te
        iterations += 1

    ss_res = float(np.sum((y[te] - X[te] @ coef) ** 2))
    ss_tot = float(np.sum((y[te] - y[te].mean()) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else float("nan")

    over = est_te / np.maximum(y[te], 1.0)
    # The number that actually bounds safety: how far the estimate can fall
    # *below* the truth. The chunk cap must absorb this ratio.
    under_ratio = float(np.max(y[te] / est_te))
    report = {
        "fitted": "2026-10-12",
        "features": FEATURES,
        "coefficients": {f: round(float(c), 6) for f, c in zip(FEATURES, coef)},
        "safety_A": A,
        "safety_min_tokens": MIN_CALIBRATION_TOKENS,
        "safety_formula": "1 + safety_A / sqrt(max(predicted, safety_min_tokens))",
        "n_samples": int(len(y)),
        "n_train": int(len(tr)),
        "n_test": int(len(te)),
        "r2_test_raw": r2,
        "train_underprediction_count": int(under_tr.sum()),
        "test_underprediction_count": int(under_te.sum()),
        "test_underprediction_rate": float(np.mean(under_te)),
        "test_max_underestimate_ratio": under_ratio,
        "recommended_cap_for_510": int(math.floor(510.0 / under_ratio)),
        "test_max_overestimate_ratio": float(np.max(over)),
        "test_median_overestimate_ratio": float(np.median(over)),
        "ground_truth_max_tokens": float(np.max(y)),
        "calibration_iterations": iterations,
    }

    print("\ncoefficients (tokens per unit):")
    for f, c in zip(FEATURES, coef):
        print(f"  {f:<14} {c:9.4f}")
    print(f"\nsafety A = {A:.4f}  ->  safety(L) = 1 + {A:.3f}/sqrt(max(L,{MIN_CALIBRATION_TOKENS}))")
    for L in (50, 100, 175, 250, 400, 510):
        print(f"  safety({L:>3}) = {1.0 + A / math.sqrt(max(L, MIN_CALIBRATION_TOKENS)):.3f}")
    print(f"\nR2 (test)                    : {r2:.4f}")
    print(f"train under-predictions      : {int(under_tr.sum())}")
    print(f"test  under-predictions      : {int(under_te.sum())} "
          f"({np.mean(under_te):.5f})")
    print(f"median over-estimate (test)  : {np.median(over):.3f}")
    print(f"max    over-estimate (test)  : {np.max(over):.3f}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
