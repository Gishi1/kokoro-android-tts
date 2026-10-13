#!/usr/bin/env python3
"""Generate KokoroCostModel.java from the fitted token model.

The Java port and the Python fit must agree exactly, or the safety guarantee
(the estimate never under-predicts) is void. Generating the class from
docs/research/token_model.json removes the possibility of drift.

Usage:
    python3 tools/gen_cost_model.py \
        --model docs/research/token_model.json \
        --out core/src/main/java/dev/kokoro/tts/KokoroCostModel.java
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

TEMPLATE = '''package dev.kokoro.tts;

/**
 * Estimated Kokoro phoneme-token cost of a piece of text.
 *
 * <p><strong>Generated file - do not edit by hand.</strong> Produced by
 * {@code tools/gen_cost_model.py} from {@code docs/research/token_model.json},
 * fitted on @@FITTED@@ against real {@code kokoro.KPipeline} phonemisation of
 * @@N_SAMPLES@@ samples (@@N_TRAIN@@ train / @@N_TEST@@ test).
 *
 * <p>Kokoro's hard limit is 510 phoneme tokens, and the server counts the length
 * of the IPA phoneme string, so the cost is a property of the <em>phonemes</em>,
 * not of the characters. Character count is a poor proxy: measured
 * 2026-10-12, English prose is ~1.00 tokens/char while CJK is ~13 and
 * digit-dense text ~3.5.
 *
 * <p>The estimate is a linear model over per-character-class counts, inflated by
 * a <em>length-dependent</em> safety factor:
 *
 * <pre>
 *   predicted = c[0] + sum over chars of c[class(char)] + c[nrun]*runs + c[urun]*acronyms
 *   estimate  = predicted * (1 + A / sqrt(max(predicted, @@MIN_TOKENS@@)))
 * </pre>
 *
 * <p>The factor shrinks with length because the ratio observed/predicted
 * concentrates as text grows; a single global factor had to cover the worst very
 * short sample and came out far looser, which would have halved every chunk.
 *
 * <p>Held-out fit quality: R2 = @@R2@@, under-prediction rate = @@UNDER_RATE@@
 * (@@TEST_UNDER@@ samples), median over-estimate = @@MED_OVER@@x, max = @@MAX_OVER@@x.
 * The factor is calibrated on the training split only.
 */
public final class KokoroCostModel {

    /** Numerator of the safety factor: safety(L) = 1 + A / sqrt(max(L, minTokens)). */
    private static final double SAFETY_A = @@SAFETY_A@@;

    /** Floor on the predicted length used by the safety factor. */
    private static final double SAFETY_MIN_TOKENS = @@MIN_TOKENS@@;

    /** Coefficient order matches @@FEATURE_ORDER@@. */
    private static final double[] C = new double[] {
@@COEF_LINES@@
    };

    public static final String[] FEATURE_NAMES = new String[] {
@@FEATURE_LINES@@
    };

    /** Number of character classes, excluding the intercept. */
    public static final int CLASS_COUNT = C.length - 1;

    private static final int CLS_LOWER = 0;
    private static final int CLS_UPPER = 1;
    private static final int CLS_DIGIT = 2;
    private static final int CLS_SPACE = 3;
    private static final int CLS_PUNCT = 4;
    private static final int CLS_SYMBOL = 5;
    private static final int CLS_CJK = 6;
    private static final int CLS_OTHER = 7;

    /** Per-character-class counts, plus numeric-run and acronym-run counts. */
    public static final class Features {
        public final double[] counts = new double[CLASS_COUNT];
        public int numberRuns;
        public int upperRuns;

        public double[] toArray() {
            double[] x = new double[CLASS_COUNT + 1];
            x[0] = 1.0;
            for (int i = 0; i < CLASS_COUNT; i++) x[i + 1] = counts[i];
            x[9] = numberRuns;
            x[10] = upperRuns;
            return x;
        }
    }

    /** Count characters by class. Mirrors the Python feature extraction exactly. */
    public static Features features(String text) {
        Features f = new Features();
        int n = text.length();
        boolean inNumber = false;
        boolean inUpper = false;
        int upperRunLen = 0;
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            int cls = classOf(ch);
            if (cls >= 0 && cls < 8) f.counts[cls]++;

            // Numeric run: a digit starts one; ',' and '.' continue it only when
            // a digit follows, so "3.14" is one run but "end. 5" is not.
            boolean digit = (ch >= '0' && ch <= '9');
            boolean numericJoiner = (ch == ',' || ch == '.')
                    && i + 1 < n && text.charAt(i + 1) >= '0' && text.charAt(i + 1) <= '9';
            if (digit) {
                if (!inNumber) {
                    f.numberRuns++;
                    inNumber = true;
                }
            } else if (!numericJoiner) {
                inNumber = false;
            }

            // Acronym run: a maximal run of 2 or more capitals.
            if (ch >= 'A' && ch <= 'Z') {
                upperRunLen++;
                inUpper = true;
            } else {
                if (inUpper && upperRunLen >= 2) f.upperRuns++;
                inUpper = false;
                upperRunLen = 0;
            }
        }
        if (inUpper && upperRunLen >= 2) f.upperRuns++;

        double used = 0;
        for (int i = 0; i < 8; i++) used += f.counts[i];
        f.counts[CLS_OTHER] = Math.max(0, n - used);
        return f;
    }

    private static int classOf(char ch) {
        if (ch >= 'a' && ch <= 'z') return CLS_LOWER;
        if (ch >= 'A' && ch <= 'Z') return CLS_UPPER;
        if (ch >= '0' && ch <= '9') return CLS_DIGIT;
        if (isSpace(ch)) return CLS_SPACE;
        if (isPunct(ch)) return CLS_PUNCT;
        if (isSymbol(ch)) return CLS_SYMBOL;
        if (isCjk(ch)) return CLS_CJK;
        return CLS_OTHER;
    }

    private static boolean isSpace(char ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch);
    }

    private static boolean isPunct(char ch) {
        switch (ch) {
            case ',': case '.': case '!': case '?': case ';': case ':':
            case '\\'': case '"': case '-': case '(': case ')':
            case '[': case ']':
                return true;
            default:
                return false;
        }
    }

    private static boolean isSymbol(char ch) {
        switch (ch) {
            case '/': case '\\\\': case '@': case '#': case '$': case '%':
            case '^': case '&': case '*': case '{': case '}': case '<':
            case '>': case '=': case '+': case '_': case '~': case '`':
            case '|':
                return true;
            default:
                return false;
        }
    }

    private static boolean isCjk(char ch) {
        return (ch >= 0x3040 && ch <= 0x30FF)     // hiragana + katakana
                || (ch >= 0x3400 && ch <= 0x4DBF) // CJK extension A
                || (ch >= 0x4E00 && ch <= 0x9FFF) // CJK unified ideographs
                || (ch >= 0xF900 && ch <= 0xFAFF) // CJK compatibility
                || (ch >= 0xAC00 && ch <= 0xD7AF); // hangul syllables
    }

    /** Safety multiplier for a given predicted length. */
    public static double safety(double predicted) {
        return 1.0 + SAFETY_A / Math.sqrt(Math.max(predicted, SAFETY_MIN_TOKENS));
    }

    /** Raw predicted tokens, before the safety factor. */
    public double predicted(String text) {
        if (text == null || text.isEmpty()) return 0.0;
        Features f = features(text);
        double sum = C[0];
        for (int i = 0; i < CLASS_COUNT; i++) sum += C[i + 1] * f.counts[i];
        sum += C[9] * f.numberRuns;
        sum += C[10] * f.upperRuns;
        return Math.max(sum, 1.0);
    }

    /** Estimated phoneme tokens for {@code text}, rounded up. */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        double p = predicted(text);
        double est = p * safety(p);
        if (est < 1.0) return 1;
        if (est > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.ceil(est);
    }

    /**
     * Length of the longest prefix of {@code text} starting at {@code from} whose
     * estimate stays within {@code cap}. Always advances at least one character,
     * so a single character costlier than the whole cap cannot stall a caller.
     */
    public int longestPrefixWithin(String text, int from, int cap) {
        double sum = C[0];
        if (sum * safety(Math.max(sum, 1.0)) > cap) {
            return Math.min(text.length(), from + 1);
        }
        int i = from;
        while (i < text.length()) {
            double add = C[classOf(text.charAt(i)) + 1];
            double next = sum + add;
            if (next * safety(next) > cap) break;
            sum = next;
            i++;
        }
        if (i == from) i = Math.min(text.length(), from + 1);
        return i;
    }

    public double safetyNumerator() {
        return SAFETY_A;
    }
}
'''


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, default=Path("docs/research/token_model.json"))
    ap.add_argument("--out", type=Path,
                    default=Path("core/src/main/java/dev/kokoro/tts/KokoroCostModel.java"))
    args = ap.parse_args()

    data = json.loads(args.model.read_text())
    features = data["features"]
    coefs = data["coefficients"]
    expected = ["const", "n_lower", "n_upper", "n_digit", "n_space", "n_punct",
                "n_symbol", "n_cjk", "n_other", "n_number_run", "n_upper_run"]
    if features != expected:
        raise SystemExit(
            "feature list changed; update the Java template to match:\n"
            f"  model: {features}\n  java : {expected}")

    coef_lines = "\n".join(
        "            {:.6f},  // {}".format(float(coefs[f]), f) for f in features
    )
    feature_lines = "\n".join('            "{}",'.format(f) for f in features)

    subs = {
        "@@FITTED@@": str(data["fitted"]),
        "@@N_SAMPLES@@": str(data["n_samples"]),
        "@@N_TRAIN@@": str(data["n_train"]),
        "@@N_TEST@@": str(data["n_test"]),
        "@@SAFETY_A@@": repr(float(data["safety_A"])),
        "@@MIN_TOKENS@@": repr(float(data["safety_min_tokens"])),
        "@@R2@@": f"{data['r2_test_raw']:.4f}",
        "@@UNDER_RATE@@": f"{data['test_underprediction_rate']:.4f}",
        "@@TEST_UNDER@@": str(data["test_underprediction_count"]),
        "@@MED_OVER@@": f"{data['test_median_overestimate_ratio']:.3f}",
        "@@MAX_OVER@@": f"{data['test_max_overestimate_ratio']:.3f}",
        "@@FEATURE_ORDER@@": ", ".join(features),
        "@@COEF_LINES@@": coef_lines,
        "@@FEATURE_LINES@@": feature_lines,
    }
    src = TEMPLATE
    for k, v in subs.items():
        src = src.replace(k, v)
    leftover = re.findall(r"@@[A-Z_]+@@", src)
    if leftover:
        raise SystemExit(f"unsubstituted placeholders: {sorted(set(leftover))}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(src)
    print(f"wrote {args.out} ({len(src)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
