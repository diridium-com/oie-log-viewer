// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

/**
 * Like {@link LogFixture}, but the archive extension is a parameter, so a
 * test can lay out {@code mirth.log.%i.gz} or {@code mirth.log.%i.bz2}
 * archives, and an archive's bytes are written as given (a test can plant
 * garbage, a truncated zip or a format the viewer cannot read).
 */
final class PatternLogFixture {

    final Path dir;
    final Path active;
    final String extension;
    final Charset charset;
    final AtomicLong tick = new AtomicLong();
    private final AtomicLong now = new AtomicLong();
    final LogViewerService service;

    PatternLogFixture(Path dir, String extension, Charset charset) {
        this.dir = dir;
        this.active = dir.resolve("mirth.log");
        this.extension = extension;
        this.charset = charset;
        LogAppenderSource source = () -> List.of(new LogAppenderSource.Appender("fout",
                active.toString(), dir.resolve("mirth.log.%i." + extension).toString(), charset));
        this.service = new LogViewerService(source, () -> now.addAndGet(tick.get()));
    }

    void writeActive(byte[] content, long mtimeMillis) throws IOException {
        Files.write(active, content);
        Files.setLastModifiedTime(active, FileTime.fromMillis(mtimeMillis));
    }

    /** An archive holding exactly these bytes, whatever they are. */
    Path writeRaw(int index, byte[] bytes, long mtimeMillis) throws IOException {
        Path path = dir.resolve("mirth.log." + index + "." + extension);
        Files.write(path, bytes);
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    /** A gzip archive, the way log4j's GzCompressAction writes one. */
    Path writeGzip(int index, byte[] content, long mtimeMillis) throws IOException {
        Path path = dir.resolve("mirth.log." + index + "." + extension);
        try (OutputStream out = Files.newOutputStream(path); GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(content);
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeMillis));
        return path;
    }

    LogFileInfo info(String name) {
        for (LogFileInfo info : service.listFiles().getFiles()) {
            if (info.getName().equals(name)) {
                return info;
            }
        }
        throw new AssertionError(name + " not listed");
    }

    String idOf(String name) {
        return info(name).getId();
    }
}
