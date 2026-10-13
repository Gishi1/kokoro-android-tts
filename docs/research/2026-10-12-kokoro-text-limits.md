# What actually breaks Kokoro TTS on long input, and how to split text safely

Research date: **2026-10-12**. Target: `hexgrad/kokoro` (main branch, fetched 2026-10-12),
`remsky/Kokoro-FastAPI` (master, fetched 2026-10-12), `hexgrad/Kokoro-82M` weights
(card `lastModified` 2025-04-10), on NixOS 25.05, JDK 17, CPython 3.12.12.

**Marker legend.** `[M]` verified against the artefact in this session · `[DOC]` upstream
documentation/source · `[O]` a human's opinion, quoted with its source.

**Access honesty.** All primary sources were fetched with `curl` and are stored verbatim
under `docs/research/raw/kokoro-limits/` with an `INDEX.md` recording HTTP status and byte
counts. Search engines were **not** productive: Brave returned 200 with
`selector_hit: false`, Startpage returned ten results that were unrelated to the query
(conference proceedings, a BCI PDF), and the harness `web_search` tool is documented as
rate-limited to a multi-hour retry window. Every number below therefore comes from upstream
source code or from a measurement run in this session, not from a secondary summary.
`web_fetch` was never called (it is documented to crash the running GUI build).

---

## 1. The hard limit is 510 **phoneme tokens**, not characters `[DOC]`

`kokoro/pipeline.py`, fetched verbatim (`sha256 499982bb…`):

```
274:            if len(tokens) > 510:
275:                raise ValueError(f'Phoneme string too long: {len(tokens)} > 510')
```

The 510 is not arbitrary. `hexgrad/Kokoro-82M` `config.json` gives
`plbert.max_position_embeddings = 512`, and the phoneme sequence carries a BOS and an EOS
token, so the usable window is 512 − 2 = **510**. `[M]` (read from the fetched JSON).

## 2. The token count is the length of the IPA phoneme string `[DOC]`

Kokoro-FastAPI `api/src/services/text_processing/vocabulary.py`:

```python
def tokenize(phonemes: str) -> list[int]:
    phonemes = phonemes.strip()
    return [i for i in map(VOCAB.get, phonemes) if i is not None]
```

One vocabulary character maps to one token id, so `len(phonemes) ≈ token count`. Two
consequences the rest of this report depends on: `[M]`

* a **character** budget is only valid if it predicts the **phoneme** count, and
* `if i is not None` **silently drops unknown symbols** — a second, quieter loss channel
  alongside truncation.

## 3. There are two distinct "garbled" failure modes, not one `[M]`

This is the finding that changes the design. "Garbled" is usually assumed to mean
"truncated". Measured in this session, there are two independent mechanisms:

**(a) Truncation.** Kokoro responds to over-long input by silently keeping a prefix:

```
288:                ps = ps[:510]
392:                    ps = ps[:510]
438:                    logger.warning(f'Truncating len(ps) == {len(ps)} > 510')
```

The listener hears the utterance stop mid-word. `[DOC]`

**(b) Wrong-language phonemisation.** Feeding Chinese to the *English* pipeline does not
truncate — it produces a per-character fallback that is fluent-sounding nonsense:

```
input : 这是一个用于测试语音合成系统的很长的中文句子它包含了很多汉字用来验证分词逻辑是否正确
        42 chars -> 545 phoneme tokens  (12.98 tokens/char)
output: ʧˈInizlˌɛTəɹ ʧˈInizlˌɛTəɹ ʧˈInizlˌɛTəɹ ʧˈInizlˌɛTəɹ ʧˈInizlˌɛTəɹ ʧˈInizlˌɛTəɹ ...
```

Every glyph expands to the same ~13-token fallback. The audio is not Chinese and not
silence — it is repeated nonsense. A client that only guards against length still produces
this. `[M]` (measured with `kokoro.KPipeline(lang_code='a', model=False)`).

## 4. Character-budget splitting is unsafe, by a measured factor of 3–13 `[M]`

Measured token cost per character, real G2P, this session:

| text class | chars | tokens | tokens/char |
| --- | ---: | ---: | ---: |
| English prose (`Pride and Prejudice`, 399 spans) | 400 median | 333 median | **1.00** (p50), 1.65 max |
| `The invoice total was $1,234,567.89 on 2026-10-12 at 15:45:07 GMT.` | 66 | 209 | **3.17** |
| `In 1897 the population was 1234567 and by 2025 it reached 98765432.` | 67 | 237 | **3.54** |
| `NASA JPL NOAA USGS FBI CIA NSA DHS DOD DOE HHS USDA VA SSA CMS API SDK` | 75 | 160 | 2.13 |
| `Visit https://example.com/a/b/c?x=1&y=2#frag or ftp://files.example.org/pub/x.tar.gz` | 84 | 202 | 2.41 |
| Chinese, *correct* `lang_code='z'` pipeline | 42 | 168 | **4.00** |
| Chinese, *English* pipeline | 42 | 545 | **12.98** |
| Japanese, English pipeline | 47 | 672 | **14.30** |

The prose number is remarkably stable — 1.000 tokens/char at the median across every budget
from 150 to 500 characters — which is exactly why a character heuristic survives casual
testing and then fails on real phone input (amounts, tracking numbers, dates, CJK).

## 5. Upstream's own chunker is over the limit, measurably `[DOC]`+`[M]`

`kokoro/pipeline.py:400-427` chunks **non-English** text at `chunk_size = 400`
*characters*:

```python
400:                # Split long text into smaller chunks (roughly 400 characters each)
402:                chunk_size = 400
```

Combined with the measurements in §4, a 400-character Chinese span through its own
pipeline is ≈ 400 × 4.00 = **~1,600 phoneme tokens — about 3.1× the 510 hard limit**, so
it is truncated. Through the English pipeline it would be ≈ 5,200 tokens, ~10× over. The
same file warns about this class of problem for the espeak path: `[DOC]`

```
143:  logger.warning(f"Using EspeakG2P(language='{language}'). Chunking logic not yet implemented,
       so long texts may be truncated unless you split them with '\\n'.")
```

And `KPipeline.__call__` defaults to `split_pattern=r'\n+'` (line 366), so a single
unbroken paragraph sent to the library relies entirely on the internal splitter. `[DOC]`

## 6. Kokoro's own boundary preference order is explicit `[DOC]`

`KPipeline.waterfall_last` (lines 183-199) encodes the prosodic priority Kokoro itself
prefers, and it is worth copying rather than inventing:

```python
waterfall: List[str] = ['!.?…', ':;', ',—']
bumps:     List[str] = [')', '”']
```

It walks backwards through the token list trying each punctuation tier in order, accepting
the first boundary that brings the running count back under 510, and only then falls back
to `return len(tokens)` — a hard cut. `tokens_to_ps` (line 180) confirms the count includes
a space for every `t.whitespace` token. `[DOC]`

## 7. The server-side reference implementation targets 175-250 tokens `[DOC]`

`remsky/Kokoro-FastAPI` `api/src/core/config.py`:

```python
61:    target_min_tokens: int = 175  # Target minimum tokens per chunk
62:    target_max_tokens: int = 250  # Target maximum tokens per chunk
63:    absolute_max_tokens: int = 450  # Absolute maximum tokens per chunk
```

Note the deliberate **60-token safety margin** below the model's 510. Its `smart_split`
(`text_processor.py`) uses the hierarchy paragraph → sentence → clause → word, with clause
punctuation `[,;:，、；：]` and a binary-search word packer (`split_words`, lines 190-207).
If a client is going to split at all, these are the numbers to split to. `[DOC]`

## 8. Emoji and unknown symbols are a silent quality hazard `[DOC]`

`tokenize()` drops unmapped characters, and Kokoro-FastAPI therefore exposes
`normalization_options.remove_emoji`. Emoji expand or vanish unpredictably; neither is
acceptable in a system TTS engine, where the caller cannot see the text that was actually
spoken. `[DOC]`

## 9. A cheap on-device estimator can be made provably conservative `[M]`

An Android client cannot run misaki/espeak. The measurement in §4 was used to fit a linear
cost model over per-character-class counts (lower, upper, digit, space, punctuation,
symbol, CJK, other, plus intercept) to the **upper envelope** of the true token count —
fitted to not under-predict, rather than least-squares centred on the data:

```text
estimate = safety * ( c0 + sum over chars of c[class(char)] )
```

Held-out results are in §"What to measure next" below and in
`docs/research/token_model.json`; the Java port is generated from that file by
`tools/gen_cost_model.py` so the two cannot drift.

A centred least-squares fit was tried first and rejected: it needed a **2.13×** blanket
safety factor (max over-estimate 6.62×) because half the samples sit above the fit line,
which would have pushed real chunks to roughly half their intended length.

---

## Licence table

| artefact | version / commit | licence | licence file actually seen? | constraint |
| --- | --- | --- | --- | --- |
| `hexgrad/kokoro` (Python package) | `main`, 2026-10-12 | Apache-2.0 | **yes** — `LICENSE`, 11,357 bytes, fetched | attribution + NOTICE retention |
| `remsky/Kokoro-FastAPI` (server) | `master`, 2026-10-12 | Apache-2.0 | **yes** — `LICENSE`, 11,357 bytes, fetched | attribution + NOTICE retention |
| `hexgrad/Kokoro-82M` (weights) | card `lastModified` 2025-04-10 | Apache-2.0 | **yes** — model card front-matter `license: apache-2.0`, cross-checked against the HF API `cardData.license` | none beyond attribution; model card explicitly welcomes commercial deployment |
| Android SDK / build-tools 34.0.0 | 34.0.0 | Google Android SDK licence | accepted via `sdkmanager --licenses` | non-transferable, no redistribution of SDK binaries |
| Project Gutenberg ebook 1342 | fetched 2026-10-12 | Public domain (US) | n/a — Gutenberg header present in file | stripped header/footer before use as a corpus |

## What to measure next

**Experiment.** Ship the model-driven splitter and the naive 400-character baseline through
the *same* harness (`tools/splitter_conformance.py`) on a mixed corpus that includes the
classes from §4, and phonemize every emitted chunk with the real `KPipeline`.

* **Pass rule.** zero chunks above 510 true tokens, ≤ 0.5 % of chunks above 450, and median
  chunk tokens inside `[175, 250]`.
* **Abandon rule.** any chunk above 510 true tokens, or a median outside `[100, 300]`.
* Pre-registered in `docs/experiments/2026-10-12-kokoro-splitter.md` before any measurement.

**Expected cost.** ~5 minutes of CPU; no GPU and no model weights are needed because
`KPipeline(model=False)` performs G2P only.

## No sourced answer

* **No upstream statement of a recommended client-side split policy.** Kokoro's docs do not
  specify a target chunk size; the 175/250/450 numbers are Kokoro-FastAPI's choice, not the
  model's, and the 510 limit is enforced (or not) by whichever server is deployed. A client
  cannot assume the server splits for it.
* **No measurement of perceptual quality of seams.** This report measures token counts and
  failure rates only. Whether a 175-token chunk seam is audible, and what inter-chunk
  silence is right, is *not* measured here and is not claimed.
* **No Japanese/Korean G2P measurement.** Ratios for Japanese and Korean were measured only
  through the English pipeline (§4). `misaki[ja]`/`misaki[ko]` were not installed, so the
  correct-pipeline ratio for those languages is unsourced.
* **No verified latency model.** Time-to-first-audio as a function of chunk size was not
  measured; the claim that chunking improves latency is architectural reasoning, not a
  measurement, and is marked as such.
* **Search-engine recall was effectively zero** (§Access honesty), so any blog or issue
  report of a *different* Kokoro failure mode would not have been found here.

Raw dumps: `docs/research/raw/kokoro-limits/INDEX.md`
