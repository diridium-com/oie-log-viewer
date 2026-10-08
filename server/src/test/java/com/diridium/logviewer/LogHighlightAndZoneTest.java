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
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

/** Engine-computed search highlights, the page read time, and the time zone each file is written in. */
class LogHighlightAndZoneTest {

    private static final LongSupplier STILL = () -> 0L;

    @TempDir
    Path dir;

    // ---- the highlighter ----------------------------------------------------------------

    private static LogHighlighter.Result find(String regex, String text) {
        return new LogHighlighter(Pattern.compile(regex), STILL).find(text);
    }

    private static void assertMarks(String text, int[] positions, String... expected) {
        assertEquals(expected.length * 2, positions.length, "number of positions");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], text.substring(positions[2 * i], positions[2 * i + 1]), "match " + i);
            if (i > 0) {
                assertTrue(positions[2 * i] >= positions[2 * i - 1], "in order, not overlapping");
            }
        }
    }

    @Test
    void marksEveryMatchOnEveryLine() {
        String text = "INFO ab ab\r\nWARN x\rPID|ab\nend";
        LogHighlighter.Result found = find("ab", text);
        assertNull(found.stopped());
        assertMarks(text, found.positions(), "ab", "ab", "ab");
    }

    @Test
    void anchorsWorkPerLineUnderEveryLineEnd() {
        // ^ and $ mean what they mean in search: per line, whether the line ended
        // in CRLF, LF or a lone CR (an HL7 segment).
        String text = "INFO a\r\nWARN x\rPID|1\nend";
        assertEquals(text.indexOf("WARN"), find("^WARN", text).positions()[0]);
        assertEquals(text.indexOf("PID"), find("^PID", text).positions()[0]);
        assertEquals(text.indexOf("x"), find("x$", text).positions()[0]);
        assertEquals(0, find("a\\s+WARN", text).positions().length, "a match never spans a line end");
    }

    @Test
    void emptyMatchesAreNotMarked() {
        LogHighlighter.Result found = find("z*", "abc\ndef\n");
        assertEquals(0, found.positions().length);
        assertNull(found.stopped());
    }

    @Test
    void positionsAreUtf16IndicesLikeTheTextItself() {
        String text = "smile " + new String(Character.toChars(0x1F600)) + " ab";
        assertMarks(text, find("ab", text).positions(), "ab");
        assertEquals(9, find("ab", text).positions()[0], "the emoji counts as two chars");
    }

    @Test
    void stopsAtTheMatchCapAndSaysSo() {
        String text = "a".repeat(LogHighlighter.MAX_MATCHES + 10);
        LogHighlighter.Result found = find("a", text);
        assertEquals(LogHighlightStop.MATCH_LIMIT, found.stopped());
        assertEquals(LogHighlighter.MAX_MATCHES * 2, found.positions().length);
    }

    @Test
    void stopsABacktrackingPatternAtItsDeadline() {
        AtomicLong now = new AtomicLong();
        LongSupplier clock = () -> now.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
        LogHighlighter highlighter = new LogHighlighter(Pattern.compile("(.*a){10}x"), clock);
        LogHighlighter.Result found = assertTimeoutPreemptively(Duration.ofSeconds(30),
                () -> highlighter.find("a".repeat(60) + "\n" + "a".repeat(60)));
        assertEquals(LogHighlightStop.TIME_LIMIT, found.stopped());
    }

    @Test
    void stopsAPatternThatOverflowsTheMatchersStackAndSaysSo() throws Exception {
        // (a|b)* recurses once per repetition in java.util.regex; a small thread stack makes
        // the overflow certain whatever the machine's default stack size.
        String text = "ab ok\n" + "ab".repeat(100_000) + "\nab";
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread small = new Thread(null, () -> {
            try {
                outcome.set(find("(a|b)+", text));
            } catch (Throwable t) {
                outcome.set(t);
            }
        }, "small-stack", 256 * 1024);
        small.start();
        small.join(TimeUnit.SECONDS.toMillis(30));
        assertTrue(outcome.get() instanceof LogHighlighter.Result, "returned, not thrown: " + outcome.get());
        LogHighlighter.Result found = (LogHighlighter.Result) outcome.get();
        assertEquals(LogHighlightStop.TOO_COMPLEX, found.stopped());
        assertMarks(text, found.positions(), "ab");
    }

    // ---- through the service --------------------------------------------------------------

    @Test
    void aPageCarriesWhereTheSearchMatchesInItsText() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(LogFixture.mixedLines(3000).getBytes(StandardCharsets.UTF_8), 2_000_000L);
        fx.writeArchive(1, "mirth.log.2", LogFixture.mixedLines(3000).getBytes(StandardCharsets.UTF_8), 1_000_000L);

        for (String name : new String[] {"mirth.log", "mirth.log.1.zip"}) {
            LogPage page = fx.service.readPage(fx.idOf(name), LogPageAnchor.TAIL, null, "pid", false, false);
            String text = page.getText();
            int expected = 0;
            for (int at = text.indexOf("PID"); at >= 0; at = text.indexOf("PID", at + 1)) {
                expected++;
            }
            assertTrue(expected > 10, name + ": the fixture must put PID on the page");
            String[] all = new String[expected];
            java.util.Arrays.fill(all, "PID");
            assertMarks(text, page.getHighlights(), all);
            assertNull(page.getHighlightsStopped(), name);
        }
    }

    @Test
    void aPageWithoutASearchHasNoHighlights() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO one\nERROR two\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        assertNull(fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null).getHighlights());
        assertNull(fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null, "", true, true).getHighlights());
    }

    @Test
    void anInvalidHighlightPatternIsABadRequest() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO one\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        LogViewerException e = assertThrows(LogViewerException.class,
                () -> fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null, "(unclosed", true, true));
        assertEquals(LogViewerException.Kind.BAD_REQUEST, e.getKind());
    }

    @Test
    void aPageRecordsWhenTheEngineReadIt() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO one\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        long before = System.currentTimeMillis();
        LogPage page = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null);
        long after = System.currentTimeMillis();
        assertTrue(page.getReadAt() >= before && page.getReadAt() <= after, "read time " + page.getReadAt());
    }

    @Test
    void highlightsSurviveTheAdministratorsXStream() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO ab ab\r\nPID|ab\rx\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        LogPage page = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null, "ab", false, true);
        page.setHighlightsStopped(LogHighlightStop.TIME_LIMIT);
        XStream xstream = new XStream(new Xpp3Driver());
        xstream.allowTypesByWildcard(new String[] {"com.diridium.logviewer.**"});
        LogPage back = (LogPage) xstream.fromXML(xstream.toXML(page));
        assertArrayEquals(page.getHighlights(), back.getHighlights());
        assertEquals(page.getReadAt(), back.getReadAt());
        assertEquals(LogHighlightStop.TIME_LIMIT, back.getHighlightsStopped());
    }

    // ---- time zones -----------------------------------------------------------------------

    @Test
    void filesAreInTheEngineDefaultZoneUnlessTheLayoutNamesOne() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("INFO one\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        LogFileInfo info = fx.service.listFiles().getFiles().get(0);
        assertEquals(TimeZone.getDefault().getID(), info.getTimeZoneId());
        assertFalse(info.getTimeZoneLabel().isEmpty());

        Path active = dir.resolve("mirth.log");
        LogViewerService utc = new LogViewerService(() -> List.of(new LogAppenderSource.Appender("fout",
                active.toString(), dir.resolve("mirth.log.%i.zip").toString(), StandardCharsets.UTF_8, "UTC")),
                STILL);
        LogFileInfo named = utc.listFiles().getFiles().get(0);
        assertEquals("UTC", named.getTimeZoneId());
        assertEquals("UTC", named.getTimeZoneLabel());
    }

    @Test
    void labelsGiveTheShortNameAndTheOffsetInForceThen() {
        long july = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        long january = ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals("MDT (UTC-06:00)", LogViewerService.timeZoneLabel(TimeZone.getTimeZone("America/Denver"), july));
        assertEquals("MST (UTC-07:00)", LogViewerService.timeZoneLabel(TimeZone.getTimeZone("America/Denver"), january));
        assertEquals("IST (UTC+05:30)", LogViewerService.timeZoneLabel(TimeZone.getTimeZone("Asia/Kolkata"), july));
        assertEquals("UTC-07:00", LogViewerService.timeZoneLabel(TimeZone.getTimeZone("GMT-07:00"), july));
        assertEquals("UTC", LogViewerService.timeZoneLabel(TimeZone.getTimeZone("UTC"), july));
    }

    @Test
    void theZoneIsReadFromTheLayoutsDatePattern() {
        assertNull(Log4jAppenderSource.dateZoneOf("%-5p %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c: %m%n"),
                "the engine's shipped pattern names no zone");
        assertEquals("UTC", Log4jAppenderSource.dateZoneOf("%d{HH:mm:ss}{UTC} %m%n"));
        assertEquals("America/Denver", Log4jAppenderSource.dateZoneOf("%-5p %date{ISO8601}{America/Denver} %m%n"));
        assertNull(Log4jAppenderSource.dateZoneOf(null));
    }
}
