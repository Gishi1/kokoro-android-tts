package dev.kokoro.tts;

import java.util.ArrayList;
import java.util.List;

/**
 * The naive baseline: split on a fixed <em>character</em> budget.
 *
 * <p>This is not a straw man. It is what upstream Kokoro does today —
 * {@code kokoro/pipeline.py:400-427} chunks non-English text at
 * {@code chunk_size = 400} characters, preferring sentence boundaries and
 * falling back to a hard character cut. Its author's own warning at
 * {@code pipeline.py:143} is that "Chunking logic not yet implemented, so long
 * texts may be truncated unless you split them with '\n'".
 *
 * <p>It is kept in the tree as the measured baseline for the experiment in
 * {@code docs/experiments/2026-10-12-kokoro-splitter.md}. It is never used by
 * the Android service.
 */
final class CharBaselineSplitter {

    private CharBaselineSplitter() {
    }

    static List<KokoroSplitter.Chunk> split(String text, int budget) {
        List<KokoroSplitter.Chunk> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SentenceBreaker.split(text)) {
            if (sentence.length() > budget) {
                if (current.length() > 0) {
                    out.add(chunk(current.toString()));
                    current.setLength(0);
                }
                for (String piece : hardWrap(sentence, budget)) {
                    out.add(chunk(piece));
                }
                continue;
            }
            if (current.length() + sentence.length() + 1 > budget) {
                if (current.length() > 0) {
                    out.add(chunk(current.toString()));
                }
                current = new StringBuilder(sentence);
            } else {
                if (current.length() > 0) current.append(' ');
                current.append(sentence);
            }
        }
        if (current.length() > 0) out.add(chunk(current.toString()));
        return out;
    }

    /** The baseline's only safety valve: a hard cut every {@code budget} characters. */
    private static List<String> hardWrap(String text, int budget) {
        List<String> out = new ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0 && sb.length() + w.length() + 1 > budget) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            if (w.length() > budget) {
                if (sb.length() > 0) {
                    out.add(sb.toString());
                    sb.setLength(0);
                }
                for (int i = 0; i < w.length(); i += budget) {
                    out.add(w.substring(i, Math.min(w.length(), i + budget)));
                }
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    /**
     * The baseline has no cost model, so it reports a character-derived guess.
     * The harness ignores this field and measures the truth with real G2P.
     */
    private static KokoroSplitter.Chunk chunk(String text) {
        return new KokoroSplitter.Chunk(text.trim(), text.trim().length());
    }
}
