// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diridium.logviewer.LogFileCatalog.Listing;

/**
 * Symbolic links: a link in the log directory is never served, but the log
 * directory itself may be a link (installs commonly point logs at another disk).
 * Each test is skipped where the platform cannot create a link.
 */
class LogSymlinkEdgeTest {

    @TempDir
    Path dir;

    @TempDir
    Path elsewhere;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void link(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links not available here: " + e);
        }
    }

    private static List<String> names(LogFileList list) {
        return list.getFiles().stream().map(LogFileInfo::getName).collect(Collectors.toList());
    }

    private static LogViewerException.Kind kindOf(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(LogViewerException.class, call).getKind();
    }

    private static LogAppenderSource source(LogFixture fx) {
        return () -> List.of(new LogAppenderSource.Appender("fout", fx.active.toString(),
                fx.dir.resolve("mirth.log.%i.zip").toString(), StandardCharsets.UTF_8));
    }

    @Test
    void anArchiveLinkToAnotherArchiveInTheSameDirectoryIsNotListedEither() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("now\n"), 5_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("real archive\n"), 1_000_000L);
        link(dir.resolve("mirth.log.2.zip"), dir.resolve("mirth.log.1.zip"));
        Path realDirectory = Files.createDirectory(elsewhere.resolve("a-directory"));
        link(dir.resolve("mirth.log.3.zip"), realDirectory);

        assertEquals(List.of("mirth.log", "mirth.log.1.zip"), names(fx.service.listFiles()));
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.readPage("fout/mirth.log.2.zip@000000000000", LogPageAnchor.TAIL, null)));
    }

    @Test
    void aSymlinkedActiveFileIsNotListedNotPagedNotSearchedAndNotDownloaded() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        Path real = Files.write(elsewhere.resolve("real.log"), utf8("ERROR secret outside the log directory\n"));
        link(fx.active, real);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in the archive\n"), 1_000_000L);

        LogFileList list = fx.service.listFiles();
        assertEquals(List.of("mirth.log.1.zip"), names(list));
        assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());

        // Any id that names it, whatever the fingerprint, is refused as gone.
        for (String id : new String[] {"fout/mirth.log@000000000000", "fout/mirth.log@" + "a".repeat(12)}) {
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.readPage(id, LogPageAnchor.TAIL, null)), id);
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.openDownload(id)), id);
            assertEquals(LogViewerException.Kind.STALE, kindOf(() -> fx.service.search("ERROR", false, true, id, null, null)), id);
        }
        LogSearchResult all = fx.service.search("secret|archive", true, true, null, null, null);
        assertEquals(1, all.getMatches().size());
        assertEquals("ERROR in the archive", all.getMatches().get(0).getLineText());
    }

    @Test
    void aFileReplacedByASymlinkAfterTheListingIsNeverOpenedBySearchOrByTheOpenCall() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO 10:00:00.001 first\nERROR in active\n"), 5_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in the archive\n"), 1_000_000L);
        Listing listing = new LogFileCatalog(source(fx)).list();
        assertEquals(2, listing.entries().size());
        // The target is a perfectly good zip and a perfectly good log, so only the link check can refuse them.
        Path goodZip = fx.writeArchive(7, "elsewhere", utf8("ERROR secret archive\n"), 2_000_000L);
        Path movedZip = Files.move(goodZip, elsewhere.resolve("good.zip"));
        Path goodLog = Files.write(elsewhere.resolve("good.log"), utf8("INFO 10:00:00.001 first\nERROR secret active\n"));

        Files.delete(dir.resolve("mirth.log.1.zip"));
        link(dir.resolve("mirth.log.1.zip"), movedZip);
        Files.delete(fx.active);
        link(fx.active, goodLog);

        for (LogFileCatalog.Entry entry : listing.entries()) {
            assertThrows(IOException.class, () -> OpenLogFile.open(entry.file()).close(), entry.file().name());
        }
        LogSearchResult result = new LogSearcher(() -> 0L).searchAll(LogSearcher.compile("ERROR", false, true),
                listing.entries(), 0, 0);
        assertTrue(result.getMatches().isEmpty(), "nothing read through either link: " + result.getMatches().size());
        assertFalse(result.isComplete());
        assertEquals(2, result.getWarnings().stream().filter(w -> w.contains("could not be searched completely")).count(),
                result.getWarnings().toString());
        // And through the service, the replaced files are simply not found.
        assertEquals(List.of(), names(fx.service.listFiles()));
        assertEquals(LogViewerException.Kind.STALE,
                kindOf(() -> fx.service.readPage(listing.entries().get(0).id(), LogPageAnchor.TAIL, null)));
    }

    @Test
    void aSymlinkedLogDirectoryIsAllowedForListingPagingSearchAndDownload() throws Exception {
        Path real = Files.createDirectory(elsewhere.resolve("real-logs"));
        Path linkedDir = dir.resolve("logs");
        link(linkedDir, real);
        LogFixture fx = new LogFixture(linkedDir, StandardCharsets.UTF_8);
        byte[] active = utf8("INFO 10:00:00.001 first\nERROR in active\n");
        fx.writeActive(active, 5_000_000L);
        fx.writeArchive(1, "mirth.log.1", utf8("ERROR in the archive\n"), 1_000_000L);
        assertTrue(Files.isSymbolicLink(linkedDir));
        assertTrue(Files.exists(real.resolve("mirth.log")), "written through the link");

        LogFileList list = fx.service.listFiles();
        assertEquals(List.of("mirth.log", "mirth.log.1.zip"), names(list));
        assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());
        String activeId = list.getFiles().get(0).getId();
        String archiveId = list.getFiles().get(1).getId();

        assertEquals(new String(active, StandardCharsets.UTF_8),
                fx.service.readPage(activeId, LogPageAnchor.TAIL, null).getText());
        assertEquals("ERROR in the archive\n", fx.service.readPage(archiveId, LogPageAnchor.TAIL, null).getText());
        LogSearchResult result = fx.service.search("ERROR", false, true, null, null, null);
        assertEquals(2, result.getMatches().size());
        assertTrue(result.isComplete());
        try (InputStream in = fx.service.openDownload(activeId).stream()) {
            assertArrayEquals(active, in.readAllBytes());
        }
        try (InputStream in = fx.service.openDownload(archiveId).stream()) {
            assertArrayEquals(Files.readAllBytes(real.resolve("mirth.log.1.zip")), in.readAllBytes());
        }
        // A symlink inside the linked directory is still refused.
        link(real.resolve("mirth.log.9.zip"), elsewhere.resolve("real-logs").resolve("mirth.log.1.zip"));
        assertEquals(List.of("mirth.log", "mirth.log.1.zip"), names(fx.service.listFiles()));
    }
}
