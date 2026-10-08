// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.junit.jupiter.api.Test;

/**
 * Find on this page with a regular expression runs on the event thread, so a pattern that
 * backtracks must stop at the time limit, and one too deep for the matcher's stack must not escape
 * as an error. The clock here is a counter, so the limit is reached by reading, not by waiting.
 */
class LogPageFindTest {

    /** A clock that never moves: nothing here may be stopped by the time limit. */
    private static final LongSupplier STILL = () -> 0L;

    private static LogPageFind.Result find(String regex, String text, int from, boolean forward) {
        return LogPageFind.find(LogPageFind.compile(regex, false), text, from, forward, STILL);
    }

    @Test
    void forwardFindsTheNextMatchAfterTheSelection() {
        String text = "INFO a\nERROR b\nERROR c";
        LogPageFind.Result r = find("ERROR \\w", text, 0, true);
        assertEquals(LogPageFind.Outcome.FOUND, r.outcome);
        assertArrayEquals(new int[] {7, 14}, r.ranges.get(0));
        // from the end of that match, the next one
        assertArrayEquals(new int[] {15, 22}, find("ERROR \\w", text, 14, true).ranges.get(0));
    }

    @Test
    void forwardWrapsToTheStartOnce() {
        String text = "ERROR a\nINFO b";
        LogPageFind.Result r = find("error", text, 6, true);
        assertEquals(LogPageFind.Outcome.WRAPPED, r.outcome);
        assertArrayEquals(new int[] {0, 5}, r.ranges.get(0));
    }

    @Test
    void backwardFindsTheLastMatchBeforeTheSelectionAndWrapsToTheEnd() {
        String text = "x1 x2 x3";
        assertArrayEquals(new int[] {3, 5}, find("x\\d", text, 6, false).ranges.get(0));
        LogPageFind.Result wrapped = find("x\\d", text, 0, false);
        assertEquals(LogPageFind.Outcome.WRAPPED, wrapped.outcome);
        assertArrayEquals(new int[] {6, 8}, wrapped.ranges.get(0));
    }

    @Test
    void caretAndDollarMeanEachLineAndTheCaretIsNotALineStart() {
        String text = "ab\nab";
        // from inside the first line, ^ does not match at the caret; the next line start does
        assertArrayEquals(new int[] {3, 5}, find("^ab", text, 1, true).ranges.get(0));
        assertEquals(LogPageFind.Outcome.FOUND, find("b$", text, 0, true).outcome);
    }

    @Test
    void emptyMatchesAreSkippedAndNothingIsNotFound() {
        assertArrayEquals(new int[] {3, 4}, find("x*", "ab x", 0, true).ranges.get(0));
        assertEquals(LogPageFind.Outcome.NOT_FOUND, find("zzz", "ab x", 0, true).outcome);
        assertEquals(LogPageFind.Outcome.NOT_FOUND, find("q*", "ab", 0, true).outcome);
    }

    @Test
    void matchCaseIsAnOption() {
        assertEquals(LogPageFind.Outcome.NOT_FOUND,
                LogPageFind.find(LogPageFind.compile("error", true), "ERROR", 0, true, STILL).outcome);
        assertEquals(LogPageFind.Outcome.FOUND,
                LogPageFind.find(LogPageFind.compile("\u00e9t\u00e9", false), "\u00c9T\u00c9", 0, true, STILL).outcome);
    }

    @Test
    void anInvalidPatternIsRefusedWhenCompiled() {
        assertThrows(PatternSyntaxException.class, () -> LogPageFind.compile("a(", false));
    }

    @Test
    void markAllListsEveryNonEmptyMatch() {
        LogPageFind.Result r = LogPageFind.all(LogPageFind.compile("b+|x*", false), "abbcb", STILL);
        assertEquals(LogPageFind.Outcome.FOUND, r.outcome);
        assertEquals(2, r.ranges.size());
        assertArrayEquals(new int[] {1, 3}, r.ranges.get(0));
        assertArrayEquals(new int[] {4, 5}, r.ranges.get(1));
        assertEquals(LogPageFind.Outcome.NOT_FOUND,
                LogPageFind.all(LogPageFind.compile("z", false), "abc", STILL).outcome);
    }

    @Test
    void aBacktrackingPatternStopsAtTheTimeLimitInsideTheMatch() {
        // Every clock reading moves 10 ms on, so the 2-second limit passes after 200 readings, one
        // every 1,024 characters the matcher reads: only a check inside the match can get there.
        AtomicLong now = new AtomicLong();
        LongSupplier clock = () -> now.addAndGet(10_000_000L);
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            line.append('a');
        }
        Pattern backtracking = LogPageFind.compile("(.*a){10}x", false);
        assertEquals(LogPageFind.Outcome.TIME_LIMIT,
                LogPageFind.find(backtracking, line.toString(), 0, true, clock).outcome);
        assertEquals(LogPageFind.Outcome.TIME_LIMIT, LogPageFind.all(backtracking, line.toString(), clock).outcome);
        assertTrue(now.get() < 1_000L * 10_000_000L, "stopped soon after the limit, not at the end of the search");
    }

    @Test
    void aPatternTooDeepForTheStackIsCaughtNotThrown() throws Exception {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            line.append(i % 2 == 0 ? 'a' : 'b');
        }
        AtomicReference<LogPageFind.Outcome> found = new AtomicReference<>();
        AtomicReference<LogPageFind.Outcome> marked = new AtomicReference<>();
        // A small stack, so the overflow comes at the same depth on every machine.
        Thread small = new Thread(null, () -> {
            Pattern deep = LogPageFind.compile("(a|b)*c", false);
            found.set(LogPageFind.find(deep, line.toString(), 0, true, STILL).outcome);
            marked.set(LogPageFind.all(deep, line.toString(), STILL).outcome);
        }, "small-stack", 256 * 1024);
        small.start();
        small.join();
        assertEquals(LogPageFind.Outcome.TOO_COMPLEX, found.get());
        assertEquals(LogPageFind.Outcome.TOO_COMPLEX, marked.get());
    }

    @Test
    void eachOutcomeHasItsOwnWords() {
        assertEquals("", LogPageFind.status(LogPageFind.Outcome.FOUND, true));
        assertEquals("Wrapped to the start of the page.", LogPageFind.status(LogPageFind.Outcome.WRAPPED, true));
        assertEquals("Wrapped to the end of the page.", LogPageFind.status(LogPageFind.Outcome.WRAPPED, false));
        assertEquals("Not found on this page.", LogPageFind.status(LogPageFind.Outcome.NOT_FOUND, true));
        assertEquals("The expression took too long on this page.",
                LogPageFind.status(LogPageFind.Outcome.TIME_LIMIT, true));
        assertEquals("The expression is too complex for this page.",
                LogPageFind.status(LogPageFind.Outcome.TOO_COMPLEX, false));
        assertEquals("Not found on this page.", LogPageFind.markedStatus(0));
        assertEquals("1 match on this page.", LogPageFind.markedStatus(1));
        assertEquals("12 matches on this page.", LogPageFind.markedStatus(12));
    }
}
