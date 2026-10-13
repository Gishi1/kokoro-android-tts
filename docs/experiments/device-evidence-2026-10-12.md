# Device evidence — 2026-10-12

Raw logcat excerpts and WAV verification for the three end-to-end runs on the
Android 14 (API 34, x86_64) emulator `kokoro_test`, driven through the real
Android `TextToSpeech.synthesizeToFile()` API against the real Kokoro server at
http://10.0.2.2:8880/v1/audio/speech (ghcr.io/remsky/kokoro-fastapi-cpu:latest).

Audio properties were read from the pulled RIFF header, not inferred from logs.

```
10-12 21:01:46.188  7053  7053 I KokoroTtsTest: ENGINE_AVAILABLE name=dev.kokoro.tts label=Kokoro TTS
10-12 21:01:46.189  7053  7053 I KokoroTtsTest: ENGINE_COUNT 1
10-12 21:01:46.204  7053  7053 I KokoroTts: engine created; endpoint=http://10.0.2.2:8880/v1/audio/speech
10-12 21:01:53.300  7053  7074 I KokoroTtsTest: ENGINE_TEST_DONE id=kokoro-test bytes=1144282 ms=6914
10-12 21:01:53.300  7053  7074 I KokoroTtsTest: ENGINE_TEST_RESULT DONE bytes=1144282
10-12 21:01:53.302  7053  7074 I KokoroTts: synth ok: chars=281 chunks=2 estTokens=248/333 voice=af_heart ms=6908
```

## Verified WAV headers

```
  final.wav                1ch 24000Hz 16bit   1144238 bytes =  23.84s
  kokoro-e2e.wav           1ch 24000Hz 16bit    241920 bytes =   5.04s
  long.wav                 1ch 24000Hz 16bit   1856384 bytes =  38.67s
```
