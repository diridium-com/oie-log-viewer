// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Search resume points and the search's hard limits. */
class LogSearchEdgeTest {

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
    // 13. Resume
    // ========================

    @Test
    void resumingInsideASplitLongLineContinuesAtThePieceAndKeepsTheLineNumbers() throws Exception {
        // The 1,001st match is in the second piece of a line longer than a search piece, so the
        // cap stops the search inside that line. Once with 1-byte characters, once with 3-byte ones.
        String euro = String.valueOf((char) 0x20AC);
        for (String filler : new String[] {"x".repeat(SEGMENT + 10), euro.repeat(400_000)}) {
            String where = filler.startsWith("x") ? "ASCII" : "3-byte characters";
            LogFixture fx = fixture();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= LogSearcher.MAX_MATCHES; i++) {
                head.append("hit ").append(i).append('\n');
            }
            byte[] content = utf8(head + filler + "hit" + "\nhit tail\n");
            long lineStart = utf8(head.toString()).length;
            fx.writeActive(content, 1_000_000L);
            String id = fx.idOf("mirth.log");

            LogSearchResult first = fx.service.search("hit", false, true, null, null, null);
            assertEquals(LogSearcher.MAX_MATCHES, first.getMatches().size(), where);
            assertEquals(LogSearchStopReason.MAX_MATCHES, first.getStopReason(), where);
            assertEquals(id, first.getResumeFileId(), where);
            long pieceBytes = filler.startsWith("x") ? SEGMENT : SEGMENT - SEGMENT % 3;
            assertEquals(lineStart + pieceBytes, first.getResumeOffset(), where + ": the start of the second piece");
            assertEquals(1, first.getSplitLineCount(), where);

            LogSearchResult rest = fx.service.search("hit", false, true, null, first.getResumeFileId(),
                    first.getResumeOffset());
            assertEquals(2, rest.getMatches().size(), where);
            LogSearchMatch inLongLine = rest.getMatches().get(0);
            LogSearchMatch tail = rest.getMatches().get(1);
            assertEquals(LogSearcher.MAX_MATCHES + 1L, inLongLine.getLineNumber(), where);
            assertEquals(first.getResumeOffset(), inLongLine.getLineOffset(), where);
            int at = (int) inLongLine.getMatchOffset();
            assertArrayEquals(utf8("hit"), Arrays.copyOfRange(content, at, at + 3), where);
            assertEquals(LogSearcher.MAX_MATCHES + 2L, tail.getLineNumber(), where);
            assertEquals("hit tail", tail.getLineText(), where);
            assertEquals(LogFixture.lineEndsBefore(content, tail.getLineOffset()) + 1, tail.getLineNumber(), where);
            assertEquals(LogFixture.lineEndsBefore(content, inLongLine.getLineOffset()) + 1, inLongLine.getLineNumber(), where);
            assertNull(rest.getStopReason(), where);
            // The resumed request starts inside the long line and still treats it as one.
            assertTrue(inLongLine.isTruncated(), where + ": a piece of a long line");
            assertFalse(tail.isTruncated(), where);
            assertEquals(1, rest.getSplitLineCount(), where);
            assertTrue(rest.getWarnings().stream().anyMatch(w -> w.contains("pieces")), where + " " + rest.getWarnings());

            LogSearchResult restCount = fx.service.count("hit", false, true, null, first.getResumeFileId(),
                    first.getResumeOffset());
            assertEquals(2, restCount.getFileCounts().get(0).getMatchingLines(), where);
            assertEquals(1, restCount.getSplitLineCount(), where);
            assertTrue(restCount.getWarnings().stream().anyMatch(w -> w.contains("pieces")),
                    where + " " + restCount.getWarnings());
        }
    }

    @Test
    void resumingAtOrPastTheEndOfAFileContinuesWithTheNextFileWithoutError() throws Exception {
        LogFixture fx = fixture();
        byte[] active = utf8("ERROR a1\nERROR a2\n");
        fx.writeActive(active, 3_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR one\n"), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8("ERROR two\n"), 2_000_000L);
        String activeId = fx.idOf("mirth.log");
        String twoId = fx.idOf("mirth.log.2.zip");

        // Order is active, archive 2, archive 1.
        for (long offset : new long[] {active.length, active.length + 1, active.length + 1_000_000}) {
            LogSearchResult result = fx.service.search("ERROR", false, true, null, activeId, offset);
            assertEquals(List.of("ERROR two", "ERROR one"),
                    result.getMatches().stream().map(LogSearchMatch::getLineText).toList(), "offset " + offset);
            assertTrue(result.isComplete(), "offset " + offset);
            assertEquals(3, result.getFilesSearched(), "offset " + offset);
        }
        // Resuming in an archive, at and past its (decompressed) end.
        for (long offset : new long[] {"ERROR two\n".length(), "ERROR two\n".length() + 7}) {
            LogSearchResult result = fx.service.search("ERROR", false, true, null, twoId, offset);
            assertEquals(List.of("ERROR one"), result.getMatches().stream().map(LogSearchMatch::getLineText).toList(),
                    "archive offset " + offset);
            assertTrue(result.isComplete(), "archive offset " + offset);
        }
        // One file in scope: nothing left to find, and that is a complete answer.
        LogSearchResult only = fx.service.search("ERROR", false, true, activeId, activeId, (long) active.length);
        assertTrue(only.getMatches().isEmpty());
        assertTrue(only.isComplete());
        assertEquals(1, only.getFilesSearched());
        // Resume at 0 is a fresh search.
        assertEquals(4, fx.service.search("ERROR", false, true, null, activeId, 0L).getMatches().size());
    }

    @Test
    void aResumePointThatDoesNotFitTheRequestIsRefused() throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(utf8("ERROR a\n"), 3_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR one\n"), 1_000_000L);
        String activeId = fx.idOf("mirth.log");
        String oneId = fx.idOf("mirth.log.1.zip");

        assertEquals(LogViewerException.Kind.BAD_REQUEST,
                kindOf(() -> fx.service.search("ERROR", false, true, null, activeId, null)));
        assertEquals(LogViewerException.Kind.BAD_REQUEST,
                kindOf(() -> fx.service.search("ERROR", false, true, null, activeId, -1L)));
        assertEquals(LogViewerException.Kind.BAD_REQUEST,
                kindOf(() -> fx.service.search("ERROR", false, true, null, "not-an-id", 0L)));
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.search("ERROR", false, true, null, "fout/gone.log.9.zip@000000000000", 0L)));
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.search("ERROR", false, true, null, "fout/mirth.log@000000000000", 0L)),
                "right name, wrong fingerprint");
        // The resume file is outside the one file searched.
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.search("ERROR", false, true, oneId, activeId, 0L)));
        // An empty resume file id is no resume at all.
        assertEquals(2, fx.service.search("ERROR", false, true, null, "", null).getMatches().size());
    }

    @Test
    void theCapReachedExactlyInOneFileResumesAtTheFirstLineOfTheNextAndCompletesWhenNothingFollows() throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(utf8(LogFixture.lines(LogSearcher.MAX_MATCHES, "hit")), 3_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("quiet\nhit in the archive\n"), 1_000_000L);
        String oneId = fx.idOf("mirth.log.1.zip");

        LogSearchResult first = fx.service.search("hit", false, true, null, null, null);
        assertEquals(LogSearcher.MAX_MATCHES, first.getMatches().size());
        assertEquals(LogSearchStopReason.MAX_MATCHES, first.getStopReason());
        assertEquals(oneId, first.getResumeFileId());
        assertEquals("quiet\n".length(), first.getResumeOffset(), "the archive's matching line, not the active file's end");

        LogSearchResult rest = fx.service.search("hit", false, true, null, first.getResumeFileId(), first.getResumeOffset());
        assertEquals(1, rest.getMatches().size());
        assertEquals(2L, rest.getMatches().get(0).getLineNumber());
        assertTrue(rest.isComplete());

        // The same file set with nothing to find after the cap: complete, no resume point.
        LogFixture quiet = fixture();
        quiet.writeActive(utf8(LogFixture.lines(LogSearcher.MAX_MATCHES, "hit")), 3_000_000L);
        quiet.writeArchive(1, "mirth.log.1", utf8("quiet\n"), 1_000_000L);
        LogSearchResult done = quiet.service.search("hit", false, true, null, null, null);
        assertEquals(LogSearcher.MAX_MATCHES, done.getMatches().size());
        assertNull(done.getStopReason());
        assertNull(done.getResumeFileId());
        assertTrue(done.isComplete());
    }

    // ========================
    // 14. Limits
    // ========================

    @Test
    void aPatternThatOverflowsTheStackWhileCompilingIsABadRequest() throws Exception {
        // Pattern.compile recurses once per nesting level and turns its own stack overflow into a
        // PatternSyntaxException; a small thread stack makes the overflow certain.
        String nested = "(".repeat(450) + "a" + ")".repeat(450);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread small = new Thread(null, () -> {
            try {
                LogSearcher.compile(nested, true, true);
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "small-stack", 64 * 1024);
        small.start();
        small.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(outcome.get() instanceof LogViewerException, "refused, not thrown: " + outcome.get());
        LogViewerException e = (LogViewerException) outcome.get();
        assertEquals(LogViewerException.Kind.BAD_REQUEST, e.getKind());
        assertTrue(e.getMessage().contains("Stack overflow"), e.getMessage());
    }

    @Test
    void aRegularExpressionThatOverflowsTheMatcherStackGivesPatternTooComplexAndKeepsEarlierMatches() throws Exception {
        LogFixture fx = fixture();
        fx.writeActive(utf8("hit one\n" + "a".repeat(500_000) + "\nhit two\n"), 1_000_000L);

        // (?:a|b)* recurses once per repetition; the long line has half a million.
        LogSearchResult result = fx.service.search("(?:a|b)*c|hit", true, true, null, null, null);
        assertEquals(LogSearchStopReason.PATTERN_TOO_COMPLEX, result.getStopReason());
        assertEquals(1, result.getMatches().size());
        assertEquals("hit one", result.getMatches().get(0).getLineText());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("too complex") && w.contains("line 2")),
                result.getWarnings().toString());
        assertNull(result.getResumeFileId(), "resuming would hit the same line");
        assertFalse(result.isComplete());

        // The search slot was given back and the next search is not affected.
        LogSearchResult next = fx.service.search("hit two", false, true, null, null, null);
        assertEquals(1, next.getMatches().size());
        assertTrue(next.isComplete());
    }

    @Test
    void theSearcherHandsOutExactlyItsPermitsAndTakesThemBack() {
        LogSearcher searcher = new LogSearcher(() -> 0L);
        for (int i = 0; i < LogSearcher.MAX_CONCURRENT_SEARCHES; i++) {
            assertTrue(searcher.tryAcquire(), "permit " + i);
        }
        assertFalse(searcher.tryAcquire());
        assertFalse(searcher.tryAcquire());
        searcher.release();
        assertTrue(searcher.tryAcquire());
        assertFalse(searcher.tryAcquire());
        searcher.release();
        searcher.release();
        for (int i = 0; i < LogSearcher.MAX_CONCURRENT_SEARCHES; i++) {
            assertTrue(searcher.tryAcquire(), "permit again " + i);
        }
        assertFalse(searcher.tryAcquire());
    }

    @Test
    void theBusyMessagesStateTheCapsThatAreInForce() {
        assertEquals(4, LogViewerService.MAX_CONCURRENT_PAGE_READS);
        assertTrue(LogViewerService.PAGES_BUSY.startsWith("Four log pages"));
        assertEquals(2, LogSearcher.MAX_CONCURRENT_SEARCHES);
        assertTrue(LogViewerService.SEARCHES_BUSY.startsWith("Two log searches"));
        assertEquals(15, TimeUnit.NANOSECONDS.toSeconds(LogSearcher.DEADLINE_NANOS));
        assertTrue(LogViewerService.SEARCHES_BUSY.contains("up to 15 seconds"));
    }

    @Test
    void aPageReadPastTheCapIsBusyAndEverySlotComesBack() throws Exception {
        Path active = dir.resolve("mirth.log");
        Files.write(active, utf8("hit\n"));
        LogAppenderSource source = () -> List.of(new LogAppenderSource.Appender("fout", active.toString(), null,
                StandardCharsets.UTF_8));
        int cap = LogViewerService.MAX_CONCURRENT_PAGE_READS;
        AtomicReference<CountDownLatch[]> gate = new AtomicReference<>();
        // The highlighter reads the clock inside the page read's slot: hold it there until released.
        LongSupplier clock = () -> {
            CountDownLatch[] current = gate.get();
            if (current != null) {
                current[0].countDown();
                try {
                    if (!current[1].await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("page read was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return 0L;
        };
        LogViewerService service = new LogViewerService(source, clock);
        String id = service.listFiles().getFiles().get(0).getId();
        ExecutorService pool = Executors.newFixedThreadPool(cap);
        try {
            for (int round = 1; round <= 2; round++) {
                String where = "round " + round;
                CountDownLatch[] current = {new CountDownLatch(cap), new CountDownLatch(1)};
                gate.set(current);
                List<Future<LogPage>> held = new ArrayList<>();
                for (int i = 0; i < cap; i++) {
                    held.add(pool.submit(() -> service.readPage(id, LogPageAnchor.TAIL, null, "hit", false, true)));
                }
                assertTrue(current[0].await(20, TimeUnit.SECONDS), where + ": every slot should be taken");

                LogViewerException busy = assertThrows(LogViewerException.class,
                        () -> service.readPage(id, LogPageAnchor.TAIL, null));
                assertEquals(LogViewerException.Kind.BUSY, busy.getKind(), where);
                assertEquals(LogViewerService.PAGES_BUSY, busy.getMessage(), where);

                current[1].countDown();
                for (Future<LogPage> page : held) {
                    assertEquals("hit\n", page.get(20, TimeUnit.SECONDS).getText(), where);
                }
                gate.set(null);
                // Failing page reads give their slot back too.
                for (int i = 0; i < cap + 2; i++) {
                    assertEquals(LogViewerException.Kind.STALE, kindOf(() -> service.readPage(
                            "fout/mirth.log@000000000000", LogPageAnchor.TAIL, null)), where + " failing read " + i);
                }
            }
        } finally {
            CountDownLatch[] leftover = gate.get();
            if (leftover != null) {
                leftover[1].countDown();
            }
            pool.shutdownNow();
        }
    }

    /** Pauses every search at its first clock read, which is inside the permit, until released. */
    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(LogSearcher.MAX_CONCURRENT_SEARCHES);
        final CountDownLatch release = new CountDownLatch(1);
    }

    @Test
    void theThirdConcurrentSearchGetsBusyWhileTwoRunAndEverySlotComesBackAfterFailures() throws Exception {
        Path active = dir.resolve("mirth.log");
        Files.write(active, utf8("hit\n"));
        LogAppenderSource source = () -> List.of(new LogAppenderSource.Appender("fout", active.toString(), null,
                StandardCharsets.UTF_8));
        AtomicReference<Gate> gate = new AtomicReference<>();
        LongSupplier clock = () -> {
            Gate current = gate.get();
            if (current != null) {
                current.entered.countDown();
                try {
                    if (!current.release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("search was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return 0L;
        };
        LogViewerService service = new LogViewerService(source, clock);
        ExecutorService pool = Executors.newFixedThreadPool(LogSearcher.MAX_CONCURRENT_SEARCHES);
        try {
            // Two rounds, with searches that fail after taking a slot in between: if a failure
            // leaked its permit, the second round could not get both slots.
            for (int round = 1; round <= 2; round++) {
                String where = "round " + round;
                Gate current = new Gate();
                gate.set(current);
                Future<LogSearchResult> first = pool.submit(() -> service.search("hit", false, true, null, null, null));
                Future<LogSearchResult> second = pool.submit(() -> service.search("hit", false, true, null, null, null));
                assertTrue(current.entered.await(20, TimeUnit.SECONDS), where + ": two searches should be running");

                assertEquals(LogViewerException.Kind.BUSY,
                        kindOf(() -> service.search("hit", false, true, null, null, null)), where);
                assertEquals(LogViewerException.Kind.BUSY,
                        kindOf(() -> service.search("hit", false, true, "fout/mirth.log@000000000000", null, null)), where);
                assertEquals(LogViewerException.Kind.BUSY,
                        kindOf(() -> service.count("hit", false, true, null, null, null)), where + ": counts share the limit");

                current.release.countDown();
                assertEquals(1, first.get(20, TimeUnit.SECONDS).getMatches().size(), where);
                assertEquals(1, second.get(20, TimeUnit.SECONDS).getMatches().size(), where);
                gate.set(null);

                for (int i = 0; i < 5; i++) {
                    assertEquals(LogViewerException.Kind.STALE, kindOf(() -> service.search("hit", false, true,
                            "fout/mirth.log@000000000000", null, null)), where + " failing search " + i);
                    assertEquals(LogViewerException.Kind.BAD_REQUEST, kindOf(() -> service.search("hit", false, true,
                            null, "fout/mirth.log@000000000000", null)), where + " failing resume " + i);
                }
            }
            assertNotNull(service.search("hit", false, true, null, null, null));
        } finally {
            Gate leftover = gate.get();
            if (leftover != null) {
                leftover.release.countDown();
            }
            pool.shutdownNow();
        }
    }
}
