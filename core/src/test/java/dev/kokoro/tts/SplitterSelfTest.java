package dev.kokoro.tts;

import java.util.List;

/**
 * Plain-JVM self-test for the splitter. No JUnit, so it runs with nothing but a
 * JDK on the classpath.
 *
 * <p>It checks the invariants that do not need a phonemiser:
 * <ul>
 *   <li>no chunk's <em>estimated</em> token count exceeds the configured cap;</li>
 *   <li>text is neither lost nor duplicated (whitespace-insensitive);</li>
 *   <li>the sentence breaker does not cut decimals, versions, or abbreviations;</li>
 *   <li>an oversized unbreakable run (CJK, a long URL) is still cut.</li>
 * </ul>
 *
 * <p>Whether the estimate is actually conservative against real Kokoro tokens is a
 * separate question and is answered by {@code tools/splitter_conformance.py},
 * which phonemizes the output with the real pipeline.
 */
public final class SplitterSelfTest {

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        KokoroSplitter splitter = new KokoroSplitter();

        // --- decimals and versions must not be treated as sentence ends
        expect("decimals stay whole", 1,
                SentenceBreaker.split("Pi is 3.14 and e is 2.718 approximately.").size());
        expect("version numbers stay whole", 1,
                SentenceBreaker.split("Upgrade to version 2025.11.003 today.").size());
        expect("abbreviations do not end a sentence", 1,
                SentenceBreaker.split("Mr. Smith met Dr. Jones at 5 p.m. yesterday.").size());
        expect("initials do not end a sentence", 1,
                SentenceBreaker.split("J. R. R. Tolkien wrote it.").size());
        expect("two real sentences are split", 2,
                SentenceBreaker.split("First one. Second one.").size());
        expect("CJK terminator splits without whitespace", 2,
                SentenceBreaker.split("这是第一句。这是第二句。").size());

        // --- the cap invariant
        String[] tricky = {
                "The invoice total was $1,234,567.89 on 2026-10-12 at 15:45:07 GMT.",
                "In 1897 the population was 1234567 and by 2025 it reached 98765432.",
                "这是一个用于测试语音合成系统的很长的中文句子它包含了很多汉字用来验证分词逻辑是否正确",
                "Visit https://example.com/a/b/c?x=1&y=2#frag or ftp://files.example.org/pub/x.tar.gz",
                "Serial 9X4-88712-A7, batch 0042, revision 2025.11.003 shipped from AMS-IX.",
                "",
                "   ",
        };
        for (String t : tricky) {
            for (KokoroSplitter.Chunk c : splitter.split(t)) {
                check("estimate within cap for " + brief(t), c.estimatedTokens <= KokoroSplitter.DEFAULT_HARD_CAP,
                        "est=" + c.estimatedTokens);
            }
        }

        // --- a normal sentence should survive as one chunk
        List<KokoroSplitter.Chunk> one =
                splitter.split("The quick brown fox jumps over the lazy dog.");
        expect("short prose stays one chunk", 1, one.size());

        // --- long prose must be cut into several chunks
        StringBuilder longProse = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longProse.append("This is sentence number ").append(i)
                    .append(" of a deliberately long paragraph. ");
        }
        List<KokoroSplitter.Chunk> many = splitter.split(longProse.toString());
        check("long prose is split", many.size() > 1, "chunks=" + many.size());
        for (KokoroSplitter.Chunk c : many) {
            check("long-prose chunk within cap", c.estimatedTokens <= KokoroSplitter.DEFAULT_HARD_CAP,
                    "est=" + c.estimatedTokens);
        }

        // --- losslessness: nothing invented, nothing dropped
        for (String t : new String[]{
                longProse.toString(),
                "Mr. Smith paid $1,234.56 on 2026-10-12. Then he left.",
                "这是一个用于测试语音合成系统的很长的中文句子它包含了很多汉字用来验证分词逻辑是否正确",
        }) {
            check("lossless: " + brief(t), squashed(t).equals(squashed(join(splitter.split(t)))));
        }

        // --- paragraph breaks are always chunk boundaries
        List<KokoroSplitter.Chunk> paras = splitter.split("First para.\n\nSecond para.");
        expect("paragraph break yields two chunks", 2, paras.size());
        check("last chunk of a paragraph is marked paraEnd", paras.get(0).paraEnd);
        check("final chunk is also marked paraEnd", paras.get(1).paraEnd);

        // A multi-chunk paragraph must mark only its last chunk, so the client can
        // pause longer at a paragraph break than at a seam inside one.
        List<KokoroSplitter.Chunk> multi = splitter.split(longProse + "\n\nTail paragraph.");
        check("multi-chunk text produces more than one chunk", multi.size() > 2,
                "chunks=" + multi.size());
        int paraEnds = 0;
        for (KokoroSplitter.Chunk c : multi) {
            if (c.paraEnd) paraEnds++;
        }
        expect("exactly one paraEnd per paragraph", 2, paraEnds);
        check("only the last chunk of a paragraph carries paraEnd", multi.get(multi.size() - 1).paraEnd);

        System.out.println();
        if (failures == 0) {
            System.out.println("SELF-TEST PASSED (" + checks + " checks)");
        } else {
            System.out.println("SELF-TEST FAILED: " + failures + " of " + checks + " checks");
            System.exit(1);
        }
    }

    private static String join(List<KokoroSplitter.Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (KokoroSplitter.Chunk c : chunks) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.text);
        }
        return sb.toString();
    }

    private static String squashed(String s) {
        return s.replaceAll("\\s+", "");
    }

    private static String brief(String s) {
        String t = s.trim();
        if (t.isEmpty()) return "<blank>";
        return t.length() > 28 ? t.substring(0, 28) + "..." : t;
    }

    private static void expect(String what, int want, int got) {
        check(what, want == got, "want " + want + " got " + got);
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + "  (" + detail + ")");
        }
    }
}
