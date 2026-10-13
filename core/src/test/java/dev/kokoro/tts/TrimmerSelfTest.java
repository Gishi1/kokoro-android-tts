package dev.kokoro.tts;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * Isolated test for {@link Pcm}.
 *
 * <p>Synthetic PCM with known silence boundaries, so a trimming bug shows up here
 * rather than as a faint artefact in real audio. The engine uses the buffered path
 * because measurement showed Kokoro-FastAPI sends no body bytes until it has
 * synthesised the whole chunk, so there is nothing to stream.
 */
public final class TrimmerSelfTest {

    private static int failures = 0;
    private static int checks = 0;

    private static final int RATE = 24000;

    public static void main(String[] args) {
        // 360 ms silence, 1000 ms tone, 360 ms silence — the shape of a real chunk.
        byte[] silence = new byte[bytes(360)];
        byte[] tone = tone(bytes(1000), 8000);
        byte[] chunk = concat(silence, tone, silence);

        // ---- buffered path
        Pcm.Span span = Pcm.audibleSpan(chunk, 200, 25, RATE, 80);
        int bufferedFrontMs = span.leadingMs;
        int bufferedBackMs = span.trailingMs;
        check("buffered: trims most of the 360 ms front padding (got " + bufferedFrontMs + ")",
                bufferedFrontMs >= 250 && bufferedFrontMs <= 360);
        check("buffered: trims most of the 360 ms back padding (got " + bufferedBackMs + ")",
                bufferedBackMs >= 250 && bufferedBackMs <= 360);

        // ---- pausing across a seam
        // Chunk A ends with 300 ms of silence, chunk B starts with 300 ms of it. Capped
        // independently they leave 600 ms at the join, which is the bug this covers.
        byte[] a = concat(tone(bytes(400), 9000), new byte[bytes(300)]);
        byte[] b = concat(new byte[bytes(300)], tone(bytes(400), 9000));
        byte[] aCapped = Pcm.capSilences(a, 200, 350, RATE);
        int aTail = Pcm.trailingSilenceMs(aCapped, 200, RATE);
        byte[] bCapped = Pcm.capSilences(b, 200, 350, RATE, Math.max(0, 350 - aTail));
        int bHead = leadingSilenceMs(bCapped);
        check("seam silence is capped as one pause (" + (aTail + bHead) + " ms)",
                aTail + bHead <= 350 + 15);

        System.out.println();
        if (failures == 0) {
            System.out.println("TRIMMER SELF-TEST PASSED (" + checks + " checks)");
        } else {
            System.out.println("TRIMMER SELF-TEST FAILED: " + failures + " of " + checks);
            System.exit(1);
        }
    }

    /** Milliseconds of silence at the start of a buffer. */
    private static int leadingSilenceMs(byte[] pcm) {
        int run = 0;
        int samples = pcm.length / 2;
        while (run < samples) {
            int v = ((pcm[2 * run + 1] << 8) | (pcm[2 * run] & 0xFF));
            if (v < 0) v = -v;
            if (v >= 200) break;
            run++;
        }
        return run * 1000 / RATE;
    }

    private static int bytes(int ms) {
        return ms * RATE / 1000 * 2;
    }

    private static byte[] tone(int length, int amplitude) {
        byte[] out = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            short v = (short) (amplitude * Math.sin(2 * Math.PI * 220 * (i / 2) / RATE));
            out[i] = (byte) (v & 0xFF);
            out[i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    /** Longest run of samples below the silence threshold, in ms. */
    private static int longestSilenceMs(byte[] pcm) {
        int best = 0;
        int cur = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int lo = pcm[i] & 0xFF;
            int hi = pcm[i + 1];
            int v = (hi << 8) | lo;
            if (v < 0) v = -v;
            if (v < 200) {
                cur++;
                if (cur > best) best = cur;
            } else {
                cur = 0;
            }
        }
        return best * 1000 / RATE;
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what);
        }
    }
}
