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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
    // Shared with SettingsActivity so keys and defaults cannot drift apart.
    private static final String KEY_ADD_GAPS = SettingsActivity.KEY_ADD_GAPS;
    private static final String KEY_TRIM_SILENCE = SettingsActivity.KEY_TRIM_SILENCE;
    private static final String KEY_APPS_OVERRIDE_VOICE =
            SettingsActivity.KEY_APPS_OVERRIDE_VOICE;
    /** Amplitude below which a sample counts as silence, in int16 units (~0.6% FS). */
    private static final int SILENCE_THRESHOLD = 200;
    /** Silence kept either side of speech so a soft release is not clipped. */
    private static final int TRIM_GUARD_MS = 25;
    /** 0 leaves Kokoro's own long pauses alone. */
    private static final String KEY_MAX_PAUSE_MS = SettingsActivity.KEY_MAX_PAUSE_MS;
    /**
     * Silence kept before the first word of the utterance.
     *
     * <p>The audio output path needs samples to spin up; if speech begins at sample
     * zero the attack of the first word is lost. The server's own 50 ms of leading
     * padding used to hide this, so trimming it away without keeping a replacement
     * introduced exactly that bug. Only the first chunk needs it: the rest are
     * mid-stream in one continuous track.
     */
    private static final int FIRST_CHUNK_LEAD_IN_MS = 80;
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
    /** Last voice an app asked to load; only consulted when apps may override. */
    private volatile String requestedVoice;
    private volatile Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                        if (KEY_VOICE.equals(key)) {
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
     * <p>By default the engine's own setting wins outright, because that is the
     * whole point of picking a voice. This used to be inferred from timestamps --
     * honour an app's voice only if it was loaded after the user's last change --
     * and that was wrong: the timestamp was per-process, so after any restart it
     * read as zero and the first voice an app loaded looked newer than the user's
     * choice. Selection then appeared to work immediately after being set and to
     * stop working later, which is a confusing way to fail.
     *
     * <p>{@code apps_override_voice} turns the classic Android behaviour back on,
     * where an app's {@code setVoice()} wins. Off by default.
     */
    private String voice(SynthesisRequest request) {
        String prefVoice = pref(KEY_VOICE, DEFAULT_VOICE);
        boolean appsMayOverride = prefBool(KEY_APPS_OVERRIDE_VOICE, false);

        String requested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                ? request.getVoiceName() : null;
        if (requested == null || requested.isEmpty()) requested = requestedVoice;

        boolean useApp = appsMayOverride && requested != null && !requested.isEmpty();
        String chosen = useApp ? requested : prefVoice;

        Log.i(TAG, "voice: default=" + prefVoice + " requested=" + requested
                + " appsOverride=" + appsMayOverride
                + " -> " + chosen + (useApp ? " (app choice)" : " (engine default)"));
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
        Log.i(TAG, "voice loaded by client: " + voiceName + " (not persisted)");
        return TextToSpeech.SUCCESS;
    }

    @Override
    protected void onStop() {
        stopped = true;
        // Worth logging: after a stop, audioAvailable() correctly returns an error,
        // and without this line that rejection looks like a fault in the log.
        Log.i(TAG, "stop requested by the client");
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
        // Apps that insert their own pauses between utterances would otherwise get
        // this engine's pauses on top of theirs, which reads as choppy. The setting
        // screen exists so the user can turn ours off.
        boolean addGaps = prefBool(KEY_ADD_GAPS, true);
        int midSentenceGapMs = addGaps
                ? prefInt(SettingsActivity.KEY_MID_SENTENCE_GAP_MS,
                          SettingsActivity.DEFAULT_MID_GAP_MS)
                : 0;
        boolean trimSilence = prefBool(KEY_TRIM_SILENCE, true);
        int paragraphGapMs = addGaps
                ? prefInt(SettingsActivity.KEY_PARAGRAPH_GAP_MS,
                          SettingsActivity.DEFAULT_PARA_GAP_MS)
                : 0;

        int declaredMax = callback.getMaxBufferSize();
        int maxBuffer = declaredMax > 0 ? declaredMax : 8192;

        StringBuilder trace = new StringBuilder();
        int silenceInsertedMs = 0;
        int silenceTrimmedMs = 0;
        int totalWaitMs = 0;
        // Wall time from starting the first request to the first audio handed to
        // the framework: the number that decides whether playback keeps up.
        long requestStartMs = System.currentTimeMillis();
        final long[] firstAudio = {-1};
        long startedAt = System.currentTimeMillis();
        int chunkIndex = 0;

        // Prefetch the next chunk while the current one is still being written.
        //
        // Measured 2026-10-12: Kokoro-FastAPI processes one request at a time (two
        // concurrent requests each took 5.0 s versus 2.7 s + 2.8 s sequential), so
        // this does not shorten the total or the first chunk's latency. What it
        // fixes is idleness: audioAvailable() blocks once the framework's buffer
        // fills, and without prefetching the server would sit unused for the whole
        // time we are writing and playing the current chunk.
        final java.util.concurrent.BlockingQueue<Object[]> ready =
                new java.util.concurrent.ArrayBlockingQueue<>(2);
        // Identity marker: an Object[] so it can live in the same queue.
        final Object[] poison = new Object[0];
        final KokoroSpeechClient fetchClient = client;
        final List<KokoroSplitter.Chunk> fetchChunks = chunks;
        final String fetchVoice = voice;
        final String fetchModel = model;
        final String fetchFormat = fmt;
        final float fetchSpeed = speed;

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    for (int i = 0; i < fetchChunks.size() && !stopped; i++) {
                        KokoroSplitter.Chunk c = fetchChunks.get(i);
                        java.io.ByteArrayOutputStream buffer =
                                new java.io.ByteArrayOutputStream(512 * 1024);
                        final java.io.ByteArrayOutputStream sinkBuffer = buffer;
                        KokoroSpeechClient.AudioSink sink = new KokoroSpeechClient.AudioSink() {
                            @Override
                            public void write(byte[] data, int offset, int length) {
                                sinkBuffer.write(data, offset, length);
                            }
                        };
                        KokoroSpeechClient.StreamResult result;
                        try {
                            result = fetchClient.synthesizeStreaming(
                                    new KokoroSpeechClient.Request(c.text, fetchVoice,
                                            fetchSpeed, fetchModel, fetchFormat), sink);
                        } catch (Exception e) {
                            Log.e(TAG, "fetch of chunk " + i + " failed", e);
                            ready.put(new Object[]{i, null, null});
                            return;
                        }
                        ready.put(new Object[]{i, buffer.toByteArray(), result});
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    try {
                        ready.put(poison);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        producer.setDaemon(true);
        producer.start();

        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (stopped) {
                    Log.i(TAG, "stopped before chunk " + chunkIndex);
                    return;
                }

                Object[] item = ready.take();
                if (item == poison) break;

                KokoroSplitter.Chunk chunk = chunks.get(i);
                byte[] audio = (byte[]) item[1];
                KokoroSpeechClient.StreamResult result =
                        (KokoroSpeechClient.StreamResult) item[2];

                if (result == null || !result.isOk()) {
                    // A failure here is the exact failure this driver exists to
                    // prevent; the estimate and the server's answer are both logged.
                    int status = result == null ? -1 : result.httpStatus;
                    Log.e(TAG, "chunk " + chunkIndex + " failed: http=" + status
                            + " est=" + chunk.estimatedTokens
                            + " chars=" + chunk.text.length()
                            + " body=" + (result == null ? "fetch error" : result.errorBody));
                    callback.error(status > 0 ? status : -1);
                    return;
                }

                int from = 0;
                int playLength = audio.length;
                int trimFront = 0;
                int trimBack = 0;
                int cappedMs = 0;
                if (trimSilence) {
                    // Kokoro pads every chunk with ~50 ms lead and ~360 ms trail, so a
                    // seam otherwise carries ~410 ms of silence nobody asked for. The
                    // first chunk additionally keeps a lead-in so the output path can
                    // start before speech arrives and the first word is not clipped.
                    Pcm.Span span = Pcm.audibleSpan(audio, SILENCE_THRESHOLD,
                            TRIM_GUARD_MS, sampleRate,
                            chunkIndex == 0 ? FIRST_CHUNK_LEAD_IN_MS : 0);
                    from = span.offset;
                    playLength = span.length;
                    trimFront = span.leadingMs;
                    trimBack = span.trailingMs;
                    silenceTrimmedMs += trimFront + trimBack;
                }

                // Kokoro's own long pauses survive trimming, so cap them if asked.
                int maxPauseMs = prefInt(KEY_MAX_PAUSE_MS, 0);
                byte[] play = audio;
                if (from != 0 || playLength != audio.length) {
                    play = java.util.Arrays.copyOfRange(audio, from, from + playLength);
                }
                if (maxPauseMs > 0) {
                    int before = play.length;
                    play = Pcm.capSilences(play, SILENCE_THRESHOLD, maxPauseMs, sampleRate);
                    cappedMs = (before - play.length) / 2 * 1000 / sampleRate;
                }

                Log.i(TAG, "chunk " + chunkIndex + "/" + chunks.size()
                        + " chars=" + chunk.text.length()
                        + " est=" + chunk.estimatedTokens
                        + " send=" + result.sendMs + "ms"
                        + " wait=" + result.waitMs + "ms"
                        + " body=" + result.bodyMs + "ms"
                        + " bytes=" + result.bytes
                        + " trimFront=" + trimFront + "ms trimBack=" + trimBack + "ms"
                        + (cappedMs > 0 ? " cappedPauses=" + cappedMs + "ms" : ""));

                totalWaitMs += result.waitMs;

                if (firstAudio[0] < 0) {
                    firstAudio[0] = System.currentTimeMillis() - requestStartMs;
                }
                for (int off = 0; off < play.length; off += maxBuffer) {
                    if (stopped) return;
                    int len = Math.min(maxBuffer, play.length - off);
                    if (callback.audioAvailable(play, off, len) != TextToSpeech.SUCCESS) {
                        // Normal when playback was stopped: the framework closes the
                        // callback and rejects anything still in flight. Only a
                        // genuine fault if nothing asked us to stop.
                        Log.i(TAG, "audioAvailable rejected at chunk " + chunkIndex
                                + (stopped ? " (after a stop request - expected)"
                                           : " (no stop request - unexpected)"));
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
                        silenceInsertedMs += gapMs;
                    }
                }
                chunkIndex++;
            }
            callback.done();
            Log.i(TAG, "synth ok: chars=" + text.length()
                    + " chunks=" + chunks.size()
                    + " estTokens=" + trace
                    + " voice=" + voice
                    + " pauses=" + (addGaps ? midSentenceGapMs + "/" + paragraphGapMs + "ms"
                                            : "off")
                    + " inserted=" + silenceInsertedMs + "ms"
                    + " trimmed=" + silenceTrimmedMs + "ms"
                    + " avgWait=" + (chunkIndex > 0 ? totalWaitMs / chunkIndex : 0) + "ms"
                    + " firstAudio=" + firstAudio[0] + "ms"
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
