// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class LogSearcherTest {

    @TempDir
    Path dir;

    @Test
    void searchesNewestFileFirstIncludingArchives() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO ok\nERROR in active\n".getBytes(StandardCharsets.UTF_8), 3_000_000L);
        fx.writeArchive(1, "mirth.log.4", "ERROR in archive\nINFO ok\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);

        LogSearchResult result = fx.service.search("error", false, false, null, null, null);
        assertTrue(result.isComplete());
        assertEquals(2, result.getMatches().size());
        assertEquals("ERROR in active", result.getMatches().get(0).getLineText());
        assertEquals(2, result.getMatches().get(0).getLineNumber());
        assertEquals("ERROR in archive", result.getMatches().get(1).getLineText());
        assertTrue(result.getMatches().get(1).getFileId().startsWith("fout/mirth.log.1.zip@"));
        assertEquals(2, result.getFilesSearched());
    }

    @Test
    void matchOffsetOpensTheMatchAfterMultiByteText() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = "a\n€€ Müller €\r\n".getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);

        LogSearchResult result = fx.service.search("mÜLLER", false, false, null, null, null);
        assertEquals(1, result.getMatches().size());
        LogSearchMatch match = result.getMatches().get(0);
        assertEquals(2, match.getLineOffset());
        byte[] needle = "Müller".getBytes(StandardCharsets.UTF_8);
        int at = (int) match.getMatchOffset();
        assertArrayEquals(needle, Arrays.copyOfRange(content, at, at + needle.length));
        assertEquals("Müller", match.getLineText().substring(match.getMatchStart(), match.getMatchEnd()));
        // The line's CR is part of the terminator, so $ anchors before it.
        assertEquals(1, fx.service.search("€$", true, true, null, null, null).getMatches().size());
    }

    @Test
    void capsMatchesAndResumesWhereItStopped() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        int total = LogSearcher.MAX_MATCHES + 500;
        fx.writeActive(LogFixture.lines(total, "hit").getBytes(StandardCharsets.UTF_8), 1_000_000L);

        LogSearchResult first = fx.service.search("hit", false, true, null, null, null);
        assertEquals(LogSearcher.MAX_MATCHES, first.getMatches().size());
        assertEquals(LogSearchStopReason.MAX_MATCHES, first.getStopReason());
        assertFalse(first.isComplete());

        LogSearchResult rest = fx.service.search("hit", false, true, null,
                first.getResumeFileId(), first.getResumeOffset());
        assertEquals(500, rest.getMatches().size());
        assertTrue(rest.isComplete());
        assertEquals(LogSearcher.MAX_MATCHES + 1L, rest.getMatches().get(0).getLineNumber());
    }

    @Test
    void exactlyTheCapIsComplete() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(LogFixture.lines(LogSearcher.MAX_MATCHES, "hit").getBytes(StandardCharsets.UTF_8), 1_000_000L);

        LogSearchResult result = fx.service.search("hit", false, true, null, null, null);
        assertEquals(LogSearcher.MAX_MATCHES, result.getMatches().size());
        assertNull(result.getStopReason());
        assertTrue(result.isComplete());
    }

    @Test
    void catastrophicBacktrackingStopsAtTheDeadline() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(("a".repeat(60) + "\n").getBytes(StandardCharsets.UTF_8), 1_000_000L);
        // Each clock read moves time on 1 ms, so the 15 s deadline passes after
        // 15,000 reads. The classic (a+)+$ is no test on Java 9+, which
        // memoizes it; (.*a){10}x is polynomial of degree 10 and, measured on
        // Java 17, did not finish within 20 s on just 40 characters.
        fx.tick.set(TimeUnit.MILLISECONDS.toNanos(1));

        LogSearchResult result = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> fx.service.search("(.*a){10}x", true, true, null, null, null));
        assertEquals(LogSearchStopReason.DEADLINE, result.getStopReason());
        assertEquals(0L, result.getResumeOffset());
        // The stop and its resume point say it all; whether the line itself is the problem
        // shows when a continuation makes no progress, which the viewers report.
        assertTrue(result.getWarnings().isEmpty(), result.getWarnings().toString());
    }

    @Test
    void anOrdinarySearchWhoseTimeRunsOutPartWayThroughALineStopsThereWithoutBlamingThePattern() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = ("INFO first\n" + "x".repeat(400_000) + "\nINFO last\n").getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);
        // 100 ms per clock read: the matcher reads the clock every 1,024 chars it reads, so
        // the 15 s run out about 150 KB into the long line. An alternation reads every char;
        // a plain-text search would not (Java skips through a literal with Boyer-Moore).
        fx.tick.set(TimeUnit.MILLISECONDS.toNanos(100));

        LogSearchResult result = fx.service.search("ERROR|FATAL", true, true, null, null, null);
        assertEquals(LogSearchStopReason.DEADLINE, result.getStopReason());
        assertEquals((long) "INFO first\n".length(), result.getResumeOffset(), "resumes at the long line's start");
        assertTrue(result.getWarnings().isEmpty(), result.getWarnings().toString());
    }

    @Test
    void searchesAVeryLongLineInPiecesAndSaysSo() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String longLine = "€".repeat(LogSearcher.SEGMENT_BYTES) + "NEEDLE";
        fx.writeActive(("x\n" + longLine + "\nNEEDLE\n").getBytes(StandardCharsets.UTF_8), 1_000_000L);

        LogSearchResult result = fx.service.search("NEEDLE", false, true, null, null, null);
        assertEquals(2, result.getMatches().size());
        assertEquals(2, result.getMatches().get(0).getLineNumber());
        assertTrue(result.getMatches().get(0).isTruncated());
        assertEquals(1, result.getSplitLineCount());
        assertFalse(result.isComplete(), "split lines are reported, not silently searched");
        // Opening the match's offset lands on a page containing it.
        LogSearchMatch match = result.getMatches().get(0);
        LogPage page = fx.service.readPage(match.getFileId(), LogPageAnchor.AT, match.getMatchOffset());
        assertTrue(page.getText().contains("NEEDLE"));
    }

    @Test
    void numbersHl7SegmentsAsTheirOwnLines() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String content = "INFO one\r\nINFO msg MSH|a\rPID|1||42\rOBX|1\r\nINFO after\r\n";
        fx.writeActive(content.getBytes(StandardCharsets.UTF_8), 1_000_000L);

        LogSearchMatch pid = fx.service.search("PID", false, true, null, null, null).getMatches().get(0);
        assertEquals(3, pid.getLineNumber());
        assertEquals("PID|1||42", pid.getLineText());
        assertEquals(content.indexOf("PID"), pid.getLineOffset());
        assertEquals(4, fx.service.search("OBX", false, true, null, null, null).getMatches().get(0).getLineNumber());
        assertEquals(5, fx.service.search("after", false, true, null, null, null).getMatches().get(0).getLineNumber());
    }

    @Test
    void countsACrOnAReadBufferEdgeOnce() throws Exception {
        // Files are read in IO_BUFFER blocks; put a lone CR, or the CR of a
        // CRLF, on and around the block edge, where the byte deciding which
        // it is arrives with the next read.
        for (int at = OpenLogFile.IO_BUFFER - 2; at <= OpenLogFile.IO_BUFFER + 1; at++) {
            for (String ending : new String[] {"\r", "\r\n"}) {
                LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
                String content = "x".repeat(at) + ending + "NEEDLE one\nNEEDLE two\r\n";
                fx.writeActive(content.getBytes(StandardCharsets.UTF_8), 1_000_000L);

                LogSearchResult result = fx.service.search("NEEDLE", false, true, null, null, null);
                String where = "CR at " + at + " ending " + ending.length();
                assertEquals(2, result.getMatches().size(), where);
                assertEquals(2, result.getMatches().get(0).getLineNumber(), where);
                assertEquals(at + ending.length(), result.getMatches().get(0).getLineOffset(), where);
                assertEquals(3, result.getMatches().get(1).getLineNumber(), where);
            }
        }
    }

    @Test
    void searchAndPagesAgreeOnLineNumbersForEveryKindOfLineEnd() throws Exception {
        byte[] content = LogFixture.mixedLines(6000).getBytes(StandardCharsets.UTF_8);
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(content, 2_000_000L);
        fx.writeArchive(1, "mirth.log.2", content, 1_000_000L);

        for (String name : new String[] {"mirth.log", "mirth.log.1.zip"}) {
            String id = fx.idOf(name);
            LogSearchResult result = fx.service.search("PID", false, true, id, null, null);
            assertTrue(result.isComplete(), name);
            assertEquals(6000 / 11, result.getMatches().size(), name);
            for (LogSearchMatch match : result.getMatches()) {
                assertEquals(LogFixture.lineEndsBefore(content, match.getLineOffset()) + 1, match.getLineNumber(),
                        name + " match at " + match.getLineOffset());
                LogPage page = fx.service.readPage(match.getFileId(), LogPageAnchor.AT, match.getMatchOffset());
                assertTrue(page.getStartOffset() <= match.getLineOffset()
                        && match.getMatchOffset() < page.getEndOffset(), name + " page holds the match");
                assertTrue(page.getText().startsWith("PID", page.getTargetIndex()), name + " target is the match");
                assertEquals(LogFixture.lineEndsBefore(content, page.getStartOffset()) + 1,
                        page.getFirstLineNumber(), name + " page numbering");
            }
        }
    }

    @Test
    void rejectsBadQueries() {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        for (String query : new String[] {"", "(unclosed", "x".repeat(LogSearcher.MAX_QUERY_CHARS + 1)}) {
            LogViewerException e = assertThrows(LogViewerException.class,
                    () -> fx.service.search(query, true, false, null, null, null));
            assertEquals(LogViewerException.Kind.BAD_REQUEST, e.getKind());
        }
    }

    @Test
    void resultsSurviveTheAdministratorsXStream() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        Files.write(fx.active, "ERROR \u000bMSH|\u001c\u0000 café\rPID|1\r\nINFO next\n"
                .getBytes(StandardCharsets.UTF_8));

        LogSearchResult result = fx.service.search("error", false, false, null, null, null);
        LogPage page = fx.service.readPage(result.getMatches().get(0).getFileId(), LogPageAnchor.TAIL, null);
        assertTrue(page.getText().contains("\rPID|1\r\n"), "line terminators are sent as written");

        // Same driver the engine's serializer uses. The DTOs must hold only
        // types XStream round-trips (ArrayList, not List.of()). This checks the
        // XML path only; the JSON path the web administrator uses was measured
        // separately against the 4.6.0 ObjectJSONSerializer (CR, CRLF, lone CR
        // and \r\r\n all round-trip), since loading the engine's serializers
        // here would drag in its whole classpath.
        XStream xstream = new XStream(new Xpp3Driver());
        xstream.allowTypesByWildcard(new String[] {"com.diridium.logviewer.**"});
        LogSearchResult back = (LogSearchResult) xstream.fromXML(xstream.toXML(result));
        String pageXml = xstream.toXML(page);
        LogPage pageBack = (LogPage) xstream.fromXML(pageXml);
        assertEquals(result.getMatches().get(0).getLineText(), back.getMatches().get(0).getLineText());
        assertEquals(page.getText(), pageBack.getText());
        // CR is legal XML, written as &#xd; so a parser keeps it; no other
        // character reference may appear, since XML 1.0 forbids the rest.
        assertFalse(pageXml.replace("&#xd;", "").contains("&#x"), "no character references XML 1.0 forbids");
    }
}
