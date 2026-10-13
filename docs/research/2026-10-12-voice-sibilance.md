# Sibilance by voice: why some Kokoro voices sound raspy on "s"

Research date: **2026-10-12**. Target: `ghcr.io/remsky/kokoro-fastapi-cpu:latest`
(Kokoro TTS API v0.5.0) on `localhost:8880`, and this driver's output on a a physical phone
2025 running Android 16.

Reported symptom: *the audio seems a bit raspy on s's.*

**Marker legend.** `[M]` verified against the artefact in this session · `[DOC]`
upstream documentation/source · `[O]` opinion.

**Access honesty.** No web route was used or needed: every number here comes from the
server running on this machine and from the phone attached to it. `mp3`, `opus` and
`flac` responses were fetched successfully but **could not be decoded** — no decoder
is installed — so they are reported as unmeasured rather than assumed equivalent.
The `web_fetch` tool was never called (it is documented to crash the running GUI).
One methodological correction is recorded below rather than quietly fixed.

## Could it be the format? No

That was the next hypothesis, and it is ruled out:

| format | bytes | content type | rate | duration | peak |
| --- | ---: | --- | ---: | ---: | ---: |
| `pcm` | 252,404 | `audio/pcm` | 24,000 | 5.26 s | 15,055 |
| `wav` | 252,452 | `audio/wav` | 24,000 | 5.26 s | 14,860 |

**252,452 − 252,404 = 48 bytes, exactly a WAV header.** The `pcm` response *is* the
WAV payload, byte for byte. There is no resampling, no codec, no normalisation step
between them, so `pcm` — which this driver uses — is the lossless path. `mp3` and
`opus` would only add artefacts, and switching to them cannot remove any.

The other format worry is aliasing: 24 kHz output has a 12 kHz Nyquist, and junk
bunched just under it would survive upsampling to the phone's 48 kHz and sound harsh.
It is not there. Energy per 1 kHz band for the sibilant sentence:

```
   0- 1kHz  92.1%      4- 5kHz   1.0%      8- 9kHz   0.1%
   1- 2kHz   2.4%      5- 6kHz   0.3%      9-10kHz   0.1%
   2- 3kHz   1.6%      6- 7kHz   0.9%     10-11kHz   0.1%
   3- 4kHz   0.9%      7- 8kHz   0.5%     11-12kHz   0.0%
```

Nothing accumulates near Nyquist, so there is no aliasing to filter out.

## Is it the driver? No

| check | result |
| --- | --- |
| Does the signal clip? | **No.** Peak 15,942 of 32,767 (−6.4 dBFS); zero samples at or above 32,000 |
| Does the engine alter samples? | **No.** It copies the response body verbatim; trimming only picks a start and end offset, and inserted pauses are zeros |
| Does the device output differ spectrally from the server's? | **No.** 0–2 kHz 35.7 % vs 35.4 %, 4–6 kHz 17.0 % vs 17.3 % |

A sample-by-sample comparison across two *separate* requests is not a valid test —
Kokoro is not bit-deterministic, and the same sentence synthesised twice differs in
fine detail and timing while the spectrum matches. Compare spectra, not samples.

## The measurement that does matter: voice

Reported two ways, because they disagree in the tail and the disagreement is
instructive. **Power** is the standard definition of spectral energy; **magnitude** is
not, and it inflates the high bands. The first version of this document quoted
magnitude under the heading "energy", which was wrong.

| voice | **power >5 kHz** | magnitude >5 kHz | spectral centroid |
| --- | ---: | ---: | ---: |
| **am_onyx** | **0.16 %** | 8.0 % | **333 Hz** |
| am_fenrir | 1.02 % | 14.5 % | 665 Hz |
| am_adam | 1.03 % | 13.8 % | 717 Hz |
| bm_george | 1.19 % | 16.7 % | 415 Hz |
| am_echo | 1.32 % | 19.0 % | 409 Hz |
| am_liam | 1.90 % | 16.3 % | 711 Hz |
| am_puck | 1.98 % | 21.8 % | 559 Hz |
| **am_michael** | **2.01 %** | 21.5 % | 579 Hz |
| af_heart | 3.11 % | 25.8 % | 777 Hz |
| bf_emma | 3.23 % | 22.8 % | 1,032 Hz |

`am_onyx` is the outlier by a wide margin on both metrics and has by far the lowest
centroid. `am_michael` — the voice in use when the complaint was made — sits in the
harsher group on both. The spread is **12x on power** (0.16 % → 2.01 % for the two
voices in question) and 20x across the full set.

**Speed is not a lever.** For `am_michael`: 21.0 % at speed 0.85, 21.5 % at 1.0,
20.8 % at 1.15 (magnitude metric) — flat within noise, while duration changes from
6.12 s to 4.86 s.

## Licence table

| artefact | version | licence | licence file actually seen? | constraint |
| --- | --- | --- | --- | --- |
| `remsky/Kokoro-FastAPI` (the server measured) | v0.5.0, `ghcr.io/remsky/kokoro-fastapi-cpu:latest` | Apache-2.0 | **yes** — `LICENSE`, 11,357 bytes, in `docs/research/raw/kokoro-limits/` | attribution + NOTICE retention |
| `hexgrad/Kokoro-82M` (the weights) | card `lastModified` 2025-04-10 | Apache-2.0 | **yes** — model card front-matter, cross-checked against the HF API | none beyond attribution |
| voice `.pt` files served by the API | ship inside the image | covered by the image's Apache-2.0 | n/a — not redistributed here | none |

Nothing from the server is redistributed by this repository; the measurements are
numbers and the audio was pulled to a local scratch directory.

## What to do about it

1. **Change voice first.** `am_onyx` reduces high-frequency power by roughly 92 %
   against `am_michael`, with the lowest centroid of the ten measured. It is a
   two-tap change in the app drawer and the only fix supported by a measurement here.
2. **Rule out the listening path.** Bluetooth with SBC or low-bitrate AAC is a
   classic cause of raspy sibilance and is entirely independent of the model. Compare
   wired against Bluetooth on the same utterance before concluding anything about
   Kokoro.
3. **Only then consider DSP.** A de-esser or a gentle high shelf would reduce it
   further, at the cost of dulling the consonant. Not implemented; offered, not
   assumed.

## What to measure next

**Experiment.** Re-run the ten-voice table with a listening test rather than a
spectral proxy, since the proxy is the weakest link in this report.

* **Pass rule.** For each voice, at least two of three listeners rank it as less
  harsh than `am_michael` on the same sentence, and the ranking correlates with the
  >5 kHz power fraction (Spearman rho > 0.6).
* **Abandon rule.** If listener rankings are uncorrelated with the spectral measure,
  the proxy is not usable and voice choice must be made by ear alone; say so rather
  than continuing to quote percentages.

Cheaper first step, since it needs no listeners: play the same utterance wired and
over Bluetooth on the phone. If the raspiness is present wired, it is the model; if
only over Bluetooth, it is the codec and no amount of voice selection will help.

## No sourced answer

* **No listening test.** These are spectral statistics, not perceived harshness. They
  usually track for sibilance, but this is a proxy and is labelled as one.
* **A metric was changed mid-report.** The first version quoted FFT **magnitude**
  summed above 5 kHz under the heading "energy". Power is the standard definition and
  gives much smaller numbers (2.0 % vs 21.5 % for `am_michael`). Both are now shown.
  The *ranking* is broadly unchanged and `am_onyx` is the clear outlier either way,
  but the absolute figures in the earlier version were not comparable to anything.
* **Ten of the sixty-seven voices were measured**, chosen as plausible English
  candidates. The rest, including the non-English families, are unsourced.
* **The phone's playback path was not measured.** Everything above concerns the file
  the engine produced, which is bit-identical to what it received. Anything the audio
  HAL or a Bluetooth codec does afterwards is outside it.
* **`mp3`, `opus` and `flac` could not be decoded**, so whether they sound
  different is unmeasured. They cannot be *better* than lossless PCM, but that is an
  argument, not a measurement.
