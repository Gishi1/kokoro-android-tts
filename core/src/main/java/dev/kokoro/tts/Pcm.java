package dev.kokoro.tts;

/**
 * Helpers for the raw 16-bit PCM Kokoro returns.
 *
 * <p>Kokoro pads <em>every</em> chunk with silence. Measured against a real
 * server on 2026-10-12, for all three input lengths tried:
 *
 * <pre>
 *   leading  silence: 50 ms
 *   trailing silence: 360 ms
 * </pre>
 *
 * <p>So a seam between two chunks carries 360 + 50 = <strong>410 ms</strong> of
 * silence that no client setting asked for. That is why turning off this engine's
 * own inter-chunk pauses appeared to do nothing: the audible gap was never ours.
 * Trimming the padding is what makes the pause setting actually control the pause.
 *
 * <p>Cutting at the first and last non-silent sample cannot click, because the cut
 * lands inside a run of near-zero samples. A short guard is kept either side so a
 * soft onset is not clipped.
 */
final class Pcm {

    private Pcm() {
    }

    /** Byte offset and byte length of the region worth playing. */
    static final class Span {
        final int offset;
        final int length;
        /** Milliseconds of silence removed from the front and back. */
        final int leadingMs;
        final int trailingMs;

        Span(int offset, int length, int leadingMs, int trailingMs) {
            this.offset = offset;
            this.length = length;
            this.leadingMs = leadingMs;
            this.trailingMs = trailingMs;
        }
    }

    /**
     * Find the audible part of a little-endian 16-bit mono PCM buffer.
     *
     * @param threshold amplitude below which a sample counts as silence, in
     *                  int16 units; ~0.6% of full scale by default
     * @param guardMs   silence to keep either side of the speech, for a natural onset
     * @param sampleRate rate of the buffer, used only for the reported durations
     */
    static Span audibleSpan(byte[] pcm, int threshold, int guardMs, int sampleRate) {
        int samples = pcm.length / 2;
        int guardSamples = guardMs * sampleRate / 1000;
        if (guardSamples < 0) guardSamples = 0;

        int first = -1;
        int last = -1;
        for (int i = 0; i < samples; i++) {
            int lo = pcm[2 * i] & 0xFF;
            int hi = pcm[2 * i + 1];
            int value = (hi << 8) | lo;   // little-endian signed 16-bit
            if (value < 0) value = -value;
            if (value >= threshold) {
                if (first < 0) first = i;
                last = i;
            }
        }

        // Entirely silent: nothing to trim, and the caller may still want the
        // buffer (it becomes the configured pause).
        if (first < 0) {
            return new Span(0, pcm.length - (pcm.length % 2), 0, 0);
        }

        int from = Math.max(0, first - guardSamples);
        int to = Math.min(samples - 1, last + guardSamples);
        int offset = from * 2;
        int length = (to - from + 1) * 2;

        int leadMs = (int) ((long) from * 1000 / sampleRate);
        int trailMs = (int) ((long) (samples - 1 - to) * 1000 / sampleRate);
        return new Span(offset, length, leadMs, trailMs);
    }
}
