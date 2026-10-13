# Does ao3-reader's existing narration chunker protect Kokoro's 510-token limit?

Research date: **2026-10-12**. Target: `<ao3-reader>` at its working
tree state of 2026-10-12 (read-only; nothing in that repository was modified), scored
against `hexgrad/kokoro` G2P as installed in
`<repo>/.tooling/kokoro-venv`.

**Marker legend.** `[M]` verified against the artefact in this session · `[DOC]` upstream
documentation/source · `[O]` opinion/field report.

**Access honesty.** ao3-reader is a local checkout, so its source is a primary artefact and
was read directly. Its chunker was not re-implemented: `tools/ao3_chunker_harness.ts`
imports `web/src/lib/narration/chunker.ts` **by absolute path via `tsx`** and runs the real
exported functions, so no transcription error is possible. The scoring uses the same
referee as this project's own splitter (`tools/splitter_conformance.py`), phonemising every
emitted chunk with the real `kokoro.KPipeline`. No web source is cited in this report, so
no web route was needed; `web_fetch` was never called.

Source of the chunker's parameters: `web/src/views/Reader.svelte:237` applies
`mergeChunks(p, { max: 300, min: 100 })` for the MSE narration variant — those are the
parameters reproduced here. `[M]`

**Reproduce (read-only; nothing is written into ao3-reader):**

```sh
TSX=<ao3-reader>/node_modules/.bin/tsx   # its own tsx, its own module
$TSX tools/ao3_chunker_harness.ts --corpus data/corpus_mixed.txt    --mode lines > .tooling/ao3/mixed.jsonl
$TSX tools/ao3_chunker_harness.ts --corpus data/corpus_longform.txt --mode paras > .tooling/ao3/longform.jsonl

.tooling/kokoro-venv/bin/python tools/splitter_conformance.py \
    --corpus data/corpus_longform.txt --chunks-jsonl .tooling/ao3/longform.jsonl \
    --label "ao3-reader mergeChunks(max=300,min=100)" \
    --out docs/research/conformance-ao3-longform.json --check-lossless
```

`--chunks-jsonl` was added to the referee for exactly this purpose: it scores chunks
produced by *any* implementation, so a foreign chunker is judged by the same G2P and the
same thresholds rather than by a bespoke script. `[M]`

---

## 1. Short answer

**No.** For the *character* budget it sets out to enforce, and for the token limit that
Kokoro actually imposes, ao3-reader's chunker overruns:

| corpus | chunks | over **450** tokens | over **510** tokens | max tokens | median tokens |
| --- | ---: | ---: | ---: | ---: | ---: |
| `data/corpus_mixed.txt` (41 utterances) | 41 | 3 (7.32 %) | **3 (7.32 %)** | **672** | 151 |
| `data/corpus_longform.txt` (316 paragraphs, 336 k chars) | 1,380 | 26 (1.88 %) | **10 (0.72 %)** | **871** | 247 |

`[M]` Raw results: `docs/research/conformance-ao3-mixed.json`,
`docs/research/conformance-ao3-longform.json`. Both runs are lossless (input and output
identical after whitespace removal), so this is purely a sizing problem, not a text-loss
problem.

This is **not** a criticism of the chunker's design intent. It is very well suited to the
job it was written for (§4), and its median of 247 true tokens on English prose sits
squarely inside Kokoro-FastAPI's 175–250 target band. The gap is specific and fixable (§5).

## 2. There are two independent failure paths, and both come from budgeting in characters

**Path A — an oversized single sentence is never split.** `mergeChunks` merges sentences up
to `max` but has no branch that *splits* one: an input sentence longer than the budget is
emitted whole. Verified directly:

```
input: 183-char single sentence
mergeChunks(chunkText(text), { max: 300, min: 100 })
-> 1 chunk of 183 chars
```

On the long-form corpus this is not a corner case: **168 of 1,380 chunks (12.17 %) exceed
the chunker's own 300-character budget**, the longest at 890 characters. Since English prose
costs ~0.98 tokens/char (measured here: 890 chars → 871 tokens), those become the ten
>510-token requests, up to 871 tokens — 71 % over the model's hard limit. `[M]`

Every one of the ten failures is of this shape:

| true tokens | chars | text |
| ---: | ---: | --- |
| 871 | 890 | `Bingley, when questioned by Jane, had long ago asserted his blamelessness…` |
| 660 | 677 | `After discharging his conscience on that head, he proceeded to inform them…` |
| 629 | 634 | `His present pursuit could not make him forget that Elizabeth had been…` |
| 596 | 610 | `Denny, to whom Lydia eagerly applied, and who told them that Wickham…` |
| 582 | 589 | `Darcy was expected there in the course of a few weeks; and though…` |

**Path B — text with no ASCII terminator is one indivisible chunk.** `chunkText` splits on
`.`, `!`, `?` only, so CJK text is a single sentence by construction. The three mixed-corpus
failures are 42–47 characters — tiny against a 300-character budget, and therefore never
even considered for splitting — but 545, 584 and 672 phoneme tokens. `[M]`

Both paths are the same root cause: **the budget is denominated in characters, and Kokoro's
limit is denominated in phoneme tokens.** Measured tokens/char on this machine: English
prose ~1.0, money/dates ~3.5, CJK through the English pipeline ~13.

## 3. It is not enough to be "usually right"

On English long-form prose the chunker is *usually* correct — 99.28 % of its chunks are
within the limit — and its median is better calibrated than a naive fixed splitter. That is
exactly why the failure is easy to miss: it shows up on the long sentences and the
non-Latin scripts, not on a typical page.

For comparison, on the same corpora, measured by the same referee: `[M]`

| splitter (long-form corpus) | over 510 | max tokens | median |
| --- | ---: | ---: | ---: |
| ao3-reader `mergeChunks(max=300)` | 10 (0.72 %) | 871 | 247 |
| naive fixed 400-character splitter | 0 | 416 | 303 |
| this project's token-aware splitter | **0** | 284 | 194 |

| splitter (mixed corpus) | over 510 | max tokens | median |
| --- | ---: | ---: | ---: |
| ao3-reader `mergeChunks(max=300)` | 3 (7.32 %) | 672 | 151 |
| naive fixed 400-character splitter | 3 (0.256 %) | 672 | 68 |
| this project's token-aware splitter | **0** | 330 | 68 |

Note the second row: on long-form prose the *naive* 400-character splitter is safer than
ao3-reader's 300-character one, purely because a larger character budget happens to cover
more English sentences and it has a word-level fallback. Character budget size is not the
lever; the fallback for an oversized unit is.

## 4. What ao3-reader gets right, and what this project should copy

Recorded because two of these are things this project does *not* do, and one is independent
confirmation of a design choice made separately. `[M]` for the code, `[O]` where quoted.

1. **Pace-uniformity as the stated rationale.** *"Kokoro's pace fluctuates on small inputs,
   so MSE playback merges sentences into ~300-char chunks per paragraph"*
   (`Reader.svelte:222`), with a measurement behind it: *"~3.5 chars/s for 'No.' vs ~17 for
   long text"* (`chunker.ts:39-42`). This is a **second, independent axis** from the token
   limit, and this project does not model it at all. The convergence is worth noting: 300
   characters of English prose ≈ 300 tokens ≈ the 175–250-token band this project targets,
   reached from a completely different starting point.
2. **Never merge across paragraphs**, and mark each paragraph's final chunk
   (`paraEnd`) so narration inserts a 3× longer pause (`Reader.svelte:43, 263-265`). This
   project always treats a paragraph boundary as a chunk boundary, but does **not**
   distinguish pause length at a paragraph end from a mid-paragraph seam.
3. **An explicit silence asset** (`/silence-120ms.mp3`, repeated `gapRepeats` times) rather
   than relying on the TTS output's own trailing silence. This project inserts 90 ms of raw
   zeros at mid-sentence seams only — a default, not a measurement.
4. **Offsets that point back into the source text** (`start`/`end` per chunk), which makes
   highlighting exact. Not needed by a system TTS engine, which receives already-plain text.

## 5. The concrete fix, if it is wanted

The chunker does not need to be replaced — it needs one guard in front of the request. The
minimal change that closes both paths, in the terms the existing code already uses:

* After `mergeChunks`, walk the result and **split any chunk whose estimated token cost
  exceeds the cap**, first at `,;:—` clause boundaries, then at whitespace, then (only for
  unspaced scripts) inside the run. That is the fallback `mergeChunks` is missing.
* Estimate cost as **tokens, not characters**. A linear model over per-character-class
  counts is cheap; `docs/research/token_model.json` and `core/src/main/java/dev/kokoro/tts/KokoroCostModel.java`
  in this repository are a fitted, ready-made one (R² 0.949, one under-prediction in 11,083
  held-out samples).
* Keep `max: 300` for the pace reason — it is well calibrated — and treat the token cap as a
  separate, hard ceiling above it.

Note that ao3-reader requests Kokoro without `response_format`, so it receives MP3 and needs
`web/src/lib/narration/mpegSanitize.ts`; requesting `response_format: "pcm"` returns raw
signed 16-bit 24 kHz mono and removes the container parsing entirely. That is a separate
simplification, not a correctness issue.

## Licence table

| artefact | version / commit | licence | licence file actually seen? | constraint |
| --- | --- | --- | --- | --- |
| `<ao3-reader>` (chunker measured here) | working tree, 2026-10-12 | not re-checked in this report | **no** — see note | quoted code is read-only reference; no code was copied from it |
| `hexgrad/kokoro` (G2P used as referee) | `main`, 2026-10-12 | Apache-2.0 | **yes** — `LICENSE`, 11,357 bytes, in `docs/research/raw/kokoro-limits/` | attribution + NOTICE retention |
| `hexgrad/Kokoro-82M` (weights) | card `lastModified` 2025-04-10 | Apache-2.0 | **yes** — model card + HF API `cardData.license` | none beyond attribution |
| Project Gutenberg ebook 1342 (long-form corpus) | fetched 2026-10-12 | Public domain (US) | n/a — Gutenberg header present in file | header/footer stripped before use |

The ao3-reader licence was **not** re-checked here because nothing from it is redistributed
or copied: `tools/ao3_chunker_harness.ts` imports its module by path at run time. If any of
its logic is ever copied into this project, that licence must be read first.

## What to measure next

**Experiment.** Add the token-cap guard from §5 in front of `fetchTtsAudio` in
`web/src/lib/narration/` and re-run the same referee over the same two corpora.

* **Pass rule.** zero emitted chunks above 510 true phoneme tokens on both corpora, and
  median true tokens inside `[175, 250]` on the long-form corpus.
* **Abandon rule.** any chunk above 510 true tokens, or a median outside `[100, 300]`.
* **Secondary, and the more interesting one.** ao3-reader's own pace claim ("~3.5 chars/s
  for 'No.' vs ~17 for long text") is a measurable hypothesis this project has *not*
  tested: synthesise the chunk set and compute **characters per second of produced audio**
  per chunk, then compare the spread under `max: 300` against the spread under a
  token-equivalent budget. If pace uniformity is the real goal, that measurement — not the
  token count — decides the chunk size, and the token cap only bounds it from above.

**Expected cost.** The token scoring is ~10 minutes of CPU with cached ground truth
(`docs/research/g2p_cache.jsonl`); the pace measurement additionally needs one synthesis
pass over ~1,400 chunks.

## No sourced answer

* **No measurement of perceived audio quality on ao3-reader's output.** This report counts
  tokens and failures only. Whether its 247-token chunks sound better or worse than this
  project's 194-token chunks is unmeasured, and its own pace rationale suggests they may
  sound *better*.
* **The `max: 300, min: 100` values' provenance is not traced.** The comment cites a pace
  measurement but no data file or script for it was located in the repository in the time
  available, so the number is quoted, not verified.
* **The other ao3-reader voices/providers were not measured.** Only the Kokoro path was
  scored; whether the same chunk sizes are safe for the OpenAI, Puter or browser
  `speechSynthesis` paths is unsourced.
* **No claim is made about ao3-reader's non-narration uses of these chunks**, such as the
  display highlighting, which depends on `start`/`end` offsets this report did not exercise.
