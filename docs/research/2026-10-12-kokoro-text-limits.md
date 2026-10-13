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

Held-out results are in the experiment section at the end of this report and in
`docs/research/token_model.json`; the Java port is generated from that file by
`tools/gen_cost_model.py` so the two cannot drift.

A centred least-squares fit was tried first and rejected: it needed a **2.13×** blanket
safety factor (max over-estimate 6.62×) because half the samples sit above the fit line,
which would have pushed real chunks to roughly half their intended length.

## 10. The real server returns **HTTP 200 for text it cannot read correctly** `[M]`

Everything above is read from source. The following was measured against the Kokoro
server actually running on this machine — `ghcr.io/remsky/kokoro-fastapi-cpu:latest`,
`openapi.json` reporting "Kokoro TTS API" v0.5.0, listening on `0.0.0.0:8880` — via
`tools/probe_real_server.py`. Raw results: `docs/research/server-probe.json`.

| request | chars | HTTP | time to first byte | total | audio produced |
| --- | ---: | ---: | ---: | ---: | ---: |
| Chinese text, English voice | 42 | **200** | 10.31 s | 10.32 s | **27.13 s** |
| no-punctuation wall | 1,013 | **200** | 10.49 s | 21.70 s | 57.38 s |
| long prose paragraph | 1,883 | **200** | 6.11 s | 35.36 s | 104.08 s |

The first row is the finding that matters most for a client. That input is **545 phoneme
tokens** and, through the English G2P, every glyph is the same ~13-token fallback (§3b).
The server answered **200 OK** and returned 27 seconds of audio — roughly 0.65 s per
character, which is the signature of reading the fallback out in full.

**A client cannot detect this failure from the HTTP response.** There is no error status,
no truncation flag, and no field distinguishing real speech from a per-character
fallback. A driver that only guards against length and errors still ships nonsense audio
to the user. Guarding this requires the client to notice the script/language mismatch
itself — which is a reason to do the text analysis on the device rather than delegate it.

## 11. Splitting also removes a long, opaque wait before the first sound `[M]`

Same server, same paragraph (1,883 characters), measured both ways:

| | time to first audio | total | audio produced |
| --- | ---: | ---: | ---: |
| one request for the whole utterance | **6.108 s** | 35.36 s | 104.08 s |
| split into 12 chunks and streamed | **3.545 s** | 35.20 s | 104.08 s |

Time-to-first-audio improves **1.72x**; total time is unchanged (35.36 s vs 35.20 s). For
a system TTS engine this is the difference between the caller waiting 6 seconds for any
sound and hearing the first words in under 4 — and total time is not a proxy for it.

This is a single paragraph on a single machine and is reported as such; it is a
measurement, not a model. Note also that Android's `SynthesisCallback` is a streaming
interface, so the client *can* exploit this without waiting for the whole utterance.

## 12. The result holds on a real device, not only in a harness `[M]`

The signed APK was installed on an Android 14 (API 34, x86_64) emulator, discovered by
the framework, and driven through `TextToSpeech.synthesizeToFile()` against the server on
`10.0.2.2:8880`. The engine's own log:

```
KokoroTts: engine created; endpoint=http://10.0.2.2:8880/v1/audio/speech
KokoroTts: synth ok: chars=100 chunks=1 estTokens=163 voice=af_heart ms=1980
KokoroTts: synth ok: chars=442 chunks=4 estTokens=322/194/223/241 voice=af_heart ms=11019
```

The 442-character utterance was cut into four requests on-device, each below the cap, and
the pulled file is a valid 24 kHz mono 16-bit WAV of 38.67 s (RIFF header read from the
file, not inferred from the log). Two integration facts cost real time and are worth
recording: an engine whose `TTS_SERVICE` intent filter lacks
`android.intent.category.DEFAULT` is registered with `PackageManager` but **invisible to
`TextToSpeech.getEngines()`** (which queries with `MATCH_DEFAULT_ONLY`), and
`TextToSpeech.setEngineByPackageName()` is a deprecated no-op — the engine must be set as
the system default or passed to the three-argument constructor.

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

**Outcome (2026-10-12).** Run. The safety half passed decisively — the prototype produced
**0 chunks above 510 tokens and 0 above 450** against the naive baseline's **3 hard
failures, worst case 672 tokens**, with both splitters verified lossless. The median
criterion **failed** on the mixed corpus (68, because that corpus is line-oriented) and
**passed** on a long-form corpus added afterwards as a diagnostic (median 194.0). The
rule is recorded as partially failed rather than re-scored; the full table, the failing
criterion and the reason are in the experiment report.

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
