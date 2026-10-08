// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Page-size boundaries, long-line splits at the search piece size, and the
 * position matrix (AT, BEFORE, AFTER at 0, the length, past it, negative and
 * missing).
 */
class LogBoundaryEdgeTest {

    private static final int CAP = LogPager.PAGE_MAX_BYTES;
    private static final int SEGMENT = LogSearcher.SEGMENT_BYTES;

    @TempDir
    Path dir;

    private int sequence;

    private LogFixture fixture() throws Exception {
        return new LogFixture(Files.createDirectory(dir.resolve("f" + sequence++)), StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static LogViewerException.Kind kindOf(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(LogViewerException.class, call).getKind();
    }

    // ========================
    // 7. Lines exactly as long as a page
    // ========================

    @Test
    void aLineExactlyAsLongAsAPageIsHandledForEveryTerminator() throws Exception {
        for (String terminator : new String[] {"\n", "\r\n", "\r"}) {
            for (int total : new int[] {CAP - 1, CAP, CAP + 1}) {
                for (String prefix : new String[] {"", "head\n"}) {
                    String where = "terminator " + terminator.length() + (terminator.equals("\r") ? " (CR)" : "")
                            + ", line of " + total + " bytes incl. terminator, prefix '" + prefix.trim() + "'";
                    LogFixture fx = fixture();
                    String line = "x".repeat(total - terminator.length()) + terminator;
                    byte[] content = utf8(prefix + line + "tail\n");
                    fx.writeActive(content, 1_000_000L);
                    String id = fx.idOf("mirth.log");

                    List<LogPage> forward = LogFixture.walkForward(fx.service, id);
                    List<LogPage> back = LogFixture.walkBackward(fx.service, id);
                    LogPagerTest.assertCovers(forward, content, StandardCharsets.UTF_8);
                    LogPagerTest.assertCovers(back, content, StandardCharsets.UTF_8);

                    if (prefix.isEmpty()) {
                        LogPage first = forward.get(0);
                        if (total <= CAP) {
                            assertEquals(total, first.getEndOffset(), where + ": the line alone is the first page");
                            assertFalse(first.isEndsMidLine(), where);
                        } else if (terminator.equals("\r\n")) {
                            assertEquals(CAP - 1, first.getEndOffset(), where + ": cut before the CR");
                            assertTrue(first.isEndsMidLine(), where);
                        } else {
                            assertEquals(CAP, first.getEndOffset(), where);
                            assertTrue(first.isEndsMidLine(), where);
                        }
                    }
                    // Opening the next line's offset, and the long line's own ends.
                    long tailAt = content.length - "tail\n".length();
                    for (long offset : new long[] {prefix.length(), tailAt - terminator.length(), tailAt, content.length}) {
                        LogPage page = fx.service.readPage(id, LogPageAnchor.AT, offset);
                        LogPagerTest.assertCovers(List.of(page), content, StandardCharsets.UTF_8, page.getStartOffset());
                        assertTrue(page.getStartOffset() <= offset
                                && (offset < page.getEndOffset() || (offset == content.length && page.isAtEnd())),
                                where + " AT " + offset + " on " + page.getStartOffset() + ".." + page.getEndOffset());
                    }
                    LogSearchResult result = fx.service.search("tail", false, true, id, null, null);
                    assertEquals(1, result.getMatches().size(), where);
                    LogSearchMatch match = result.getMatches().get(0);
                    assertEquals(tailAt, match.getLineOffset(), where);
                    assertEquals(LogFixture.lineEndsBefore(content, tailAt) + 1, match.getLineNumber(), where);
                }
            }
        }
    }

    /**
     * Accepted limitation (2026-10-02), pinned here so a change to it is noticed: AT the LF of a CRLF
     * that ends a line longer than half a page, the page starts between the CR and the LF, one byte
     * off. Neither viewer asks for such a position: a search match's offset is the start of a match,
     * never a line terminator.
     */
    @Test
    void atTheLfOfACrlfEndingALongLineStartsThePageBetweenTheCrAndTheLf() throws Exception {
        LogFixture fx = fixture();
        byte[] content = utf8("x".repeat(CAP - 3) + "\r\ntail\n");
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");
        long lf = CAP - 2;
        assertEquals('\r', content[(int) lf - 1]);
        assertEquals('\n', content[(int) lf]);

        LogPage page = fx.service.readPage(id, LogPageAnchor.AT, lf);
        assertEquals(lf, page.getStartOffset(), "the page starts at the LF, after the CR");
        assertTrue(lf < page.getEndOffset(), "page holds the offset");
    }

    @Test
    void aLastLineWithNoTerminatorExactlyAsLongAsAPageOrOneByteMore() throws Exception {
        for (int total : new int[] {CAP - 1, CAP, CAP + 1}) {
            LogFixture fx = fixture();
            byte[] content = utf8("head\n" + "x".repeat(total));
            fx.writeActive(content, 1_000_000L);
            String id = fx.idOf("mirth.log");
            List<LogPage> forward = LogFixture.walkForward(fx.service, id);
            List<LogPage> back = LogFixture.walkBackward(fx.service, id);
            LogPagerTest.assertCovers(forward, content, StandardCharsets.UTF_8);
            LogPagerTest.assertCovers(back, content, StandardCharsets.UTF_8);
            assertTrue(forward.get(forward.size() - 1).isAtEnd(), "last line of " + total);
            assertTrue(back.get(back.size() - 1).isAtEnd(), "last line of " + total);
        }
    }

    // ========================
    // 7. Long lines at the search piece boundary
    // ========================

    @Test
    void aLongLineEndingAtASearchPieceBoundaryNumbersAndOffsetsTheNextLinesCorrectly() throws Exception {
        int[] lengths = {SEGMENT - 1, SEGMENT, SEGMENT + 1, 2 * SEGMENT - 1, 2 * SEGMENT, 2 * SEGMENT + 1};
        for (int n : lengths) {
            for (String ending : new String[] {"\r", "\r\n"}) {
                String where = "line of " + n + " bytes (SEGMENT" + (n - SEGMENT >= 0 ? "+" : "") + (n - SEGMENT)
                        + ") ended by " + (ending.length() == 1 ? "CR" : "CRLF");
                LogFixture fx = fixture();
                // The long line's last byte is Z; the two lines after it start with NEEDLE.
                byte[] content = utf8("x".repeat(n - 1) + "Z" + ending + "NEEDLE one\nNEEDLE two\r\n");
                fx.writeActive(content, 1_000_000L);
                String id = fx.idOf("mirth.log");

                LogSearchResult result = fx.service.search("Z|NEEDLE", true, true, id, null, null);
                assertEquals(3, result.getMatches().size(), where);
                boolean split = n > SEGMENT;
                assertEquals(split ? 1 : 0, result.getSplitLineCount(), where);
                assertEquals(!split, result.isComplete(), where);

                LogSearchMatch z = result.getMatches().get(0);
                long pieceStart = ((long) (n - 1) / SEGMENT) * SEGMENT;
                assertEquals(1L, z.getLineNumber(), where);
                assertEquals(pieceStart, z.getLineOffset(), where + ": the piece holding the Z");
                assertEquals(n - 1, z.getMatchOffset(), where);
                assertTrue(z.isTruncated(), where + ": the line is far longer than the text window returned");

                long next = n + ending.length();
                LogSearchMatch one = result.getMatches().get(1);
                LogSearchMatch two = result.getMatches().get(2);
                assertEquals(next, one.getLineOffset(), where);
                assertEquals(2L, one.getLineNumber(), where);
                assertEquals("NEEDLE one", one.getLineText(), where);
                assertEquals(next + "NEEDLE one\n".length(), two.getLineOffset(), where);
                assertEquals(3L, two.getLineNumber(), where);
                assertEquals("NEEDLE two", two.getLineText(), where);
                for (LogSearchMatch match : result.getMatches()) {
                    assertEquals(LogFixture.lineEndsBefore(content, match.getLineOffset()) + 1, match.getLineNumber(), where);
                }

                // The pager numbers the same lines the same way.
                LogPage page = fx.service.readPage(id, LogPageAnchor.AT, one.getMatchOffset());
                assertTrue(page.getText().contains("NEEDLE one"), where);
                LogPagerTest.assertCovers(List.of(page), content, StandardCharsets.UTF_8, page.getStartOffset());
                LogPagerTest.assertCovers(LogFixture.walkForward(fx.service, id), content, StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    void aLongLineEndedByALoneCrAtTheVeryEndOfTheFileIsSearchedAndCounted() throws Exception {
        for (int n : new int[] {SEGMENT, SEGMENT + 1}) {
            LogFixture fx = fixture();
            byte[] content = utf8("x".repeat(n - 1) + "Z\r");
            fx.writeActive(content, 1_000_000L);
            LogSearchResult result = fx.service.search("Z", false, true, null, null, null);
            assertEquals(1, result.getMatches().size(), "n=" + n);
            assertEquals(1L, result.getMatches().get(0).getLineNumber(), "n=" + n);
            assertEquals(n - 1, result.getMatches().get(0).getMatchOffset(), "n=" + n);
        }
    }

    // ========================
    // 12. Positions
    // ========================

    /** Runs {@code check} for the plain active file and for a zip holding the same bytes. */
    private interface PositionCheck {
        void run(LogFixture fx, String id, byte[] content, boolean archive) throws Exception;
    }

    private void forPlainAndZip(byte[] content, PositionCheck check) throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(content, 2_000_000L);
        fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);
        check.run(fx, fx.idOf("mirth.log"), content, false);
        check.run(fx, fx.idOf("mirth.log.1.zip"), content, true);
    }

    @Test
    void atPositionsAtTheEdgesOfAFile() throws Exception {
        forPlainAndZip(utf8(LogFixture.lines(3000, "INFO")), (fx, id, content, archive) -> {
            String where = archive ? "zip" : "plain";
            long length = content.length;
            assertTrue(length > CAP, "more than one page");

            LogPage zero = fx.service.readPage(id, LogPageAnchor.AT, 0L);
            assertEquals(0L, zero.getStartOffset(), where);
            assertEquals(0, zero.getTargetIndex(), where);
            assertEquals(1L, zero.getFirstLineNumber(), where);
            assertFalse(zero.isStartsMidLine(), where);
            LogPagerTest.assertCovers(List.of(zero), content, StandardCharsets.UTF_8, 0);

            LogPage end = fx.service.readPage(id, LogPageAnchor.AT, length);
            assertEquals(length, end.getEndOffset(), where);
            assertEquals(end.getText().length(), end.getTargetIndex(), where + ": the end of the file is the end of the text");
            assertTrue(end.isAtEnd(), where);
            assertTrue(end.getStartOffset() < length, where + ": context above the end");
            LogPagerTest.assertCovers(List.of(end), content, StandardCharsets.UTF_8, end.getStartOffset());

            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, length + 1)), where);
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, length + CAP)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, -1L)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST,
                    kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, Long.MIN_VALUE)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, null)), where);
            assertEquals(archive ? LogViewerException.Kind.TOO_LARGE : LogViewerException.Kind.STALE,
                    kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, Long.MAX_VALUE)), where);
        });
    }

    @Test
    void beforePositionsAtTheEdgesOfAFile() throws Exception {
        forPlainAndZip(utf8(LogFixture.lines(3000, "INFO")), (fx, id, content, archive) -> {
            String where = archive ? "zip" : "plain";
            long length = content.length;

            // Nothing precedes offset 0: an empty page there, not an error.
            LogPage zero = fx.service.readPage(id, LogPageAnchor.BEFORE, 0L);
            assertEquals("", zero.getText(), where);
            assertEquals(0L, zero.getStartOffset(), where);
            assertEquals(0L, zero.getEndOffset(), where);
            assertFalse(zero.isAtEnd(), where);

            LogPage end = fx.service.readPage(id, LogPageAnchor.BEFORE, length);
            assertEquals(length, end.getEndOffset(), where);
            assertTrue(end.isAtEnd(), where);
            LogPagerTest.assertCovers(List.of(end), content, StandardCharsets.UTF_8, end.getStartOffset());
            assertEquals(fx.service.readPage(id, LogPageAnchor.TAIL, null).getText(), end.getText(), where + ": BEFORE the end is the tail");

            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.BEFORE, length + 1)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.BEFORE, -1L)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.BEFORE, null)), where);
            assertEquals(archive ? LogViewerException.Kind.TOO_LARGE : LogViewerException.Kind.STALE,
                    kindOf(() -> fx.service.readPage(id, LogPageAnchor.BEFORE, Long.MAX_VALUE)), where);
        });
    }

    @Test
    void afterPositionsAtTheEdgesOfAFile() throws Exception {
        forPlainAndZip(utf8(LogFixture.lines(3000, "INFO")), (fx, id, content, archive) -> {
            String where = archive ? "zip" : "plain";
            long length = content.length;

            LogPage zero = fx.service.readPage(id, LogPageAnchor.AFTER, 0L);
            LogPage head = fx.service.readPage(id, LogPageAnchor.HEAD, null);
            assertEquals(head.getText(), zero.getText(), where + ": AFTER 0 is HEAD");
            assertEquals(0L, zero.getStartOffset(), where);
            assertEquals(1L, zero.getFirstLineNumber(), where);

            // After the last byte: an empty page that says it is at the end (a poller's "nothing new").
            LogPage end = fx.service.readPage(id, LogPageAnchor.AFTER, length);
            assertEquals("", end.getText(), where);
            assertEquals(length, end.getStartOffset(), where);
            assertEquals(length, end.getEndOffset(), where);
            assertTrue(end.isAtEnd(), where);
            assertEquals(LogFixture.lineEndsBefore(content, length) + 1, end.getFirstLineNumber(), where);

            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AFTER, length + 1)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AFTER, -1L)), where);
            assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AFTER, null)), where);
            assertEquals(archive ? LogViewerException.Kind.TOO_LARGE : LogViewerException.Kind.STALE,
                    kindOf(() -> fx.service.readPage(id, LogPageAnchor.AFTER, Long.MAX_VALUE)), where);
        });
    }

    @Test
    void tailAndHeadIgnoreAnOffsetAndANullAnchorMeansTail() throws Exception {
        forPlainAndZip(utf8(LogFixture.lines(3000, "INFO")), (fx, id, content, archive) -> {
            String where = archive ? "zip" : "plain";
            String tail = fx.service.readPage(id, LogPageAnchor.TAIL, null).getText();
            String head = fx.service.readPage(id, LogPageAnchor.HEAD, null).getText();
            for (Long junk : new Long[] {-5L, 0L, 12345L, Long.MAX_VALUE}) {
                assertEquals(tail, fx.service.readPage(id, LogPageAnchor.TAIL, junk).getText(), where + " TAIL " + junk);
                assertEquals(head, fx.service.readPage(id, LogPageAnchor.HEAD, junk).getText(), where + " HEAD " + junk);
            }
            assertEquals(tail, fx.service.readPage(id, null, null).getText(), where);
            assertEquals(tail, fx.service.readPage(id, null, 7L).getText(), where);
        });
    }

    @Test
    void anArchiveOffsetPastItsEndButInsideTheScanLimitIsStaleAndOneBeyondTheLimitIsTooLarge() throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(utf8("INFO active\n"), 2_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8(LogFixture.lines(50, "INFO")), 1_000_000L);
        String id = fx.idOf("mirth.log.1.zip");
        long limit = LogPager.ARCHIVE_SCAN_LIMIT;
        for (LogPageAnchor anchor : new LogPageAnchor[] {LogPageAnchor.AT, LogPageAnchor.BEFORE, LogPageAnchor.AFTER}) {
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, anchor, limit)), anchor + " at the limit");
            assertEquals(LogViewerException.Kind.TOO_LARGE, kindOf(() -> fx.service.readPage(id, anchor, limit + 1)),
                    anchor + " past the limit");
        }
        // The limit is on the uncompressed content; the viewers add the download only for users who may.
        assertEquals("This page of mirth.log.1.zip is more than 1 GiB into the archive's uncompressed content, too far"
                + " to page to. Searching the archive still works.", assertThrows(LogViewerException.class,
                        () -> fx.service.readPage(id, LogPageAnchor.AT, limit + 1)).getMessage());
    }

    @Test
    void everyAnchorWorksOnAnEmptyActiveFileAtOffsetZeroAndRefusesOffsetOne() throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(new byte[0], 1_000_000L);
        String id = fx.idOf("mirth.log");
        for (LogPageAnchor anchor : LogPageAnchor.values()) {
            LogPage page = fx.service.readPage(id, anchor, 0L);
            assertEquals("", page.getText(), anchor.toString());
            assertEquals(0L, page.getStartOffset(), anchor.toString());
            assertEquals(0L, page.getEndOffset(), anchor.toString());
            assertTrue(page.isAtEnd(), anchor.toString());
        }
        for (LogPageAnchor anchor : new LogPageAnchor[] {LogPageAnchor.AT, LogPageAnchor.BEFORE, LogPageAnchor.AFTER}) {
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, anchor, 1L)), anchor.toString());
        }
    }

    // ========================
    // 12. AT inside a line longer than half a page
    // ========================

    /** "abc" plus a 3-byte character, repeated: every fourth char is multi-byte and offsets are easy to place. */
    private static String mixedRun(int bytes) {
        StringBuilder sb = new StringBuilder();
        int size = 0;
        while (size + 6 <= bytes) {
            sb.append("abc").append((char) 0x20AC);
            size += 6;
        }
        return sb.toString();
    }

    /** Byte offsets, from the start of the run, of its characters. */
    private static List<Integer> characterOffsets(String run) {
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        for (int i = 0; i < run.length(); i++) {
            offsets.add(at);
            at += String.valueOf(run.charAt(i)).getBytes(StandardCharsets.UTF_8).length;
        }
        return offsets;
    }

    private static int boundaryAtOrAfter(List<Integer> offsets, int target) {
        for (int offset : offsets) {
            if (offset >= target) {
                return offset;
            }
        }
        return offsets.get(offsets.size() - 1);
    }

    @Test
    void atAnOffsetInsideALineLongerThanHalfAPageStillShowsTheOffset() throws Exception {
        for (int longBytes : new int[] {CAP / 2 + 5000, CAP - 100, CAP + 5000, 3 * CAP}) {
            String run = mixedRun(longBytes);
            String prefix = LogFixture.lines(40, "pre");
            byte[] content = utf8(prefix + run + "\n" + LogFixture.lines(40, "post"));
            LogFixture fx = fixture();
            fx.writeActive(content, 1_000_000L);
            fx.writeArchive(1, "mirth.log.1", content, 500_000L);
            long lineStart = utf8(prefix).length;
            List<Integer> offsets = characterOffsets(run);
            int runBytes = utf8(run).length;
            long lineEnd = lineStart + runBytes;

            List<Long> targets = new ArrayList<>();
            targets.add(lineStart);
            targets.add(lineStart + offsets.get(1));
            for (int bytes : new int[] {1000, CAP / 4, CAP / 2 - 1, CAP / 2, CAP / 2 + 1, CAP - 1, CAP, CAP + 1, runBytes / 2,
                    runBytes - CAP / 2, runBytes - 7}) {
                if (bytes > 0 && bytes < runBytes) {
                    targets.add(lineStart + boundaryAtOrAfter(offsets, bytes));
                }
            }
            targets.add(lineEnd); // the line's own terminator
            targets.add(lineEnd + 1); // the first byte of the next line

            for (String name : new String[] {"mirth.log", "mirth.log.1.zip"}) {
                String id = fx.idOf(name);
                for (long target : targets) {
                    String where = name + ", long line " + longBytes + " bytes, AT " + target + " (" + (target - lineStart) + " into the line)";
                    LogPage page = fx.service.readPage(id, LogPageAnchor.AT, target);
                    assertNotNull(page.getText(), where);
                    assertTrue(page.getStartOffset() <= target && target < page.getEndOffset(),
                            where + ": page " + page.getStartOffset() + ".." + page.getEndOffset());
                    assertTrue(page.getEndOffset() - page.getStartOffset() <= CAP, where);
                    LogPagerTest.assertCovers(List.of(page), content, StandardCharsets.UTF_8, page.getStartOffset());
                    // The targets are character starts: the char there is the one the target's bytes begin.
                    String fromTarget = new String(content, (int) target, (int) Math.min(4, content.length - target),
                            StandardCharsets.UTF_8);
                    assertEquals(LogText.sanitize(fromTarget).codePointAt(0),
                            page.getText().codePointAt(page.getTargetIndex()), where + ": target index");
                }
            }
        }
    }

    /**
     * Accepted limitation (2026-10-02), pinned here so a change to it is noticed: AT an offset inside a
     * multi-byte character, deep in a long line, the page starts at the next character, up to 3 bytes
     * after the offset. Neither viewer asks for such a position: a search match's offset is the start
     * of a match, always on a character boundary.
     */
    @Test
    void atAnOffsetInTheMiddleOfACharacterInALongLineStartsThePageAtTheNextCharacter() throws Exception {
        String run = mixedRun(3 * CAP);
        String prefix = LogFixture.lines(40, "pre");
        byte[] content = utf8(prefix + run + "\n");
        LogFixture fx = fixture();
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");
        long euroStart = utf8(prefix).length + 3 + 6 * (CAP / 6); // the third byte-position of a mid-run character
        // The 3-byte character starts at euroStart; ask for its second byte.
        assertEquals((byte) 0xE2, content[(int) euroStart], "fixture: a character starts here");
        long target = euroStart + 1;

        LogPage page = fx.service.readPage(id, LogPageAnchor.AT, target);
        assertEquals(euroStart + 3, page.getStartOffset(),
                "AT " + target + " (inside the character at " + euroStart + ") got page " + page.getStartOffset() + ".."
                        + page.getEndOffset());
    }
}
