// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogDownloadCopierTest {

    @TempDir
    Path dir;

    private static byte[] data(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31);
        }
        return bytes;
    }

    @Test
    void copiesTheWholeStreamAndReportsProgress() throws IOException {
        byte[] content = data(200_000);
        Path part = dir.resolve("mirth.log.part");
        List<Long> progress = new ArrayList<>();

        boolean complete = LogDownloadCopier.copy(new ByteArrayInputStream(content), part, () -> false, progress::add);

        assertTrue(complete);
        assertArrayEquals(content, Files.readAllBytes(part));
        assertFalse(progress.isEmpty());
        assertEquals(200_000L, progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i) > progress.get(i - 1), "progress must only go up");
        }
    }

    @Test
    void cancellingStopsTheCopyAndRemovesThePartFile() throws IOException {
        Path part = dir.resolve("mirth.log.part");
        AtomicBoolean cancelled = new AtomicBoolean();

        boolean complete = LogDownloadCopier.copy(new ByteArrayInputStream(data(1_000_000)), part,
                cancelled::get, written -> cancelled.set(true));

        assertFalse(complete);
        assertFalse(Files.exists(part), "the plugin's own part file must not be left behind");
    }

    @Test
    void cancellingBeforeTheFirstReadLeavesNothing() throws IOException {
        Path part = dir.resolve("mirth.log.part");
        boolean complete = LogDownloadCopier.copy(new ByteArrayInputStream(data(10)), part, () -> true, w -> { });
        assertFalse(complete);
        assertFalse(Files.exists(part));
    }

    @Test
    void aReadFailureRemovesThePartFileAndStillThrows() {
        Path part = dir.resolve("mirth.log.part");
        InputStream failing = new InputStream() {
            private int served;

            @Override
            public int read() throws IOException {
                return read(new byte[1], 0, 1);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (served > 0) {
                    throw new IOException("connection reset");
                }
                served = Math.min(len, 1000);
                return served;
            }
        };

        IOException e = assertThrows(IOException.class,
                () -> LogDownloadCopier.copy(failing, part, () -> false, w -> { }));

        assertEquals("connection reset", e.getMessage());
        assertFalse(Files.exists(part));
    }

    @Test
    void doesNotCloseTheInput() throws IOException {
        // Closing is the caller's job, on a background thread: closing a client stream early drains it.
        AtomicBoolean closed = new AtomicBoolean();
        InputStream in = new ByteArrayInputStream(data(10)) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        LogDownloadCopier.copy(in, dir.resolve("a.part"), () -> false, w -> { });
        assertFalse(closed.get());
    }

    @Test
    void thePartFileIsTheTargetsNameWithPartAfterIt() throws IOException {
        Path part = LogDownloadCopier.createPart(dir.resolve("mirth.log.5.zip"));
        assertEquals(dir.resolve("mirth.log.5.zip.part"), part);
        assertTrue(Files.exists(part));
        assertEquals(0, Files.size(part));
    }

    @Test
    void aPartFileAlreadyThereIsLeftAloneAndTheNextNameIsTaken() throws IOException {
        Path theirs = dir.resolve("mirth.log.part");
        Files.write(theirs, data(30));
        Files.write(dir.resolve("mirth.log.1.part"), data(5));

        Path part = LogDownloadCopier.createPart(dir.resolve("mirth.log"));

        assertEquals(dir.resolve("mirth.log.2.part"), part);
        assertArrayEquals(data(30), Files.readAllBytes(theirs));
        assertArrayEquals(data(5), Files.readAllBytes(dir.resolve("mirth.log.1.part")));
    }
}
