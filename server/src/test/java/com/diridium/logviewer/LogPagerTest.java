// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogPagerTest {

    private static final int CAP = LogPager.PAGE_MAX_BYTES;

    @TempDir
    Path dir;

    @Test
    void walksBackwardAndForwardOverTheSameBytes() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.lines(8000, "INFO").getBytes(StandardCharsets.UTF_8);
        assertTrue(content.length > 3 * CAP, "fixture must span several pages");
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        List<LogPage> back = LogFixture.walkBackward(fx.service, id);
        List<LogPage> forward = LogFixture.walkForward(fx.service, id);
        assertCovers(back, content, StandardCharsets.UTF_8);
        assertCovers(forward, content, StandardCharsets.UTF_8);
        for (LogPage page : back) {
            assertFalse(page.isStartsMidLine() || page.isEndsMidLine());
            assertNotNull(page.getFirstLineNumber());
            assertEquals((Long) (long) content.length, page.getContentLength());
        }
        assertTrue(back.get(back.size() - 1).isAtEnd());
    }

    @Test
    void pagesHoldAThousandLinesUnlessTheByteCapComesFirst() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String[] endings = {"\n", "\r\n", "\r"};
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3500; i++) {
            sb.append("short line ").append(i).append(endings[i % endings.length]);
        }
        // Short lines: 1,000 of them are far inside the byte cap, so the line limit decides.
        for (String last : new String[] {"", "a last line with no terminator"}) {
            byte[] content = (sb + last).getBytes(StandardCharsets.UTF_8);
            fx.writeActive(content, 1_000_000L);
            String id = fx.idOf("mirth.log");
            long total = last.isEmpty() ? 3500 : 3501;
            long rest = total - 3000;

            List<LogPage> forward = LogFixture.walkForward(fx.service, id);
            List<LogPage> back = LogFixture.walkBackward(fx.service, id);
            assertCovers(forward, content, StandardCharsets.UTF_8);
            assertCovers(back, content, StandardCharsets.UTF_8);
            assertEquals(List.of(1000L, 1000L, 1000L, rest), linesPerPage(forward, content));
            assertEquals(List.of(rest, 1000L, 1000L, 1000L), linesPerPage(back, content));
            for (LogPage page : forward) {
                assertEquals((Long) total, page.getTotalLines());
            }

            // A page opened at a line holds the 500 lines above it.
            int line2000 = sb.indexOf("short line 2000" + endings[2000 % endings.length]);
            LogPage at = fx.service.readPage(id, LogPageAnchor.AT, (long) line2000);
            assertEquals((Long) 1500L, at.getFirstLineNumber());
            assertEquals(1000L, LogFixture.lineCount(content, (int) at.getStartOffset(), (int) at.getEndOffset()));
        }

        // Lines of 400 bytes: 1,000 would be 400 KB, so the byte cap ends each page first.
        StringBuilder wideLines = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            wideLines.append("y".repeat(399)).append('\n');
        }
        byte[] wide = wideLines.toString().getBytes(StandardCharsets.UTF_8);
        fx.writeActive(wide, 1_000_000L);
        List<LogPage> forward = LogFixture.walkForward(fx.service, fx.idOf("mirth.log"));
        assertCovers(forward, wide, StandardCharsets.UTF_8);
        LogPage first = forward.get(0);
        assertEquals(CAP / 400 * 400, first.getEndOffset(), "as many whole lines as fit in the cap");
    }

    @Test
    void countsTheLinesOfAnArchiveAndOfAnEmptyFile() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.mixedLines(5000).getBytes(StandardCharsets.UTF_8);
        fx.writeActive(new byte[0], 2_000_000L);
        fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);
        long total = LogFixture.lineCount(content, 0, content.length);

        String archive = fx.idOf("mirth.log.1.zip");
        for (LogPage page : List.of(fx.service.readPage(archive, LogPageAnchor.HEAD, null),
                fx.service.readPage(archive, LogPageAnchor.TAIL, null),
                fx.service.readPage(archive, LogPageAnchor.AT, (long) content.length / 2))) {
            assertEquals((Long) total, page.getTotalLines());
            assertEquals((Long) (long) content.length, page.getContentLength());
        }
        LogPage empty = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null);
        assertEquals((Long) 0L, empty.getTotalLines());
    }

    private static List<Long> linesPerPage(List<LogPage> pages, byte[] content) {
        List<Long> counts = new ArrayList<>();
        for (LogPage page : pages) {
            counts.add(LogFixture.lineCount(content, (int) page.getStartOffset(), (int) page.getEndOffset()));
        }
        return counts;
    }

    @Test
    void splitsALineLongerThanAPageOnCharacterBoundaries() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        // 3-byte characters, so a split at the byte cap would land mid-character.
        String longLine = "€".repeat(300_000);
        byte[] content = ("first\n" + longLine + "\nlast\n").getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        List<LogPage> forward = LogFixture.walkForward(fx.service, id);
        List<LogPage> back = LogFixture.walkBackward(fx.service, id);
        assertCovers(forward, content, StandardCharsets.UTF_8);
        assertCovers(back, content, StandardCharsets.UTF_8);
        assertTrue(forward.stream().anyMatch(LogPage::isEndsMidLine));
        assertTrue(forward.stream().anyMatch(LogPage::isStartsMidLine));
        assertTrue(back.stream().anyMatch(LogPage::isStartsMidLine));
        // Every piece of the long line is numbered as line 2.
        for (LogPage page : forward) {
            if (page.isStartsMidLine()) {
                assertEquals(2L, page.getFirstLineNumber());
            }
        }
    }

    @Test
    void atPageContainsTheOffsetWithContextBefore() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.lines(6000, "WARN").getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        for (long offset : new long[] {0, 1, 5000, CAP / 2, CAP, content.length / 2, content.length - 1,
                content.length}) {
            LogPage page = fx.service.readPage(id, LogPageAnchor.AT, offset);
            assertTrue(page.getStartOffset() <= offset, "start <= " + offset);
            assertTrue(offset < page.getEndOffset() || (offset == content.length && page.isAtEnd()),
                    "offset " + offset + " on page " + page.getStartOffset() + ".." + page.getEndOffset());
            if (offset == content.length) {
                assertEquals(page.getText().length(), page.getTargetIndex(), "the end of the file");
            } else if ((content[(int) offset] & 0xC0) != 0x80) { // a character's start, as a match offset always is
                String there = new String(content, (int) offset, (int) Math.min(4, content.length - offset),
                        StandardCharsets.UTF_8);
                assertEquals(LogText.sanitize(there).codePointAt(0), page.getText().codePointAt(page.getTargetIndex()),
                        "the target index at " + offset);
            }
            if (offset > CAP) {
                assertTrue(offset - page.getStartOffset() > 1000, "context kept above " + offset);
            }
        }
    }

    @Test
    void keepsLineEndsRawAndShowsControlCharacters() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = "one\r\ntwo \u000bMSH|a\rPID|b\u001c\r\nthree\u0000\n"
                .getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);

        LogPage page = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null);
        // CRLF, the lone CR between HL7 segments and LF all arrive as written,
        // so a viewer can break lines where the file does and show which
        // terminator each line had; the MLLP VT/FS and the NUL become pictures.
        assertEquals("one\r\ntwo \u240BMSH|a\rPID|b\u241C\r\nthree\u2400\n", page.getText());
        assertEquals(1L, page.getFirstLineNumber());
        assertNull(page.getTargetIndex(), "only a page read AT an offset has a target");
    }

    @Test
    void showsTheDeleteCharacterAndNeutralizesBidirectionalOverrides() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        char rightToLeftOverride = (char) 0x202E;
        char delete = (char) 0x7F;
        char rightToLeftMark = (char) 0x200F;
        String hebrewName = new String(new char[] {(char) 0x05E9, (char) 0x05DC, (char) 0x05D5, (char) 0x05DD});
        String line = "Rejected attachment: invoice" + rightToLeftOverride + "fdp.exe" + delete
                + " patient " + hebrewName + rightToLeftMark + "\n";
        fx.writeActive(line.getBytes(StandardCharsets.UTF_8), 1_000_000L);

        // The override would make the line read "invoiceexe.pdf"; replaced, it
        // reads as stored. Right-to-left letters and a directional mark pass through.
        String text = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null).getText();
        assertEquals("Rejected attachment: invoice" + (char) 0x2426 + "fdp.exe" + (char) 0x2421
                + " patient " + hebrewName + rightToLeftMark + "\n", text);

        // All nine explicit bidirectional controls, one for one; their neighbours untouched.
        for (int c : new int[] {0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069}) {
            assertEquals(String.valueOf((char) 0x2426), LogText.sanitize(String.valueOf((char) c)),
                    Integer.toHexString(c));
        }
        for (int c : new int[] {0x2029, 0x2065, 0x206A, 0x200E}) {
            assertEquals(String.valueOf((char) c), LogText.sanitize(String.valueOf((char) c)), Integer.toHexString(c));
        }
    }

    @Test
    void pagesBinaryLoggedAsTextWithoutBreaking() throws Exception {
        // A channel that logs a binary payload as a string puts arbitrary bytes
        // in the log: sequences invalid in the charset, NULs and other control
        // characters, and CR and LF bytes at random, which chop the "text"
        // into many short garbage lines. Each page must still cover exactly
        // its bytes, number its lines by the same rule, and hold only
        // characters XML 1.0 can carry, or the whole response fails on the wire.
        for (Charset charset : new Charset[] {StandardCharsets.UTF_8, Charset.forName("windows-1252")}) {
            LogFixture fx = new LogFixture(Files.createDirectory(dir.resolve(charset.name())), charset);
            byte[] binary = new byte[3 * CAP];
            new Random(42).nextBytes(binary);
            byte[] content = concat("INFO before\n".getBytes(charset), binary, "\nINFO after\n".getBytes(charset));
            fx.writeActive(content, 2_000_000L);
            fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);

            for (String name : new String[] {"mirth.log", "mirth.log.1.zip"}) {
                String id = fx.idOf(name);
                String where = charset + " " + name;
                for (List<LogPage> pages : List.of(LogFixture.walkBackward(fx.service, id),
                        LogFixture.walkForward(fx.service, id))) {
                    assertCovers(pages, content, charset, 0, false);
                    assertEquals(content.length, pages.get(pages.size() - 1).getEndOffset(), where);
                    for (LogPage page : pages) {
                        assertXmlCharacters(page.getText(), where);
                    }
                }
                LogSearchResult result = fx.service.search("INFO after", false, true, id, null, null);
                assertEquals(1, result.getMatches().size(), where);
                LogSearchMatch match = result.getMatches().get(0);
                assertEquals(LogFixture.lineEndsBefore(content, match.getLineOffset()) + 1, match.getLineNumber(),
                        where);
            }
        }
    }

    @Test
    void numbersLinesEndedByLfCrlfAndLoneCr() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.mixedLines(9000).getBytes(StandardCharsets.UTF_8);
        assertTrue(content.length > 3 * CAP, "fixture must span several pages");
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        assertCovers(LogFixture.walkBackward(fx.service, id), content, StandardCharsets.UTF_8);
        assertCovers(LogFixture.walkForward(fx.service, id), content, StandardCharsets.UTF_8);
        // Pages opened at a CR and at the byte after it (the LF of a CRLF, or
        // the start of the next line after a lone CR), for every 37th CR in the
        // file: the page holds the offset and is numbered by the same rule.
        List<Integer> crs = new ArrayList<>();
        for (int i = 0; i < content.length; i++) {
            if (content[i] == '\r') {
                crs.add(i);
            }
        }
        assertTrue(crs.size() > 1000, "fixture must have plenty of CRs");
        for (int k = 0; k < crs.size(); k += 37) {
            int i = crs.get(k);
            for (long offset : new long[] {i, i + 1}) {
                LogPage page = fx.service.readPage(id, LogPageAnchor.AT, offset);
                assertCovers(List.of(page), content, StandardCharsets.UTF_8, page.getStartOffset());
                assertTrue(page.getStartOffset() <= offset && offset <= page.getEndOffset());
            }
        }
    }

    @Test
    void numbersArchiveLinesByTheSameRule() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.mixedLines(9000).getBytes(StandardCharsets.UTF_8);
        fx.writeActive("active\n".getBytes(StandardCharsets.UTF_8), 2_000_000L);
        fx.writeArchive(1, "mirth.log.3", content, 1_000_000L);
        String id = fx.idOf("mirth.log.1.zip");

        assertCovers(LogFixture.walkBackward(fx.service, id), content, StandardCharsets.UTF_8);
        assertCovers(LogFixture.walkForward(fx.service, id), content, StandardCharsets.UTF_8);
    }

    @Test
    void neverCutsACrlfWhenSplittingALongLine() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        // A line with no line end inside the page cap, whose CRLF straddles the
        // cap: a plain split would end one page on the CR and start the next on
        // its LF.
        byte[] content = ("x".repeat(CAP - 1) + "\r\nnext\n").getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        List<LogPage> forward = LogFixture.walkForward(fx.service, id);
        assertEquals(CAP - 1, forward.get(0).getEndOffset(), "cut before the CR");
        assertTrue(forward.get(1).getText().startsWith("\r\n"));
        assertCovers(forward, content, StandardCharsets.UTF_8);
        assertCovers(LogFixture.walkBackward(fx.service, id), content, StandardCharsets.UTF_8);
    }

    @Test
    void decodesWithTheAppendersCharset() throws Exception {
        Charset cp1252 = Charset.forName("windows-1252");
        LogFixture fx = new LogFixture(dir, cp1252);
        fx.writeActive("Café €\n".getBytes(cp1252), 1_000_000L);

        assertEquals("Café €\n", fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null)
                .getText());
    }

    @Test
    void pagesAZipArchiveWhateverItsEntryIsCalled() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.lines(7000, "ERROR").getBytes(StandardCharsets.UTF_8);
        fx.writeActive("active\n".getBytes(StandardCharsets.UTF_8), 2_000_000L);
        fx.writeArchive(1, "mirth.log.5", content, 1_000_000L);
        String id = fx.idOf("mirth.log.1.zip");

        List<LogPage> back = LogFixture.walkBackward(fx.service, id);
        List<LogPage> forward = LogFixture.walkForward(fx.service, id);
        assertCovers(back, content, StandardCharsets.UTF_8);
        assertCovers(forward, content, StandardCharsets.UTF_8);
        assertEquals((Long) (long) content.length, back.get(back.size() - 1).getContentLength());
        // Within the line-number limit the first page reads on to the end for
        // the total, which gives the archive's length too.
        assertEquals((Long) (long) content.length, forward.get(0).getContentLength());
        assertEquals((Long) 7000L, forward.get(0).getTotalLines());
    }

    @Test
    void refusesAnIdAfterTheActiveFileIsRecreated() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO 10:00:00.001 first\nINFO more\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        String id = fx.idOf("mirth.log");

        // Growth keeps the id valid.
        java.nio.file.Files.write(fx.active, "INFO appended\n".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        assertTrue(fx.service.readPage(id, LogPageAnchor.TAIL, null).getText().endsWith("appended\n"));

        // Rollover recreates the file with a new first line.
        fx.writeActive("INFO 10:05:00.002 after rollover\n".getBytes(StandardCharsets.UTF_8), 1_000_001L);
        LogViewerException e = assertThrows(LogViewerException.class,
                () -> fx.service.readPage(id, LogPageAnchor.TAIL, null));
        assertEquals(LogViewerException.Kind.STALE, e.getKind());
    }

    @Test
    void refusesAPositionPastTheEndOfATruncatedFile() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String first = "INFO same first line\n";
        fx.writeActive((first + LogFixture.lines(100, "x")).getBytes(StandardCharsets.UTF_8), 1_000_000L);
        String id = fx.idOf("mirth.log");
        long end = fx.service.readPage(id, LogPageAnchor.TAIL, null).getEndOffset();

        // Same first line, shorter: the fingerprint cannot tell, the length check can.
        fx.writeActive(first.getBytes(StandardCharsets.UTF_8), 1_000_000L);
        LogViewerException e = assertThrows(LogViewerException.class,
                () -> fx.service.readPage(id, LogPageAnchor.AFTER, end));
        assertEquals(LogViewerException.Kind.STALE, e.getKind());
    }

    /** Every character is one XML 1.0 allows, and surrogates come in pairs. */
    static void assertXmlCharacters(String text, String where) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)),
                        where + ": unpaired surrogate at " + i);
                i++;
                continue;
            }
            boolean legal = c == 0x9 || c == 0xA || c == 0xD
                    || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD);
            assertTrue(legal, where + ": character 0x" + Integer.toHexString(c) + " at " + i);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] all = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }

    static void assertCovers(List<LogPage> pages, byte[] content, Charset charset) {
        assertCovers(pages, content, charset, 0);
        LogPage last = pages.get(pages.size() - 1);
        assertEquals(content.length, last.getEndOffset(), "pages must reach the end");
    }

    static void assertCovers(List<LogPage> pages, byte[] content, Charset charset, long firstStart) {
        assertCovers(pages, content, charset, firstStart, true);
    }

    /**
     * Checks contiguous pages from {@code firstStart} against the content, using the test's own line
     * oracle. {@code validText}: the content decodes cleanly, so a replacement character in a page
     * can only mean a character was split at a page boundary.
     */
    static void assertCovers(List<LogPage> pages, byte[] content, Charset charset, long firstStart,
                             boolean validText) {
        long expectedStart = firstStart;
        for (LogPage page : pages) {
            int start = (int) page.getStartOffset();
            int end = (int) page.getEndOffset();
            assertEquals(expectedStart, start, "pages must be contiguous");
            assertTrue(end > start || content.length == 0, "page must not be empty");
            assertTrue(end - start <= CAP, "page within cap");
            assertEquals(LogText.sanitize(new String(content, start, end - start, charset)), page.getText());
            assertFalse(validText && page.getText().contains("\uFFFD"),
                    "no character split at " + start + ".." + end);
            assertFalse(start > 0 && start < content.length && content[start - 1] == '\r' && content[start] == '\n',
                    "no page boundary between the CR and LF of a CRLF at " + start);
            if (!page.isStartsMidLine()) {
                assertTrue(start == 0 || LogFixture.endsLine(content, start - 1), "starts on a line at " + start);
            }
            if (!page.isEndsMidLine()) {
                assertTrue(end == content.length || LogFixture.endsLine(content, end - 1), "ends on a line at " + end);
            }
            if (page.getFirstLineNumber() != null) {
                assertEquals(LogFixture.lineEndsBefore(content, start) + 1L, page.getFirstLineNumber(),
                        "line number of the page at " + start);
            }
            assertTrue(LogFixture.lineCount(content, start, end) <= LogPager.PAGE_MAX_LINES,
                    "at most a page of lines at " + start);
            if (page.getTotalLines() != null) {
                assertEquals(LogFixture.lineCount(content, 0, content.length), page.getTotalLines(),
                        "total lines on the page at " + start);
            }
            expectedStart = end;
        }
    }
}
