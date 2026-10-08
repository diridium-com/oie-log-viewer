// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class LogCountTest {

    @TempDir
    Path dir;

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code count} lines, every {@code every}th holding the word "hit" twice. */
    private static String lines(int count, int every) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line ").append(i).append(i % every == 0 ? " hit and hit again" : " nothing").append('\n');
        }
        return sb.toString();
    }

    @Test
    void countsMatchingLinesPerFileNewestFirstWithNoMatchCap() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        int many = LogSearcher.MAX_MATCHES * 3;
        fx.writeActive(utf8(lines(many, 1)), 4_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8(lines(300, 3)), 3_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8(lines(50, 1000)), 2_000_000L);

        LogSearchResult result = fx.service.count("HIT", false, false, null, null, null);
        assertTrue(result.isComplete());
        assertNull(result.getStopReason());
        assertTrue(result.getMatches().isEmpty(), "a count returns no matches");
        assertEquals(List.of(fx.idOf("mirth.log"), fx.idOf("mirth.log.2.zip"), fx.idOf("mirth.log.1.zip")),
                result.getFileCounts().stream().map(LogFileMatchCount::getFileId).toList());
        // Two matches on a line count once; a file with none is listed with 0.
        assertEquals(List.of((long) many, 100L, 0L),
                result.getFileCounts().stream().map(LogFileMatchCount::getMatchingLines).toList());
        assertTrue(result.getFileCounts().stream().allMatch(LogFileMatchCount::isComplete));
        assertEquals(3, result.getFilesSearched());

        // One file only.
        LogSearchResult one = fx.service.count("hit", false, true, fx.idOf("mirth.log.2.zip"), null, null);
        assertEquals(1, one.getFileCounts().size());
        assertEquals(100L, one.getFileCounts().get(0).getMatchingLines());

        // A search of the same query is still capped and carries no counts.
        LogSearchResult search = fx.service.search("hit", false, true, null, null, null);
        assertEquals(LogSearchStopReason.MAX_MATCHES, search.getStopReason());
        assertNull(search.getFileCounts());
    }

    @Test
    void aLongLineSearchedInPiecesCountsOnce() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String longLine = "NEEDLE" + "x".repeat(LogSearcher.SEGMENT_BYTES) + "NEEDLE";
        fx.writeActive(utf8("NEEDLE\n" + longLine + "\nnone\nNEEDLE\n"), 1_000_000L);

        LogSearchResult count = fx.service.count("NEEDLE", false, true, null, null, null);
        assertEquals(3L, count.getFileCounts().get(0).getMatchingLines());
        assertEquals(1, count.getSplitLineCount());
        // Control: the search finds the long line's two pieces as two matches.
        assertEquals(4, fx.service.search("NEEDLE", false, true, null, null, null).getMatches().size());
    }

    @Test
    void aCountStoppedByTheTimeLimitResumesToTheSameTotals() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        // Every line of the active file matches, so wherever a stop falls in
        // it, the line it stops on is one that could be counted twice.
        fx.writeActive(utf8(lines(40_000, 1)), 3_000_000L);
        fx.writeArchive(1, "mirth.log.5", utf8(lines(30_000, 5)), 1_000_000L);
        LogSearchResult whole = fx.service.count("hit", false, true, null, null, null);
        assertTrue(whole.isComplete());

        // Each clock read moves time on 1 ms: the 15 s limit passes after
        // 15,000 reads, several times over these files.
        fx.tick.set(TimeUnit.MILLISECONDS.toNanos(1));
        Map<String, Long> totals = new LinkedHashMap<>();
        List<Boolean> lastComplete = new ArrayList<>();
        int requests = 0;
        String resumeFileId = null;
        Long resumeOffset = null;
        do {
            LogSearchResult part = fx.service.count("hit", false, true, null, resumeFileId, resumeOffset);
            requests++;
            for (LogFileMatchCount c : part.getFileCounts()) {
                totals.merge(c.getFileId(), c.getMatchingLines(), Long::sum);
            }
            LogFileMatchCount last = part.getFileCounts().get(part.getFileCounts().size() - 1);
            if (part.getStopReason() != null) {
                assertEquals(LogSearchStopReason.DEADLINE, part.getStopReason());
                assertFalse(last.isComplete(), "the file the count stopped in is partial");
                assertEquals(last.getFileId(), part.getResumeFileId());
            }
            lastComplete.add(last.isComplete());
            resumeFileId = part.getResumeFileId();
            resumeOffset = part.getResumeOffset();
        } while (resumeFileId != null);

        assertTrue(requests > 2, "the time limit must have stopped the count more than once: " + requests);
        assertTrue(lastComplete.get(lastComplete.size() - 1));
        Map<String, Long> expected = new LinkedHashMap<>();
        for (LogFileMatchCount c : whole.getFileCounts()) {
            expected.put(c.getFileId(), c.getMatchingLines());
        }
        assertEquals(Map.of(fx.idOf("mirth.log"), 40_000L, fx.idOf("mirth.log.1.zip"), 6_000L), expected);
        assertEquals(expected, totals);
    }

    @Test
    void countResultsSurviveTheAdministratorsXStream() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8(lines(10, 2)), 1_000_000L);
        LogSearchResult result = fx.service.count("hit", false, true, null, null, null);

        XStream xstream = new XStream(new Xpp3Driver());
        xstream.allowTypesByWildcard(new String[] {"com.diridium.logviewer.**"});
        LogSearchResult back = (LogSearchResult) xstream.fromXML(xstream.toXML(result));
        assertEquals(1, back.getFileCounts().size());
        assertEquals(5L, back.getFileCounts().get(0).getMatchingLines());
        assertTrue(back.getFileCounts().get(0).isComplete());
        assertEquals(result.getFileCounts().get(0).getFileId(), back.getFileCounts().get(0).getFileId());
    }
}
