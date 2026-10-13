# Device evidence — 2026-10-12

Raw logcat excerpts and WAV verification for the end-to-end runs on the
Android 14 (API 34, x86_64) emulator `kokoro_test`, driven through the real
Android `TextToSpeech.synthesizeToFile()` API against the real Kokoro server at
http://10.0.2.2:8880/v1/audio/speech (ghcr.io/remsky/kokoro-fastapi-cpu:latest).

Audio properties were read from the pulled RIFF header, not inferred from logs.

In the engine's `estTokens` trace, `/` marks a seam inside a paragraph and
`|` marks a paragraph-end seam, which gets a longer pause (280 ms vs 90 ms).

## Latest run — paragraph-aware pauses (two paragraphs)

```
10-12 21:12:59.917  7210  7210 I KokoroTts: engine created; endpoint=http://10.0.2.2:8880/v1/audio/speech
10-12 21:13:03.071  7210  7232 I KokoroTts: synth ok: chars=146 chunks=2 estTokens=115|119 voice=af_heart ms=2957
10-12 21:13:03.069  7210  7232 I KokoroTtsTest: ENGINE_TEST_DONE id=kokoro-test bytes=445490 ms=2970
```

## Engine discovery, single-chunk and multi-chunk runs

```
10-12 21:01:46.188  7053  7053 I KokoroTtsTest: ENGINE_AVAILABLE name=dev.kokoro.tts label=Kokoro TTS
10-12 21:01:46.189  7053  7053 I KokoroTtsTest: ENGINE_COUNT 1
10-12 21:01:46.204  7053  7053 I KokoroTts: engine created; endpoint=http://10.0.2.2:8880/v1/audio/speech
10-12 21:01:53.300  7053  7074 I KokoroTtsTest: ENGINE_TEST_DONE id=kokoro-test bytes=1144282 ms=6914
10-12 21:01:53.302  7053  7074 I KokoroTts: synth ok: chars=281 chunks=2 estTokens=248/333 voice=af_heart ms=6908
10-12 20:54:45.874  6858  6880 I KokoroTts: synth ok: chars=442 chunks=4 estTokens=322/194/223/241 voice=af_heart ms=11019
10-12 20:54:45.872  6858  6880 I KokoroTtsTest: ENGINE_TEST_DONE id=kokoro-test bytes=1856428 ms=11026
10-12 20:54:04.721  6858  6858 I KokoroTts: engine created; endpoint=http://10.0.2.2:8880/v1/audio/speech
10-12 20:54:06.883  6858  6880 I KokoroTts: synth ok: chars=100 chunks=1 estTokens=163 voice=af_heart ms=1980
```

## Verified WAV headers

```
  final.wav              1ch 24000Hz 16bit   1144238 bytes =  23.84s
  kokoro-e2e.wav         1ch 24000Hz 16bit    241920 bytes =   5.04s
  long.wav               1ch 24000Hz 16bit   1856384 bytes =  38.67s
  para.wav               1ch 24000Hz 16bit    445446 bytes =   9.28s
```
