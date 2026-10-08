// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Streams a download into a part file of its own. It never closes the input: the
 * caller does that on a background thread, because closing the client's stream
 * early drains the rest of the body before it returns (measured on 4.6.0, 17
 * seconds for a 1 GiB file closed after 1 MB), and nothing the user waits on
 * should sit behind that.
 *
 * <p>The part file is this plugin's own, so it is removed here on every way
 * out except success: cancel, a read error and a write error. Renaming it to
 * the name the user chose is the caller's step.</p>
 */
public final class LogDownloadCopier {

    private static final int BUFFER_SIZE = 64 * 1024;

    /** How many names {@link #createPart} tries before it gives up. */
    private static final int PART_NAMES = 1000;

    private LogDownloadCopier() {
    }

    /**
     * Reading the download failed part way: the body stopped before its Content-Length (the
     * file was cut while it was sent, or the connection dropped). A failure writing the part
     * file is a plain IOException.
     */
    public static final class CutShort extends IOException {
        private static final long serialVersionUID = 1L;

        CutShort(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    /**
     * Creates an empty part file beside {@code target}: "&lt;name&gt;.part", or, when a file of that
     * name is already there, "&lt;name&gt;.1.part", "&lt;name&gt;.2.part" and so on. A file that is
     * already there is never opened, so another download's part file, or anything else, is left alone.
     */
    public static Path createPart(Path target) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        String name = target.getFileName().toString();
        for (int i = 0; i < PART_NAMES; i++) {
            Path part = folder.resolve(i == 0 ? name + ".part" : name + "." + i + ".part");
            try {
                return Files.createFile(part);
            } catch (FileAlreadyExistsException e) {
                // Taken: try the next name.
            }
        }
        throw new IOException("Could not create a part file beside " + target + ".");
    }

    /**
     * @param cancelled checked before each read; true stops the copy
     * @param progress told the total bytes written so far after each chunk
     * @return true when the whole stream was copied, false when cancelled (the part file is gone)
     * @throws CutShort when reading the download fails (the part file is gone)
     * @throws IOException when writing the part file fails (the part file is gone)
     */
    public static boolean copy(InputStream in, Path part, BooleanSupplier cancelled,
            LongConsumer progress) throws IOException {
        boolean complete = false;
        try (OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long total = 0;
            int n = 0;
            while (!cancelled.getAsBoolean() && (n = read(in, buffer)) != -1) {
                out.write(buffer, 0, n);
                total += n;
                progress.accept(total);
            }
            complete = n == -1;
        } finally {
            if (!complete) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) {
                    // Nothing more can be done, and it must not hide the failure being thrown.
                }
            }
        }
        return complete;
    }

    private static int read(InputStream in, byte[] buffer) throws CutShort {
        try {
            return in.read(buffer);
        } catch (IOException e) {
            throw new CutShort(e);
        }
    }
}
