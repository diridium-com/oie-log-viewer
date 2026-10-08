// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFileCatalogTest {

    @TempDir
    Path dir;

    @TempDir
    Path elsewhere;

    @Test
    void listsNewestFirstAndSkipsWhatThePatternDoesNotName() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("now\n".getBytes(StandardCharsets.UTF_8), 5_000_000L);
        // useMax=true order: the highest index is the newest archive.
        fx.writeArchive(1, "mirth.log.1", "oldest\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", "newer\n".getBytes(StandardCharsets.UTF_8), 2_000_000L);
        Files.writeString(dir.resolve("mirth.log.x.zip"), "not an index");
        Files.writeString(dir.resolve("other.log"), "not ours");

        List<String> names = fx.service.listFiles().getFiles().stream()
                .map(LogFileInfo::getName).collect(Collectors.toList());
        assertEquals(List.of("mirth.log", "mirth.log.2.zip", "mirth.log.1.zip"), names);
    }

    @Test
    void neverListsASymbolicLink() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("now\n".getBytes(StandardCharsets.UTF_8), 5_000_000L);
        Path secret = Files.writeString(elsewhere.resolve("secret.txt"), "not a log\n");
        try {
            Files.createSymbolicLink(dir.resolve("mirth.log.9.zip"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("symbolic links not available here: " + e);
        }

        assertFalse(fx.service.listFiles().getFiles().stream()
                .anyMatch(f -> f.getName().equals("mirth.log.9.zip")));
        LogViewerException e = assertThrows(LogViewerException.class,
                () -> fx.service.readPage("fout/mirth.log.9.zip@000000000000", LogPageAnchor.TAIL, null));
        assertEquals(LogViewerException.Kind.STALE, e.getKind());
    }

    @Test
    void archiveIdsGoStaleWhenARolloverRenamesThemDown() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive("now\n".getBytes(StandardCharsets.UTF_8), 5_000_000L);
        fx.writeArchive(1, "mirth.log.1", "first archive\n".getBytes(StandardCharsets.UTF_8), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", "second archive\n".getBytes(StandardCharsets.UTF_8), 2_000_000L);
        String oldOne = fx.idOf("mirth.log.1.zip");
        String oldTwo = fx.idOf("mirth.log.2.zip");

        // What DefaultRolloverStrategy does when full: drop index 1, rename the rest down.
        Files.delete(dir.resolve("mirth.log.1.zip"));
        Files.move(dir.resolve("mirth.log.2.zip"), dir.resolve("mirth.log.1.zip"), StandardCopyOption.ATOMIC_MOVE);
        fx.writeArchive(2, "mirth.log.3", "third archive\n".getBytes(StandardCharsets.UTF_8), 3_000_000L);

        for (String id : new String[] {oldOne, oldTwo}) {
            LogViewerException e = assertThrows(LogViewerException.class,
                    () -> fx.service.readPage(id, LogPageAnchor.TAIL, null));
            assertEquals(LogViewerException.Kind.STALE, e.getKind(), id);
        }
        // The renamed archive, listed again, reads as the same content it held before.
        assertEquals("second archive\n",
                fx.service.readPage(fx.idOf("mirth.log.1.zip"), LogPageAnchor.TAIL, null).getText());
    }

    @Test
    void rejectsMalformedIds() {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        for (String bad : new String[] {null, "", "mirth.log", "@abc", "fout/mirth.log@", "../etc/passwd"}) {
            LogViewerException e = assertThrows(LogViewerException.class,
                    () -> fx.service.readPage(bad, LogPageAnchor.TAIL, null));
            assertEquals(LogViewerException.Kind.BAD_REQUEST, e.getKind(), String.valueOf(bad));
        }
    }

    @Test
    void aDownloadDeclaresExactlyTheBytesItWillSend() throws Exception {
        // The servlet sends this as Content-Length, so a transfer cut short is a broken response.
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.lines(3000, "INFO").getBytes(StandardCharsets.UTF_8);
        fx.writeActive(content, 2_000_000L);
        Path zip = fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);

        for (String name : new String[] {"mirth.log", "mirth.log.1.zip"}) {
            LogViewerService.Download download = fx.service.openDownload(fx.idOf(name));
            try (InputStream in = download.stream()) {
                byte[] sent = in.readAllBytes();
                assertEquals(sent.length, download.length(), name);
                assertEquals(name.endsWith(".zip") ? Files.size(zip) : content.length, download.length(), name);
            }
        }
    }

    @Test
    void activeDownloadStopsAtItsLengthWhenOpenedAndAbortsOnTruncation() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        byte[] content = LogFixture.lines(20000, "INFO").getBytes(StandardCharsets.UTF_8);
        assertTrue(content.length > 4 * OpenLogFile.DOWNLOAD_BLOCK);
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        try (InputStream in = fx.service.openDownload(id).stream()) {
            Files.write(fx.active, "grown\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            assertArrayEquals(content, in.readAllBytes());
        }

        try (InputStream in = fx.service.openDownload(id).stream()) {
            ByteArrayOutputStream sent = new ByteArrayOutputStream();
            sent.write(in.readNBytes(1000));
            // log4j's copy-and-truncate fallback, then new lines past the read position.
            fx.writeActive(("INFO new first line\n" + "y".repeat(3_000_000)).getBytes(StandardCharsets.UTF_8),
                    1_000_001L);
            IOException thrown = null;
            byte[] buffer = new byte[4096];
            try {
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    sent.write(buffer, 0, n);
                }
            } catch (IOException e) {
                thrown = e;
            }
            assertNotNull(thrown, "the download must abort");
            byte[] all = sent.toByteArray();
            assertArrayEquals(Arrays.copyOf(content, all.length), all, "only the original file's bytes were sent");
        }
    }

    @Test
    void convertsRolloverPatternsToRegexes() {
        assertTrue("mirth.log.12.zip".matches(RolloverPattern.toRegex("mirth.log.%i.zip")));
        assertFalse("mirth.log.x.zip".matches(RolloverPattern.toRegex("mirth.log.%i.zip")));
        assertTrue("app-2026-09-29-3.log.gz".matches(
                RolloverPattern.toRegex("app-%d{yyyy-MM-dd}-%i.log.gz")));
        assertTrue("100%-1.log".matches(RolloverPattern.toRegex("100%%-%i.log")));
        assertTrue("app-host1.log".matches(RolloverPattern.toRegex("app-${hostName}.log")));
        assertEquals(List.of(4), RolloverPattern.separatorsOutsideBraces("logs/app-%d{yyyy/MM}.log"));
    }
}
