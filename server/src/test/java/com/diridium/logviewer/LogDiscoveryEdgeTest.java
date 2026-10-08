// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diridium.logviewer.LogAppenderSource.Appender;

/** Pattern conversion, discovery, appenders that share files, and the listing's limits. */
class LogDiscoveryEdgeTest {

    @TempDir
    Path dir;

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static LogViewerService service(Appender... appenders) {
        return new LogViewerService(() -> List.of(appenders), () -> 0L);
    }

    private static List<String> names(LogFileList list) {
        return list.getFiles().stream().map(LogFileInfo::getName).collect(Collectors.toList());
    }

    private static Path write(Path path, byte[] bytes, long mtimeMillis) throws IOException {
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    private static Path writeGzip(Path path, String content, long mtimeMillis) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(utf8(content));
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    // ========================
    // 10. Pattern conversion
    // ========================

    private static boolean matches(String pattern, String name) {
        return name.matches(RolloverPattern.toRegex(pattern));
    }

    @Test
    void patternConversionHandlesDatesLookupsPercentLiteralsPaddingAndRegexMetacharacters() {
        // A date conversion, with a quoted literal and a slash inside its braces.
        assertTrue(matches("app-%d{yyyy-MM-dd}.log", "app-2026-09-29.log"));
        assertTrue(matches("app-%d{yyyy-MM-dd'T'HH}-%i.log", "app-2026-09-29T07-3.log"));
        // A slash written by the date makes a folder, so it is a path separator in the match.
        assertTrue(matches("%d{yyyy/MM}-app.log.gz", "2026/09-app.log.gz"));
        assertFalse(matches("%d{yyyy/MM}-app.log.gz", "2026-09-app.log.gz"));
        assertFalse(matches("app-%d{yyyy-MM-dd}.log", "app-.log"), "a date is at least one character");
        assertFalse(matches("app-%d{yyyy-MM-dd}.log", "app-2026-09-29.log.bak"), "the rest of the pattern is anchored");
        assertFalse(matches("app-%d{yyyy-MM-dd}.log", "app.log"));

        // Runtime lookups, with a colon in them.
        assertTrue(matches("app-${sys:x}-%i.log", "app-prod-3.log"));
        assertTrue(matches("app-${date:yyyy-MM}.log", "app-2026-09.log"));
        assertFalse(matches("app-${sys:x}-%i.log", "app-prod-x.log"), "the index is digits");

        // %% is one literal percent sign, and is not the start of a conversion.
        assertTrue(matches("100%%.log", "100%.log"));
        assertFalse(matches("100%%.log", "100.log"));
        assertFalse(matches("100%%.log", "100%%.log"));
        assertTrue(matches("100%%-%i.log", "100%-12.log"));

        // %i with padding and alignment flags is still digits.
        for (String pattern : new String[] {"app.%3i.log", "app.%03i.log", "app.%-3i.log", "app.%i.log", "app.%index.log"}) {
            assertTrue(matches(pattern, "app.007.log"), pattern);
            assertTrue(matches(pattern, "app.12345.log"), pattern);
            assertFalse(matches(pattern, "app.x.log"), pattern);
            assertFalse(matches(pattern, "app..log"), pattern);
        }

        // Characters that mean something in a regular expression are literal in a file name.
        assertTrue(matches("mirth(1)+[x].%i.log", "mirth(1)+[x].5.log"));
        assertFalse(matches("mirth.%i.log", "mirthX5Xlog"));
        assertFalse(matches("a.b.%i.zip", "aXbX1Xzip"));
        // A conversion with no closing brace consumes the rest instead of failing.
        assertTrue(matches("app-%d{yyyy", "app-anything"));
    }

    @Test
    void discoveryFindsArchivesNamedByADatePatternAndSkipsLookalikes() throws Exception {
        Path active = write(dir.resolve("app.log"), utf8("now\n"), 9_000_000L);
        Appender appender = new Appender("fout", active.toString(),
                dir.resolve("app-%d{yyyy-MM-dd}-%i.log.gz").toString(), StandardCharsets.UTF_8);
        writeGzip(dir.resolve("app-2026-09-29-1.log.gz"), "one\n", 1_000_000L);
        writeGzip(dir.resolve("app-2026-09-30-1.log.gz"), "two\n", 2_000_000L);
        writeGzip(dir.resolve("app-2026-09-30-12.log.gz"), "three\n", 3_000_000L);
        // Lookalikes: a non-numeric index, another compression, another stem, a suffix after the extension.
        writeGzip(dir.resolve("app-2026-09-30-x.log.gz"), "no\n", 4_000_000L);
        write(dir.resolve("app-2026-09-30-1.log.zip"), utf8("no"), 4_000_000L);
        writeGzip(dir.resolve("other.log.gz"), "no\n", 4_000_000L);
        writeGzip(dir.resolve("app-2026-09-30-1.log.gz.tmp"), "no\n", 4_000_000L);
        Files.createDirectory(dir.resolve("app-2026-09-30-9.log.gz")); // a directory with a matching name

        LogViewerService service = service(appender);
        LogFileList list = service.listFiles();
        assertEquals(List.of("app.log", "app-2026-09-30-12.log.gz", "app-2026-09-30-1.log.gz", "app-2026-09-29-1.log.gz"),
                names(list));
        assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());
        for (LogFileInfo info : list.getFiles()) {
            assertEquals(!info.isActive(), info.isCompressed(), info.getName());
            assertTrue(info.isViewable(), info.getName());
        }
        String id = list.getFiles().get(1).getId();
        assertEquals("three\n", service.readPage(id, LogPageAnchor.TAIL, null).getText());
    }

    @Test
    void discoveryFindsPaddedPlainArchivesAndAPercentLiteralInTheName() throws Exception {
        Path active = write(dir.resolve("app.log"), utf8("now\n"), 9_000_000L);
        Appender padded = new Appender("padded", active.toString(), dir.resolve("app-%3i.log").toString(),
                StandardCharsets.UTF_8);
        write(dir.resolve("app-001.log"), utf8("first\n"), 1_000_000L);
        write(dir.resolve("app-002.log"), utf8("second\n"), 2_000_000L);
        write(dir.resolve("app-x.log"), utf8("no\n"), 3_000_000L);

        LogViewerService service = service(padded);
        assertEquals(List.of("app.log", "app-002.log", "app-001.log"), names(service.listFiles()));
        assertFalse(service.listFiles().getFiles().get(1).isCompressed());
        assertEquals("second\n", service.readPage(service.listFiles().getFiles().get(1).getId(), LogPageAnchor.TAIL, null).getText());

        Path other = Files.createDirectory(dir.resolve("pct"));
        write(other.resolve("100%-1.log"), utf8("pct\n"), 1_000_000L);
        write(other.resolve("100-1.log"), utf8("no\n"), 1_000_000L);
        Appender percent = new Appender("pct", other.resolve("100.log").toString(), other.resolve("100%%-%i.log").toString(),
                StandardCharsets.UTF_8);
        assertEquals(List.of("100%-1.log"), names(service(percent).listFiles()));
    }

    @Test
    void archivesInAVariableDirectoryAreListedWithTheirFolder() throws Exception {
        Path active = write(dir.resolve("app.log"), utf8("now\n"), 9_000_000L);
        Path month = Files.createDirectory(dir.resolve("2026-09"));
        writeGzip(month.resolve("app-1.log.gz"), "archived\n", 1_000_000L);
        for (String pattern : new String[] {
                dir + "/${date:yyyy-MM}/app-%i.log.gz", // what log4j reports for $${date:yyyy-MM}
                dir + "/%d{yyyy-MM}/app-%i.log.gz",
                dir + "/${sys:x}/app-%i.log.gz"}) {
            Appender appender = new Appender("fout", active.toString(), pattern, StandardCharsets.UTF_8);
            LogFileList list = service(appender).listFiles();
            assertEquals(List.of("app.log", "2026-09/app-1.log.gz"), names(list), pattern);
            assertTrue(list.getWarnings().isEmpty(), pattern + ": " + list.getWarnings());
        }
    }

    @Test
    void aMissingArchiveDirectoryIsSilentAndOneThatIsAFileIsAWarning() throws Exception {
        Path active = write(dir.resolve("app.log"), utf8("now\n"), 9_000_000L);
        Appender missing = new Appender("fout", active.toString(), dir.resolve("not-yet/app-%i.log.gz").toString(),
                StandardCharsets.UTF_8);
        LogFileList none = service(missing).listFiles();
        assertEquals(List.of("app.log"), names(none));
        assertTrue(none.getWarnings().isEmpty(), "no rollover has happened yet: " + none.getWarnings());

        Path file = write(dir.resolve("plainfile"), utf8("x"), 1_000_000L);
        Appender notDirectory = new Appender("fout", active.toString(), file + "/app-%i.log.gz", StandardCharsets.UTF_8);
        LogFileList warned = service(notDirectory).listFiles();
        assertEquals(List.of("app.log"), names(warned));
        assertEquals(1, warned.getWarnings().size(), warned.getWarnings().toString());
        assertTrue(warned.getWarnings().get(0).contains("not a directory"), warned.getWarnings().get(0));
    }

    @Test
    void anAppenderWithAMalformedPatternDoesNotHideTheOthers() throws Exception {
        Path good = write(dir.resolve("good.log"), utf8("good\n"), 9_000_000L);
        // A file name Paths.get rejects (it holds a NUL character) makes this appender's discovery throw.
        Appender bad = new Appender("bad", dir.resolve("bad.log").toString() + (char) 0, null, StandardCharsets.UTF_8);
        LogFileList list = service(new Appender("good", good.toString(), null, StandardCharsets.UTF_8), bad).listFiles();
        assertEquals(List.of("good.log"), names(list));
        assertEquals(1, list.getWarnings().size(), list.getWarnings().toString());
        assertTrue(list.getWarnings().get(0).contains("appender bad"), list.getWarnings().get(0));
    }

    @Test
    void noAppendersGivesAnEmptyListWithAWarning() {
        LogFileList list = service().listFiles();
        assertTrue(list.getFiles().isEmpty());
        assertEquals(1, list.getWarnings().size());
        assertTrue(list.getWarnings().get(0).contains("no file appenders"));
    }

    // ========================
    // 11. Two appenders naming the same file
    // ========================

    @Test
    void twoAppendersNamingTheSameFilesListEachOnceUnderTheFirstNameWhateverTheOrder() throws Exception {
        Path active = write(dir.resolve("mirth.log"), utf8("now\n"), 9_000_000L);
        write(dir.resolve("mirth.log.1.zip"), utf8("not read"), 1_000_000L);
        String pattern = dir.resolve("mirth.log.%i.zip").toString();
        // Differently spelled paths to the same file.
        Appender zeta = new Appender("zeta", dir + "/./mirth.log", pattern, StandardCharsets.UTF_8);
        Appender alpha = new Appender("alpha", active.toString(), pattern, StandardCharsets.UTF_8);
        Appender middle = new Appender("middle", dir + "/sub/../mirth.log", dir + "/./mirth.log.%i.zip", StandardCharsets.UTF_8);

        for (List<Appender> order : List.of(List.of(zeta, alpha, middle), List.of(middle, alpha, zeta),
                List.of(alpha, middle, zeta))) {
            LogFileList list = new LogViewerService(() -> order, () -> 0L).listFiles();
            assertEquals(List.of("mirth.log", "mirth.log.1.zip"), names(list), order.toString());
            for (LogFileInfo info : list.getFiles()) {
                assertEquals("alpha", info.getAppenderName(), "owned by the first name alphabetically");
                assertTrue(info.getId().startsWith("alpha/" + info.getName() + "@"), info.getId());
            }
            assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());
        }
    }

    @Test
    void anAppenderWhoseActiveFileIsAnotherAppendersArchiveDoesNotListItTwice() throws Exception {
        write(dir.resolve("mirth.log"), utf8("now\n"), 9_000_000L);
        Path archive = writeGzip(dir.resolve("mirth.log.1.gz"), "archived\n", 1_000_000L);
        Appender owner = new Appender("a", dir.resolve("mirth.log").toString(), dir.resolve("mirth.log.%i.gz").toString(),
                StandardCharsets.UTF_8);
        Appender odd = new Appender("b", archive.toString(), null, StandardCharsets.UTF_8);
        LogFileList list = service(owner, odd).listFiles();
        assertEquals(List.of("mirth.log", "mirth.log.1.gz"), names(list));
        assertEquals(List.of("a", "a"), list.getFiles().stream().map(LogFileInfo::getAppenderName).toList());
        // Reading it back works through the owner's id and decodes as an archive.
        assertEquals("archived\n", service(owner, odd).readPage(list.getFiles().get(1).getId(), LogPageAnchor.TAIL, null).getText());
    }

    // ========================
    // 14. The listing's limits
    // ========================

    @Test
    void theListingIsCappedAtTheNewestFilesWithAWarning() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        int archives = LogFileCatalog.MAX_LISTED_FILES + 5;
        for (int i = 1; i <= archives; i++) {
            fx.writeArchive(i, "mirth.log." + i, utf8("hit " + i + "\n"), 1_000_000L + i * 1000L);
        }
        fx.writeActive(utf8("hit active\n"), 9_000_000_000L);

        LogFileList list = fx.service.listFiles();
        assertEquals(LogFileCatalog.MAX_LISTED_FILES, list.getFiles().size());
        assertEquals("mirth.log", list.getFiles().get(0).getName());
        List<String> listed = names(list);
        // The oldest archives (lowest mtime, here the lowest indexes) are the ones left out.
        for (int i = 1; i <= 6; i++) {
            assertFalse(listed.contains("mirth.log." + i + ".zip"), "oldest archive " + i + " is left out");
        }
        assertTrue(listed.contains("mirth.log.7.zip"));
        assertTrue(listed.contains("mirth.log." + archives + ".zip"));
        assertEquals(List.of("Only the newest " + LogFileCatalog.MAX_LISTED_FILES + " of " + (archives + 1)
                + " log files are listed."), list.getWarnings());

        // A search over everything covers the listed files only, and says the answer is not complete.
        LogSearchResult result = fx.service.search("hit", false, true, null, null, null);
        assertEquals(LogFileCatalog.MAX_LISTED_FILES, result.getFilesInScope());
        assertEquals(LogFileCatalog.MAX_LISTED_FILES, result.getFilesSearched());
        assertEquals(LogFileCatalog.MAX_LISTED_FILES, result.getMatches().size());
        assertFalse(result.isComplete());
        assertTrue(result.getWarnings().get(0).contains("Only the newest"), result.getWarnings().toString());
    }

    @Test
    void exactlyTheCapIsListedWithoutAWarning() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        for (int i = 1; i < LogFileCatalog.MAX_LISTED_FILES; i++) {
            fx.writeArchive(i, "mirth.log." + i, utf8("x\n"), 1_000_000L + i * 1000L);
        }
        fx.writeActive(utf8("x\n"), 9_000_000_000L);
        LogFileList list = fx.service.listFiles();
        assertEquals(LogFileCatalog.MAX_LISTED_FILES, list.getFiles().size());
        assertTrue(list.getWarnings().isEmpty(), list.getWarnings().toString());
    }

    /**
     * A charset that deletes a file the moment discovery asks it to encode for
     * the third time. Discovery calls {@code isNewlineSafe(charset)} (two
     * encodes) for each file after it has checked the file exists and before
     * it opens it, so the third encode is the second file, between its
     * directory listing and its open.
     */
    private static final class VanishingCharset extends Charset {
        private final Path victim;
        private int encoders;

        VanishingCharset(Path victim) {
            super("x-vanishing-test", null);
            this.victim = victim;
        }

        @Override
        public boolean contains(Charset other) {
            return other == this;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            if (++encoders == 3) {
                try {
                    Files.delete(victim);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    @Test
    void aFileDeletedBetweenTheDirectoryListingAndItsOpenIsLeftOutQuietly() throws Exception {
        LogFixture seed = new LogFixture(dir, StandardCharsets.UTF_8);
        seed.writeActive(utf8("now\n"), 9_000_000L);
        Path archive = seed.writeArchive(1, "mirth.log.1", utf8("old\n"), 1_000_000L);
        VanishingCharset charset = new VanishingCharset(archive);
        Appender appender = new Appender("fout", seed.active.toString(), dir.resolve("mirth.log.%i.zip").toString(), charset);

        LogFileList list = service(appender).listFiles();
        assertFalse(Files.exists(archive), "the archive vanished during discovery (the hook ran)");
        assertEquals(List.of("mirth.log"), names(list));
        assertTrue(list.getWarnings().isEmpty(), "quietly: " + list.getWarnings());
    }

    @Test
    void aFileThatCannotBeReadIsAWarningAndTheOthersAreStillListedAndSearched() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("ERROR active\n"), 9_000_000L);
        Path locked = fx.writeArchive(1, "mirth.log.1", utf8("ERROR locked\n"), 1_000_000L);
        fx.writeArchive(2, "mirth.log.2", utf8("ERROR open\n"), 2_000_000L);
        Set<PosixFilePermission> original = Files.getPosixFilePermissions(locked);
        try {
            Files.setPosixFilePermissions(locked, Collections.emptySet());
            assumeFalse(Files.isReadable(locked), "running as a user that ignores file permissions");

            LogFileList list = fx.service.listFiles();
            assertEquals(List.of("mirth.log", "mirth.log.2.zip"), names(list));
            assertEquals(1, list.getWarnings().size(), list.getWarnings().toString());
            assertTrue(list.getWarnings().get(0).startsWith("mirth.log.1.zip could not be opened: "),
                    list.getWarnings().get(0));

            LogSearchResult result = fx.service.search("ERROR", false, true, null, null, null);
            assertEquals(2, result.getMatches().size());
            assertFalse(result.isComplete(), "a file the list could not open was not searched either");
            assertNotNull(result.getWarnings().get(0));
        } finally {
            Files.setPosixFilePermissions(locked, original);
        }
    }

    @Test
    void discoveryDoesNotCacheAnythingBetweenRequests() throws Exception {
        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(utf8("now\n"), 9_000_000L);
        List<String> seen = new ArrayList<>();
        seen.addAll(names(fx.service.listFiles()));
        fx.writeArchive(1, "mirth.log.1", utf8("old\n"), 1_000_000L);
        seen.addAll(names(fx.service.listFiles()));
        assertEquals(List.of("mirth.log", "mirth.log", "mirth.log.1.zip"), seen);
    }
}
