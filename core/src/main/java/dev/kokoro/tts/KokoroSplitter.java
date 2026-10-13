package dev.kokoro.tts;

/**
 * Kokoro-optimised text splitter.
 *
 * <p>Kokoro's hard input limit is 510 <em>phoneme tokens</em>:
 *
 * <pre>
 *   kokoro/pipeline.py:274   if len(tokens) &gt; 510:
 *   kokoro/pipeline.py:275       raise ValueError(f'Phoneme string too long: {len(tokens)} &gt; 510')
 *   kokoro/pipeline.py:288       ps = ps[:510]        // silent truncation
 * </pre>
 *
 * <p>The server-side token count is the length of the IPA phoneme string
 * (Kokoro-FastAPI {@code vocabulary.tokenize()} maps one vocab character to one
 * token id), so it is a property of the <em>phonemes</em>, not of the
 * characters. Character count is a poor proxy and, measured on 2026-10-12,
 * unsafe by a wide margin:
 *
 * <table>
 *   <caption>Measured token cost per character</caption>
 *   <tr><td>English prose</td><td>~1.00</td></tr>
 *   <tr><td>digit / money dense</td><td>~3.2-3.5</td></tr>
 *   <tr><td>CJK through the English pipeline</td><td>~13.0</td></tr>
 *   <tr><td>CJK through its own pipeline</td><td>~4.0</td></tr>
 * </table>
 *
 * <p>So this splitter counts estimated tokens with {@link KokoroCostModel} and
 * breaks text on Kokoro's own prosodic waterfall, mirroring the order used by
 * upstream {@code KPipeline.waterfall_last} ({@code ['!.?…', ':;', ',—']}) and
 * the paragraph/sentence/clause/word hierarchy of Kokoro-FastAPI
 * {@code smart_split}.
 *
 * <p>The class is deliberately free of Android imports so it can be unit-tested
 * on a plain JVM.
 */
public final class KokoroSplitter {

    /** The model's hard limit: {@code plbert.max_position_embeddings} (512) minus BOS and EOS. */
    public static final int MODEL_HARD_LIMIT = 510;

    /**
     * Cap in <em>estimated</em> tokens, derived from measurement rather than chosen.
     *
     * <p>The cost model is fitted not to under-predict, but it is still an estimate:
     * on the held-out split the worst case was a true count **1.348x** the estimate
     * (1 sample in 11,083). A cap of 450 estimated tokens could therefore reach
     * 450 x 1.348 = 607 real tokens and be truncated. The measurement's own
     * recommendation is 510 / 1.348 = 378; this default derates that by ~5 % to 360,
     * so the worst observed case lands at 485 real tokens with headroom left.
     *
     * <p>See {@code docs/research/token_model.json} and
     * {@code docs/research/2026-10-12-kokoro-text-limits.md}.
     */
    public static final int DEFAULT_HARD_CAP = 360;

    /**
     * Target minimum, in estimated tokens. With the model's median over-estimate of
     * 1.611x, packing to {@link #DEFAULT_TARGET_MAX} lands the median chunk near
     * 223 true tokens, inside Kokoro-FastAPI's 175-250 band.
     */
    public static final int DEFAULT_TARGET_MIN = 240;

    /** Target maximum, in estimated tokens. Equal to the cap: fill as far as is safe. */
    public static final int DEFAULT_TARGET_MAX = 360;

    private final KokoroCostModel model;
    private final int targetMin;
    private final int targetMax;
    private final int hardCap;
    private final boolean stripEmoji;

    /** One unit of synthesis output: the original text plus its estimated token cost. */
    public static final class Chunk {
        public final String text;
        public final int estimatedTokens;

        public Chunk(String text, int estimatedTokens) {
            this.text = text;
            this.estimatedTokens = estimatedTokens;
        }

        @Override
        public String toString() {
            return "Chunk{" + estimatedTokens + " tokens, " + text.length() + " chars: "
                    + (text.length() > 60 ? text.substring(0, 60) + "..." : text) + "}";
        }
    }

    public KokoroSplitter() {
        this(new KokoroCostModel(), DEFAULT_TARGET_MIN, DEFAULT_TARGET_MAX, DEFAULT_HARD_CAP, true);
    }

    public KokoroSplitter(KokoroCostModel model, int targetMin, int targetMax, int hardCap,
                          boolean stripEmoji) {
        if (targetMin < 0) throw new IllegalArgumentException("targetMin < 0");
        if (targetMax < targetMin) throw new IllegalArgumentException("targetMax < targetMin");
        if (hardCap > MODEL_HARD_LIMIT) {
            throw new IllegalArgumentException(
                    "hardCap " + hardCap + " exceeds the model limit " + MODEL_HARD_LIMIT);
        }
        if (targetMax > hardCap) throw new IllegalArgumentException("targetMax > hardCap");
        this.model = model;
        this.targetMin = targetMin;
        this.targetMax = targetMax;
        this.hardCap = hardCap;
        this.stripEmoji = stripEmoji;
    }

    /** Sentence-level unit, ready to be packed into a request. */
    private static final class Unit {
        final String text;
        final int cost;

        Unit(String text, int cost) {
            this.text = text;
            this.cost = cost;
        }
    }

    /**
     * Split {@code input} into chunks that Kokoro will synthesise whole.
     *
     * <p>Every returned chunk satisfies {@code estimate(chunk) <= hardCap}, and
     * paragraph boundaries are always chunk boundaries so the client can insert
     * a real pause between them.
     */
    public java.util.List<Chunk> split(String input) {
        java.util.List<Chunk> out = new java.util.ArrayList<>();
        if (input == null) return out;
        String text = stripEmoji ? Emoji.strip(input) : input;
        if (text.trim().isEmpty()) return out;

        for (String paragraph : paragraphs(text)) {
            java.util.List<Unit> units = new java.util.ArrayList<>();
            for (String sentence : SentenceBreaker.split(paragraph)) {
                explode(sentence, units);
            }
            if (units.isEmpty()) continue;
            for (Unit u : pack(units)) {
                out.add(new Chunk(u.text, u.cost));
            }
        }
        return out;
    }

    /** Paragraphs are separated by a blank line; a single newline is a soft wrap. */
    private static java.util.List<String> paragraphs(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String block : text.split("\n[ \t]*\n+")) {
            String joined = block.replaceAll("[ \t]*\n[ \t]*", " ").trim();
            if (!joined.isEmpty()) out.add(joined);
        }
        return out;
    }

    /**
     * Turn one sentence into packable units.
     *
     * <p>An oversized sentence is broken at clauses, an oversized clause at word
     * boundaries, and an oversized word at character boundaries. That last case
     * is not hypothetical: CJK text has no spaces, so a whole Chinese sentence
     * arrives as a single "word" and must be cut by estimated cost.
     */
    private void explode(String sentence, java.util.List<Unit> units) {
        String s = sentence.trim();
        if (s.isEmpty()) return;
        int cost = model.estimate(s);
        if (cost <= hardCap) {
            units.add(new Unit(s, cost));
            return;
        }
        // Clause level: mirrors the punctuation classes in Kokoro-FastAPI split_clauses.
        for (String clause : SentenceBreaker.clauses(s)) {
            String c = clause.trim();
            if (c.isEmpty()) continue;
            int cCost = model.estimate(c);
            if (cCost <= hardCap) {
                units.add(new Unit(c, cCost));
            } else {
                splitWords(c, units);
            }
        }
    }

    private void splitWords(String clause, java.util.List<Unit> units) {
        String[] words = clause.split("\\s+");
        int i = 0;
        while (i < words.length) {
            // Longest run of words from i that still fits the cap.
            int lo = i + 1, hi = words.length, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                String candidate = join(words, i, mid);
                if (model.estimate(candidate) <= hardCap) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (best == -1) {
                // A single word is already oversized (CJK run, long URL, base64 blob).
                splitAtoms(words[i], units);
                i++;
            } else {
                String candidate = join(words, i, best);
                units.add(new Unit(candidate, model.estimate(candidate)));
                i = best;
            }
        }
    }

    /**
     * Cut a single oversized token at character boundaries, using the linear cost
     * model incrementally so the cut is the longest prefix that still fits.
     */
    private void splitAtoms(String word, java.util.List<Unit> units) {
        int n = word.length();
        int start = 0;
        while (start < n) {
            int end = model.longestPrefixWithin(word, start, hardCap);
            if (end <= start) {
                // Defensive: a single character costs more than the whole cap.
                // Emit it alone rather than looping forever.
                end = start + 1;
            }
            String piece = word.substring(start, end);
            units.add(new Unit(piece, model.estimate(piece)));
            start = end;
        }
    }

    /**
     * Greedy packer, mirroring Kokoro-FastAPI {@code pack()}: fill to
     * {@code targetMax}, run past it up to {@code hardCap} only while the chunk
     * is still below {@code targetMin}, so short sentences are not left alone.
     */
    private java.util.List<Unit> pack(java.util.List<Unit> units) {
        java.util.List<Unit> out = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int tokens = 0;
        for (Unit u : units) {
            int total = tokens + u.cost;
            boolean fits = total <= targetMax
                    || (total <= hardCap && tokens < targetMin)
                    || tokens == 0;
            if (fits && total <= hardCap) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(u.text);
                tokens = total;
                continue;
            }
            if (sb.length() > 0) out.add(new Unit(sb.toString(), tokens));
            sb = new StringBuilder(u.text);
            tokens = u.cost;
        }
        if (sb.length() > 0) out.add(new Unit(sb.toString(), tokens));

        // A tiny trailing fragment reads better attached to the previous chunk
        // when the two together still fit the soft target.
        if (out.size() >= 2) {
            Unit last = out.get(out.size() - 1);
            Unit prev = out.get(out.size() - 2);
            if (last.cost < targetMin / 2 && prev.cost + last.cost <= targetMax) {
                out.remove(out.size() - 1);
                out.remove(out.size() - 1);
                String merged = prev.text + " " + last.text;
                out.add(new Unit(merged, prev.cost + last.cost));
            }
        }
        return out;
    }

    private static String join(String[] words, int from, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < toExclusive; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }
}
