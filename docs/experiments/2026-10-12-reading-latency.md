# Where the reading delay actually comes from

Measured **2026-10-12** by watching a live session: `adb logcat` filtered to
`KokoroTts` plus the audio stack, and `docker logs -f` on the Kokoro container, while
the phone's reading app spoke. 18 utterances, 135 s of wall time. Tool:
`tools/watch_analyze.py`.

Reported symptom: *a delay every once in a while, even with pauses disabled.*

## Summary

The delay is **not** a slow server, a slow request, or a stall in this driver. It is
the cost of the app speaking **one sentence at a time**: each utterance costs its
full synthesis time as silence, because the TTS callback API does not let an engine
synthesise utterance N+1 until N has been fully written.

| | measured |
| --- | ---: |
| utterances | 18 |
| wall time | 135.0 s |
| synthesising | 106.8 s (79 %) |
| **dead time between utterances** | **28.2 s (21 %)** |
| median gap | 0.95 s |
| worst gaps | 8.3, 4.4, 2.6, 1.8, 1.3 s |
| underruns, errors, failed requests | **0** |

The app's utterances are short: **median 57 characters**, range 11–393.

## The contrast that matters

**Within one long utterance the engine is essentially perfect.** A 1,053-character
utterance split into 6 chunks:

| chunk | synthesis | audio produced | buffered when the next landed |
| ---: | ---: | ---: | ---: |
| 0 | 4,134 ms | 14,071 ms | — |
| 1 | 1,432 ms | 3,545 ms | +711 ms |
| 2 | 3,282 ms | 11,966 ms | +770 ms |
| 3 | 2,474 ms | 9,538 ms | +698 ms |
| 4 | 2,365 ms | 8,115 ms | +818 ms |
| 5 | 1,938 ms | 7,598 ms | +733 ms |

**Total dead time across the five internal seams: 0.16 s**, over 54.83 s of audio
delivered in 54.10 s. Prefetching keeps 0.7–0.8 s of audio in hand at every seam, so
playback never runs dry.

**Between two utterances there is always a gap.** Measured with two utterances queued
back to back via `QUEUE_ADD`:

```
23:20:20.75  utterance A starts synthesising
23:20:22.69  A finishes writing  (took 3228 ms, blocked on playback)
23:20:23.82  utterance B starts synthesising   <- 1.13 s of silence
```

The 1.13 s is B's synthesis time, and it is unavoidable from inside the engine:
`onSynthesizeText` occupies the framework's single synthesis thread until all of the
utterance's audio has been written, and writing blocks once the audio buffer fills.
Only then is the next utterance dispatched.

## What to do about it

**Send larger units.** The engine already splits long text on-device at Kokoro's 510
token limit and prefetches, so a paragraph costs one handoff instead of one per
sentence. The numbers above are the argument: five seams inside an utterance cost
0.16 s, while five separate utterances of the same total length cost roughly five
synthesis times, about 5 s.

Concretely, at ~20 ms per phoneme token, a 57-character sentence is about 1.1 s of
synthesis, which matches the observed median gap of 0.95 s almost exactly. Grouping
even three sentences per utterance cuts the dead time by roughly two thirds.

`QUEUE_ADD` does **not** fix this on its own. It preserves ordering and avoids
re-triggering engine setup, but the synthesis thread still serialises, so the gap
remains the next utterance's synthesis time.

If an app needs sentence granularity for highlighting, the tension is real: it is
paying one synthesis latency per highlight boundary. A paragraph per utterance with
internal offsets is the usual way out.

## What is not measured

* **The app's own code was not inspected.** It is identified only by its behaviour in
  the logs: short utterances, no queuing ahead. The recommendation follows from the
  measurements, not from reading it.
* **Playback position was not observed**, only engine-side delivery. That the buffered
  audio never went negative is computed from delivery times and durations, which is a
  model of playback, not a recording of it.
* **One session on one device.** 18 utterances, one voice (`am_adam`), one book.
* **No underrun messages appeared**, which is consistent with the model above but does
  not by itself prove playback never glitched — some devices do not log them.
