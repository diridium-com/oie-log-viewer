// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Character sets and archive formats: what is paged and searched, what is
 * listed as download-only, and what a damaged archive does.
 */
class LogFormatEdgeTest {

    private static final int CAP = LogPager.PAGE_MAX_BYTES;

    @TempDir
    Path dir;

    /** Built from code points, so this file stays ASCII. */
    private static String chars(int... codePoints) {
        StringBuilder sb = new StringBuilder();
        for (int cp : codePoints) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ========================
    // 6. Character sets
    // ========================

    /** Han characters every legacy CJK charset below can encode; U+8868 and U+80FD end in the byte 0x5C in Shift_JIS. */
    private static final String COMMON = chars(0x4E2D, 0x6587, 0x8868, 0x80FD);
    private static final String WORD = chars(0x8868, 0x793A);

    private static byte[] cjkLog(Charset charset, int lines) {
        StringBuilder sb = new StringBuilder();
        String[] endings = {"\n", "\r\n", "\r"};
        for (int i = 1; i <= lines; i++) {
            sb.append("INFO ").append(i).append(' ').append(COMMON).append(' ').append("y".repeat(i % 50));
            if (i % 97 == 0) {
                sb.append(' ').append(WORD).append(" end");
            }
            sb.append(endings[i % endings.length]);
        }
        return sb.toString().getBytes(charset);
    }

    private static final String[] LEGACY_CJK = {"Shift_JIS", "windows-31j", "EUC-JP", "GBK", "Big5", "EUC-KR"};

    @Test
    void shiftJisAndTheOtherLegacyCjkCharsetsPageAndSearchCorrectly() throws Exception {
        int tested = 0;
        for (String name : LEGACY_CJK) {
            if (!Charset.isSupported(name)) {
                continue;
            }
            Charset charset = Charset.forName(name);
            if (!new String((COMMON + WORD).getBytes(charset), charset).equals(COMMON + WORD)) {
                continue; // this JDK's table lacks one of the characters
            }
            tested++;
            Path home = Files.createDirectory(dir.resolve(name));
            LogFixture fx = new LogFixture(home, charset);
            byte[] content = cjkLog(charset, 20000);
            assertTrue(content.length > 3 * CAP, name + " fixture must span several pages");
            fx.writeActive(content, 2_000_000L);
            fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);

            for (String file : new String[] {"mirth.log", "mirth.log.1.zip"}) {
                String id = fx.idOf(file);
                String where = name + " " + file;
                assertTrue(fx.service.listFiles().getFiles().stream()
                        .filter(f -> f.getName().equals(file)).findFirst().orElseThrow().isViewable(), where);
                LogPagerTest.assertCovers(LogFixture.walkBackward(fx.service, id), content, charset);
                LogPagerTest.assertCovers(LogFixture.walkForward(fx.service, id), content, charset);

                LogSearchResult result = fx.service.search(WORD, false, true, id, null, null);
                assertTrue(result.isComplete(), where);
                assertEquals(20000 / 97, result.getMatches().size(), where);
                byte[] needle = WORD.getBytes(charset);
                for (int m = 0; m < result.getMatches().size(); m++) {
                    LogSearchMatch match = result.getMatches().get(m);
                    int at = (int) match.getMatchOffset();
                    assertArrayEquals(needle, Arrays.copyOfRange(content, at, at + needle.length), where);
                    assertEquals(WORD, match.getLineText().substring(match.getMatchStart(), match.getMatchEnd()), where);
                    assertEquals(LogFixture.lineEndsBefore(content, match.getLineOffset()) + 1, match.getLineNumber(),
                            where + " line of match at " + at);
                    if (m % 13 == 0) { // opening every match in an archive would decompress it hundreds of times
                        LogPage page = fx.service.readPage(id, LogPageAnchor.AT, match.getMatchOffset());
                        assertTrue(page.getStartOffset() <= match.getMatchOffset()
                                && match.getMatchOffset() < page.getEndOffset(), where);
                        assertTrue(page.getText().contains(WORD), where);
                        assertTrue(page.getText().startsWith(WORD, page.getTargetIndex()), where + " target");
                    }
                }
                // A regular expression over the decoded text, anchored to the line's end.
                LogSearchResult anchored = fx.service.search(WORD + " end$", true, true, id, null, null);
                assertEquals(20000 / 97, anchored.getMatches().size(), where);
            }
        }
        assumeTrue(tested > 0, "no legacy CJK charset available in this JDK");
    }

    /** Bytes as written, for lines that are deliberately not valid in their charset. */
    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] join(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }

    private static void assertEveryMatchOffsetIsExact(LogFixture fx, byte[] content, byte[] needle, String query,
                                                      int expected, String where) throws Exception {
        LogSearchResult result = fx.service.search(query, false, true, fx.idOf("mirth.log"), null, null);
        // Not isComplete(): a line searched in pieces makes the result incomplete, with a warning.
        assertNull(result.getStopReason(), where + ": " + result.getWarnings());
        assertEquals(expected, result.getMatches().size(), where);
        for (LogSearchMatch match : result.getMatches()) {
            int at = (int) match.getMatchOffset();
            assertArrayEquals(needle, Arrays.copyOfRange(content, at, Math.min(content.length, at + needle.length)),
                    where + ": line " + match.getLineNumber() + " match offset " + at);
            LogPage page = fx.service.readPage(fx.idOf("mirth.log"), LogPageAnchor.AT, match.getMatchOffset());
            assertTrue(page.getText().startsWith(query, page.getTargetIndex()),
                    where + ": line " + match.getLineNumber() + " page target " + page.getTargetIndex());
        }
    }

    @Test
    void aMatchAfterBytesThatAreNotValidInTheCharsetHasItsExactOffset() throws Exception {
        byte[][] before = {
            bytes(0x80),                   // a continuation byte with no lead
            bytes(0xC3, 0x28),             // a lead byte followed by ASCII
            bytes(0xE2, 0x82),             // a three-byte character cut short
            bytes(0xF0, 0x9F, 0x98),       // a four-byte character cut short
            bytes(0xFF, 0xFE),             // bytes UTF-8 never uses
            bytes(0xC0, 0xAF),             // an overlong encoding
            bytes(0xED, 0xA0, 0x80),       // a UTF-16 surrogate encoded in UTF-8
            utf8(chars(0xFFFD)),           // a real U+FFFD, which is valid
            utf8(chars(0x1F600)),          // a character outside the BMP, two chars in Java
            bytes(0x80, 0x80, 0xE2, 0x41, 0xF0, 0x9F, 0x98, 0x80, 0xC3),
            // Then multi-byte text: far fewer chars than bytes before the match.
            join(bytes(0x80), utf8(chars(0x20AC).repeat(8))),
        };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] prefix : before) {
            out.write(utf8("INFO "));
            out.write(prefix);
            out.write(utf8(" text MATCH tail\n"));
            // The same bytes right against the match.
            out.write(utf8("INFO "));
            out.write(prefix);
            out.write(utf8("MATCH tail\n"));
        }
        // A line longer than a search piece, with damage before a match in its second piece.
        out.write(utf8("WARN "));
        out.write("x".repeat(LogSearcher.SEGMENT_BYTES + 100).getBytes(StandardCharsets.US_ASCII));
        out.write(bytes(0x80, 0xFF, 0xE2, 0x82));
        out.write(utf8(" MATCH end\n"));
        byte[] content = out.toByteArray();

        LogFixture fx = new LogFixture(dir, StandardCharsets.UTF_8);
        fx.writeActive(content, 1_000_000L);
        assertEveryMatchOffsetIsExact(fx, content, utf8("MATCH"), "MATCH", 2 * before.length + 1, "UTF-8");
    }

    @Test
    void aMatchAfterABrokenShiftJisCharacterHasItsExactOffset() throws Exception {
        assumeTrue(Charset.isSupported("Shift_JIS"), "Shift_JIS is not available in this JDK");
        Charset sjis = Charset.forName("Shift_JIS");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("INFO ".getBytes(sjis));
        out.write(bytes(0x81, 0x20));          // a lead byte followed by a space, which cannot trail it
        out.write((" " + COMMON + " ").getBytes(sjis));
        out.write(bytes(0xA0));                // a byte Shift_JIS does not map
        out.write((" " + WORD + " end\n").getBytes(sjis));
        byte[] content = out.toByteArray();

        LogFixture fx = new LogFixture(dir, sjis);
        fx.writeActive(content, 1_000_000L);
        assertEveryMatchOffsetIsExact(fx, content, WORD.getBytes(sjis), WORD, 1, "Shift_JIS");
    }

    @Test
    void aShiftJisLineLongerThanAPageSplitsOnCharacterBoundariesWhenReadForward() throws Exception {
        Charset charset = Charset.forName("Shift_JIS");
        LogFixture fx = new LogFixture(dir, charset);
        // Every character ends in the byte 0x5C, the Shift_JIS trail byte that is also a backslash.
        String longLine = chars(0x8868).repeat(200_000);
        byte[] content = ("first\n" + longLine + "\nlast\n").getBytes(charset);
        assertTrue(content.length > CAP + 1000);
        fx.writeActive(content, 1_000_000L);
        String id = fx.idOf("mirth.log");

        LogPagerTest.assertCovers(LogFixture.walkForward(fx.service, id), content, charset);
        // Read backward, a page that starts inside a long run of double-byte
        // characters cannot be resynchronized (only UTF-8 can), so it may begin
        // with a replacement character; the bytes covered and the flags are still exact.
        List<LogPage> back = LogFixture.walkBackward(fx.service, id);
        LogPagerTest.assertCovers(back, content, charset, 0, false);
        assertTrue(back.stream().anyMatch(LogPage::isStartsMidLine));
    }

    @Test
    void utf16AndUtf32LogsAreListedAsDownloadOnlyWithANoteAndDownloadByteExact() throws Exception {
        for (String name : new String[] {"UTF-16", "UTF-16LE", "UTF-16BE", "UTF-32"}) {
            Charset charset = Charset.forName(name);
            Path home = Files.createDirectory(dir.resolve(name));
            LogFixture fx = new LogFixture(home, charset);
            byte[] content = "INFO first line\nERROR second line\n".getBytes(charset);
            fx.writeActive(content, 2_000_000L);
            Path zip = fx.writeArchive(1, "mirth.log.1", content, 1_000_000L);

            LogFileList list = fx.service.listFiles();
            assertEquals(2, list.getFiles().size(), name);
            for (LogFileInfo info : list.getFiles()) {
                assertFalse(info.isViewable(), name + " " + info.getName());
                assertNotNull(info.getNote(), name);
                // The note states the fact; the viewers offer the download to those who may.
                assertEquals("Written in " + charset.name() + ", which the viewer cannot page.", info.getNote(), name);
                assertEquals(charset.name(), info.getCharset());
            }
            String active = fx.idOf("mirth.log");
            String archive = fx.idOf("mirth.log.1.zip");

            for (String id : new String[] {active, archive}) {
                assertEquals(LogViewerException.Kind.NOT_VIEWABLE, assertThrows(LogViewerException.class,
                        () -> fx.service.readPage(id, LogPageAnchor.TAIL, null)).getKind(), name);
                assertEquals(LogViewerException.Kind.NOT_VIEWABLE, assertThrows(LogViewerException.class,
                        () -> fx.service.readPage(id, LogPageAnchor.AT, 0L)).getKind(), name);
                assertEquals(LogViewerException.Kind.NOT_VIEWABLE, assertThrows(LogViewerException.class,
                        () -> fx.service.search("ERROR", false, true, id, null, null)).getKind(), name);
            }
            // Searching every file skips them with a warning, never decodes them.
            LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
            assertTrue(all.getMatches().isEmpty(), name);
            assertEquals(0, all.getFilesSearched(), name);
            assertFalse(all.isComplete(), name);
            assertEquals(2, all.getWarnings().stream().filter(w -> w.contains("was not searched")).count(), name);

            try (InputStream in = fx.service.openDownload(active).stream()) {
                assertArrayEquals(content, in.readAllBytes(), name);
            }
            try (InputStream in = fx.service.openDownload(archive).stream()) {
                assertArrayEquals(Files.readAllBytes(zip), in.readAllBytes(), name);
            }
        }
    }

    // ========================
    // 8. Archives
    // ========================

    @Test
    void aGzipArchivePagesAndSearchesWithTheSameLineNumbers() throws Exception {
        PatternLogFixture fx = new PatternLogFixture(dir, "gz", StandardCharsets.UTF_8);
        byte[] content = utf8(LogFixture.mixedLines(9000));
        assertTrue(content.length > 3 * CAP);
        fx.writeActive(utf8("INFO active\n"), 2_000_000L);
        fx.writeGzip(1, content, 1_000_000L);
        LogFileInfo info = fx.info("mirth.log.1.gz");
        assertTrue(info.isCompressed());
        assertTrue(info.isViewable());
        String id = info.getId();

        List<LogPage> back = LogFixture.walkBackward(fx.service, id);
        LogPagerTest.assertCovers(back, content, StandardCharsets.UTF_8);
        LogPagerTest.assertCovers(LogFixture.walkForward(fx.service, id), content, StandardCharsets.UTF_8);
        assertEquals((Long) (long) content.length, back.get(back.size() - 1).getContentLength());

        LogSearchResult result = fx.service.search("PID", false, true, id, null, null);
        assertTrue(result.isComplete());
        assertEquals(9000 / 11, result.getMatches().size());
        for (int m = 0; m < result.getMatches().size(); m++) {
            LogSearchMatch match = result.getMatches().get(m);
            assertEquals(LogFixture.lineEndsBefore(content, match.getLineOffset()) + 1, match.getLineNumber());
            if (m % 37 == 0) { // each page of an archive decompresses it from the start
                LogPage page = fx.service.readPage(id, LogPageAnchor.AT, match.getMatchOffset());
                assertTrue(page.getText().contains("PID"));
            }
        }
        // Newest first across files: the active file, then the gzip archive.
        LogSearchResult both = fx.service.search("INFO active|PID", true, true, null, null, null);
        assertTrue(both.getMatches().get(0).getFileId().startsWith("fout/mirth.log@"));
        assertTrue(both.getMatches().get(1).getFileId().startsWith("fout/mirth.log.1.gz@"));
    }

    @Test
    void anEmptyGzipArchivePagesAsAnEmptyPage() throws Exception {
        PatternLogFixture fx = new PatternLogFixture(dir, "gz", StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO active\n"), 2_000_000L);
        fx.writeGzip(1, new byte[0], 1_000_000L);
        String id = fx.idOf("mirth.log.1.gz");
        for (LogPageAnchor anchor : new LogPageAnchor[] {LogPageAnchor.TAIL, LogPageAnchor.HEAD}) {
            LogPage page = fx.service.readPage(id, anchor, null);
            assertEquals("", page.getText());
            assertTrue(page.isAtEnd());
        }
        LogSearchResult result = fx.service.search("anything", false, true, id, null, null);
        assertTrue(result.isComplete());
        assertTrue(result.getMatches().isEmpty());
    }

    @Test
    void unsupportedCompressionIsListedAsDownloadOnlyAndDownloadsByteExact() throws Exception {
        for (String extension : new String[] {"bz2", "xz", "zst", "deflate", "7z", "pack200"}) {
            PatternLogFixture fx = new PatternLogFixture(Files.createDirectory(dir.resolve(extension)), extension,
                    StandardCharsets.UTF_8);
            fx.writeActive(utf8("INFO active\nERROR in active\n"), 2_000_000L);
            // Larger than a download block, and arbitrary bytes (a real bzip2 header first).
            byte[] bytes = new byte[3 * OpenLogFile.DOWNLOAD_BLOCK + 123];
            new Random(7).nextBytes(bytes);
            System.arraycopy(utf8("BZh9"), 0, bytes, 0, 4);
            fx.writeRaw(1, bytes, 1_000_000L);
            String name = "mirth.log.1." + extension;

            LogFileInfo info = fx.info(name);
            assertTrue(info.isCompressed(), extension);
            assertFalse(info.isViewable(), extension);
            assertEquals("Compressed in a format the viewer cannot read.", info.getNote(), extension);
            assertEquals(bytes.length, info.getSize(), extension);
            String id = info.getId();

            assertEquals(LogViewerException.Kind.NOT_VIEWABLE, assertThrows(LogViewerException.class,
                    () -> fx.service.readPage(id, LogPageAnchor.TAIL, null)).getKind(), extension);
            assertEquals(LogViewerException.Kind.NOT_VIEWABLE, assertThrows(LogViewerException.class,
                    () -> fx.service.search("ERROR", false, true, id, null, null)).getKind(), extension);
            LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
            assertEquals(1, all.getMatches().size(), extension + ": the viewable file is still searched");
            assertTrue(all.getWarnings().stream().anyMatch(w -> w.contains(name + " was not searched")), extension);
            assertFalse(all.isComplete(), extension);

            try (InputStream in = fx.service.openDownload(id).stream()) {
                assertArrayEquals(bytes, in.readAllBytes(), extension);
            }
        }
    }

    private static byte[] zipOf(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Pseudo-random lines that do not compress to nothing, with an ERROR every tenth line. */
    private static byte[] randomLines(int lines) {
        Random random = new Random(11);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(i % 10 == 0 ? "ERROR " : "INFO ");
            for (int k = 0; k < 8; k++) {
                sb.append(Long.toHexString(random.nextLong()));
            }
            sb.append('\n');
        }
        return utf8(sb.toString());
    }

    /**
     * Pages and searches {@code id}: each operation must work or fail with the
     * exceptions the servlet translates. Returns what went wrong, or null.
     */
    private static String uncleanOutcome(LogViewerService service, String id, String where) {
        return assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (Callable<Object> op : List.<Callable<Object>>of(
                    () -> service.readPage(id, LogPageAnchor.TAIL, null),
                    () -> service.readPage(id, LogPageAnchor.HEAD, null),
                    () -> service.readPage(id, LogPageAnchor.AT, 100L),
                    () -> service.search("ERROR", false, true, id, null, null))) {
                try {
                    op.call();
                } catch (IOException | LogViewerException expected) {
                    // a clean refusal
                } catch (Throwable other) {
                    return where + ": " + other;
                }
            }
            return null;
        });
    }

    @Test
    void zipsThatAreGarbageEmptyOrHaveNoLogEntryGiveACleanErrorAndASearchWarning() throws Exception {
        byte[] eocd = new byte[22]; // a valid zip with no entries: just the end-of-central-directory record
        eocd[0] = 'P';
        eocd[1] = 'K';
        eocd[2] = 5;
        eocd[3] = 6;
        byte[] garbage = new byte[5000];
        new Random(3).nextBytes(garbage);
        ByteArrayOutputStream directoryOnly = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(directoryOnly)) {
            zos.putNextEntry(new ZipEntry("logs/"));
            zos.closeEntry();
        }
        List<byte[]> variants = List.of(garbage, new byte[0], eocd, directoryOnly.toByteArray());
        List<String> names = List.of("random bytes", "empty file", "zip with no entries", "zip holding only a directory");

        for (int v = 0; v < variants.size(); v++) {
            PatternLogFixture fx = new PatternLogFixture(Files.createDirectory(dir.resolve("v" + v)), "zip",
                    StandardCharsets.UTF_8);
            fx.writeActive(utf8("INFO active\nERROR in active\n"), 2_000_000L);
            fx.writeRaw(1, variants.get(v), 1_000_000L);
            String where = names.get(v);
            String id = fx.idOf("mirth.log.1.zip");

            for (LogPageAnchor anchor : new LogPageAnchor[] {LogPageAnchor.TAIL, LogPageAnchor.HEAD}) {
                IOException e = assertThrows(IOException.class, () -> fx.service.readPage(id, anchor, null),
                        where + " " + anchor);
                assertNotNull(e.getMessage(), where);
            }
            LogSearchResult one = fx.service.search("ERROR", false, true, id, null, null);
            assertTrue(one.getMatches().isEmpty(), where);
            assertTrue(one.getWarnings().stream().anyMatch(w -> w.contains("could not be searched completely")), where);
            assertFalse(one.isComplete(), where);
            // Over every file, the bad archive is a warning and the good file is still searched.
            LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
            assertEquals(1, all.getMatches().size(), where);
            assertEquals("ERROR in active", all.getMatches().get(0).getLineText(), where);
            assertTrue(all.getWarnings().stream().anyMatch(w -> w.contains("mirth.log.1.zip could not be searched completely")),
                    where + ": " + all.getWarnings());
            assertFalse(all.isComplete(), where);
            // The raw bytes are still downloadable.
            try (InputStream in = fx.service.openDownload(id).stream()) {
                assertArrayEquals(variants.get(v), in.readAllBytes(), where);
            }
        }
    }

    @Test
    void aTruncatedZipKeepsTheMatchesBeforeTheCutAndWarns() throws Exception {
        PatternLogFixture fx = new PatternLogFixture(dir, "zip", StandardCharsets.UTF_8);
        byte[] content = randomLines(6000);
        byte[] zip = zipOf("mirth.log.1", content);
        assertTrue(zip.length > 100_000, "length " + zip.length);
        fx.writeActive(utf8("INFO active\nERROR in active\n"), 2_000_000L);
        fx.writeRaw(1, Arrays.copyOf(zip, zip.length / 2), 1_000_000L);
        String id = fx.idOf("mirth.log.1.zip");

        // The last page needs the whole stream, which is cut.
        assertThrows(IOException.class, () -> fx.service.readPage(id, LogPageAnchor.TAIL, null));
        LogSearchResult one = fx.service.search("ERROR", false, true, id, null, null);
        assertTrue(one.getMatches().size() > 0, "matches before the cut are kept");
        assertFalse(one.isComplete());
        assertTrue(one.getWarnings().stream().anyMatch(w -> w.contains("could not be searched completely")));

        LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
        assertTrue(all.getMatches().size() > 1, "the active match and some of the archive's");
        assertTrue(all.getWarnings().stream().anyMatch(w -> w.contains("mirth.log.1.zip could not be searched completely")),
                all.getWarnings().toString());
        assertFalse(all.isComplete());
        assertTrue(all.getMatches().stream().skip(1).allMatch(m -> m.getLineText().startsWith("ERROR ")));
        assertEquals(1, all.getFilesSearched(), "the damaged archive is not counted as searched");
    }

    @Test
    void everyTruncationAndEveryDamagedByteOfAZipOrGzipEndsCleanly() throws Exception {
        byte[] content = utf8(LogFixture.mixedLines(25));
        byte[] zip = zipOf("mirth.log.1", content);
        PatternLogFixture zipFx = new PatternLogFixture(Files.createDirectory(dir.resolve("zip")), "zip",
                StandardCharsets.UTF_8);
        PatternLogFixture gzFx = new PatternLogFixture(Files.createDirectory(dir.resolve("gz")), "gz",
                StandardCharsets.UTF_8);
        gzFx.writeGzip(1, content, 1_000_000L);
        byte[] gz = Files.readAllBytes(gzFx.dir.resolve("mirth.log.1.gz"));
        zipFx.writeActive(utf8("INFO active\n"), 2_000_000L);
        gzFx.writeActive(utf8("INFO active\n"), 2_000_000L);

        List<String> problems = new ArrayList<>();
        for (int cut = 0; cut < zip.length; cut++) {
            zipFx.writeRaw(1, Arrays.copyOf(zip, cut), 1_000_000L);
            addIfNotNull(problems, uncleanOutcome(zipFx.service, zipFx.idOf("mirth.log.1.zip"), "zip cut at " + cut));
        }
        for (int cut = 0; cut < gz.length; cut++) {
            gzFx.writeRaw(1, Arrays.copyOf(gz, cut), 1_000_000L);
            addIfNotNull(problems, uncleanOutcome(gzFx.service, gzFx.idOf("mirth.log.1.gz"), "gzip cut at " + cut));
        }
        for (int at = 0; at < zip.length; at++) {
            byte[] damaged = zip.clone();
            damaged[at] ^= (byte) 0xFF;
            zipFx.writeRaw(1, damaged, 1_000_000L);
            addIfNotNull(problems, uncleanOutcome(zipFx.service, zipFx.idOf("mirth.log.1.zip"), "zip byte " + at + " damaged"));
        }
        for (int at = 0; at < gz.length; at++) {
            byte[] damaged = gz.clone();
            damaged[at] ^= (byte) 0xFF;
            gzFx.writeRaw(1, damaged, 1_000_000L);
            addIfNotNull(problems, uncleanOutcome(gzFx.service, gzFx.idOf("mirth.log.1.gz"), "gzip byte " + at + " damaged"));
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void zipWhoseEntryNameIsNotValidUtf8GivesACleanError() throws Exception {
        PatternLogFixture fx = new PatternLogFixture(dir, "zip", StandardCharsets.UTF_8);
        byte[] zip = zipOf("mirth.log.1", utf8("ERROR in the archive\n"));
        assertTrue((zip[7] & 0x08) != 0, "java.util.zip marks the name as UTF-8");
        zip[30] = (byte) 0xC3; // a UTF-8 lead byte followed by an ASCII letter
        fx.writeActive(utf8("INFO active\nERROR in active\n"), 2_000_000L);
        fx.writeRaw(1, zip, 1_000_000L);
        String id = fx.idOf("mirth.log.1.zip");

        assertThrows(IOException.class, () -> fx.service.readPage(id, LogPageAnchor.TAIL, null));
        // Over every file, the damaged archive must be a warning and the active file still searched.
        LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
        assertEquals(1, all.getMatches().size());
        assertTrue(all.getWarnings().stream().anyMatch(w -> w.contains("mirth.log.1.zip could not be searched completely")));
    }

    private static void addIfNotNull(List<String> problems, String problem) {
        if (problem != null) {
            problems.add(problem);
        }
    }

    @Test
    void aCorruptGzipGivesACleanErrorAndASearchWarning() throws Exception {
        PatternLogFixture fx = new PatternLogFixture(dir, "gz", StandardCharsets.UTF_8);
        fx.writeActive(utf8("INFO active\nERROR in active\n"), 2_000_000L);
        fx.writeRaw(1, utf8("this is not gzip data at all"), 1_000_000L);
        String id = fx.idOf("mirth.log.1.gz");

        assertThrows(IOException.class, () -> fx.service.readPage(id, LogPageAnchor.TAIL, null));
        LogSearchResult all = fx.service.search("ERROR", false, true, null, null, null);
        assertEquals(1, all.getMatches().size());
        assertTrue(all.getWarnings().stream().anyMatch(w -> w.contains("mirth.log.1.gz could not be searched completely")));
        try (InputStream in = fx.service.openDownload(id).stream()) {
            assertArrayEquals(utf8("this is not gzip data at all"), in.readAllBytes());
        }
    }
}
