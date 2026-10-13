# Kokoro Android system TTS driver

An Android **system TTS engine** (`TextToSpeechService`) that speaks through a
Kokoro server, with the text analysis done **on the device and tuned to Kokoro
specifically** rather than treating the API as a generic HTTP endpoint.

Runs on Android 5.0+ (API 21, `--target-sdk-version 34`). Needs a reachable
OpenAI-compatible Kokoro `/v1/audio/speech` endpoint — this was developed and
measured against `ghcr.io/remsky/kokoro-fastapi-cpu`. Nothing is downloaded at
runtime except the voice list.

The APK is built with a generated debug keystore and the manifest sets
`debuggable="true"` and `usesCleartextTraffic="true"`. That is deliberate: it is a
sideloaded development tool, plain-HTTP endpoints are the normal case, and
`run-as` (which `tools/set-voice.sh` and `tools/set-engine-prefs.sh` use to write
preferences) only works on a debuggable app.

## Why this is not just an HTTP client

Kokoro's hard input limit is **510 phoneme tokens**
(`kokoro/pipeline.py:274-275`, from `plbert.max_position_embeddings = 512` minus BOS
and EOS), and the server counts the length of the **IPA phoneme string**, not
characters. Character count is a bad proxy for it, measured on 2026-10-12:

| input class | measured tokens/char |
| --- | ---: |
| English prose | ~1.00 |
| digit / money dense | ~3.2–3.5 |
| CJK read by its own pipeline | ~4.0 |
| CJK read by the **English** pipeline | ~13.0 |

So upstream's own `chunk_size = 400` *characters* is unsafe: a 42-character Chinese
sentence is 545 phoneme tokens — over the limit — while a character budget sees only
10 % of its allowance used. Measured on a 1.1 M-character corpus, the naive
character-budget splitter emitted **3 chunks above the 510 limit (worst case 672
tokens)**; this driver emitted **zero**, with identical losslessness.

There are **two** failure modes, and they need different guards:

1. **Truncation** — over-long input is silently cut to its first 510 tokens.
2. **Wrong-language phonemisation** — CJK through the English G2P produces a
   per-character fallback that the real server returns as **HTTP 200 with fluent
   nonsense**. No status code reveals it.

## Layout

```
core/     pure Java, no Android imports, unit-testable on a plain JVM
  KokoroSplitter        paragraph -> sentence -> clause -> word -> character cuts
  KokoroCostModel       GENERATED from docs/research/token_model.json
  SentenceBreaker       decimals, versions, abbreviations, CJK terminators
  Emoji                 strips symbols that have no spoken form
  CharBaselineSplitter  the naive 400-char baseline, kept for measurement
android/  the system TTS engine and an adb-driven end-to-end probe
tools/    calibration, model fitting, conformance harness, server probe
docs/     research report, experiment report, raw primary sources
data/     corpora (public domain + authored fixtures)
```

## Build

No Gradle is required — the APK is built with the SDK's own tools
(`aapt2` → `javac` → `d8` → `zipalign` → `apksigner`):

```sh
python3 android/build.py          # -> android/kokoro-tts.apk
bash core/build.sh                # core only, for the JVM harnesses
```

`android/build.py` needs only `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) and a JDK; it
locates the newest build-tools and platform it finds. The APK is signed with a debug
keystore that is generated on first build, so nothing secret is in the repository.

## Install

The driver is an ordinary system TTS engine: one APK, no root, no companion app.

```sh
adb install -r android/kokoro-tts.apk
```

Then select **Kokoro TTS** under *Settings → System → Languages & input →
Text-to-speech output → Preferred engine*. A launcher icon (**Kokoro TTS**) opens the
same settings screen directly.

### Pointing the engine at your Kokoro server

The default endpoint is `http://localhost:8880/v1/audio/speech`. On a phone or emulator
that is not where a server on your workstation lives, so either forward the port:

```sh
adb reverse tcp:8880 tcp:8880     # device's localhost:8880 -> workstation's :8880
```

…or point the engine at the server's address directly:

```sh
tools/set-engine-prefs.sh endpoint=http://192.168.0.10:8880/v1/audio/speech
```

`adb reverse` lasts until the device is unplugged, so re-run it after reconnecting. On an
emulator without it, the host machine is reachable at `10.0.2.2`.

This default is verified, not assumed. On 2026-10-13 a **fresh install** on an
Android 14 emulator with `adb reverse tcp:8880 tcp:8880` logged
`engine created; endpoint=http://localhost:8880/v1/audio/speech` and then
synthesised a 105-character input into 5.79 s of audio in two chunks
(first audio 1,064 ms).

Any OpenAI-compatible `/v1/audio/speech` endpoint works — the request is
`{model, input, voice, response_format}` and the response body is taken as audio.

### Verify it end to end

`EngineTestActivity` drives the engine through the ordinary `TextToSpeech` API and
writes the result to a WAV file, so a run can be checked rather than listened to.
Note the **inner quoting** — `adb shell` re-parses the command on the device, so an
unquoted sentence arrives truncated at its first space:

```sh
adb shell "am start -n dev.kokoro.tts/.EngineTestActivity \
    -e text 'The quick brown fox jumps over the lazy dog.' \
    -e out /sdcard/Download/kokoro.wav"
adb logcat -s KokoroTtsTest:I KokoroTts:I      # ENGINE_TEST_RESULT DONE ...
adb pull /sdcard/Download/kokoro.wav
```

It also logs every engine the framework exposes (`ENGINE_AVAILABLE` / `ENGINE_COUNT`),
which is how a mis-registered engine is told apart from one that registers and then
fails to bind — the failure that a missing `DEFAULT` category causes.

## Test

```sh
# splitter invariants on a plain JVM (no Android, no device)
javac -d core/build/test-classes -cp core/build/classes \
      core/src/test/java/dev/kokoro/tts/SplitterSelfTest.java
java -cp core/build/classes:core/build/test-classes dev.kokoro.tts.SplitterSelfTest

# measure a splitter against real Kokoro G2P ground truth
.tooling/kokoro-venv/bin/python tools/splitter_conformance.py \
    --mode model --corpus data/corpus_mixed.txt \
    --out docs/research/conformance-model.json --check-lossless
```

## If a voice sounds raspy on "s"

Not the format, and not this driver. `response_format=pcm` returns **exactly the WAV
payload** (252,452 − 252,404 = a 48-byte header), so it is the lossless path, and the
engine copies samples verbatim. There is also no aliasing junk below the 12 kHz
Nyquist: 92 % of the energy is below 1 kHz and 11–12 kHz holds 0.0 %.

It is voice-dependent, by a wide margin. Power above 5 kHz, same sentence:

| voice | >5 kHz power | centroid |
| --- | ---: | ---: |
| **am_onyx** | **0.16 %** | 333 Hz |
| am_adam | 1.03 % | 717 Hz |
| am_michael | 2.01 % | 579 Hz |
| af_heart | 3.11 % | 777 Hz |
| bf_emma | 3.23 % | 1,032 Hz |

So **try `am_onyx`** if `am_michael` sounds harsh. Full table and method in
`docs/research/2026-10-12-voice-sibilance.md`. If it persists with a different voice,
check the listening path — Bluetooth with SBC or low-bitrate AAC causes raspy
sibilance independently of the model.

## Capturing what a listener actually heard

The engine can log every buffer it hands to the framework, so the exact audio a
session produced can be pulled and inspected. Set `dump_audio=true` (or
`tools/set-engine-prefs.sh dump_audio=true`), then pull:

```sh
adb pull /sdcard/Android/data/dev.kokoro.tts/files/kokoro_dump.pcm
adb pull /sdcard/Android/data/dev.kokoro.tts/files/kokoro_dump.txt   # offsets + text
python3 tools/waveform.py kokoro_dump.pcm --out dump.png
```

It writes to the app's own external files directory, so no storage permission is
needed. Off by default; it grows a file per session.

## A captured real session

`docs/experiments/2026-10-13-librera-capture.md` analyses 91.7 s captured from Librera
Reader on a real phone, through this engine:

| | |
| --- | ---: |
| silence overall | 29.3 % |
| pauses ≥150 ms | **23** (longest 593 ms) |
| silence the engine inserted | **0 ms** |
| largest sample jump vs peak | 43 % (no clipping) |

So the model, not the driver, produces roughly one noticeable pause every four
seconds — which is what "random silence" sounds like. Capping them at 350 ms was
measured on the same book: every pause above 400 ms disappears, the longest falls
from 593 ms to exactly 350 ms, and total pause time drops 30 %.

The capture also shows Librera's own *"short pause after `… ! ?`"* setting adding to
it, and that Librera sends sentences run together (`announcing.Turning`), a text
extraction artefact on the app's side.

## Inspecting the audio

`tools/waveform.py` renders a waveform PNG so audio can be looked at rather than only
measured — useful for exactly the problems this driver had (a clipped first word, a
seam that is too long, padding that will not go away). It is pure Python standard
library (`array`, `struct`, `zlib`), so it runs under any Python 3 with no numpy,
matplotlib or Pillow.

```sh
python3 tools/waveform.py audio.wav --out wave.png
python3 tools/waveform.py audio.wav \
    --out seam.png --start 8.35 --end 9.35 --mark 8.845   # zoom on a known seam
```

The bottom band marks where the signal is below the silence threshold, so gaps read
at a glance. `docs/experiments/audio-inspection.md` works through four examples:
an intact onset, Kokoro's visible padding, a bounded seam, and a whole utterance.

## Performance: where the time actually goes

Measured against a real Kokoro-FastAPI server on 2026-10-12, per chunk. Every
utterance logs these numbers, so this is checkable rather than folklore:

```
KokoroTts: chunk 0/3 chars=156 est=244 send=25ms wait=6ms body=2928ms bytes=436666 trimFront=0ms trimBack=252ms
```

| phase | measured | what it is |
| --- | ---: | --- |
| `send` | 1-25 ms | connection setup and request upload |
| `wait` | 4-30 ms | time to response **headers** |
| `body` | 1.4-2.9 s | the audio itself |

**The cost is server synthesis, not request handling.** Three independent checks:

1. **`wait` is ~5 ms, `body` is seconds.** Kokoro answers with headers immediately
   and `Transfer-Encoding: chunked`.
2. **The body does not actually stream.** Despite the chunked encoding, the first body
   bytes arrive only after synthesis finishes — at 4914 ms for a 9.1 s clip. So there
   is nothing to play early, and a streaming client gains nothing. (An earlier version
   of this engine streamed the body and was reverted for that reason.)
3. **Requests serialise.** Two concurrent requests took 5.03 s each against 2.74 s +
   2.77 s sequential — a ratio of 1.10. The server handles one at a time.

Synthesis runs at roughly **16-20 ms per phoneme token**, linear across the sizes
tried (62 tokens → 1157 ms, 211 → 4299 ms, 235 → 4638 ms). That is about a third of
real time, so playback keeps up comfortably in steady state; what it costs is
**time-to-first-audio**, because the first chunk must be fully synthesised before
anything can be played. Smaller chunks trade more requests for a shorter wait.

### Kokoro's own pauses, and capping them

Disabling the engine's pauses does **not** remove every gap, and this is the one that
surprises people. Kokoro inserts long pauses of its own **inside a single chunk**.
Measured on a 393-character passage with pauses off, the raw server audio and the
device output contained pauses at *identical* positions:

| chunk | in the raw server audio | in the device output |
| --- | --- | --- |
| 0 | 485 ms at 4.97 s | 485 ms at 4.97 s |
| 1 | 490 ms at 3.64 s into the chunk | 490 ms at 11.64 s |
| 2 | 280 ms at 1.52 s into the chunk | 280 ms at 17.22 s |

Nothing in the driver introduced them, and there were no long pauses at the two chunk
seams — a seam is capped at 50 ms by construction (25 ms guard either side).

*Pauses between chunks → Longest pause allowed inside Kokoro's own audio* caps them.
Set it to 400 ms and the longest pause becomes exactly 400 ms; the passage above
shortens from 20.03 s to 19.85 s. Samples are removed from the middle of the silent
run, so both sides stay silence and no click is introduced (peak sample-to-sample
jump unchanged at ~11,300, which is ordinary high-frequency content).

It defaults to **no limit** — this only ever shortens audio, so it is opt-in.

**A pause can straddle a chunk boundary**, and capping each chunk on its own left the
previous chunk's trailing silence and the next chunk's leading silence adjacent in the
output. Measured over nine minutes of reading, a 350 ms cap still left 11 pauses above
it, the longest 657 ms, every one of them at a seam. The engine now carries the
previous chunk's trailing silence forward and gives the next chunk only what remains of
the budget, so a seam is capped as one pause. Verified: seven pauses, longest exactly
350 ms, none over.

### The delay is the app's, not the engine's

Watching a real reading session (18 utterances, 135 s) separates the two cleanly:

| | dead time |
| --- | ---: |
| **inside** one 1,053-character utterance, across 5 chunk seams | **0.16 s** |
| **between** sentence-sized utterances | **28.2 s of 135 s (21 %)** |

The app sends **median 57 characters** per utterance. Each one costs its full
synthesis time as silence, because `onSynthesizeText` owns the framework's single
synthesis thread until all of its audio is written — so the next utterance cannot
start early. At ~20 ms/token a 57-character sentence is ~1.1 s, which is the observed
median gap of 0.95 s.

`QUEUE_ADD` does not fix it: measured back to back, the second utterance still waited
1.13 s. **Send larger units** — the engine splits long text on-device and prefetches,
so three sentences per utterance cuts the dead time by roughly two thirds. Full
numbers and method in `docs/experiments/2026-10-12-reading-latency.md`.

### Fast start: the first chunk is deliberately small

A reader that sends one page per utterance pays the **first chunk's synthesis time as
silence at every page turn** — measured from Librera Reader, 30 utterances of 500–900
characters with 2.5–5.4 s of silence before each, equal to its own `firstAudio`.

`fast_start_tokens` (default **140**) caps the opening chunk and leaves the rest of the
page packing normally. Measured on a 531-character page, same voice:

| | first chunk | time to first audio | total |
| --- | ---: | ---: | ---: |
| off | est 335 | **6,624 ms** | 11,733 ms |
| on | est **79** | **1,062 ms** | **8,837 ms** |

**6.2x sooner to first sound**, and faster overall, because the extra chunk is fetched
while the first is still playing. It costs one more request, and it is lossless — same
words in the same order, verified against the input.

Set it to the maximum (360) to disable.

### Prefetching

The engine fetches chunk *N+1* while chunk *N* is still being written to the
callback, via a one-deep buffer. This cannot shorten the total or the first chunk's
latency — the server serialises, so nothing can start earlier. What it fixes is
idleness: `audioAvailable()` blocks once the framework's audio buffer fills, and
without prefetching the server would sit unused for the whole time we are writing and
playing the current chunk.

## Changing the voice

**On the phone — two routes:**

1. **App drawer → "Kokoro TTS"** (a launcher icon; the quickest way).
2. **Settings > System > Languages & input > Text-to-speech output → the gear beside
   "Preferred engine: Kokoro TTS".**

![system TTS output screen](docs/experiments/img/system-tts-output-screen.png)

The screen fetches the voice list live from the
server's `/v1/audio/voices` — the server this was built against offers **67**
voices, so nothing is hardcoded — and has a *Speak a test sentence* button so you
can hear each one before committing. Tapping a voice applies it immediately: the
settings screen and the engine share one process, so the engine sees the new
value on the next utterance.

The screen is split into two tabs so the voice list gets the full height rather than
competing with the configuration controls. Voices also has a filter, which matters when a
server offers 67 of them:

![voices tab](docs/experiments/img/settings-voices-tab.png)

![voice filter](docs/experiments/img/settings-voice-filter.png)

Both routes are verified on a real device; see
`docs/experiments/img/settings-from-app-icon.png` and
`docs/experiments/img/settings-from-system-gear.png`.

**From a shell:** `tools/set-voice.sh --list` to see what the server offers, then
`tools/set-voice.sh bm_george`. This writes the preference file directly, which the
running process has cached in memory, so the script force-stops the app afterwards
to make it re-read.

The engine also advertises the fetched list through `onGetVoices()`, so any app
that picks a TTS voice sees all of them rather than a built-in subset.

### Pauses between chunks, and the silence Kokoro adds itself

This is the part that surprises people, so it is worth stating plainly:

**Kokoro pads every chunk with about 0.4 s of silence.** Measured against a real server on
2026-10-12, identical across every input length tried:

| | leading | trailing |
| --- | ---: | ---: |
| every chunk | **50 ms** | **360 ms** |

So a seam between two chunks already carries 360 + 50 = **410 ms** of silence that no
setting asked for. That is why simply turning the engine's own pauses off appeared to do
nothing — the audible gap was never ours. The engine therefore **trims that padding** by
default, cutting at the first and last non-silent sample (which cannot click, because the
cut lands in a run of near-zero samples, and a 10 ms guard is kept either side).

**Trimming keeps a lead-in at the start of the utterance.** The audio output path needs
samples to spin up, and speech that begins at sample zero loses the attack of its first
word. The server's own leading padding was previously hiding that, so trimming it away
without a replacement made the first word clip. The engine therefore keeps 80 ms of
lead-in before the first chunk (measured against a 50 ms padding, so in practice the whole
lead-in survives) and trims normally from the second chunk onward, since those are
mid-stream in one continuous track.

With trimming on, the pause settings control the pause exactly:

| settings | inserted | total audio |
| --- | ---: | ---: |
| trimming on, pauses off | 0 ms | 22.386 s |
| trimming on, pauses on at 200 ms | 400 ms | 22.786 s |

The difference is exactly 400 ms — two seams x 200 ms. Before trimming, the same 200 ms
change was buried under 410 ms of server padding per seam.

| setting | default | meaning |
| --- | ---: | --- |
| `trim_silence` | `true` | strip the 50 ms / 360 ms padding Kokoro adds to each chunk |
| `add_gaps` | `true` | master switch for the pauses below |
| `mid_sentence_gap_ms` | 90 ms | pause where a seam cuts mid-sentence |
| `paragraph_gap_ms` | 280 ms | pause where a seam is a paragraph break |

A seam that already lands on a sentence end gets **no** inserted pause, because the
synthesised audio ends with its own natural pause there. So 90 ms does not mean every join
grows by 90 ms, only the ones that would otherwise run together.

**Turn `add_gaps` off if the app you listen with inserts its own pauses**, or you get both
and the reading sounds choppy. Each utterance logs what it actually did:

```
KokoroTts: synth ok: chars=393 chunks=3 estTokens=244/234/134 voice=af_heart \
           pauses=off inserted=0ms trimmed=1005ms ms=7165
```

![pause settings](docs/experiments/img/settings-settings-tab.png)

### Which voice wins

**By default the engine's own setting wins outright.** An app's `setVoice()` is ignored
unless you turn on *Voice precedence → Let apps choose the voice* in the Settings tab,
which restores the classic Android behaviour.

This was previously inferred from timestamps — honour an app's voice only if it was
loaded after your last change — and that was subtly wrong: the timestamp was
per-process, so after any restart it read as zero and the first voice an app loaded
looked newer than your choice. The result was voice selection working immediately
after being set and silently reverting later. The failure mode is gone with the
heuristic:

| setting | app requests | engine uses |
| --- | --- | --- |
| off (default) | `am_adam` | the voice you picked |
| on | `am_adam` | `am_adam` |
| off, after a process restart | `am_michael` | the voice you picked |

Every utterance logs the decision, so this is never guesswork again:

```
KokoroTts: voice: default=am_onyx requested=am_adam appsOverride=false -> am_onyx (engine default)
```

## Configure the engine

Configuration is read from SharedPreferences (`kokoro`), so it can be set over adb
without a settings UI. Defaults:

| key | default |
| --- | --- |
| `endpoint` | `http://localhost:8880/v1/audio/speech` |
| `voice` | `af_heart` |
| `model` | `kokoro` |
| `format` | `pcm` |
| `hard_cap` | `360` (estimated tokens — see below) |
| `target_min` / `target_max` | `240` / `360` |
| `trim_silence` | `true` (strip Kokoro's per-chunk padding) |
| `add_gaps` | `true` (master switch for inserted silence) |
| `mid_sentence_gap_ms` | `90` (seam cut mid-sentence) |
| `paragraph_gap_ms` | `280` (seam at a paragraph break) |

A preference with the wrong type degrades to its default and logs a warning; it never
crashes the engine. That is deliberate — a malformed preference written by hand took the
whole TTS service down on a real device before this was fixed.

### Why the cap is 360 and not 450

The splitter caps *estimated* tokens. The cost model is fitted not to
under-predict, but it is still an estimate: on 11,083 held-out samples the worst
case was a true count **1.348×** the estimate, so a 450 cap could reach 607 real
tokens. The fit's own recommendation is `510 / 1.348 = 378`; the default derates
that ~5 % to **360**. Fit quality: R² 0.949, 1 under-prediction in 11,083 samples,
median over-estimate 1.611×.

## Reports

- `docs/research/2026-10-12-kokoro-text-limits.md` — what the sources support,
  with a licence table and an explicit list of what could **not** be sourced.
- `docs/research/2026-10-12-ao3-reader-chunker-vs-kokoro.md` — the existing
  `ao3-reader` narration chunker, scored by the same referee against real Kokoro
  G2P. It is well calibrated for pace (median 247 tokens) but has **10 chunks
  over 510 tokens on long-form prose and 3 of 41 on the mixed corpus**, because
  `mergeChunks` merges but never splits an oversized sentence, and `chunkText`
  only recognises ASCII terminators.
- `docs/experiments/2026-10-12-kokoro-splitter.md` — the pre-registered
  experiment, **including the criterion that failed** and why.
- `docs/experiments/device-evidence-2026-10-12.md` — raw logcat and verified
  RIFF headers from the on-device runs.

### What the chunk budget is, and is not

Two independent concerns get conflated when picking a chunk size:

* **Pace uniformity** — Kokoro's speaking rate varies with input size, so very
  short chunks sound rushed. `ao3-reader` measured this and chose ~300
  characters; this project reached the same region from the token side (median
  194 true tokens on long-form prose). For English prose the two are nearly the
  same number, because prose is ~1.0 tokens/char.
* **The 510-token ceiling** — a hard limit that a character budget cannot
  enforce, because the cost per character ranges from ~1.0 (prose) to ~13 (CJK).

## Support

If this is useful to you, you can buy me a coffee — or, given what it does, help keep the
GPU warm:

**<https://ko-fi.com/gishi>**

There is also a *Support this project on Ko-fi* button in the app's settings screen.

## Licence

**Apache-2.0** — see [`LICENSE`](LICENSE).

The driver code here is original. It depends at runtime on a Kokoro server;
`hexgrad/kokoro`, `remsky/Kokoro-FastAPI` and the `hexgrad/Kokoro-82M` weights are
all **Apache-2.0** (licence files fetched and recorded in the research report), so
nothing in the distribution path adds a copyleft obligation.
`data/corpus_pride_prejudice.txt` is public domain (Project Gutenberg ebook 1342);
the other corpora under `data/` are authored fixtures.
