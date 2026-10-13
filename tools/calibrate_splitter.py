#!/usr/bin/env python3
"""Calibrate an on-device character budget against Kokoro's real phoneme-token count.

Why this exists
---------------
Kokoro's hard input limit is **510 phoneme tokens**, not characters:

    kokoro/pipeline.py:274   if len(tokens) > 510:
    kokoro/pipeline.py:275       raise ValueError(f'Phoneme string too long: {len(tokens)} > 510')

and the server-side token count is literally the length of the IPA phoneme
string (Kokoro-FastAPI `vocabulary.tokenize()` maps one vocab char -> one id).

An Android client cannot run misaki/espeak G2P, so it must *estimate* tokens
from characters. A guess is not good enough: too high and the server truncates
mid-word (the garble failure); too low and latency and prosody suffer.

This script measures the true distribution of phoneme-tokens per character so
the client budget can be derived from measurement instead of guessed.

Usage
-----
    .tooling/kokoro-venv/bin/python tools/calibrate_splitter.py \
        --corpus data/corpus_pride_prejudice.txt --out docs/research/calibration.json
"""

from __future__ import annotations

import argparse
import json
import random
import re
import statistics
import sys
from pathlib import Path

# ---------------------------------------------------------------- corpus prep

GUTENBERG_START = re.compile(r"\*\*\* START OF (?:THE|THIS) PROJECT GUTENBERG.*?\*\*\*", re.S)
GUTENBERG_END = re.compile(r"\*\*\* END OF (?:THE|THIS) PROJECT GUTENBERG.*?\*\*\*", re.S)

# Adversarial-but-realistic Android TTS inputs. These are the ones that break a
# naive character budget, because their phoneme expansion per character is far
# above prose. Kept short so the per-sample token counts stay comparable.
ADVERSARIAL = [
    # Numbers, currency, dates, times: digits are spelled out at length.
    "The invoice total was $1,234,567.89 on 2026-10-12 at 15:45:07 GMT.",
    "Call 1-800-555-0199 or 44-20-7946-0958 ext. 4823 before 11/12/2026.",
    "Serial 9X4-88712-A7, batch 0042, revision 2025.11.003 shipped.",
    "In 1897 the population was 1234567 and by 2025 it reached 98765432.",
    # Acronyms and initialisms phonemize into many letters.
    "The NASA JPL NOAA USGS FBI CIA NSA DHS DOD DOE HHS USDA VA SSA CMS API SDK.",
    "Use HTTPS TLS SSH TCP UDP IPv4 IPv6 DNS DHCP VPN NTP SMTP IMAP POP3 LDAP SSO.",
    # URLs and file paths: every slash and dot is a spoken boundary.
    "Visit https://example.com/a/b/c?x=1&y=2#frag or ftp://files.example.org/pub/x.tar.gz",
    "Open C:/Users/name/AppData/Local/Temp/file_2026_10_12_final_v3.zip now.",
    # Symbols and math expand enormously.
    "Solve x^2 + 3y <= 42 && (a != b) || c >= 10% per unit ~ approx 3.14159.",
    # Hashtags, handles, code identifiers (camelCase / snake_case).
    "@user123 check #Release2026 with getInputStreamFromNetworkAsync() and my_var_name_x.",
    # Ordinals, units, measurements.
    "It measured 12.5mm x 3.75cm x 0.002km and weighed 1,200.5kg at 98.6F.",
    "The 1st, 2nd, 3rd, 4th, 21st, 22nd, 23rd and 100th items cost $0.99 each.",
    # No terminal punctuation at all: a wall the splitter cannot break cleanly.
    "the quick brown fox jumps over the lazy dog and then keeps running without any pause "
    "because there is no punctuation here at all so the splitter must fall back to words",
    # CJK: no spaces, each glyph is roughly a syllable.
    "这是一个用于测试语音合成系统的很长的中文句子它包含了很多汉字用来验证分词逻辑是否正确",
    "日本語のテキストを音声合成システムで読み上げるためのテスト文です区切りがないため注意が必要です",
]


def load_corpus(path: Path) -> str:
    text = path.read_text(encoding="utf-8", errors="replace")
    text = GUTENBERG_START.sub(" ", text)
    text = GUTENBERG_END.sub(" ", text)
    # Collapse the hard-wrapped Gutenberg line breaks into real paragraphs so the
    # sentence splitter sees prose, not 70-column wrapping.
    paragraphs = []
    for block in re.split(r"\n\s*\n", text):
        joined = " ".join(line.strip() for line in block.splitlines() if line.strip())
        if joined:
            paragraphs.append(joined)
    return "\n\n".join(paragraphs)


SENTENCE_END = re.compile(r"(?<=[.!?…])[\"'”’)\]]*\s+")


def build_spans(text: str, budget: int, limit: int, rng: random.Random) -> list[str]:
    """Split prose the way the real client would: greedily pack sentences to `budget` chars.

    This is deliberately the *same* shape the production splitter uses, so the
    measured token counts describe the spans that will actually be sent.
    """
    sentences = [s for s in SENTENCE_END.split(text) if s.strip()]
    spans: list[str] = []
    current = ""
    for sentence in sentences:
        if len(sentence) > budget:
            # Oversized sentence: hard-split it at word boundaries so it still
            # conforms to the budget under test.
            if current:
                spans.append(current.strip())
                current = ""
            words = sentence.split()
            piece = ""
            for word in words:
                if len(piece) + len(word) + 1 > budget:
                    if piece:
                        spans.append(piece.strip())
                    piece = word
                else:
                    piece = f"{piece} {word}".strip()
            if piece:
                spans.append(piece.strip())
            continue
        if len(current) + len(sentence) + 1 > budget:
            if current:
                spans.append(current.strip())
            current = sentence
        else:
            current = f"{current} {sentence}".strip()
    if current.strip():
        spans.append(current.strip())
    rng.shuffle(spans)
    return spans[:limit]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--limit", type=int, default=400, help="spans sampled per budget")
    ap.add_argument("--seed", type=int, default=20261012)
    ap.add_argument("--budgets", type=int, nargs="+",
                    default=[150, 200, 250, 300, 350, 400, 450, 500])
    args = ap.parse_args()

    from kokoro import KPipeline  # imported late so --help works without the stack

    print("loading G2P (no model weights)...", flush=True)
    pipeline = KPipeline(lang_code="a", model=False)

    def tokens_of(text: str) -> int:
        """Exact server-side token count for `text`.

        KPipeline.g2p returns misaki MTokens; tokens_to_ps() is the same
        concatenation en_tokenize() measures against the 510 limit.
        """
        ps, tokens = pipeline.g2p(text)
        return len(KPipeline.tokens_to_ps(tokens))

    corpus = load_corpus(args.corpus)
    rng = random.Random(args.seed)
    report: dict = {
        "corpus": str(args.corpus),
        "corpus_chars": len(corpus),
        "seed": args.seed,
        "hard_limit_tokens": 510,
        "server_absolute_max_tokens": 450,
        "server_target_min_tokens": 175,
        "server_target_max_tokens": 250,
        "budgets": {},
        "adversarial": [],
    }

    # ---- prose: what character budget keeps us inside each token ceiling?
    for budget in args.budgets:
        spans = build_spans(corpus, budget, args.limit, rng)
        rows = []
        for span in spans:
            n = len(span)
            if n < 20:
                continue
            try:
                t = tokens_of(span)
            except Exception as exc:  # noqa: BLE001 - record, never silently drop
                rows.append({"chars": n, "tokens": None, "error": str(exc)[:120]})
                continue
            rows.append({"chars": n, "tokens": t, "ratio": t / n})
        ok = [r for r in rows if r.get("tokens")]
        toks = sorted(r["tokens"] for r in ok)
        ratios = sorted(r["ratio"] for r in ok)

        def pct(vals: list[float], p: float) -> float:
            if not vals:
                return float("nan")
            k = min(len(vals) - 1, max(0, int(round((p / 100) * (len(vals) - 1)))))
            return vals[k]

        entry = {
            "n_spans": len(ok),
            "chars_median": statistics.median([r["chars"] for r in ok]) if ok else None,
            "tokens_min": toks[0] if toks else None,
            "tokens_p50": pct(toks, 50),
            "tokens_p95": pct(toks, 95),
            "tokens_p99": pct(toks, 99),
            "tokens_max": toks[-1] if toks else None,
            "ratio_tokens_per_char_p50": pct(ratios, 50),
            "ratio_tokens_per_char_p95": pct(ratios, 95),
            "ratio_tokens_per_char_max": ratios[-1] if ratios else None,
            "spans_over_450": sum(1 for r in ok if r["tokens"] > 450),
            "spans_over_510": sum(1 for r in ok if r["tokens"] > 510),
            "errors": len(rows) - len(ok),
        }
        report["budgets"][str(budget)] = entry
        print(
            f"budget={budget:>4}  n={entry['n_spans']:>4}  "
            f"tok p50={entry['tokens_p50']:>6.1f} p95={entry['tokens_p95']:>6.1f} "
            f"p99={entry['tokens_p99']:>6.1f} max={entry['tokens_max']:>6}  "
            f"over450={entry['spans_over_450']:>3} over510={entry['spans_over_510']:>3}  "
            f"tok/char p50={entry['ratio_tokens_per_char_p50']:.3f} "
            f"max={entry['ratio_tokens_per_char_max']:.3f}",
            flush=True,
        )

    # ---- adversarial: the inputs that blow a character budget
    print("\nadversarial samples:", flush=True)
    for sample in ADVERSARIAL:
        n = len(sample)
        try:
            t = tokens_of(sample)
        except Exception as exc:  # noqa: BLE001
            report["adversarial"].append({"chars": n, "tokens": None,
                                          "text": sample[:70], "error": str(exc)[:120]})
            continue
        rec = {"chars": n, "tokens": t, "ratio": t / n, "text": sample[:70]}
        report["adversarial"].append(rec)
        print(f"  chars={n:>4} tokens={t:>5} ratio={t / n:.3f}  {sample[:52]!r}", flush=True)

    adv = [r for r in report["adversarial"] if r.get("tokens")]
    if adv:
        worst = max(adv, key=lambda r: r["ratio"])
        report["adversarial_worst_ratio"] = worst["ratio"]
        report["adversarial_worst_text"] = worst["text"]
        report["adversarial_median_ratio"] = statistics.median(r["ratio"] for r in adv)
        print(f"\nworst adversarial ratio: {worst['ratio']:.3f} tokens/char "
              f"({worst['text']!r})")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
