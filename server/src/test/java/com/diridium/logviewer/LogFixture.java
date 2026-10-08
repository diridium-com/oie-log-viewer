// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A log directory laid out the way the engine's shipped configuration writes
 * it ({@code mirth.log} plus {@code mirth.log.%i.zip}), with a service that
 * discovers it through a stub appender instead of a LoggerContext.
 */
final class LogFixture {

    final Path dir;
    final Path active;
    final Charset charset;
    /** Nanoseconds the clock advances each time it is read; 0 keeps the deadline from ever passing. */
    final AtomicLong tick = new AtomicLong();
    private final AtomicLong now = new AtomicLong();
    final LogViewerService service;

    LogFixture(Path dir, Charset charset) {
        this.dir = dir;
        this.active = dir.resolve("mirth.log");
        this.charset = charset;
        LogAppenderSource source = () -> List.of(new LogAppenderSource.Appender("fout",
                active.toString(), dir.resolve("mirth.log.%i.zip").toString(), charset));
        this.service = new LogViewerService(source, () -> now.addAndGet(tick.get()));
    }

    void writeActive(byte[] content, long mtimeMillis) throws IOException {
        Files.write(active, content);
        Files.setLastModifiedTime(active, FileTime.fromMillis(mtimeMillis));
    }

    /**
     * Writes an archive the way log4j's ZipCompressAction does: one deflated
     * entry, named after the uncompressed file at the time it was compressed,
     * which need not match the archive's current name.
     */
    Path writeArchive(int index, String entryName, byte[] content, long mtimeMillis) throws IOException {
        Path zip = dir.resolve("mirth.log." + index + ".zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        Files.setLastModifiedTime(zip, FileTime.fromMillis(mtimeMillis));
        return zip;
    }

    String idOf(String name) {
        for (LogFileInfo info : service.listFiles().getFiles()) {
            if (info.getName().equals(name)) {
                return info.getId();
            }
        }
        throw new AssertionError(name + " not listed");
    }

    /** Numbered lines of assorted lengths, with multi-byte characters. */
    static String lines(int count, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(prefix).append(" line ").append(i).append(' ');
            sb.append("x".repeat((i * 37) % 300));
            if (i % 7 == 0) {
                sb.append(" café € 😀");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Lines ended every way a log file ends them: LF (Unix engine), CRLF
     * (Windows engine), a lone CR, and the {@code \r\r\n} a Windows engine
     * writes after a logged message that itself ends in CR. Every 11th line
     * carries an HL7 message whose segments are separated by lone CRs.
     */
    static String mixedLines(int count) {
        String[] endings = {"\n", "\r\n", "\r", "\r\r\n"};
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append("line ").append(i).append(' ').append("x".repeat((i * 53) % 400));
            if (i % 11 == 0) {
                sb.append(" MSH|^~\\&|A\rPID|1||").append(i).append("\rOBX|1|TX|café €");
            }
            sb.append(endings[(i * 7) % endings.length]);
        }
        return sb.toString();
    }

    /**
     * Test oracle, deliberately naive: whether {@code content[i]} ends a line
     * (LF, or a CR not followed by LF), judged against the whole content.
     */
    static boolean endsLine(byte[] content, int i) {
        return content[i] == '\n'
                || (content[i] == '\r' && (i + 1 == content.length || content[i + 1] != '\n'));
    }

    /** Test oracle: line ends that finish before {@code offset}, by a scan of the whole content. */
    static long lineEndsBefore(byte[] content, long offset) {
        long count = 0;
        for (int i = 0; i < offset; i++) {
            if (endsLine(content, i)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Test oracle: lines in {@code content[from, to)}, a last line without its
     * terminator included.
     */
    static long lineCount(byte[] content, int from, int to) {
        long count = lineEndsBefore(content, to) - lineEndsBefore(content, from);
        if (to > from && !endsLine(content, to - 1)) {
            count++;
        }
        return count;
    }

    static List<LogPage> walkBackward(LogViewerService service, String id) throws Exception {
        List<LogPage> pages = new ArrayList<>();
        LogPage page = service.readPage(id, LogPageAnchor.TAIL, null);
        pages.add(page);
        while (page.getStartOffset() > 0) {
            page = service.readPage(id, LogPageAnchor.BEFORE, page.getStartOffset());
            pages.add(0, page);
        }
        return pages;
    }

    static List<LogPage> walkForward(LogViewerService service, String id) throws Exception {
        List<LogPage> pages = new ArrayList<>();
        LogPage page = service.readPage(id, LogPageAnchor.HEAD, null);
        pages.add(page);
        while (!page.isAtEnd()) {
            page = service.readPage(id, LogPageAnchor.AFTER, page.getEndOffset());
            pages.add(page);
        }
        return pages;
    }
}
