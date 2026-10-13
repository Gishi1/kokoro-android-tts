package dev.kokoro.tts;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.util.Locale;

/**
 * Headless end-to-end probe for the engine, driven from adb.
 *
 * <p>It exercises the driver the way a real app does — through the Android
 * {@link TextToSpeech} API bound to this engine by package name — and writes the
 * result to a WAV file so the audio can be pulled off the device and inspected.
 *
 * <pre>
 *   adb shell am start -n dev.kokoro.tts/.EngineTestActivity \
 *       -e text "Hello world." -e out /sdcard/Download/kokoro.wav
 *   adb shell am broadcast -a dev.kokoro.tts.TEST_DONE
 * </pre>
 *
 * <p>Result is published as logcat lines tagged {@code KokoroTtsTest} and as a
 * broadcast, so the harness can wait for completion rather than sleeping.
 */
public class EngineTestActivity extends Activity {

    static final String TAG = "KokoroTtsTest";
    static final String ACTION_DONE = "dev.kokoro.tts.TEST_DONE";

    private TextToSpeech tts;
    private String utteranceId = "kokoro-test";
    private String outPath;
    private long startedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String text = getIntent().getStringExtra("text");
        if (text == null) {
            text = "Hello from the Kokoro text to speech engine.";
        }
        outPath = getIntent().getStringExtra("out");
        if (outPath == null) {
            outPath = "/sdcard/Download/kokoro.wav";
        }
        final String finalText = text;

        Log.i(TAG, "ENGINE_TEST_START chars=" + text.length() + " out=" + outPath);

        // Which TTS engines does the framework actually expose to us? Without
        // this, an engine that fails to register looks identical to one that
        // registers and then fails to bind.
        try {
            java.util.List<android.speech.tts.TextToSpeech.EngineInfo> engines =
                    new TextToSpeech(this, null).getEngines();
            for (android.speech.tts.TextToSpeech.EngineInfo info : engines) {
                Log.i(TAG, "ENGINE_AVAILABLE name=" + info.name
                        + " label=" + info.label);
            }
            Log.i(TAG, "ENGINE_COUNT " + engines.size());
        } catch (Throwable t) {
            Log.w(TAG, "could not list engines", t);
        }

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    finishWith("INIT_FAILED status=" + status);
                    return;
                }
                // Explicitly bind to this engine so the test never silently uses
                // whichever engine happens to be the system default.
                int set = tts.setEngineByPackageName(getPackageName());
                Log.i(TAG, "setEngineByPackageName=" + set);
                tts.setLanguage(Locale.US);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        Log.i(TAG, "UTTERANCE_START id=" + id);
                    }

                    @Override
                    public void onDone(String id) {
                        File f = new File(outPath);
                        long elapsed = System.currentTimeMillis() - startedAt;
                        Log.i(TAG, "ENGINE_TEST_DONE id=" + id
                                + " bytes=" + (f.exists() ? f.length() : -1)
                                + " ms=" + elapsed);
                        finishWith("DONE bytes=" + (f.exists() ? f.length() : -1));
                    }

                    @Override
                    public void onError(String id) {
                        Log.e(TAG, "ENGINE_TEST_ERROR id=" + id);
                        finishWith("ERROR id=" + id);
                    }

                    @Override
                    public void onError(String id, int errorCode) {
                        Log.e(TAG, "ENGINE_TEST_ERROR id=" + id + " code=" + errorCode);
                        finishWith("ERROR id=" + id + " code=" + errorCode);
                    }
                });

                // Optional: exercise the app-explicit path, i.e. TextToSpeech.setVoice().
                String wantVoice = getIntent().getStringExtra("voice");
                if (wantVoice != null && !wantVoice.isEmpty()) {
                    try {
                        tts.setVoice(new android.speech.tts.Voice(wantVoice, Locale.US,
                                android.speech.tts.Voice.QUALITY_VERY_HIGH,
                                android.speech.tts.Voice.LATENCY_NORMAL, false,
                                new java.util.HashSet<String>()));
                        Log.i(TAG, "app called setVoice(" + wantVoice + ")");
                    } catch (Throwable t) {
                        Log.w(TAG, "setVoice failed", t);
                    }
                }

                Bundle params = new Bundle();
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
                startedAt = System.currentTimeMillis();
                File out = new File(outPath);
                File dir = out.getParentFile();
                if (dir != null && !dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                // Optional second utterance, queued behind the first, to measure
                // whether the framework pipelines synthesis or waits for playback.
                String second = getIntent().getStringExtra("text2");
                if (second != null && !second.isEmpty()) {
                    Bundle p2 = new Bundle();
                    int rc2 = tts.speak(second, TextToSpeech.QUEUE_ADD, p2, "kokoro-second");
                    Log.i(TAG, "queued second utterance via QUEUE_ADD rc=" + rc2);
                }
                // Optional: ask the framework to stop part way through, so the
                // rejection-after-stop path can be exercised deliberately.
                int stopAfter = getIntent().getIntExtra("stop_after_ms", 0);
                if (stopAfter > 0) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                            new Runnable() {
                                @Override
                                public void run() {
                                    Log.i(TAG, "calling tts.stop() after " + stopAfter + " ms");
                                    if (tts != null) tts.stop();
                                }
                            }, stopAfter);
                }
                int rc = tts.synthesizeToFile(finalText, params, out, utteranceId);
                Log.i(TAG, "synthesizeToFile rc=" + rc);
                if (rc != TextToSpeech.SUCCESS) {
                    finishWith("SYNTH_CALL_FAILED rc=" + rc);
                }
            }
        }, getPackageName());
    }

    private void finishWith(String detail) {
        Log.i(TAG, "ENGINE_TEST_RESULT " + detail);
        android.content.Intent i = new android.content.Intent(ACTION_DONE);
        i.putExtra("detail", detail);
        sendBroadcast(i);
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {
            // shutting down a half-initialised engine is not interesting
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
