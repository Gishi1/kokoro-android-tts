package dev.kokoro.tts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Command-line front end for {@link KokoroSplitter}.
 *
 * <p>It exists so the splitter can be measured against real Kokoro ground truth
 * from a Python harness without an Android device in the loop, and so the
 * baseline (naive fixed-character splitting) and the prototype can be driven by
 * exactly the same code path.
 *
 * <pre>
 *   java -cp build/classes dev.kokoro.tts.SplitterCli --mode model --corpus data/corpus_mixed.txt
 *   java -cp build/classes dev.kokoro.tts.SplitterCli --mode chars --budget 400 ...
 * </pre>
 *
 * <p>Output is JSON Lines, one object per chunk, so the harness can stream it.
 */
public final class SplitterCli {

    public static void main(String[] args) throws IOException {
        String mode = "model";
        int budget = 400;
        int hardCap = KokoroSplitter.DEFAULT_HARD_CAP;
        int targetMin = KokoroSplitter.DEFAULT_TARGET_MIN;
        int targetMax = KokoroSplitter.DEFAULT_TARGET_MAX;
        int firstMax = 0;
        List<Path> corpora = new ArrayList<>();
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode": mode = args[++i]; break;
                case "--budget": budget = Integer.parseInt(args[++i]); break;
                case "--hard-cap": hardCap = Integer.parseInt(args[++i]); break;
                case "--target-min": targetMin = Integer.parseInt(args[++i]); break;
                case "--target-max": targetMax = Integer.parseInt(args[++i]); break;
                case "--first-max": firstMax = Integer.parseInt(args[++i]); break;
                case "--corpus": corpora.add(Paths.get(args[++i])); break;
                case "--quiet": quiet = true; break;
                default:
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                false, StandardCharsets.UTF_8);
        int index = 0;

        for (Path corpus : corpora) {
            int lineNo = 0;
            for (String raw : readLines(corpus)) {
                lineNo++;
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                List<KokoroSplitter.Chunk> chunks = "chars".equals(mode)
                        ? CharBaselineSplitter.split(line, budget)
                        : new KokoroSplitter(new KokoroCostModel(), targetMin, targetMax,
                                hardCap, true, firstMax).split(line);
                for (KokoroSplitter.Chunk c : chunks) {
                    out.println(jsonLine(index++, corpus.getFileName().toString(), lineNo,
                            c.text, c.estimatedTokens));
                }
            }
        }
        out.flush();
        if (!quiet) {
            System.err.println("emitted " + index + " chunks (mode=" + mode + ")");
        }
    }

    private static List<String> readLines(Path p) throws IOException {
        if (p.getFileName().toString().endsWith(".txt")) {
            return Files.readAllLines(p, StandardCharsets.UTF_8);
        }
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    private static String jsonLine(int index, String file, int line, String text, int est) {
        return "{\"index\":" + index
                + ",\"file\":" + quote(file)
                + ",\"line\":" + line
                + ",\"est\":" + est
                + ",\"text\":" + quote(text) + "}";
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Reads a single line from stdin, used by the self-test. */
    static String readOne(BufferedReader r) throws IOException {
        return r.readLine();
    }

    static BufferedReader stdin() {
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }
}
