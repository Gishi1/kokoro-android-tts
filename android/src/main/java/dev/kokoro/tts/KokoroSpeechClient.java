package dev.kokoro.tts;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal OpenAI-compatible {@code /v1/audio/speech} client.
 *
 * <p>Implemented on {@link HttpURLConnection} rather than OkHttp so the driver
 * has no third-party dependencies and can be built without a Gradle dependency
 * resolution step.
 *
 * <p>Requests raw PCM ({@code response_format=pcm}) instead of WAV so the bytes
 * can be forwarded straight into {@code SynthesisCallback.audioAvailable()}
 * without parsing a container per chunk. Kokoro emits 24 kHz mono signed 16-bit
 * little-endian.
 */
final class KokoroSpeechClient {

    static final int SAMPLE_RATE_HZ = 24000;
    private static final String TAG = "KokoroTts";

    /** One synthesis request. */
    static final class Request {
        final String text;
        final String voice;
        final float speed;
        final String model;
        final String format;

        Request(String text, String voice, float speed, String model, String format) {
            this.text = text;
            this.voice = voice;
            this.speed = speed;
            this.model = model;
            this.format = format;
        }
    }

    /** Result of one request, including the server's status for diagnosis. */
    static final class Response {
        final int httpStatus;
        final byte[] audio;
        final String errorBody;

        Response(int httpStatus, byte[] audio, String errorBody) {
            this.httpStatus = httpStatus;
            this.audio = audio;
            this.errorBody = errorBody;
        }

        boolean isOk() {
            return httpStatus >= 200 && httpStatus < 300 && audio != null && audio.length > 0;
        }
    }

    private final String endpoint;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    KokoroSpeechClient(String endpoint, int connectTimeoutMs, int readTimeoutMs) {
        this.endpoint = endpoint;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    Response synthesize(Request request) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "*/*");

            JSONObject body = new JSONObject();
            try {
                body.put("model", request.model);
                body.put("input", request.text);
                body.put("voice", request.voice);
                body.put("response_format", request.format);
                body.put("speed", request.speed);
            } catch (org.json.JSONException e) {
                throw new IOException("could not build request body", e);
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String err = readAll(conn.getErrorStream());
                Log.w(TAG, "server returned " + status + " for " + request.text.length()
                        + " chars: " + err);
                return new Response(status, null, err);
            }
            byte[] audio = readAllBytes(conn.getInputStream());
            return new Response(status, audio, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        byte[] bytes = readAllBytes(in);
        String s = new String(bytes, StandardCharsets.UTF_8);
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
