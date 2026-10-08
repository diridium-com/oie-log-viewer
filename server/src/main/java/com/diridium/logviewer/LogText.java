// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Byte scanning and decoding shared by paging and search.
 *
 * <p>A line ends at {@code \n}, {@code \r\n} or a lone {@code \r}: the rule
 * Monaco and Notepad++ use, so the server's line numbers match what both
 * viewers draw. Unix engines end log lines with LF, Windows engines with
 * CRLF (log4j's {@code %n} is the platform separator), and an HL7 message
 * logged by a channel separates its segments with lone CRs on both, so each
 * segment gets its own line instead of one smushed line.</p>
 *
 * <p>Everything here works on raw bytes, which is sound for any charset that
 * encodes CR and LF as the single bytes 0x0D and 0x0A and never uses them
 * inside a multi-byte character: UTF-8, the ISO-8859 and Windows code pages,
 * and the legacy CJK encodings (their trail bytes start at 0x30 or above).
 * UTF-16/32 and EBCDIC fail that test and are offered for download only; see
 * {@link #isNewlineSafe}.</p>
 */
final class LogText {

    static final byte LF = '\n';
    static final byte CR = '\r';

    /** U+2421 SYMBOL FOR DELETE: how the delete control character (0x7F) is shown. */
    private static final char DELETE_PICTURE = (char) 0x2421;

    /** U+2426 SYMBOL FOR SUBSTITUTE FORM TWO: stands in for a neutralized bidirectional control. */
    private static final char BIDIRECTIONAL_CONTROL_PICTURE = (char) 0x2426;

    /** U+FFFD REPLACEMENT CHARACTER. */
    private static final char REPLACEMENT_CHARACTER = (char) 0xFFFD;

    private LogText() {
    }

    /** True when {@code '\n'} and {@code '\r'} encode as the single bytes 0x0A and 0x0D. */
    static boolean isNewlineSafe(Charset charset) {
        return Arrays.equals("\n".getBytes(charset), new byte[] {0x0A})
                && Arrays.equals("\r".getBytes(charset), new byte[] {0x0D});
    }

    /**
     * Decodes bytes that end on a line boundary or at the end of the content.
     * Malformed or unmappable input becomes U+FFFD; it never throws, since a
     * log file is not guaranteed to be valid in its declared charset.
     */
    static String decode(byte[] bytes, int from, int to, Charset charset) {
        return new String(bytes, from, to - from, charset);
    }

    /**
     * Returns how many bytes of {@code bytes[from, to)} form whole characters,
     * for splitting a line that is longer than a page or search piece. The
     * decoder is run with {@code endOfInput=false}, so a character cut off at
     * {@code to} is left unconsumed rather than replaced, and the split lands
     * before it whatever the charset.
     */
    static int wholeCharacterBytes(byte[] bytes, int from, int to, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer in = ByteBuffer.wrap(bytes, from, to - from);
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult result = decoder.decode(in, out, false);
            out.clear();
            if (result.isUnderflow()) {
                break;
            }
        }
        int consumed = in.position() - from;
        // A window of a few bytes can be all "incomplete character". Never
        // return zero, or the caller could not make progress.
        return consumed > 0 ? consumed : to - from;
    }

    /**
     * Moves an arbitrary start position forward past UTF-8 continuation bytes,
     * so a page that starts inside a long line does not begin with a broken
     * character. Only UTF-8 can be resynchronized from an arbitrary byte;
     * single-byte charsets need nothing, and for the legacy multi-byte ones the
     * first character of such a page may decode as U+FFFD (the page is
     * flagged as starting mid-line either way).
     */
    static int characterStart(byte[] bytes, int index, int limit, Charset charset) {
        if (!StandardCharsets.UTF_8.equals(charset)) {
            return index;
        }
        int i = index;
        while (i < limit && i < index + 3 && (bytes[i] & 0xC0) == 0x80) {
            i++;
        }
        return i;
    }

    /**
     * How many of {@code bytes[0, length)} decode to {@code text[0, end)}, where
     * {@code text} is what {@link #decode} made of them. Re-encoding the text
     * gives the answer while every char before {@code end} came from a valid
     * character. A U+FFFD there may stand for one malformed byte or several,
     * which only decoding the bytes again can tell, so then the bytes are
     * decoded up to that char, in bounded memory.
     */
    static long bytesBefore(byte[] bytes, int length, String text, int end, Charset charset) {
        if (end == 0 || text.lastIndexOf(REPLACEMENT_CHARACTER, end - 1) < 0) {
            return encodedLength(text, end, charset);
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer in = ByteBuffer.wrap(bytes, 0, length);
        CharBuffer out = CharBuffer.allocate(Math.min(end, 8192));
        int remaining = end;
        while (remaining > 0) {
            out.clear();
            out.limit(Math.min(remaining, out.capacity()));
            CoderResult result = decoder.decode(in, out, true);
            remaining -= out.position();
            if (result.isUnderflow()) {
                break; // the bytes ran out
            }
            if (out.position() == 0) {
                // The next sequence wants more room than is left. The JDK's UTF-8 decoder asks
                // for two chars at a four-byte lead before it knows whether the sequence is
                // valid, so a malformed one stops here too.
                return in.position() + bytesDecodingTo(bytes, in.position(), length,
                        text.substring(end - remaining, end), charset);
            }
        }
        return in.position();
    }

    /**
     * The longest run of bytes from {@code from} that decodes to exactly
     * {@code chars} (a char or two). Any byte more would add a char, so that
     * run is where those chars end. Zero when none does: the chars are the
     * first half of a surrogate pair, which a match never starts inside.
     */
    private static int bytesDecodingTo(byte[] bytes, int from, int length, String chars, Charset charset) {
        int found = 0;
        for (int n = 1; n <= Math.min(16, length - from); n++) {
            if (decode(bytes, from, from + n, charset).equals(chars)) {
                found = n;
            }
        }
        return found;
    }

    /** Byte length of {@code text[0, end)} in the charset, in bounded memory. */
    static long encodedLength(CharSequence text, int end, Charset charset) {
        if (charset.newEncoder().maxBytesPerChar() == 1.0f) {
            return end;
        }
        CharsetEncoder encoder = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer in = CharBuffer.wrap(text, 0, end);
        ByteBuffer out = ByteBuffer.allocate(8192);
        long total = 0;
        while (true) {
            CoderResult result = encoder.encode(in, out, true);
            total += out.position();
            out.clear();
            if (result.isUnderflow()) {
                break;
            }
        }
        while (encoder.flush(out).isOverflow()) {
            total += out.position();
            out.clear();
        }
        return total + out.position();
    }

    static int indexOfNewline(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == LF) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether {@code bytes[i]} is the last byte of a line terminator: an LF
     * (alone or ending a CRLF) or a lone CR. A CR is lone when the byte after
     * it is not LF. When the CR is the array's last byte, that depends on a
     * byte not read yet, so it counts as lone only if the content ends there.
     */
    static boolean isLineEnd(byte[] bytes, int i, boolean contentEndsAfterArray) {
        if (bytes[i] == LF) {
            return true;
        }
        if (bytes[i] != CR) {
            return false;
        }
        return i + 1 < bytes.length ? bytes[i + 1] != LF : contentEndsAfterArray;
    }

    /** First index in {@code [from, to)} that ends a line; see {@link #isLineEnd}. */
    static int indexOfLineEnd(byte[] bytes, int from, int to, boolean contentEndsAfterArray) {
        for (int i = from; i < to; i++) {
            if (isLineEnd(bytes, i, contentEndsAfterArray)) {
                return i;
            }
        }
        return -1;
    }

    /** Last index in {@code [from, to)} that ends a line; see {@link #isLineEnd}. */
    static int lastIndexOfLineEnd(byte[] bytes, int from, int to, boolean contentEndsAfterArray) {
        for (int i = to - 1; i >= from; i--) {
            if (isLineEnd(bytes, i, contentEndsAfterArray)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the {@code n}th line end in {@code [from, to)}, counting
     * forward from {@code from}; -1 when there are fewer. See {@link #isLineEnd}.
     */
    static int indexOfNthLineEnd(byte[] bytes, int from, int to, int n, boolean contentEndsAfterArray) {
        int seen = 0;
        for (int i = from; i < to; i++) {
            if (isLineEnd(bytes, i, contentEndsAfterArray) && ++seen == n) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Index of the {@code n}th line end in {@code [from, to)}, counting
     * backward from {@code to - 1}; -1 when there are fewer. See {@link #isLineEnd}.
     */
    static int lastIndexOfNthLineEnd(byte[] bytes, int from, int to, int n, boolean contentEndsAfterArray) {
        int seen = 0;
        for (int i = to - 1; i >= from; i--) {
            if (isLineEnd(bytes, i, contentEndsAfterArray) && ++seen == n) {
                return i;
            }
        }
        return -1;
    }

    /** Line ends in {@code [from, to)}; see {@link #isLineEnd}. */
    static int countLineEnds(byte[] bytes, int from, int to, boolean contentEndsAfterArray) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (isLineEnd(bytes, i, contentEndsAfterArray)) {
                count++;
            }
        }
        return count;
    }

    /** First CR or LF in {@code [from, to)}. */
    static int indexOfCrOrLf(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] == LF || bytes[i] == CR) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Lines in content of {@code length} bytes holding {@code lineEnds} line
     * ends, given its last byte ({@code -1} for none): a last line without a
     * terminator counts too, and empty content has no lines.
     */
    static long lineCount(long lineEnds, int lastByte, long length) {
        if (length == 0) {
            return 0;
        }
        return lineEnds + (lastByte == LF || lastByte == CR ? 0 : 1);
    }

    /**
     * Counts line ends across a stream read in buffers. A CR at the end of one
     * buffer is held until the next byte shows whether it is lone or the first
     * half of a CRLF, so a buffer boundary never changes the count.
     */
    static final class LineEndCounter {
        private long count;
        private boolean pendingCr;

        void feed(byte[] bytes, int from, int to) {
            for (int i = from; i < to; i++) {
                byte b = bytes[i];
                if (pendingCr) {
                    pendingCr = false;
                    if (b != LF) {
                        count++;
                    }
                }
                if (b == LF) {
                    count++;
                } else if (b == CR) {
                    pendingCr = true;
                }
            }
        }

        /**
         * Line ends before the next byte, given that byte ({@code -1} at the
         * end of the content). A held CR counts unless the next byte is its LF,
         * in which case the terminator ends at that LF, after this point.
         */
        long countBefore(int nextByte) {
            return count + (pendingCr && nextByte != LF ? 1 : 0);
        }
    }

    /**
     * Makes decoded log text safe to send, keeping line terminators exactly
     * as they are in the file.
     *
     * <ul>
     * <li>CR, LF and tab pass through unchanged, so a viewer can break lines
     * where the file does and, on request, show which terminator each line
     * had (CRLF on a Windows engine, LF on Unix, a lone CR between HL7
     * segments).</li>
     * <li>Any other C0 control becomes its Unicode control picture
     * (U+2400 + code), such as U+240B for the VT that starts an MLLP frame,
     * and the delete control character (0x7F) becomes U+2421.</li>
     * <li>Unicode's explicit bidirectional embedding, override and isolate
     * characters (U+202A to U+202E, U+2066 to U+2069) become U+2426.</li>
     * <li>U+FFFE and U+FFFF become U+FFFD.</li>
     * </ul>
     *
     * <p>Why the bidirectional controls are replaced: they are invisible, and
     * both Swing text components and browsers obey them, so a log line
     * carrying one can display in a different order than it is stored. A
     * filename logged from an inbound message as {@code invoice<U+202E>fdp.exe}
     * reads as {@code invoiceexe.pdf} (the "Trojan Source" technique,
     * CVE-2021-42574, which uses exactly these nine characters). Ordinary
     * right-to-left text such as a Hebrew or Arabic name still displays
     * correctly: its direction comes from its letters, not from these
     * controls. The directional marks (U+200E, U+200F, U+061C) are left
     * alone, since they cannot reverse a run of text.</p>
     *
     * <p>Why the controls are replaced: XStream writes them as character
     * references such as {@code &#xb;}, which XML 1.0 forbids. Measured against
     * the 4.6.0 release jars, the Administrator's XML path reads them back, but
     * the JSON path the web administrator uses (XML parsed again by the JDK's
     * StAX parser) fails the whole response with a ParseError on VT, NUL, ESC
     * and U+FFFE. Log files do contain these: the VT and FS that frame MLLP
     * messages, ANSI colour codes, NUL runs after a crash. CR is legal XML:
     * the engine's own ObjectXMLSerializer writes it as {@code &#xd;} (so a
     * parser does not normalize it away) and CRLF, lone CR and {@code \r\r\n}
     * were measured to round-trip exactly through both the XML and the JSON
     * serializer of 4.6.0.</p>
     *
     * <p>Every replacement is one char for one char, so match indices stay
     * valid.</p>
     */
    static String sanitize(String text) {
        int n = text.length();
        int i = 0;
        while (i < n && isClean(text.charAt(i))) {
            i++;
        }
        if (i == n) {
            return text;
        }
        StringBuilder sb = new StringBuilder(n);
        sb.append(text, 0, i);
        for (; i < n; i++) {
            char c = text.charAt(i);
            if (isClean(c)) {
                sb.append(c);
            } else if (c < 0x20) {
                sb.append((char) (0x2400 + c));
            } else if (c == 0x7F) {
                sb.append(DELETE_PICTURE);
            } else if (isBidirectionalControl(c)) {
                sb.append(BIDIRECTIONAL_CONTROL_PICTURE);
            } else {
                sb.append(REPLACEMENT_CHARACTER);
            }
        }
        return sb.toString();
    }

    private static boolean isClean(char c) {
        if (c < 0x20) {
            return c == '\n' || c == '\r' || c == '\t';
        }
        return c != 0x7F && !isBidirectionalControl(c) && c < 0xFFFE;
    }

    /** Unicode's explicit bidirectional embedding, override and isolate characters. */
    static boolean isBidirectionalControl(char c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
