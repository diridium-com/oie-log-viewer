// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diridium.logviewer.LogAppenderSource.Appender;

/**
 * Archives that log4j keeps in folders named by the rollover date, such as
 * {@code logs/${date:yyyy-MM-dd-HHmm}/mirth.log.%i.zip}: a numbered set in each
 * folder (measured on 4.6.0), listed by their path below the base directory.
 */
class LogArchiveFolderTest {

    @TempDir
    Path dir;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static LogViewerService service(Appender appender) {
        return new LogViewerService(() -> List.of(appender), () -> 0L);
    }

    private static List<String> names(LogFileList list) {
        return list.getFiles().stream().map(LogFileInfo::getName).collect(Collectors.toList());
    }

    /** A zip holding one log file, as log4j's zip action writes it, with the given modification time. */
    private static Path zip(Path path, String content, long mtimeMillis) throws IOException {
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            String name = path.getFileName().toString();
            zip.putNextEntry(new ZipEntry(name.substring(0, name.length() - ".zip".length())));
            zip.write(utf8(content));
            zip.closeEntry();
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    private static Path write(Path path, String content, long mtimeMillis) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, utf8(content));
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    private Appender probeLayout(Path logs) throws IOException {
        Path active = write(logs.resolve("mirth.log"), "INFO now\n", 9_000_000L);
        // What log4j reports for filePattern = ${log.dir}/$${date:yyyy-MM-dd-HHmm}/mirth.log.%i.zip
        return new Appender("fout", active.toString(), logs + "/${date:yyyy-MM-dd-HHmm}/mirth.log.%i.zip",
                StandardCharsets.UTF_8);
    }

    @Test
    void archivesInDateFoldersAreListedByTheirPathAndPageSearchAndDownloadLikeAnyOther() throws Exception {
        Path logs = Files.createDirectory(dir.resolve("logs"));
        Appender appender = probeLayout(logs);
        zip(logs.resolve("2026-10-06-1630/mirth.log.1.zip"), "INFO oldest\nERROR first folder\n", 1_000_000L);
        zip(logs.resolve("2026-10-06-1630/mirth.log.2.zip"), "INFO older\n", 2_000_000L);
        zip(logs.resolve("2026-10-06-1631/mirth.log.1.zip"), "ERROR second folder\n", 3_000_000L);
        // Not log4j's: a folder of another shape, a folder of the right shape one level too deep,
        // an archive in the base itself, and a file where a folder would be.
        zip(logs.resolve("old-stuff/mirth.log.1.zip"), "ERROR no\n", 4_000_000L);
        zip(logs.resolve("2026-10-06/mirth.log.1.zip"), "ERROR no\n", 4_000_000L);
        zip(logs.resolve("2026-10-06-1631/2026-10-06-1632/mirth.log.1.zip"), "ERROR no\n", 4_000_000L);
        zip(logs.resolve("mirth.log.7.zip"), "ERROR no\n", 4_000_000L);
        write(logs.resolve("2026-10-06-1633"), "not a folder\n", 4_000_000L);

        LogViewerService service = service(appender);
        LogFileList list = service.listFiles();
        assertEquals(List.of("mirth.log", "2026-10-06-1631/mirth.log.1.zip", "2026-10-06-1630/mirth.log.2.zip",
                "2026-10-06-1630/mirth.log.1.zip"), names(list));
        assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());
        LogFileInfo second = list.getFiles().get(1);
        assertTrue(second.getId().startsWith("fout/2026-10-06-1631/mirth.log.1.zip@"), second.getId());

        assertEquals("ERROR second folder\n", service.readPage(second.getId(), LogPageAnchor.HEAD, null).getText());
        LogSearchResult errors = service.search("ERROR", false, true, null, null, null);
        assertEquals(List.of("ERROR second folder", "ERROR first folder"),
                errors.getMatches().stream().map(LogSearchMatch::getLineText).collect(Collectors.toList()));
        LogSearchMatch inFolder = errors.getMatches().get(1);
        LogPage at = service.readPage(inFolder.getFileId(), LogPageAnchor.AT, inFolder.getMatchOffset());
        assertTrue(at.getText().startsWith("ERROR first folder", at.getTargetIndex()), at.getText());
        LogViewerService.Download download = service.openDownload(second.getId());
        try (InputStream in = download.stream()) {
            assertArrayEquals(Files.readAllBytes(logs.resolve("2026-10-06-1631/mirth.log.1.zip")), in.readAllBytes());
        }
    }

    @Test
    void aSymbolicLinkWithAFoldersNameIsNotEntered() throws Exception {
        Path logs = Files.createDirectory(dir.resolve("logs"));
        Appender appender = probeLayout(logs);
        zip(logs.resolve("2026-10-06-1630/mirth.log.1.zip"), "INFO real\n", 1_000_000L);
        Path elsewhere = Files.createDirectory(dir.resolve("elsewhere"));
        zip(elsewhere.resolve("mirth.log.1.zip"), "INFO linked\n", 2_000_000L);
        try {
            Files.createSymbolicLink(logs.resolve("2026-10-06-1631"), elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            assumeFalse(true, "symbolic links are not available here: " + e);
        }
        assertEquals(List.of("mirth.log", "2026-10-06-1630/mirth.log.1.zip"), names(service(appender).listFiles()));
    }

    @Test
    void nestedDateFoldersAndADateThatWritesASlashAreWalked() throws Exception {
        Path base = Files.createDirectory(dir.resolve("nested"));
        Path active = write(base.resolve("app.log"), "INFO now\n", 9_000_000L);
        write(base.resolve("2026/09/app-2026-09-29-1.log"), "INFO a\n", 1_000_000L);
        write(base.resolve("2026/10/app-2026-10-01-1.log"), "INFO b\n", 2_000_000L);
        write(base.resolve("2026/10/app-2026-10-01-1.log.bak"), "INFO no\n", 3_000_000L);
        write(base.resolve("2026/app-2026-10-01-1.log"), "INFO no\n", 3_000_000L);
        Appender nested = new Appender("app", active.toString(), base + "/%d{yyyy}/%d{MM}/app-%d{yyyy-MM-dd}-%i.log",
                StandardCharsets.UTF_8);
        assertEquals(List.of("app.log", "2026/10/app-2026-10-01-1.log", "2026/09/app-2026-09-29-1.log"),
                names(service(nested).listFiles()));

        Path slash = Files.createDirectory(dir.resolve("slash"));
        Path slashActive = write(slash.resolve("app.log"), "INFO now\n", 9_000_000L);
        write(slash.resolve("app-2026/09-1.log"), "INFO c\n", 1_000_000L);
        Appender slashed = new Appender("app", slashActive.toString(), slash + "/app-%d{yyyy/MM}-%i.log",
                StandardCharsets.UTF_8);
        assertEquals(List.of("app.log", "app-2026/09-1.log"), names(service(slashed).listFiles()));
    }

    @Test
    void datesMatchByTheirShapeSoOtherFilesWithTheSameStemAreLeftOut() throws Exception {
        Path base = Files.createDirectory(dir.resolve("shape"));
        Path active = write(base.resolve("app.log"), "INFO now\n", 9_000_000L);
        write(base.resolve("app-2026-09-29.log"), "INFO a\n", 1_000_000L);
        write(base.resolve("app-backup.log"), "INFO no\n", 2_000_000L);
        write(base.resolve("app-2026-9-29.log"), "INFO no\n", 2_000_000L);
        for (String pattern : new String[] {"/app-%d{yyyy-MM-dd}.log", "/app-%d{yyyy-MM-dd}{UTC}.log",
                "/app-${date:yyyy-MM-dd}.log"}) {
            Appender appender = new Appender("app", active.toString(), base + pattern, StandardCharsets.UTF_8);
            assertEquals(List.of("app.log", "app-2026-09-29.log"), names(service(appender).listFiles()), pattern);
        }
    }

    @Test
    void onlyTheNewestFoldersAreReadPastTheCapWithAWarning() throws Exception {
        Path logs = Files.createDirectory(dir.resolve("logs"));
        Appender appender = probeLayout(logs);
        int folders = LogFileCatalog.MAX_ARCHIVE_FOLDERS + 3;
        for (int i = 0; i < folders; i++) {
            Path folder = logs.resolve(String.format("2026-10-06-%04d", i));
            write(folder.resolve("mirth.log.1.zip"), "x", 1_000_000L + i);
            Files.setLastModifiedTime(folder, FileTime.fromMillis(1_000_000L + i));
        }
        LogFileCatalog.Discovery discovery = new LogFileCatalog(() -> List.of(appender)).discover();
        assertEquals(1 + LogFileCatalog.MAX_ARCHIVE_FOLDERS, discovery.files().size());
        Set<String> listed = discovery.files().stream().map(LogFileCatalog.LogFile::name).collect(Collectors.toSet());
        for (int i = 0; i < 3; i++) {
            assertFalse(listed.contains(String.format("2026-10-06-%04d/mirth.log.1.zip", i)), "oldest folder " + i);
        }
        assertTrue(listed.contains(String.format("2026-10-06-%04d/mirth.log.1.zip", folders - 1)));
        assertEquals(List.of("Archives of appender fout are listed from its newest "
                + LogFileCatalog.MAX_ARCHIVE_FOLDERS + " folders only."), discovery.warnings());
    }

    @Test
    void theFolderCapAppliesToEachLevelOfNestedFolders() throws Exception {
        // logs/<day>/<hour>/app.log.N.gz: the day folders must not use up the hour folders' share.
        for (int days : new int[] {LogFileCatalog.MAX_ARCHIVE_FOLDERS - 1, LogFileCatalog.MAX_ARCHIVE_FOLDERS + 1}) {
            Path logs = Files.createDirectory(dir.resolve("nested-" + days));
            Path active = write(logs.resolve("app.log"), "INFO now\n", 9_000_000L);
            Appender appender = new Appender("app", active.toString(),
                    logs + "/${date:yyyy-MM-dd}/${date:HH}/app.log.%i.gz", StandardCharsets.UTF_8);
            for (int d = 0; d < days; d++) {
                java.time.LocalDate day = java.time.LocalDate.of(2025, 1, 1).plusDays(d);
                Path hour = logs.resolve(day.toString()).resolve("07");
                write(hour.resolve("app.log.1.gz"), "x", 1_000_000L + d);
                Files.setLastModifiedTime(hour, FileTime.fromMillis(1_000_000L + d));
                Files.setLastModifiedTime(hour.getParent(), FileTime.fromMillis(1_000_000L + d));
            }
            LogFileCatalog.Discovery discovery = new LogFileCatalog(() -> List.of(appender)).discover();
            int expected = Math.min(days, LogFileCatalog.MAX_ARCHIVE_FOLDERS);
            assertEquals(1 + expected, discovery.files().size(), days + " days");
            assertEquals(days > LogFileCatalog.MAX_ARCHIVE_FOLDERS
                    ? List.of("Archives of appender app are listed from its newest "
                            + LogFileCatalog.MAX_ARCHIVE_FOLDERS + " folders only.")
                    : List.of(), discovery.warnings(), days + " days");
        }
    }

    @Test
    void anEntryOrAFolderThatCannotBeReadIsAWarningAndTheRestIsListed() throws Exception {
        Path logs = Files.createDirectory(dir.resolve("logs"));
        Appender appender = probeLayout(logs);
        zip(logs.resolve("2026-10-06-1630/mirth.log.1.zip"), "INFO a\n", 1_000_000L);
        Path locked = Files.createDirectory(logs.resolve("2026-10-06-1631"));
        zip(locked.resolve("mirth.log.1.zip"), "INFO b\n", 2_000_000L);
        Path unsearchable = Files.createDirectory(logs.resolve("2026-10-06-1632"));
        zip(unsearchable.resolve("mirth.log.1.zip"), "INFO c\n", 3_000_000L);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            // No permissions at all: the folder cannot be read. Read but not search (no x):
            // its entries are listed but their attributes cannot be read.
            Files.setPosixFilePermissions(locked, Collections.emptySet());
            Files.setPosixFilePermissions(unsearchable, Set.of(PosixFilePermission.OWNER_READ));
            assumeFalse(Files.isReadable(locked), "running as a user that ignores file permissions");

            LogFileList list = service(appender).listFiles();
            assertEquals(List.of("mirth.log", "2026-10-06-1630/mirth.log.1.zip"), names(list));
            assertEquals(2, list.getWarnings().size(), list.getWarnings().toString());
            assertTrue(list.getWarnings().stream().anyMatch(w -> w.startsWith(
                    "Archive folder 2026-10-06-1631 of appender fout could not be read: AccessDeniedException")),
                    list.getWarnings().toString());
            assertTrue(list.getWarnings().stream().anyMatch(w -> w.startsWith(
                    "2026-10-06-1632/mirth.log.1.zip could not be listed: AccessDeniedException")),
                    list.getWarnings().toString());
        } finally {
            Files.setPosixFilePermissions(locked, original);
            Files.setPosixFilePermissions(unsearchable, original);
        }
    }
}
