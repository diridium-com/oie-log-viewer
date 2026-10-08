// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diridium.logviewer.LogFileCatalog.Listing;

/**
 * What rollover (log4j renaming every archive down one index, recreating the
 * active file, or its copy-and-truncate fallback) does to ids, positions,
 * searches and downloads that were in flight.
 */
class LogRolloverEdgeTest {

    private static final int BLOCK = OpenLogFile.DOWNLOAD_BLOCK;

    @TempDir
    Path dir;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static LogViewerException.Kind kindOf(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(LogViewerException.class, call).getKind();
    }

    /** Every operation that takes a file id refuses a stale one, with kind STALE. */
    private static void assertStaleEverywhere(LogFixture fx, String id) {
        String where = id;
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.TAIL, null)), "TAIL " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.HEAD, null)), "HEAD " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.BEFORE, 0L)), "BEFORE " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AFTER, 0L)), "AFTER " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.AT, 0L)), "AT " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.search("x", false, true, id, null, null)),
                "search one file " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.openDownload(id)), "download " + where);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.search("x", false, true, null, id, 0L)),
                "resume " + where);
    }

    // ========================
    // 1. Rollover between two page requests
    // ========================

    @Test
    void aRolloverWithRoomToSpareStalesTheActiveIdButNoArchiveId() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] oldActive = utf8("INFO 10:00:00.001 first line of the old active file\nERROR one\n");
        fx.writeActive(oldActive, 3_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("archive one\n"), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8("archive two\n"), 2_000_000L);
        String active = fx.idOf("mirth.log");
        String one = fx.idOf("mirth.log.1.zip");
        String two = fx.idOf("mirth.log.2.zip");
        // A page the client already holds; its start offset is what it will send next.
        LogPage held = fx.service.readPage(active, LogPageAnchor.TAIL, null);

        // log4j with the archive window not yet full: rename the active file to
        // the next index (here compressed straight to it), start a new active file.
        fx.writeArchive(3, "mirth.log", oldActive, 3_000_000L);
        Files.move(fx.active, dir.resolve("old-active.tmp"));
        fx.writeActive(utf8("INFO 10:05:00.002 first line of the new active file\n"), 4_000_000L);

        assertStaleEverywhere(fx, active);
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.readPage(active, LogPageAnchor.BEFORE, held.getStartOffset())));
        // Nothing was renamed down, so the archives' ids are untouched and still read.
        assertEquals("archive one\n", fx.service.readPage(one, LogPageAnchor.TAIL, null).getText());
        assertEquals("archive two\n", fx.service.readPage(two, LogPageAnchor.TAIL, null).getText());
        assertEquals(one, fx.idOf("mirth.log.1.zip"));
        assertEquals(two, fx.idOf("mirth.log.2.zip"));
        // The newest archive is what the old active file held.
        assertEquals(new String(oldActive, StandardCharsets.UTF_8),
                fx.service.readPage(fx.idOf("mirth.log.3.zip"), LogPageAnchor.TAIL, null).getText());
    }

    @Test
    void aFullRolloverStalesEveryOldIdAndRelistedIdsReadTheShiftedContent() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] oldActive = utf8("INFO 10:00:00.001 first line of the old active file\nERROR one\n");
        byte[] a1 = utf8("archive one, the oldest\n");
        byte[] a2 = utf8("archive two\n");
        byte[] a3 = utf8("archive three, the newest\n");
        fx.writeActive(oldActive, 4_000_000L);
        fx.writeArchive(1, "mirth.log.1", a1, 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", a2, 2_000_000L);
        fx.writeArchive(3, "mirth.log.3", a3, 3_000_000L);
        List<String> before = new ArrayList<>();
        for (String name : new String[] {"mirth.log", "mirth.log.1.zip", "mirth.log.2.zip", "mirth.log.3.zip"}) {
            before.add(fx.idOf(name));
        }

        // The window is full (three archives): the oldest is dropped, every
        // other archive is renamed down one index (keeping its modification
        // time), the active file becomes the newest archive and a new one starts.
        Files.delete(dir.resolve("mirth.log.1.zip"));
        Files.move(dir.resolve("mirth.log.2.zip"), dir.resolve("mirth.log.1.zip"), StandardCopyOption.ATOMIC_MOVE);
        Files.move(dir.resolve("mirth.log.3.zip"), dir.resolve("mirth.log.2.zip"), StandardCopyOption.ATOMIC_MOVE);
        fx.writeArchive(3, "mirth.log", oldActive, 4_000_000L);
        Files.move(fx.active, dir.resolve("old-active.tmp"));
        fx.writeActive(utf8("INFO 10:05:00.002 first line of the new active file\n"), 5_000_000L);

        for (String id : before) {
            assertStaleEverywhere(fx, id);
        }
        // Listed again, each name reads as what it now holds.
        assertEquals("archive two\n", fx.service.readPage(fx.idOf("mirth.log.1.zip"), LogPageAnchor.TAIL, null).getText());
        assertEquals("archive three, the newest\n",
                fx.service.readPage(fx.idOf("mirth.log.2.zip"), LogPageAnchor.TAIL, null).getText());
        assertEquals(new String(oldActive, StandardCharsets.UTF_8),
                fx.service.readPage(fx.idOf("mirth.log.3.zip"), LogPageAnchor.TAIL, null).getText());
        assertEquals("INFO 10:05:00.002 first line of the new active file\n",
                fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.TAIL, null).getText());
        // Same names, different ids: the id is what notices the shift.
        assertEquals(before.get(1).substring(0, before.get(1).indexOf('@')),
                fx.idOf("mirth.log.1.zip").substring(0, fx.idOf("mirth.log.1.zip").indexOf('@')));
        assertNotEquals(before.get(1), fx.idOf("mirth.log.1.zip"));
    }

    // ========================
    // 2. Rollover during a search
    // ========================

    private static LogAppenderSource source(LogFixture fx) {
        return () -> List.of(new LogAppenderSource.Appender("fout", fx.active.toString(),
                fx.dir.resolve("mirth.log.%i.zip").toString(), StandardCharsets.UTF_8));
    }

    @Test
    void anActiveFileReplacedAfterTheListingStopsTheSearchWithFilesRotated() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 old first line\nERROR in the old file\n"), 3_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();

        // Copy-and-truncate rollover: same inode, new first line.
        fx.writeActive(utf8("INFO 10:05:00.002 new first line\nERROR in the new file\n"), 4_000_000L);

        LogSearchResult result = new LogSearcher(() -> 0L).searchAll(LogSearcher.compile("ERROR", false, true),
                listing.entries(), 0, 0);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertTrue(result.getMatches().isEmpty(), "no line of a file other than the listed one");
        assertFalse(result.isComplete());
        assertNull(result.getResumeFileId(), "resuming would not help");
    }

    @Test
    void anActiveFileRenamedAndRecreatedAfterTheListingStopsTheSearchWithFilesRotated() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 old first line\nERROR in the old file\n"), 3_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();

        Files.move(fx.active, dir.resolve("old-active.tmp"));
        fx.writeActive(utf8("INFO 10:05:00.002 new first line\nERROR in the new file\n"), 4_000_000L);

        LogSearchResult result = new LogSearcher(() -> 0L).searchAll(LogSearcher.compile("ERROR", false, true),
                listing.entries(), 0, 0);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertTrue(result.getMatches().isEmpty());
    }

    @Test
    void anArchiveRenamedDownAfterTheListingStopsTheSearchButKeepsEarlierMatches() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 first\nERROR in active\n"), 4_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in one\n"), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8("ERROR in two\n"), 2_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();
        assertEquals(3, listing.entries().size());

        Files.delete(dir.resolve("mirth.log.1.zip"));
        Files.move(dir.resolve("mirth.log.2.zip"), dir.resolve("mirth.log.1.zip"), StandardCopyOption.ATOMIC_MOVE);

        // Order is active, archive 2, archive 1. Archive 2's name is gone
        // (NoSuchFile), so the search stops there with the active file's match kept.
        LogSearchResult result = new LogSearcher(() -> 0L).searchAll(LogSearcher.compile("ERROR", false, true),
                listing.entries(), 0, 0);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertEquals(1, result.getMatches().size());
        assertEquals("ERROR in active", result.getMatches().get(0).getLineText());
        assertEquals(1, result.getFilesSearched());
        assertFalse(result.isComplete());
    }

    @Test
    void anArchiveWhoseNameNowHoldsAnotherFileStopsTheSearchWithFilesRotated() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 first\nnothing\n"), 4_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in one\n"), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8("ERROR in two\n"), 2_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();

        // Rename down, then a new archive 2 appears, so both names still exist.
        Files.delete(dir.resolve("mirth.log.1.zip"));
        Files.move(dir.resolve("mirth.log.2.zip"), dir.resolve("mirth.log.1.zip"), StandardCopyOption.ATOMIC_MOVE);
        fx.writeArchive(2, "mirth.log.3", utf8("ERROR in three\n"), 3_000_000L);

        LogSearchResult result = new LogSearcher(() -> 0L).searchAll(LogSearcher.compile("ERROR", false, true),
                listing.entries(), 0, 0);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertTrue(result.getMatches().isEmpty(), "never a line from the file that now has the listed name");
    }

    @Test
    void anActiveFileTruncatedWhileItIsReadDropsItsMatchesButNotEarlierFilesMatches() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String first = "INFO 10:00:00.001 same first line\n";
        fx.writeActive(utf8(first + "ERROR a\nERROR b\n" + "pad\n".repeat(100)), 4_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in the archive\n"), 1_000_000L);
        LogFileCatalog catalog = new LogFileCatalog(source(fx));
        Listing listing = catalog.list();
        LogFileCatalog.LogFile activeFile = listing.entries().get(0).file();
        LogFileCatalog.LogFile archiveFile = listing.entries().get(1).file();
        assertTrue(activeFile.active());

        LogSearcher searcher = new LogSearcher(() -> 0L);
        LogSearcher.Run run = searcher.start(LogSearcher.compile("ERROR", false, true));
        try (OpenLogFile archive = OpenLogFile.open(archiveFile)) {
            assertTrue(run.searchFile(archive, 0));
        }
        try (OpenLogFile active = OpenLogFile.open(activeFile)) {
            // Copy-and-truncate after the file was opened: it keeps its first
            // line and one more line, so the bytes that remain still match.
            Files.write(fx.active, utf8(first + "ERROR a\n"));
            assertFalse(run.searchFile(active, 0));
        }
        LogSearchResult result = run.finish(2);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertEquals(1, result.getMatches().size(), "only the archive's match survives");
        assertEquals("ERROR in the archive", result.getMatches().get(0).getLineText());
        assertEquals(1, result.getFilesSearched());
    }

    @Test
    void anActiveFileTruncatedAndRegrownWithANewFirstLineWhileReadDropsItsMatches() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 old first line\nERROR old\n"), 4_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();

        LogSearcher.Run run = new LogSearcher(() -> 0L).start(LogSearcher.compile("ERROR", false, true));
        try (OpenLogFile active = OpenLogFile.open(listing.entries().get(0).file())) {
            // Longer than before, so the size check passes and only the first line gives it away.
            fx.writeActive(utf8("INFO 10:05:00.002 new first line\nERROR new one\nERROR new two\nERROR new three\n"),
                    4_000_001L);
            assertFalse(run.searchFile(active, 0));
        }
        LogSearchResult result = run.finish(1);
        assertEquals(LogSearchStopReason.FILES_ROTATED, result.getStopReason());
        assertTrue(result.getMatches().isEmpty(), "a mix of two files' lines is never reported");
    }

    // ========================
    // 3. Download during copy-and-truncate
    // ========================

    private record Drained(byte[] delivered, IOException error) {
    }

    private static Drained drain(InputStream in) {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                sent.write(buffer, 0, n);
            }
        } catch (IOException e) {
            return new Drained(sent.toByteArray(), e);
        }
        return new Drained(sent.toByteArray(), null);
    }

    private static final String FIRST_LINE = "INFO 10:00:00.001 the first line of the original file\n";

    private byte[] originalFor3Blocks() {
        byte[] original = utf8(FIRST_LINE + LogFixture.lines(1300, "INFO"));
        assertTrue(original.length > 3 * BLOCK && original.length < 4 * BLOCK, "length " + original.length);
        return original;
    }

    private static byte[] fill(String firstLine, int total, char c) {
        byte[] head = utf8(firstLine);
        byte[] all = new byte[total];
        Arrays.fill(all, (byte) c);
        System.arraycopy(head, 0, all, 0, head.length);
        return all;
    }

    @Test
    void aDownloadAbortsWhenTheActiveFileIsTruncatedWhetherOrNotItRegrows() throws Exception {
        byte[] original = originalFor3Blocks();
        // name -> {bytes consumed before the truncation, the new content of the file}
        Map<String, Object[]> scenarios = new LinkedHashMap<>();
        // Truncated before the first block was read.
        scenarios.put("emptied before any read", new Object[] {0, new byte[0]});
        scenarios.put("shorter new content before any read", new Object[] {0, fill("INFO new first line\n", 500, 'N')});
        scenarios.put("regrown past the length before any read",
                new Object[] {0, fill("INFO new first line\n", original.length + 5000, 'N')});
        scenarios.put("same first line, cut short, before any read",
                new Object[] {0, Arrays.copyOf(original, 1000)});
        // Truncated after the first block was delivered.
        scenarios.put("emptied after one block", new Object[] {BLOCK, new byte[0]});
        scenarios.put("shorter than the read position after one block",
                new Object[] {BLOCK, fill("INFO new first line\n", BLOCK / 2, 'N')});
        scenarios.put("regrown past the read position after one block (new first line)",
                new Object[] {BLOCK, fill("INFO new first line\n", original.length + 5000, 'N')});
        scenarios.put("regrown to exactly the old length after one block (new first line)",
                new Object[] {BLOCK, fill("INFO new first line\n", original.length, 'N')});
        scenarios.put("same first line, cut to just past the read position",
                new Object[] {BLOCK, Arrays.copyOf(original, BLOCK + 10)});
        scenarios.put("same first line, cut to exactly the read position",
                new Object[] {BLOCK, Arrays.copyOf(original, BLOCK)});
        scenarios.put("same first line, cut to under one block, after two blocks",
                new Object[] {2 * BLOCK, Arrays.copyOf(original, 100)});

        for (Map.Entry<String, Object[]> scenario : scenarios.entrySet()) {
            Path home = Files.createDirectory(dir.resolve("s" + Math.abs(scenario.getKey().hashCode())));
            LogFixture fx = new LogFixture(home, StandardCharsets.UTF_8);
            fx.writeActive(original, 1_000_000L);
            String id = fx.idOf("mirth.log");
            int consumed = (Integer) scenario.getValue()[0];
            byte[] replacement = (byte[]) scenario.getValue()[1];
            String where = scenario.getKey();

            try (InputStream in = fx.service.openDownload(id).stream()) {
                byte[] sent = in.readNBytes(consumed);
                assertEquals(consumed, sent.length, where);
                Files.write(fx.active, replacement);
                Drained rest = drain(in);

                assertNotNull(rest.error(), where + ": the download must end in an IOException");
                assertTrue(rest.error().getMessage().contains("truncated"), where + ": " + rest.error().getMessage());
                assertEquals(0, rest.delivered().length, where + ": nothing is handed on after the file changed");
                assertArrayEquals(Arrays.copyOf(original, consumed), sent, where + ": what was sent is the original's prefix");
                // None of the new content (every filler byte is 'N') was delivered.
                assertFalse(new String(sent, StandardCharsets.ISO_8859_1).contains("INFO new first line"), where);
            }
        }
    }

    @Test
    void aDownloadByteByByteAlsoAbortsOnTruncation() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8(FIRST_LINE + "second line\n"), 1_000_000L);
        String id = fx.idOf("mirth.log");
        try (InputStream in = fx.service.openDownload(id).stream()) {
            Files.write(fx.active, new byte[0]);
            assertThrows(IOException.class, in::read);
        }
    }

    @Test
    void aFailedDownloadNeverLaterReportsACleanEndOfStream() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] original = utf8(FIRST_LINE + "second line\n");
        fx.writeActive(original, 1_000_000L);
        String id = fx.idOf("mirth.log");
        try (InputStream in = fx.service.openDownload(id).stream()) {
            Files.write(fx.active, fill("INFO new first line\n", original.length + 50, 'N'));
            assertThrows(IOException.class, in::read);
            // A caller that swallowed the exception and read on must not be told
            // the (one-block) download simply ended: that looks like success.
            int again;
            try {
                again = in.read();
            } catch (IOException expected) {
                return;
            }
            assertNotEquals(-1, again, "a failed download read again reported a clean end of stream");
        }
    }

    @Test
    void truncationThatKeepsTheFirstLineAndRegrowsPastTheLengthIsNotDetectedByDesign() throws Exception {
        // The active file's fingerprint is its first line, so a copy-and-truncate
        // that rewrites the very same first line and grows past the old length
        // passes both checks (size and first line). Observed, and a documented
        // consequence of the fingerprint choice; the engine's first line carries a
        // millisecond timestamp, which makes this unreachable in practice.
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] original = originalFor3Blocks();
        fx.writeActive(original, 1_000_000L);
        String id = fx.idOf("mirth.log");
        byte[] rewritten = fill(FIRST_LINE, original.length + 5000, 'N');
        try (InputStream in = fx.service.openDownload(id).stream()) {
            byte[] sent = in.readNBytes(BLOCK);
            Files.write(fx.active, rewritten);
            Drained rest = drain(in);
            assertNull(rest.error());
            assertEquals(original.length - BLOCK, rest.delivered().length);
            assertTrue(new String(rest.delivered(), StandardCharsets.ISO_8859_1).contains("NNNN"),
                    "bytes of the rewritten file were delivered");
            assertArrayEquals(Arrays.copyOf(original, BLOCK), sent);
        }
    }

    @Test
    void aRenameOfTheActiveFileDuringADownloadDoesNotDisturbIt() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] original = originalFor3Blocks();
        fx.writeActive(original, 1_000_000L);
        String id = fx.idOf("mirth.log");
        try (InputStream in = fx.service.openDownload(id).stream()) {
            byte[] sent = in.readNBytes(1000);
            // The normal rollover: the open handle follows the renamed file.
            Files.move(fx.active, dir.resolve("mirth.log.1.zip"));
            fx.writeActive(utf8("INFO new file after rollover\n"), 2_000_000L);
            Drained rest = drain(in);
            assertNull(rest.error());
            byte[] all = new byte[sent.length + rest.delivered().length];
            System.arraycopy(sent, 0, all, 0, sent.length);
            System.arraycopy(rest.delivered(), 0, all, sent.length, rest.delivered().length);
            assertArrayEquals(original, all);
        }
    }

    @Test
    void aRenameDownOfAnArchiveDuringADownloadDoesNotDisturbIt() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("now\n"), 5_000_000L);
        Path zip = fx.writeArchive(2, "mirth.log.2", utf8(LogFixture.lines(3000, "INFO")), 1_000_000L);
        byte[] zipBytes = Files.readAllBytes(zip);
        String id = fx.idOf("mirth.log.2.zip");
        try (InputStream in = fx.service.openDownload(id).stream()) {
            byte[] sent = in.readNBytes(10);
            Files.move(zip, dir.resolve("mirth.log.1.zip"), StandardCopyOption.ATOMIC_MOVE);
            byte[] all = new byte[zipBytes.length];
            System.arraycopy(sent, 0, all, 0, sent.length);
            byte[] rest = in.readAllBytes();
            System.arraycopy(rest, 0, all, sent.length, rest.length);
            assertEquals(zipBytes.length, sent.length + rest.length);
            assertArrayEquals(zipBytes, all);
        }
    }

    // ========================
    // 4. Truncated, same first line: positions BEFORE, AT and AFTER
    // ========================

    @Test
    void aPositionPastTheEndOfATruncatedFileIsStaleForBeforeAtAndAfterButTheEndItselfIsNot() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        String first = "INFO same first line\n";
        byte[] original = utf8(first + LogFixture.lines(200, "x"));
        fx.writeActive(original, 1_000_000L);
        String id = fx.idOf("mirth.log");
        long oldEnd = fx.service.readPage(id, LogPageAnchor.TAIL, null).getEndOffset();
        assertEquals(original.length, oldEnd);

        // Same first line, shorter: the id still matches, only the length gives it away.
        byte[] kept = Arrays.copyOf(original, 500);
        Files.write(fx.active, kept);
        for (LogPageAnchor anchor : new LogPageAnchor[] {LogPageAnchor.BEFORE, LogPageAnchor.AT, LogPageAnchor.AFTER}) {
            for (long offset : new long[] {oldEnd, kept.length + 1L, oldEnd + 1000}) {
                LogViewerException e = assertThrows(LogViewerException.class,
                        () -> fx.service.readPage(id, anchor, offset), anchor + " " + offset);
                assertEquals(LogViewerException.Kind.STALE, e.getKind(), anchor + " " + offset);
            }
            // Exactly at the new end is a real position: a page, not an error.
            LogPage page = fx.service.readPage(id, anchor, (long) kept.length);
            assertEquals(kept.length, page.getEndOffset(), anchor.toString());
        }
        // And the other pages still work on what remains.
        assertEquals(new String(kept, StandardCharsets.UTF_8),
                fx.service.readPage(id, LogPageAnchor.TAIL, null).getText());
    }

    // ========================
    // 5. A first line that is empty or half written when listed
    // ========================

    @Test
    void anEmptyActiveFileListedThenWrittenGivesAHarmlessFalseStaleAndRelistingFixesIt() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(new byte[0], 1_000_000L);
        String id = fx.idOf("mirth.log");
        LogPage empty = fx.service.readPage(id, LogPageAnchor.TAIL, null);
        assertEquals("", empty.getText());
        assertTrue(empty.isAtEnd());
        assertEquals(0L, empty.getContentLength());

        // The file's first line did not exist when it was listed, so the id (a
        // fingerprint of nothing) cannot survive the first write. Observed: STALE.
        Files.write(fx.active, utf8("INFO 10:00:00.001 first line\nsecond\n"), StandardOpenOption.APPEND);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.TAIL, null)));

        String relisted = fx.idOf("mirth.log");
        assertNotEquals(id, relisted);
        assertEquals("INFO 10:00:00.001 first line\nsecond\n",
                fx.service.readPage(relisted, LogPageAnchor.TAIL, null).getText());
    }

    @Test
    void aHalfWrittenFirstLineListedThenCompletedGivesAHarmlessFalseStale() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.00"), 1_000_000L);
        String id = fx.idOf("mirth.log");
        assertEquals("INFO 10:00:00.00", fx.service.readPage(id, LogPageAnchor.TAIL, null).getText());

        // Appended text before the newline arrives changes what "the first line" is,
        // and so does the newline itself. Observed: STALE either way.
        Files.write(fx.active, utf8("1 first line, still no newline"), StandardOpenOption.APPEND);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.TAIL, null)));

        String second = fx.idOf("mirth.log");
        Files.write(fx.active, utf8("\nnext\n"), StandardOpenOption.APPEND);
        assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(second, LogPageAnchor.TAIL, null)));

        // Once the first line is complete, growth keeps the id valid.
        String third = fx.idOf("mirth.log");
        Files.write(fx.active, utf8("more\n"), StandardOpenOption.APPEND);
        assertTrue(fx.service.readPage(third, LogPageAnchor.TAIL, null).getText().endsWith("next\nmore\n"));
    }

    @Test
    void aFirstLineThatIsEmptyKeepsItsIdAsTheFileGrowsAndCannotTellARewriteByFirstLine() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("\n"), 1_000_000L);
        String id = fx.idOf("mirth.log");

        // A complete (empty) first line is a stable fingerprint, so growth is fine.
        Files.write(fx.active, utf8("INFO 10:00:00.001 real content\n"), StandardOpenOption.APPEND);
        assertEquals("\nINFO 10:00:00.001 real content\n", fx.service.readPage(id, LogPageAnchor.TAIL, null).getText());

        // Observed limitation: an in-place rewrite (same inode, as copy-and-truncate
        // does) that again begins with an empty line has the same fingerprint, so
        // only the length check could notice it. The engine's files begin with a
        // timestamped line, so this does not arise there.
        Files.write(fx.active, utf8("\nINFO 10:05:00.002 completely different\n"));
        assertEquals("\nINFO 10:05:00.002 completely different\n",
                fx.service.readPage(id, LogPageAnchor.TAIL, null).getText());
    }

    @Test
    void aSearchOverAnEmptyActiveFileFindsNothingAndIsComplete() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(new byte[0], 1_000_000L);
        LogSearchResult result = fx.service.search("anything", false, true, null, null, null);
        assertTrue(result.getMatches().isEmpty());
        assertTrue(result.isComplete());
        assertEquals(1, result.getFilesSearched());
    }
}
