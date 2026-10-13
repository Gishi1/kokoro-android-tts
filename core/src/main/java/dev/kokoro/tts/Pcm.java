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

    /**
     * Shorten any silence longer than {@code maxPauseMs} down to that length.
     *
     * <p>Kokoro inserts its own pauses: measured 2026-10-12, 280-490 ms inside single
     * chunks, at clause boundaries such as a semicolon or colon. Those arrive whether
     * or not this engine adds anything of its own, so a listener who finds them
     * distractingly long has no other lever. Samples are removed from the middle of
     * the run, which cannot click because both sides are already silence.
     *
     * @return a new buffer, or the original when nothing exceeded the limit
     */
    static byte[] capSilences(byte[] pcm, int threshold, int maxPauseMs, int sampleRate) {
        return capSilences(pcm, threshold, maxPauseMs, sampleRate, maxPauseMs);
    }

    /**
     * As {@link #capSilences(byte[], int, int, int)}, but the run at the very start of
     * the buffer is capped separately.
     *
     * <p>Needed because a pause can straddle a chunk boundary: capping each chunk on
     * its own leaves the previous chunk's trailing silence and this chunk's leading
     * silence adjacent in the output, and they add up. Measured 2026-10-13 over nine
     * minutes of reading, a 350 ms cap still left 11 pauses above 350 ms, the longest
     * 657 ms, all of them at seams. Passing {@code maxPauseMs - previousTrailingMs}
     * for the leading run makes the total at a seam come to {@code maxPauseMs}.
     */
    static byte[] capSilences(byte[] pcm, int threshold, int maxPauseMs, int sampleRate,
                              int maxLeadingMs) {
        int samples = pcm.length / 2;
        int maxSamples = maxPauseMs * sampleRate / 1000;
        if (maxSamples <= 0 || samples == 0) return pcm;

        java.io.ByteArrayOutputStream out =
                new java.io.ByteArrayOutputStream(pcm.length);
        byte[] pair = new byte[2];
        int leadingSamples = Math.max(0, maxLeadingMs) * sampleRate / 1000;
        int i = 0;
        while (i < samples) {
            boolean silent = isSilent(pcm, i, threshold);
            int j = i;
            while (j < samples && isSilent(pcm, j, threshold) == silent) j++;
            int run = j - i;
            int limit = (silent && i == 0) ? Math.min(maxSamples, leadingSamples) : maxSamples;
            int keep = silent ? Math.min(run, limit) : run;
            // Take the kept slice from the middle of the run, so trimming is symmetric
            // and always leaves silence rather than a partial waveform edge.
            int start = silent ? i + (run - keep) / 2 : i;
            for (int k = start; k < start + keep; k++) {
                pair[0] = pcm[2 * k];
                pair[1] = pcm[2 * k + 1];
                out.write(pair, 0, 2);
            }
            i = j;
        }
        return out.toByteArray();
    }

    /** Milliseconds of silence at the end of a buffer, for the seam calculation. */
    static int trailingSilenceMs(byte[] pcm, int threshold, int sampleRate) {
        int samples = pcm.length / 2;
        int run = 0;
        while (run < samples && isSilent(pcm, samples - 1 - run, threshold)) run++;
        return (int) ((long) run * 1000 / sampleRate);
    }

    private static boolean isSilent(byte[] pcm, int sample, int threshold) {
        int lo = pcm[2 * sample] & 0xFF;
        int hi = pcm[2 * sample + 1];
        int value = (hi << 8) | lo;
        if (value < 0) value = -value;
        return value < threshold;
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
     * @param guardMs   silence to keep either side of the speech, for a natural release
     * @param sampleRate rate of the buffer, used only for the reported durations
     * @param leadInMs  silence to keep before the speech, which may exceed
     *                  {@code guardMs}. Pass a larger value for the first buffer of
     *                  an utterance so the output path can start before speech
     *                  arrives, and 0 for buffers that follow in the same stream.
     */
    static Span audibleSpan(byte[] pcm, int threshold, int guardMs, int sampleRate,
                            int leadInMs) {
        int samples = pcm.length / 2;
        int guardSamples = guardMs * sampleRate / 1000;
        if (guardSamples < 0) guardSamples = 0;

        // A lead-in exists to protect the first word, not to pad: the audio output
        // path needs some samples to spin up, and speech that starts at sample 0
        // loses its attack. Measured on 2026-10-12, the server's own 50 ms of
        // leading padding was what previously hid this, so trimming it without
        // keeping a replacement made the first word clip.
        int leadSamples = Math.max(guardSamples, leadInMs * sampleRate / 1000);

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

        int from = Math.max(0, first - leadSamples);
        int to = Math.min(samples - 1, last + guardSamples);
        int offset = from * 2;
        int length = (to - from + 1) * 2;

        int leadMs = (int) ((long) from * 1000 / sampleRate);
        int trailMs = (int) ((long) (samples - 1 - to) * 1000 / sampleRate);
        return new Span(offset, length, leadMs, trailMs);
    }
}
