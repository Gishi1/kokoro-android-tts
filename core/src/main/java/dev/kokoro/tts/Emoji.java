package dev.kokoro.tts;

/**
 * Removes characters that have no spoken form.
 *
 * <p>Emoji reach the G2P step as unknown symbols. Kokoro-FastAPI exposes
 * {@code normalization_options.remove_emoji} for exactly this reason: an
 * unknown symbol either tokenizes to nothing (silently dropped) or expands into
 * a nonsense fallback, which is audible as garbage. Stripping them client-side
 * keeps the token estimate honest.
 */
final class Emoji {

    private Emoji() {
    }

    static String strip(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (!isUnspeakable(cp)) {
                sb.appendCodePoint(cp);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            i += width;
        }
        return sb.toString();
    }

    private static boolean isUnspeakable(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)   // emoji and pictographs
                || (cp >= 0x2600 && cp <= 0x27BF) // misc symbols, dingbats
                || (cp >= 0xFE00 && cp <= 0xFE0F) // variation selectors
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF) // regional indicators (flags)
                || cp == 0x200D                    // zero-width joiner
                || (cp >= 0x2190 && cp <= 0x21FF)  // arrows
                || (cp >= 0x2B00 && cp <= 0x2BFF); // misc symbols and arrows
    }
}
