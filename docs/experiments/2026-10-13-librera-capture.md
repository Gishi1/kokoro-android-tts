# What the reader actually heard: a captured Librera session

Captured **2026-10-13** from a real listening session. The app is **Librera Reader**
(`com.foobnix.pro.pdf.reader`), reading *The Gamers Guide to Conquest, Remastered*,
through this engine (`dev.kokoro.tts`, voice `am_adam`).

Method: `dump_audio=true` makes the engine append every buffer it hands to the
framework to `kokoro_dump.pcm`, with a sidecar giving each chunk's offset, length and
text. Started with `input keyevent KEYCODE_HEADSETHOOK`, which Librera treats as a
read-aloud toggle. Pulled and analysed with `tools/waveform.py` and a silence scan.

## The capture

**91.7 s of audio** (4,399,830 bytes), **8 chunks across 2 utterances**. The engine
reported `pauses=off inserted=0ms` for the whole session — it added nothing.

| | |
| --- | ---: |
| silence overall | 29.3 % |
| silent runs ≥150 ms | **23** |
| — of 200–300 ms | 1 |
| — of 300–400 ms | 9 |
| — of 400–500 ms | 6 |
| — of 500–600 ms | 7 |
| longest single pause | **593 ms** |
| total time in those pauses | 9.8 s of 91.7 s (10.7 %) |
| largest sample-to-sample jump | 10,998 vs peak 25,591 (43 %) |

![full capture](img/librera-capture-full.png)

![26 s of it](img/librera-capture-zoom.png)

## What this establishes

**The silence is Kokoro's, not the engine's.** `inserted=0ms` throughout, so every one
of those 23 pauses came from the model. That is roughly **one noticeable pause every
four seconds**, which is what "random silence" sounds like from the listener's side
even though each pause sits at a sentence or clause boundary.

**There is no clipping.** The largest sample-to-sample jump is 43 % of peak, which is
ordinary for high-frequency speech; a genuine cut would approach the peak. No
discontinuity is being introduced at the trims or the seams.

**The trims are behaving.** `trimFront=0–1 ms` on every chunk (the full lead-in is
kept) and `trimBack=227–313 ms` (Kokoro's 360 ms trailing padding, minus the 50 ms
guard). Nothing is being cut into.

## Two things the capture exposed that are not this engine

**1. Librera inserts its own pauses.** Its TTS settings dialog has
*"☑ A short pause after the symbol: `… ! ?`"* enabled. So around `!` and `?` the
listener gets Librera's pause *plus* Kokoro's. Worth turning off while diagnosing,
since it is app-side.

**2. Librera sends sentences with no space after the full stop.** Straight from the
sidecar, verbatim:

```
that of a lich.Undead, endlessly regenerating, and very very strong.My new body is
Hey Player Two, sorry about all that.Don't worry, Lucille has been dragged home
Shiro's eternally smug voice announces.Turning around, the first thing I see
ignoring him entirely.ISIS System booting up.
```

`lich.Undead`, `strong.My`, `announcing.Turning`, `entirely.ISIS`. The G2P sees
`lich.Undead` as one token run. That is a text-extraction artefact on the app's side
and may well affect pronunciation, though it is not what causes the pauses.

## The cap, measured: before and after

The first session ran with **no limit**; the second with
*Longest pause allowed inside Kokoro's own audio* set to **350 ms**. Same app, same
book, same voice, same engine build.

| | before (no limit) | after (350 ms) |
| --- | ---: | ---: |
| captured | 91.7 s | 80.8 s |
| pauses ≥150 ms | 23 | 22 |
| — 100–200 ms | 0 | 3 |
| — 200–300 ms | 1 | 2 |
| — 300–400 ms | 9 | **17** |
| — 400–500 ms | 6 | **0** |
| — 500–600 ms | 7 | **0** |
| **longest pause** | **593 ms** | **350 ms** |
| time spent in those pauses | 9.8 s (10.7 %) | 6.9 s (8.5 %) |

Every pause above 400 ms is gone, the longest falls from 593 ms to exactly the
configured 350 ms, and total pause time drops by **2.9 s, about 30 % less silence**.
The engine reports the work it did per chunk, e.g. `cappedPauses=258ms`.

![capped](img/librera-capture-capped-zoom.png)

Compare with the uncapped picture above: the same passage, visibly tighter gaps.

## What to do

Set **Pauses between chunks → Longest pause allowed inside Kokoro's own audio** to
350 ms. That shortens the nine 500–600 ms pauses and the six 400–500 ms ones while
leaving ordinary sentence pauses alone. It was set to 350 ms on the device at the end
of this session.

Set it to **350 ms**; it was set to 350 ms on the device and measured above.

**One trap worth recording.** The first attempt to re-capture failed, and the reason was
mine: reinstalling the APK restarts the TTS engine, Librera notices the binding change
and sends the user to the system *Text-to-speech output* screen, after which its
read-aloud toggle does nothing. Do not reinstall the engine while a reader is mid-book.
Leaving the APK alone and pressing the headset key again worked immediately.

## What is not measured

* **No listening test.** These are waveform and silence statistics.
* **One session, one book, one voice.** 92 s is enough to characterise pause
  frequency, not every failure mode.
* **The app's own pause length was not measured.** Librera's "short pause after the
  symbol" has no exposed duration, so its contribution is unquantified — only known
  to be non-zero and additive.
* **Captured audio is what the engine produced**, not what the speaker emitted.
  Anything the audio HAL, a Bluetooth codec or the speaker does afterwards is outside
  this capture.
