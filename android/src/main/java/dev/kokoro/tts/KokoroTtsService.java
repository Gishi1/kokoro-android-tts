package dev.kokoro.tts;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Android system TTS engine backed by a Kokoro server.
 *
 * <p>The engine is deliberately thin: all of the Kokoro-specific intelligence
 * lives in {@link KokoroSplitter}, which is pure Java and unit-tested off-device.
 * The job of this class is to drive the framework contract correctly and to
 * stream one split chunk at a time so that time-to-first-audio does not depend
 * on the length of the utterance.
 *
 * <p>Configuration is read from SharedPreferences so an automated test can set
 * it with adb without a settings UI:
 *
 * <pre>
 *   adb shell "run-as dev.kokoro.tts cat /data/data/dev.kokoro.tts/shared_prefs/kokoro.xml"
 * </pre>
 */
public class KokoroTtsService extends TextToSpeechService {

    static final String TAG = "KokoroTts";

    private static final String PREFS = "kokoro";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_VOICE = "voice";
    private static final String KEY_MODEL = "model";
    private static final String KEY_FORMAT = "format";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_HARD_CAP = "hard_cap";
    private static final String KEY_TARGET_MIN = "target_min";
    private static final String KEY_TARGET_MAX = "target_max";
    private static final String KEY_MID_SENTENCE_GAP_MS = "mid_sentence_gap_ms";
    private static final String KEY_PARAGRAPH_GAP_MS = "paragraph_gap_ms";
    private static final String KEY_CONNECT_TIMEOUT_MS = "connect_timeout_ms";
    private static final String KEY_READ_TIMEOUT_MS = "read_timeout_ms";
    private static final String KEY_STRIP_EMOJI = "strip_emoji";
    private static final String KEY_VOICE_LIST = "voice_list";

    static final String DEFAULT_ENDPOINT = "http://10.0.2.2:8880/v1/audio/speech";
    static final String DEFAULT_VOICE = "af_heart";
    static final String DEFAULT_MODEL = "kokoro";

    /** Voices the driver advertises. Any Kokoro voice id works if set explicitly. */
    private static final String[] VOICES = {
            "af_heart", "af_bella", "af_nicole", "af_sarah", "af_sky",
            "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis",
    };

    private SharedPreferences prefs;
    private volatile boolean stopped;

    /**
     * Voice the framework asked to load, and when, versus when the user last
     * changed the engine's own default voice.
     *
     * <p>These exist because {@code onLoadVoice} and the settings screen were both
     * writing {@code KEY_VOICE}. The framework calls {@code onLoadVoice} to load a
     * voice into a client's session — including the *default*, which a long-lived
     * client may have resolved before the user changed anything. Persisting that
     * value silently reverted the user's selection, so the voice appeared not to
     * change. The user's choice is now owned by the settings screen alone.
     */
    private volatile String requestedVoice;
    private volatile long requestedVoiceAtMs;
    private volatile long voicePrefChangedAtMs;
    private volatile Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // The settings screen writes KEY_VOICE; note when, so a stale voice that
        // the framework loaded earlier cannot override the newer choice.
        prefs.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                        if (KEY_VOICE.equals(key)) {
                            voicePrefChangedAtMs = android.os.SystemClock.elapsedRealtime();
                            requestedVoice = null;
                            Log.i(TAG, "default voice set to '"
                                    + pref(KEY_VOICE, DEFAULT_VOICE) + "'");
                        }
                    }
                });
        Log.i(TAG, "engine created; endpoint=" + endpoint());
    }

    // ---------------------------------------------------------------- config

    /**
     * Type-safe reads.
     *
     * <p>SharedPreferences getters throw {@link ClassCastException} when the stored
     * value has a different type than requested, and an exception escaping
     * {@link #onSynthesizeText} kills the whole engine process — observed on a real
     * device when a helper script wrote {@code strip_emoji} as a string. A malformed
     * preference must degrade to the default, never take the engine down.
     */
    private String pref(String key, String def) {
        try {
            String v = prefs.getString(key, def);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            Log.w(TAG, "preference '" + key + "' has the wrong type; using default '" + def + "'");
            return def;
        }
    }

    private int prefInt(String key, int def) {
        try {
            return prefs.getInt(key, def);
        } catch (ClassCastException e) {
            // Accept a numeric string too, so an adb-written file still works.
            try {
                return Integer.parseInt(pref(key, String.valueOf(def)).trim());
            } catch (RuntimeException ignored) {
                Log.w(TAG, "preference '" + key + "' has the wrong type; using default " + def);
                return def;
            }
        }
    }

    private boolean prefBool(String key, boolean def) {
        try {
            return prefs.getBoolean(key, def);
        } catch (ClassCastException e) {
            String v = pref(key, String.valueOf(def)).trim();
            Log.w(TAG, "preference '" + key + "' has the wrong type; using " + v);
            return Boolean.parseBoolean(v);
        }
    }

    private String endpoint() {
        return pref(KEY_ENDPOINT, DEFAULT_ENDPOINT);
    }

    /**
     * Decide which voice this utterance uses.
     *
     * <p>Precedence: the user's choice in the engine settings screen wins, because
     * that is the whole point of picking one. An app that explicitly loaded a voice
     * <em>after</em> that choice is honoured for its own session. A voice the
     * framework loaded <em>before</em> the choice is treated as a stale default and
     * ignored — which is the case that made selection appear to do nothing.
     */
    private String voice(SynthesisRequest request) {
        String prefVoice = pref(KEY_VOICE, DEFAULT_VOICE);

        String requested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? request.getVoiceName() : null;
        if (requested == null || requested.isEmpty()) requested = requestedVoice;

        boolean appChoseItSince = requested != null && !requested.isEmpty()
                && !requested.equals(prefVoice)
                && requestedVoiceAtMs > voicePrefChangedAtMs;

        String chosen = appChoseItSince ? requested : prefVoice;
        Log.i(TAG, "voice: default=" + prefVoice + " requested=" + requested
                + " -> " + chosen + (appChoseItSince ? " (app choice)" : " (default)"));
        return chosen;
    }

    private KokoroSplitter newSplitter() {
        return new KokoroSplitter(
                new KokoroCostModel(),
                prefInt(KEY_TARGET_MIN, KokoroSplitter.DEFAULT_TARGET_MIN),
                prefInt(KEY_TARGET_MAX, KokoroSplitter.DEFAULT_TARGET_MAX),
                prefInt(KEY_HARD_CAP, KokoroSplitter.DEFAULT_HARD_CAP),
                prefBool(KEY_STRIP_EMOJI, true));
    }

    // ------------------------------------------------------- framework hooks

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"eng", "USA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return TextToSpeech.LANG_AVAILABLE;
    }

    /**
     * Advertise the voices this engine can actually speak.
     *
     * <p>The list is whatever the settings screen last fetched from the server's
     * {@code /v1/audio/voices} and cached here, because a Kokoro deployment's
     * voices belong to the server, not to this client — the server this was built
     * against offers 67, against the 11 a hardcoded array would have shown. Falls
     * back to a small built-in list until the settings screen has run once.
     */
    @Override
    public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return out;

        List<String> names = SettingsActivity.split(pref(KEY_VOICE_LIST, null));
        if (names.isEmpty()) names = Arrays.asList(VOICES);

        Set<String> features = new java.util.HashSet<>();
        for (String name : names) {
            out.add(new Voice(name, Locale.US, Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL, false, features));
        }
        return out;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return pref(KEY_VOICE, DEFAULT_VOICE);
    }

    @Override
    public int onIsValidVoiceName(String voiceName) {
        return TextToSpeech.SUCCESS;
    }

    @Override
    public int onLoadVoice(String voiceName) {
        // Record only. Persisting here is what reverted the user's selection: the
        // framework loads the default voice into every new client session, and a
        // client that resolved the default before the user changed it hands the
        // old name straight back.
        requestedVoice = voiceName;
        requestedVoiceAtMs = android.os.SystemClock.elapsedRealtime();
        Log.i(TAG, "voice loaded by client: " + voiceName + " (not persisted)");
        return TextToSpeech.SUCCESS;
    }

    @Override
    protected void onStop() {
        stopped = true;
        Thread t = worker;
        if (t != null) t.interrupt();
    }

    // -------------------------------------------------------------- synthesis

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        // Nothing may escape here. An unchecked exception from onSynthesizeText
        // propagates into the TTS framework and kills the engine process, which
        // the user experiences as the voice disappearing entirely. Every failure
        // must end in callback.error() instead.
        try {
            synthesize(request, callback);
        } catch (Throwable t) {
            Log.e(TAG, "synthesis aborted by an unexpected error", t);
            try {
                callback.error(-1);
            } catch (Throwable ignored) {
                // the callback may already be closed; nothing useful left to do
            }
        }
    }

    private void synthesize(SynthesisRequest request, SynthesisCallback callback)
            throws Exception {
        stopped = false;
        CharSequence cs = request.getCharSequenceText();
        String text = cs == null ? "" : cs.toString();

        int sampleRate = KokoroSpeechClient.SAMPLE_RATE_HZ;
        int format = android.media.AudioFormat.ENCODING_PCM_16BIT;

        float speed = request.getSpeechRate() / 100f;
        if (speed <= 0f) speed = prefInt(KEY_SPEED, 100) / 100f;
        if (speed < 0.25f) speed = 0.25f;
        if (speed > 4.0f) speed = 4.0f;

        KokoroSplitter splitter = newSplitter();
        List<KokoroSplitter.Chunk> chunks = splitter.split(text);

        int startRc = callback.start(sampleRate, format, 1);
        if (startRc != TextToSpeech.SUCCESS) {
            Log.e(TAG, "callback.start() refused: " + startRc);
            return;
        }
        if (chunks.isEmpty()) {
            callback.done();
            return;
        }

        KokoroSpeechClient client = new KokoroSpeechClient(
                endpoint(),
                prefInt(KEY_CONNECT_TIMEOUT_MS, 5000),
                prefInt(KEY_READ_TIMEOUT_MS, 60000));

        String voice = voice(request);
        String model = pref(KEY_MODEL, DEFAULT_MODEL);
        String fmt = pref(KEY_FORMAT, "pcm");
        int midSentenceGapMs = prefInt(KEY_MID_SENTENCE_GAP_MS, 90);
        int paragraphGapMs = prefInt(KEY_PARAGRAPH_GAP_MS, 280);

        int declaredMax = callback.getMaxBufferSize();
        int maxBuffer = declaredMax > 0 ? declaredMax : 8192;

        StringBuilder trace = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        int chunkIndex = 0;

        try {
            for (KokoroSplitter.Chunk chunk : chunks) {
                if (stopped) {
                    Log.i(TAG, "stopped before chunk " + chunkIndex);
                    return;
                }
                KokoroSpeechClient.Response response = client.synthesize(
                        new KokoroSpeechClient.Request(chunk.text, voice, speed, model, fmt));

                if (!response.isOk()) {
                    // A failure here is the exact failure this driver exists to
                    // prevent; the estimate and the server's answer are both logged.
                    Log.e(TAG, "chunk " + chunkIndex + " failed: http=" + response.httpStatus
                            + " est=" + chunk.estimatedTokens
                            + " chars=" + chunk.text.length()
                            + " body=" + response.errorBody);
                    callback.error(response.httpStatus > 0 ? response.httpStatus : -1);
                    return;
                }

                byte[] audio = response.audio;
                // Stream in callback-sized pieces; the framework may cap each call.
                for (int off = 0; off < audio.length; off += maxBuffer) {
                    if (stopped) return;
                    int len = Math.min(maxBuffer, audio.length - off);
                    int ret = callback.audioAvailable(audio, off, len);
                    if (ret != TextToSpeech.SUCCESS) {
                        Log.e(TAG, "audioAvailable returned " + ret + " at chunk " + chunkIndex);
                        return;
                    }
                }

                trace.append(chunk.estimatedTokens);
                if (chunkIndex < chunks.size() - 1) {
                    trace.append(chunk.paraEnd ? '|' : '/');
                    // Three gap tiers, following ao3-reader's narration model:
                    // a paragraph end gets the longest pause, a sentence end
                    // inside a paragraph needs none (the audio already has it),
                    // and a seam cut mid-sentence gets a short bridging pause so
                    // it does not sound like a full stop. The seam itself is
                    // independently reported as audible (kokoro issue #200).
                    int gapMs = chunk.paraEnd ? paragraphGapMs
                            : (endsSentence(chunk.text) ? 0 : midSentenceGapMs);
                    if (gapMs > 0) {
                        writeSilence(callback, sampleRate, gapMs, maxBuffer);
                    }
                }
                chunkIndex++;
            }
            callback.done();
            Log.i(TAG, "synth ok: chars=" + text.length()
                    + " chunks=" + chunks.size()
                    + " estTokens=" + trace
                    + " voice=" + voice
                    + " ms=" + (System.currentTimeMillis() - startedAt));
        } catch (Exception e) {
            Log.e(TAG, "synthesis failed", e);
            callback.error(-1);
        }
    }

    private static boolean endsSentence(String text) {
        String t = text.trim();
        if (t.isEmpty()) return false;
        char c = t.charAt(t.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '…'
                || c == '。' || c == '！' || c == '？' || c == '；';
    }

    /** Insert silence, so a chunk cut mid-sentence does not sound like a full stop. */
    private void writeSilence(SynthesisCallback callback, int sampleRate, int ms, int maxBuffer)
            throws Exception {
        int bytes = (sampleRate * ms / 1000) * 2; // 16-bit mono
        byte[] silence = new byte[Math.min(bytes, maxBuffer)];
        int remaining = bytes;
        while (remaining > 0 && !stopped) {
            int len = Math.min(remaining, silence.length);
            if (callback.audioAvailable(silence, 0, len) != TextToSpeech.SUCCESS) return;
            remaining -= len;
        }
    }
}
