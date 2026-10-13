package dev.kokoro.tts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sentence and clause boundaries that respect the things that break naive splitters.
 *
 * <p>A splitter that cuts on every {@code '.'} mangles decimals ({@code 3.14}),
 * version numbers ({@code 2025.11.003}), abbreviations ({@code Mr.}, {@code e.g.})
 * and initials. Kokoro cannot recover from that: the fragments are synthesised as
 * separate utterances with their own prosody, so the listener hears a sentence
 * chopped mid-thought.
 *
 * <p>CJK terminators ({@code 。！？；}) take no following whitespace, so they are
 * treated as immediate boundaries.
 */
final class SentenceBreaker {

    private SentenceBreaker() {
    }

    private static final String CLOSERS = "\"'”’)]}»›」』】）";
    private static final String CJK_TERMINATORS = "。！？；…";
    private static final String LATIN_TERMINATORS = ".!?…";

    /** Clause punctuation, mirroring Kokoro-FastAPI {@code split_clauses}. */
    private static final String CLAUSE_PUNCT = ",;:—，、；：";

    /**
     * Abbreviations that must not end a sentence. Covers the common English cases
     * plus the titles and Latin forms that appear in real documents.
     */
    private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "ft",
            "vs", "etc", "eg", "ie", "cf", "al", "approx", "dept", "est",
            "fig", "no", "vol", "pp", "ed", "eds", "inc", "ltd", "co", "corp",
            "gov", "sen", "rep", "gen", "col", "capt", "lt", "sgt", "rev",
            "hon", "pres", "supt", "det", "min", "max", "avg", "std", "temp",
            "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept",
            "oct", "nov", "dec", "mon", "tue", "tues", "wed", "thu", "thur",
            "thurs", "fri", "sat", "sun", "am", "pm", "utc", "gmt", "est", "pst"
    ));

    /** Split a paragraph into sentences. */
    static List<String> split(String paragraph) {
        List<String> out = new ArrayList<>();
        String p = paragraph;
        int n = p.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            boolean cjk = CJK_TERMINATORS.indexOf(c) >= 0;
            boolean latin = LATIN_TERMINATORS.indexOf(c) >= 0;
            if (!cjk && !latin) {
                i++;
                continue;
            }
            int j = i + 1;
            // Swallow runs like "?!" and any closing quote or bracket.
            while (j < n && (LATIN_TERMINATORS.indexOf(p.charAt(j)) >= 0
                    || CJK_TERMINATORS.indexOf(p.charAt(j)) >= 0
                    || CLOSERS.indexOf(p.charAt(j)) >= 0)) {
                j++;
            }
            if (cjk) {
                // No whitespace required after a CJK terminator.
                out.add(p.substring(start, j).trim());
                start = j;
                i = j;
                continue;
            }
            boolean followedByBreak = j >= n || Character.isWhitespace(p.charAt(j));
            if (!followedByBreak) {
                i++;
                continue;
            }
            if (c == '.') {
                // Decimal number: 3.14, 2025.11.003
                if (i + 1 < n && Character.isDigit(p.charAt(i + 1))) {
                    i++;
                    continue;
                }
                if (isAbbreviation(p, start, i)) {
                    i++;
                    continue;
                }
            }
            out.add(p.substring(start, j).trim());
            start = j;
            i = j;
        }
        if (start < n) {
            String tail = p.substring(start).trim();
            if (!tail.isEmpty()) out.add(tail);
        }
        List<String> nonEmpty = new ArrayList<>(out.size());
        for (String candidate : out) {
            if (!candidate.isEmpty()) nonEmpty.add(candidate);
        }
        return nonEmpty;
    }

    /**
     * True when the token ending just before {@code dotIndex} is an abbreviation
     * or a single initial, so the {@code '.'} does not end the sentence.
     */
    private static boolean isAbbreviation(String p, int sentenceStart, int dotIndex) {
        int k = dotIndex - 1;
        while (k >= sentenceStart && (Character.isLetter(p.charAt(k)) || p.charAt(k) == '.')) {
            k--;
        }
        String token = p.substring(k + 1, dotIndex);
        if (token.isEmpty()) return false;
        String bare = token.replace(".", "").toLowerCase();
        if (bare.isEmpty()) return false;
        // A single letter is an initial: "J. R. R. Tolkien".
        if (bare.length() == 1) return true;
        return ABBREVIATIONS.contains(bare);
    }

    /**
     * Split at clause punctuation, keeping the punctuation attached to the clause
     * it follows so prosody is preserved.
     */
    static List<String> clauses(String sentence) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            current.append(c);
            if (CLAUSE_PUNCT.indexOf(c) >= 0) {
                // Absorb any run of clause punctuation and closers.
                while (i + 1 < sentence.length()
                        && (CLAUSE_PUNCT.indexOf(sentence.charAt(i + 1)) >= 0
                        || CLOSERS.indexOf(sentence.charAt(i + 1)) >= 0)) {
                    current.append(sentence.charAt(++i));
                }
                String s = current.toString().trim();
                if (!s.isEmpty()) out.add(s);
                current.setLength(0);
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out.isEmpty() ? java.util.Collections.singletonList(sentence) : out;
    }
}
