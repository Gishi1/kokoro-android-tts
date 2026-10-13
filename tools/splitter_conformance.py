#!/usr/bin/env python3
"""Measure a splitter's chunks against real Kokoro ground truth.

This is the referee for the experiment in
docs/experiments/2026-10-12-kokoro-splitter.md. It does not trust any
implementation's own token estimate: it phonemizes every emitted chunk with the
real `kokoro.KPipeline` and counts the tokens the same way `en_tokenize` does
against the 510 limit.

Usage:
    .tooling/kokoro-venv/bin/python tools/splitter_conformance.py \
        --mode model --corpus data/corpus_mixed.txt --out docs/research/conformance-model.json
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

JAVA = "/nix/store/5badkg3gmzg1c29akwglknkizfg6zj0g-openjdk-17.0.17+8/lib/openjdk/bin/java"
CLASSES = "core/build/classes"

MODEL_HARD_LIMIT = 510        # kokoro/pipeline.py:274
SERVER_ABSOLUTE_MAX = 450     # Kokoro-FastAPI settings.absolute_max_tokens
TARGET_MIN = 240              # splitter default, in ESTIMATED tokens
TARGET_MAX = 360              # splitter default, in ESTIMATED tokens


def run_splitter(mode: str, corpora: list[Path], budget: int, hard_cap: int,
                 target_min: int, target_max: int) -> list[dict]:
    cmd = [JAVA, "-cp", CLASSES, "dev.kokoro.tts.SplitterCli",
           "--mode", mode, "--budget", str(budget),
           "--hard-cap", str(hard_cap),
           "--target-min", str(target_min), "--target-max", str(target_max)]
    for c in corpora:
        cmd += ["--corpus", str(c)]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"splitter failed ({proc.returncode}): {proc.stderr[:2000]}")
    chunks = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if line:
            chunks.append(json.loads(line))
    return chunks


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["model", "chars"], required=True)
    ap.add_argument("--corpus", type=Path, nargs="+", required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--budget", type=int, default=400, help="char budget for --mode chars")
    ap.add_argument("--hard-cap", type=int, default=360)
    ap.add_argument("--target-min", type=int, default=TARGET_MIN)
    ap.add_argument("--target-max", type=int, default=TARGET_MAX)
    ap.add_argument("--lang", default="a", help="Kokoro G2P language code to judge with")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--check-lossless", action="store_true")
    args = ap.parse_args()

    from kokoro import KPipeline

    print(f"loading G2P (lang={args.lang})...", flush=True)
    pipeline = KPipeline(lang_code=args.lang, model=False)

    def tokens_of(text: str) -> int:
        ps, tk = pipeline.g2p(text)
        if tk is None:
            # Non-English pipelines return the phoneme string directly.
            return len(ps.strip())
        return len(KPipeline.tokens_to_ps(tk))

    run_results = []
    chunks = []
    for run in range(args.runs):
        chunks = run_splitter(args.mode, args.corpus, args.budget, args.hard_cap,
                              args.target_min, args.target_max)
        scored = []
        for c in chunks:
            t = c["text"].strip()
            if not t:
                continue
            try:
                n = tokens_of(t)
            except Exception as exc:  # noqa: BLE001
                scored.append({**c, "true": None, "error": str(exc)[:120]})
                continue
            scored.append({**c, "true": n})
        ok = [s for s in scored if s.get("true") is not None]
        toks = [s["true"] for s in ok]
        run_results.append({
            "run": run,
            "n_chunks": len(ok),
            "over_450": sum(1 for t in toks if t > SERVER_ABSOLUTE_MAX),
            "over_510": sum(1 for t in toks if t > MODEL_HARD_LIMIT),
            "median_tokens": statistics.median(toks) if toks else None,
            "max_tokens": max(toks) if toks else None,
            "total_tokens": sum(toks),
        })
        print(f"  run {run}: chunks={run_results[-1]['n_chunks']} "
              f"over450={run_results[-1]['over_450']} over510={run_results[-1]['over_510']} "
              f"median={run_results[-1]['median_tokens']} max={run_results[-1]['max_tokens']}",
              flush=True)

    # The final run's detailed chunks are what we inspect.
    final = []
    for c in chunks:
        t = c["text"].strip()
        if not t:
            continue
        try:
            final.append({**c, "true": tokens_of(t)})
        except Exception as exc:  # noqa: BLE001
            final.append({**c, "true": None, "error": str(exc)[:120]})

    ok = [s for s in final if s.get("true") is not None]
    toks = [s["true"] for s in ok]
    n = len(toks)
    over450 = sum(1 for t in toks if t > SERVER_ABSOLUTE_MAX)
    over510 = sum(1 for t in toks if t > MODEL_HARD_LIMIT)

    lossless = None
    if args.check_lossless:
        lossless = check_lossless(args.corpus, final)

    worst = sorted(ok, key=lambda s: s["true"], reverse=True)[:8]
    summary = {
        "mode": args.mode,
        "lang": args.lang,
        "budget_chars": args.budget if args.mode == "chars" else None,
        "hard_cap": args.hard_cap,
        "target_min": args.target_min,
        "target_max": args.target_max,
        "corpora": [str(c) for c in args.corpus],
        "runs": run_results,
        # Compare everything except the run index itself, which naturally differs.
        "deterministic_across_runs": all(
            {k: v for k, v in r.items() if k != "run"}
            == {k: v for k, v in run_results[0].items() if k != "run"}
            for r in run_results) if run_results else None,
        "n_chunks": n,
        "over_limit_rate_450": over450 / n if n else None,
        "hard_fail_rate_510": over510 / n if n else None,
        "median_tokens": statistics.median(toks) if toks else None,
        "mean_tokens": statistics.fmean(toks) if toks else None,
        "max_tokens": max(toks) if toks else None,
        "p95_tokens": sorted(toks)[int(0.95 * (n - 1))] if n else None,
        "total_tokens": sum(toks),
        "lossless": lossless,
        "worst_chunks": [{"true": s["true"], "est": s["est"],
                          "text": s["text"][:100]} for s in worst],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(summary, indent=2, ensure_ascii=False))

    print(f"\nmode={args.mode} chunks={n} over450={over450} over510={over510}")
    print(f"  over_limit_rate={summary['over_limit_rate_450']:.4f} "
          f"hard_fail_rate={summary['hard_fail_rate_510']:.4f}")
    print(f"  median={summary['median_tokens']} p95={summary['p95_tokens']} "
          f"max={summary['max_tokens']}")
    if lossless is not None:
        print(f"  lossless={lossless}")
    print(f"wrote {args.out}")
    return 0


def check_lossless(corpora: list[Path], chunks: list[dict]) -> dict:
    """No characters may be invented or dropped between input and chunks.

    Whitespace is ignored: the splitter is allowed to re-space and to cut inside
    a CJK run, but it may not lose or duplicate content.
    """
    src = []
    for path in corpora:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                src.append(line)
    src_join = "".join(re.sub(r"\s+", "", s) for s in src)
    out_join = "".join(re.sub(r"\s+", "", c["text"]) for c in chunks)
    return {
        "input_chars": len(src_join),
        "output_chars": len(out_join),
        "identical": src_join == out_join,
        "first_diff": next(
            (i for i, (a, b) in enumerate(zip(src_join, out_join)) if a != b), None),
    }


if __name__ == "__main__":
    sys.exit(main())
