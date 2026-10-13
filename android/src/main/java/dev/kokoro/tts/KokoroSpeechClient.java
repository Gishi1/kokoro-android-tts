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

    /**
     * Result of one request, including timings.
     *
     * <p>The three phases matter for telling a slow server apart from slow request
     * handling. Kokoro synthesises a whole chunk before answering and does not
     * stream, so {@link #waitMs} is nearly all server compute, while
     * {@link #sendMs} is connection setup and upload and {@link #bodyMs} is
     * transfer. If {@code waitMs} dominates, the fix is smaller chunks or a
     * streaming server; if {@code sendMs} does, it is the network or connection
     * reuse.
     */
    static final class Response {
        final int httpStatus;
        final byte[] audio;
        final String errorBody;
        /** Building and sending the request. */
        final int sendMs;
        /** Waiting for the response headers: the server's own synthesis time. */
        final int waitMs;
        /** Reading the body off the wire. */
        final int bodyMs;
        /** sendMs + waitMs + bodyMs. */
        final int totalMs;

        Response(int httpStatus, byte[] audio, String errorBody,
                 int sendMs, int waitMs, int bodyMs) {
            this.httpStatus = httpStatus;
            this.audio = audio;
            this.errorBody = errorBody;
            this.sendMs = sendMs;
            this.waitMs = waitMs;
            this.bodyMs = bodyMs;
            this.totalMs = sendMs + waitMs + bodyMs;
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

    /** Receives response-body bytes as they arrive from the server. */
    interface AudioSink {
        void write(byte[] data, int offset, int length);
    }

    /** Outcome of a streamed request. */
    static final class StreamResult {
        final int httpStatus;
        final String errorBody;
        final int sendMs;
        final int waitMs;
        final int bodyMs;
        final long bytes;

        StreamResult(int httpStatus, String errorBody, int sendMs, int waitMs,
                     int bodyMs, long bytes) {
            this.httpStatus = httpStatus;
            this.errorBody = errorBody;
            this.sendMs = sendMs;
            this.waitMs = waitMs;
            this.bodyMs = bodyMs;
            this.bytes = bytes;
        }

        boolean isOk() {
            return httpStatus >= 200 && httpStatus < 300;
        }

        int totalMs() {
            return sendMs + waitMs + bodyMs;
        }
    }

    /**
     * POST and hand the body to {@code sink} as it arrives, instead of buffering it.
     *
     * <p>Kokoro-FastAPI streams: measured on 2026-10-12 the headers arrive in about
     * 5 ms while the body takes 1.4-2.8 s, because the server synthesises audio as
     * it sends. Reading the whole body first therefore delays the first sound by the
     * entire synthesis time for no reason.
     */
    StreamResult synthesizeStreaming(Request request, AudioSink sink) throws IOException {
        HttpURLConnection conn = null;
        long t0 = System.nanoTime();
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
            int sendMs = millisSince(t0);

            int status = conn.getResponseCode();
            int waitMs = millisSince(t0) - sendMs;
            if (status < 200 || status >= 300) {
                String err = readAll(conn.getErrorStream());
                return new StreamResult(status, err, sendMs, waitMs, 0, 0);
            }

            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            try (InputStream in = conn.getInputStream()) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    sink.write(buffer, 0, n);
                    total += n;
                }
            }
            int bodyMs = millisSince(t0) - sendMs - waitMs;
            return new StreamResult(status, null, sendMs, waitMs, bodyMs, total);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    Response synthesize(Request request) throws IOException {
        HttpURLConnection conn = null;
        long t0 = System.nanoTime();
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
            int sendMs = millisSince(t0);

            int status = conn.getResponseCode();
            int waitMs = millisSince(t0) - sendMs;
            if (status < 200 || status >= 300) {
                String err = readAll(conn.getErrorStream());
                Log.w(TAG, "server returned " + status + " for " + request.text.length()
                        + " chars: " + err);
                return new Response(status, null, err, sendMs, waitMs, 0);
            }
            byte[] audio = readAllBytes(conn.getInputStream());
            int bodyMs = millisSince(t0) - sendMs - waitMs;
            return new Response(status, audio, null, sendMs, waitMs, bodyMs);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int millisSince(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
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
