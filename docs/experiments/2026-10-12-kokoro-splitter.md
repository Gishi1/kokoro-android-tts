# kokoro-splitter — does a Kokoro-token-aware splitter eliminate over-limit requests?

Experiment date: 2026-10-12
Repo: `<repo>`
Base commit: `a4d917a` on `master`
Experiment branch: `expt/kokoro-splitter-261012`
Isolation: in-place on `expt/kokoro-splitter-261012` (new repo, created this session)

## Hypothesis

An Android TTS client that estimates Kokoro **phoneme-token** cost from text features
(rather than from character count) and splits on Kokoro's own prosodic waterfall will
drive the over-limit request rate from the naive baseline's rate down to **zero chunks
above 510 tokens**, while keeping the median chunk inside Kokoro-FastAPI's 175–250
token target band.

Falsifiable form: *if the calibrated feature model is not better than a fixed character
budget, the over-limit rate of the calibrated splitter will not be lower than the
baseline's.*

## Why the baseline is a naive splitter, not code from the base commit

The base commit `a4d917a` is the empty root commit of a brand-new repo — there is no
prior splitter to diff against. The honest baseline is therefore **what a client would
do without this research**: split on a fixed character budget. That is not a straw man;
it is exactly what upstream Kokoro does today — `kokoro/pipeline.py:400-427` chunks
non-English text at `chunk_size = 400` *characters*.

Baseline is implemented as `--mode chars` in `tools/splitter_conformance.py`, emitting
the same greedy sentence packing at a 400-character budget.

## Pass / fail rule (written before measuring)

- **Metric and unit.** Per emitted chunk, the *true* Kokoro phoneme-token count, measured
  by phonemizing the chunk text with `kokoro.KPipeline` (the same `tokens_to_ps()` value
  that `en_tokenize` measures against the 510 limit). Reported as:
  1. `over_limit_rate` — fraction of chunks with true tokens > 450 (Kokoro-FastAPI's
     `absolute_max_tokens` safety ceiling);
  2. `hard_fail_rate` — fraction of chunks with true tokens > 510 (the model's hard limit
     at `pipeline.py:274-275`);
  3. `median_tokens` — the median true token count per chunk.
- **Baseline command, exact input / seed / dataset.**
  `tools/splitter_conformance.py --mode chars --budget 400 --corpus data/corpus_pride_prejudice.txt --seed 20261012`
  Corpus: Project Gutenberg ebook 1342 (Pride and Prejudice), 738,046 bytes, plus the
  fixed adversarial sample set in `tools/calibrate_splitter.py`.
- **Pass rule (keep the branch).** `hard_fail_rate == 0` **and** `over_limit_rate <= 0.005`
  on the corpus, and `median_tokens` in `[175, 250]`.
- **Abandon rule (revert).** Any chunk with true tokens > 510 on the corpus or the
  adversarial set, or `median_tokens` outside `[100, 300]`.

Both branches run the same corpus, the same seed, and the same tokenizer. Cache state:
warm (G2P model already loaded; no model weights required since `model=False`).

## Baseline (base commit `a4d917a`)

TODO — >=3 runs, report the spread not the best.

## Prototype result

TODO — the same commands and the same inputs as the baseline, >=3 runs.

## Output equivalence

TODO — the splitter changes *how text is cut*, so this is a behaviour change by design.
The equivalence that matters is **semantic**: the concatenation of all chunk texts must
reproduce the input text (whitespace-normalised), i.e. no words are dropped and none are
duplicated. That is asserted by `tools/splitter_conformance.py --check-lossless`.

## Decision

TODO — keep or revert, decided by the pre-registered rule above.

## Not measured / open questions

TODO — the negatives, explicitly.
