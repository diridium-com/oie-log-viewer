// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LogCountTallyTest {

    private static LogFileMatchCount entry(String id, long lines, boolean complete) {
        LogFileMatchCount e = new LogFileMatchCount(id);
        e.setMatchingLines(lines);
        e.setComplete(complete);
        return e;
    }

    private static LogSearchResult result(int filesInScope, LogFileMatchCount... entries) {
        LogSearchResult r = new LogSearchResult();
        List<LogFileMatchCount> list = new ArrayList<>();
        for (LogFileMatchCount e : entries) {
            list.add(e);
        }
        r.setFileCounts(list);
        r.setFilesInScope(filesInScope);
        return r;
    }

    @Test
    void aSingleResultIsTotalledAsItCame() {
        LogCountTally tally = new LogCountTally();
        tally.add(result(3, entry("a", 10, true), entry("b", 0, true), entry("c", 5, true)));
        assertEquals(15, tally.matchingLines());
        assertEquals(2, tally.filesWithMatches());
        assertEquals(3, tally.filesCounted());
        assertEquals(3, tally.filesCompleted());
        assertEquals(3, tally.filesInScope());
    }

    @Test
    void aResumedCountAddsToThePartialFileAndAppendsTheRest() {
        LogCountTally tally = new LogCountTally();
        // The time limit stopped inside file b, which had 100 matching lines so far.
        tally.add(result(4, entry("a", 145, true), entry("b", 100, false)));
        assertEquals(245, tally.matchingLines());
        assertEquals(1, tally.filesCompleted());
        assertEquals(2, tally.filesCounted());

        // The resumed count starts with b again, continuing it, then reaches c and d.
        tally.add(result(4, entry("b", 37, true), entry("c", 0, true), entry("d", 8, true)));

        List<LogFileMatchCount> entries = tally.entries();
        assertEquals(4, entries.size());
        assertEquals("a", entries.get(0).getFileId());
        assertEquals("b", entries.get(1).getFileId());
        assertEquals(137, entries.get(1).getMatchingLines());
        assertEquals(true, entries.get(1).isComplete());
        assertEquals("d", entries.get(3).getFileId());
        assertEquals(290, tally.matchingLines());
        assertEquals(3, tally.filesWithMatches());
        assertEquals(4, tally.filesCompleted());
    }

    @Test
    void aFileThatStaysPartialStaysPartial() {
        LogCountTally tally = new LogCountTally();
        tally.add(result(2, entry("a", 50, false)));
        tally.add(result(2, entry("a", 20, false)));
        assertEquals(70, tally.entries().get(0).getMatchingLines());
        assertEquals(false, tally.entries().get(0).isComplete());
        assertEquals(0, tally.filesCompleted());
    }

    @Test
    void theResultsOwnEntriesAreNeverChanged() {
        LogCountTally tally = new LogCountTally();
        LogFileMatchCount first = entry("a", 5, false);
        tally.add(result(1, first));
        tally.add(result(1, entry("a", 5, true)));
        assertEquals(5, first.getMatchingLines());
        assertEquals(10, tally.matchingLines());
    }

    @Test
    void aResultWithNoCountsChangesNothing() {
        LogCountTally tally = new LogCountTally();
        LogSearchResult empty = new LogSearchResult();
        empty.setFilesInScope(6);
        tally.add(empty);
        assertEquals(0, tally.matchingLines());
        assertEquals(0, tally.filesCounted());
        assertEquals(6, tally.filesInScope());
    }

    @Test
    void bytesAndWaitingTimeAreSummedOverEveryRequest() {
        LogCountTally tally = new LogCountTally();
        LogSearchResult first = result(2, entry("a", 1, false));
        first.setBytesSearched(4_000);
        tally.add(first);
        tally.addElapsed(15_000);
        LogSearchResult second = result(2, entry("a", 1, true), entry("b", 2, true));
        second.setBytesSearched(1_500);
        tally.add(second);
        tally.addElapsed(400);
        assertEquals(5_500, tally.bytesSearched());
        assertEquals(15_400, tally.elapsedMillis());
    }

    private static LogSearchResult resumeAt(String fileId, Long offset) {
        LogSearchResult r = new LogSearchResult();
        r.setResumeFileId(fileId);
        r.setResumeOffset(offset);
        return r;
    }

    @Test
    void aContinuationThatStoppedWhereItStartedMadeNoProgress() {
        assertTrue(LogCountTally.sameResumePoint("fout/mirth.log@a", 4096L, resumeAt("fout/mirth.log@a", 4096L)));
    }

    @Test
    void anyMoveOnOrAnEndIsProgress() {
        assertFalse(LogCountTally.sameResumePoint("fout/mirth.log@a", 4096L, resumeAt("fout/mirth.log@a", 8192L)));
        assertFalse(LogCountTally.sameResumePoint("fout/mirth.log@a", 4096L,
                resumeAt("fout/mirth.log.5.zip@b", 4096L)));
        assertFalse(LogCountTally.sameResumePoint("fout/mirth.log@a", 4096L, resumeAt(null, null)));
        // a first request has no resume point to compare with
        assertFalse(LogCountTally.sameResumePoint(null, null, resumeAt("fout/mirth.log@a", 0L)));
    }
}
