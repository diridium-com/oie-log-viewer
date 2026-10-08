// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

/**
 * Turns the server's page text into what the viewer shows, and describes the
 * characters in it. Pure on purpose (no Swing, no AWT), so it can be unit tested.
 *
 * <p>The server sends line terminators raw: a line ends at {@code \n},
 * {@code \r\n} or a lone {@code \r}. Both display modes turn each terminator
 * into exactly one {@code \n}, so a page has the same number of lines in either
 * mode and the line numbers never shift when the user toggles special
 * characters. The {@code \n} that ends the page's last line is left out in both
 * modes, so the viewer holds exactly the page's lines and no empty line after
 * them. Special mode only adds a label in front of each {@code \n}.</p>
 *
 * <p>The labels are the ASCII letters CR and LF (as Notepad++ draws them), and
 * {@link LineEnd} says where each one is so the Swing viewer can box it. The
 * Unicode control pictures (U+240D and U+240A) are not used here because at the
 * Administrator's 13 point monospaced font they render as one or two pixel
 * dots. The web viewer deliberately keeps those symbols: Monaco draws them
 * legibly, so it needs no labels.</p>
 */
public final class LogDisplayText {

    /** The label drawn for a carriage return in special mode. */
    public static final String CR_LABEL = "CR";

    /** The label drawn for a line feed in special mode. */
    public static final String LF_LABEL = "LF";

    private static final String CRLF_MEANING = "Line end: carriage return + line feed (Windows)";
    private static final String LF_MEANING = "Line end: line feed (Unix)";
    private static final String CR_MEANING = "Line end: carriage return (HL7 segment separator)";

    /**
     * One label in special mode: where it sits in the display text and what the
     * line ending it stands for is, in words. The two labels of a CRLF are two
     * entries with the same meaning.
     */
    public static final class LineEnd {
        private final int start;
        private final int end;
        private final String meaning;

        LineEnd(int start, int end, String meaning) {
            this.start = start;
            this.end = end;
            this.meaning = meaning;
        }

        /** Offset of the label's first character in the display text. */
        public int getStart() {
            return start;
        }

        /** Offset just past the label's last character. */
        public int getEnd() {
            return end;
        }

        /** The terminator in words, for a tooltip. */
        public String getMeaning() {
            return meaning;
        }
    }

    /** The special-mode text and the labels in it. */
    public static final class Special {
        private final String text;
        private final List<LineEnd> lineEnds;

        Special(String text, List<LineEnd> lineEnds) {
            this.text = text;
            this.lineEnds = lineEnds;
        }

        public String getText() {
            return text;
        }

        /** The labels, in text order. */
        public List<LineEnd> getLineEnds() {
            return lineEnds;
        }
    }

    /** The server's stand-in for a neutralized bidirectional override character. */
    private static final int BIDI_SUBSTITUTE = 0x2426;

    /** The replacement character the server uses for bytes that are not valid text. */
    private static final int REPLACEMENT = 0xFFFD;

    /** The control picture for the delete character, which sits apart from the others. */
    private static final int DELETE_SYMBOL = 0x2421;

    private LogDisplayText() {
    }

    /** Each terminator ({@code \r\n}, {@code \r}, {@code \n}) becomes one {@code \n}. */
    public static String normal(String raw) {
        return convert(raw, null);
    }

    /**
     * Each line keeps one {@code \n}, with the line's real terminator shown in
     * front of it: CRLF as CR and LF labels, LF as the LF label, a lone CR as
     * the CR label. A last line with no terminator gets nothing.
     */
    public static Special special(String raw) {
        List<LineEnd> lineEnds = new ArrayList<>();
        return new Special(convert(raw, lineEnds), lineEnds);
    }

    /** {@code lineEnds} is null in normal mode; in special mode it receives the labels. */
    private static String convert(String raw, List<LineEnd> lineEnds) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int n = raw.length();
        StringBuilder out = new StringBuilder(n + (lineEnds != null ? n / 16 + 16 : 0));
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c != '\r' && c != '\n') {
                out.append(c);
                continue;
            }
            boolean crlf = c == '\r' && i + 1 < n && raw.charAt(i + 1) == '\n';
            if (lineEnds != null) {
                if (c == '\n') {
                    label(out, lineEnds, LF_LABEL, LF_MEANING);
                } else if (crlf) {
                    label(out, lineEnds, CR_LABEL, CRLF_MEANING);
                    label(out, lineEnds, LF_LABEL, CRLF_MEANING);
                } else {
                    label(out, lineEnds, CR_LABEL, CR_MEANING);
                }
            }
            if (crlf) {
                i++;
            }
            // The page's last terminator ends its last line; no line follows it to start.
            if (i + 1 < n) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static void label(StringBuilder out, List<LineEnd> lineEnds, String label, String meaning) {
        int start = out.length();
        out.append(label);
        lineEnds.add(new LineEnd(start, out.length(), meaning));
    }

    /**
     * The number of lines a text area holds for this text: one more than the
     * number of {@code \n}, so empty text is one empty line. This is the count
     * that must be equal in both display modes.
     */
    public static int lineCount(String display) {
        int count = 1;
        for (int i = 0; i < display.length(); i++) {
            if (display.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    /**
     * Where the engine's search highlights fall in the display text.
     *
     * <p>The engine reports positions as char indices into the raw page text, as
     * consecutive start/end pairs. The display text differs from the raw text
     * (a CRLF is one line end, and special mode adds labels before each line
     * end), so a raw index cannot be used as it is. A match never spans a line
     * end, so each position is mapped by (line index, column): the column is the
     * same in the raw and the display line, because everything before a line's
     * end is copied one for one.</p>
     *
     * @param raw the page text exactly as the server sent it
     * @param display what {@link #normal} or {@link #special} made of it
     * @param highlights start/end pairs, end exclusive; null or empty for none
     * @return the pairs in display coordinates, in the same order; a pair that
     *         does not fit the text (out of range, or empty) is left out
     */
    public static int[] mapHighlights(String raw, String display, int[] highlights) {
        if (highlights == null || highlights.length < 2 || raw == null || display == null) {
            return new int[0];
        }
        Lines lines = new Lines(raw, display);
        List<Integer> rawStarts = lines.rawStarts;
        List<Integer> contentEnds = lines.contentEnds;
        List<Integer> displayStarts = lines.displayStarts;
        int n = raw.length();

        int[] out = new int[highlights.length / 2 * 2];
        int count = 0;
        for (int i = 0; i + 1 < highlights.length; i += 2) {
            int start = highlights[i];
            int end = highlights[i + 1];
            if (start < 0 || end <= start || start > n) {
                continue;
            }
            int line = lineOf(rawStarts, start);
            if (line >= displayStarts.size()) {
                continue;
            }
            int lineStart = rawStarts.get(line);
            int contentLength = contentEnds.get(line) - lineStart;
            int startColumn = Math.min(start - lineStart, contentLength);
            int endColumn = Math.min(end - lineStart, contentLength);
            if (endColumn <= startColumn) {
                continue;
            }
            int displayLineStart = displayStarts.get(line);
            if (displayLineStart + endColumn > display.length()) {
                continue;
            }
            out[count++] = displayLineStart + startColumn;
            out[count++] = displayLineStart + endColumn;
        }
        return Arrays.copyOf(out, count);
    }

    /**
     * Where a char index of the raw page text falls in the display text: the same column on the same
     * line (an index on a line end goes to the end of that line's content). The raw text's length,
     * the end of the file, is the display text's end. -1 for an index outside the raw text.
     *
     * @param raw the page text exactly as the server sent it
     * @param display what {@link #normal} or {@link #special} made of it
     */
    public static int displayIndex(String raw, String display, int rawIndex) {
        if (raw == null || display == null || rawIndex < 0 || rawIndex > raw.length()) {
            return -1;
        }
        Lines lines = new Lines(raw, display);
        int line = lineOf(lines.rawStarts, rawIndex);
        if (line >= lines.displayStarts.size()) {
            // After the page's last line end: nothing follows it in the display text.
            return display.length();
        }
        int lineStart = lines.rawStarts.get(line);
        int column = Math.min(rawIndex - lineStart, lines.contentEnds.get(line) - lineStart);
        return Math.min(lines.displayStarts.get(line) + column, display.length());
    }

    /**
     * The page's own text behind a range of the display text, for Copy: the file's line ends (LF,
     * CRLF or a lone CR) where the range crosses one, and never the labels of special mode. A range
     * that covers any part of a line end's labels covers that line end. The page's last line end
     * has nothing to select in normal mode, so it is never copied there.
     *
     * @param raw the page text exactly as the server sent it
     * @param display what {@link #normal} or {@link #special} made of it
     * @param start the range's start in {@code display}
     * @param end the range's end in {@code display}, exclusive
     */
    public static String rawText(String raw, String display, int start, int end) {
        if (raw == null || display == null || start < 0 || end > display.length() || start >= end) {
            return "";
        }
        Lines lines = new Lines(raw, display);
        int from = lines.rawIndex(start, false);
        int to = lines.rawIndex(end, true);
        return from < 0 || to < from ? "" : raw.substring(from, to);
    }

    /**
     * Where each line starts and where its content ends, in the raw text and in the display text.
     * Both texts have the same lines (the raw text may have one more, empty, after a last line end):
     * a line's content is the same in both, and only what stands for its line end differs.
     */
    private static final class Lines {
        private final String raw;
        private final String display;
        // In the raw text: where each line starts, and where its content ends (its line end starts).
        final List<Integer> rawStarts = new ArrayList<>();
        final List<Integer> contentEnds = new ArrayList<>();
        // In the display text: where each line starts.
        final List<Integer> displayStarts = new ArrayList<>();

        Lines(String raw, String display) {
            this.raw = raw;
            this.display = display;
            rawStarts.add(0);
            int n = raw.length();
            for (int i = 0; i < n; i++) {
                char c = raw.charAt(i);
                if (c == '\n' || c == '\r') {
                    contentEnds.add(i);
                    if (c == '\r' && i + 1 < n && raw.charAt(i + 1) == '\n') {
                        i++;
                    }
                    rawStarts.add(i + 1);
                }
            }
            contentEnds.add(n);
            displayStarts.add(0);
            for (int i = 0; i < display.length(); i++) {
                if (display.charAt(i) == '\n') {
                    displayStarts.add(i + 1);
                }
            }
        }

        /**
         * Where a display offset falls in the raw text: the same column on the same line, or, inside
         * the labels of a line end, the part of that line end they stand for (rounded down for a
         * range's start and up for its end). -1 when the line is not in the raw text.
         */
        int rawIndex(int offset, boolean roundUp) {
            int line = lineOf(displayStarts, offset);
            if (line >= rawStarts.size()) {
                return -1;
            }
            int lineStart = rawStarts.get(line);
            int contentLength = contentEnds.get(line) - lineStart;
            int column = offset - displayStarts.get(line);
            if (column <= contentLength) {
                return lineStart + column;
            }
            // Past the content: special mode's labels, two letters for each char of the line end.
            int terminator = (line + 1 < rawStarts.size() ? rawStarts.get(line + 1) : raw.length())
                    - contentEnds.get(line);
            int labelsEnd = line + 1 < displayStarts.size() ? displayStarts.get(line + 1) - 1 : display.length();
            int labels = labelsEnd - displayStarts.get(line) - contentLength;
            if (terminator <= 0 || labels <= 0) {
                return contentEnds.get(line);
            }
            int into = Math.min(column - contentLength, labels);
            return contentEnds.get(line) + (roundUp ? (into * terminator + labels - 1) / labels
                    : into * terminator / labels);
        }
    }

    /** Index of the last line start at or before {@code position}. */
    private static int lineOf(List<Integer> starts, int position) {
        int low = 0;
        int high = starts.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (starts.get(mid) <= position) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * True for a character that is in the text but draws nothing a reader can
     * see: zero-width and other format characters, the byte order mark, the soft
     * hyphen, the no-break space and the other non-ASCII spaces, the C1
     * controls, the line and paragraph separators, and the directional marks.
     */
    public static boolean isInvisibleButPresent(int cp) {
        if (cp >= 0x80 && cp <= 0x9F) {
            return true;
        }
        switch (cp) {
            case 0x00AD: // soft hyphen
            case 0x061C: // Arabic letter mark
            case 0x200E: // left-to-right mark
            case 0x200F: // right-to-left mark
            case 0x2028: // line separator
            case 0x2029: // paragraph separator
            case 0xFEFF: // byte order mark / zero width no-break space
                return true;
            default:
                break;
        }
        int type = Character.getType(cp);
        if (type == Character.SPACE_SEPARATOR) {
            return cp != ' ';
        }
        return type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /**
     * The runs of {@link #isInvisibleButPresent invisible} characters as
     * {start, end} char offsets, end exclusive. Adjacent ones are merged.
     */
    public static List<int[]> invisibleRanges(String display) {
        return ranges(display, cp -> isInvisibleButPresent(cp));
    }

    /**
     * The runs of characters the font cannot draw. Plain ASCII, tabs, newlines
     * and anything already counted as invisible are left out: they are either
     * drawn or reported by {@link #invisibleRanges}.
     *
     * @param canDisplay normally {@code font::canDisplay}
     */
    public static List<int[]> undisplayableRanges(String display, IntPredicate canDisplay) {
        return ranges(display, cp -> cp >= 0x80 && !isInvisibleButPresent(cp) && !canDisplay.test(cp));
    }

    private static List<int[]> ranges(String text, IntPredicate wanted) {
        List<int[]> out = new ArrayList<>();
        int runStart = -1;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (wanted.test(cp)) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else if (runStart >= 0) {
                out.add(new int[] {runStart, i});
                runStart = -1;
            }
            i = next;
        }
        if (runStart >= 0) {
            out.add(new int[] {runStart, text.length()});
        }
        return out;
    }

    /** The code point and its Unicode name, for example {@code U+1F600 GRINNING FACE}. */
    public static String describe(int cp) {
        String name = Character.getName(cp);
        return String.format(Locale.ROOT, "U+%04X %s", cp, name != null ? name : "unassigned code point");
    }

    /**
     * A plain explanation for the characters the server substitutes, and for
     * invisible ones; null for anything else.
     */
    public static String explain(int cp) {
        if ((cp >= 0x2400 && cp <= 0x241F) || cp == DELETE_SYMBOL) {
            int control = cp == DELETE_SYMBOL ? 0x7F : cp - 0x2400;
            return "Control character " + Character.getName(control) + " shown as a symbol.";
        }
        if (cp == BIDI_SUBSTITUTE) {
            return "A bidirectional override character, replaced because it could make the line "
                    + "display in a different order than it is stored.";
        }
        if (cp == REPLACEMENT) {
            return "Bytes that are not valid text in the file's character set, or a U+FFFD character"
                    + " written in the file.";
        }
        if (isInvisibleButPresent(cp)) {
            return "An invisible character that is present in the text.";
        }
        return null;
    }

    /** {@link #describe} plus, on a second line, {@link #explain} when there is one. */
    public static String tooltip(int cp) {
        String explanation = explain(cp);
        return explanation == null ? describe(cp) : describe(cp) + "\n" + explanation;
    }
}
