// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import com.diridium.logviewer.LogFileCatalog.Format;
import com.diridium.logviewer.LogFileCatalog.LogFile;

/**
 * A discovered log file, opened for one request, with its fingerprint.
 *
 * <p><b>Opening.</b> Always through NIO ({@code Files.newByteChannel}), never
 * {@code FileInputStream}, {@code RandomAccessFile} or {@code ZipFile} (which
 * opens with {@code RandomAccessFile}). ASSUMPTION, not verified here: on
 * Windows the JDK's NIO opens files with {@code FILE_SHARE_DELETE}, the
 * {@code java.io} classes without it, so only an NIO handle lets log4j rename
 * the file while it is being read. That matters for archives as much as for
 * mirth.log, since every rollover renames every archive. When log4j cannot
 * rename mirth.log it falls back to copy-and-truncate, which the checks below
 * detect. {@code NOFOLLOW_LINKS} refuses a file swapped for a symbolic link
 * between discovery and open.</p>
 *
 * <p><b>Fingerprint.</b> What makes a file id stale-detectable, without
 * relying on an inode ({@code fileKey()} is null on Windows; it is mixed in
 * where the platform has one, as extra protection only):</p>
 * <ul>
 * <li>An archive never changes once written, and a rename keeps its
 * modification time (measured on 4.6.0), so its fingerprint is its size,
 * modification time and first 1 KiB. After a rollover the name
 * {@code mirth.log.3.zip} holds what was {@code mirth.log.4.zip}, which
 * differs in all three.</li>
 * <li>The active file grows constantly, so its size cannot be part of it.
 * Rollover recreates it under the same name, starting with a new first line
 * (which carries a millisecond timestamp in the engine's layout), so its
 * fingerprint is its first line, up to 1 KiB.</li>
 * </ul>
 *
 * <p>The fingerprint is computed from the open channel's bytes but the
 * modification time and file key come from the path, so a rename racing the
 * open produces a mismatched fingerprint: it fails closed, as "rotated",
 * never as another file's content.</p>
 */
final class OpenLogFile implements Closeable {

    /** Bytes of the file's start that go into the fingerprint. Stable once written, one small read. */
    static final int HEAD_BYTES = 1024;

    /** Read buffer size for streaming. */
    static final int IO_BUFFER = 64 * 1024;

    /**
     * A download reads the file in blocks of this size and, for the active
     * file, checks each block before handing any of it on: one fstat and one
     * read of at most 1 KiB per 64 KiB sent, under 2% extra I/O, all from the
     * page cache. Checking less often would let bytes of a truncated-and-
     * rewritten file out before the check caught it.
     */
    static final int DOWNLOAD_BLOCK = 64 * 1024;

    private final LogFile file;
    private final SeekableByteChannel channel;
    private final long length;
    private final byte[] head;
    private final String id;

    private OpenLogFile(LogFile file, SeekableByteChannel channel, long length, byte[] head, String id) {
        this.file = file;
        this.channel = channel;
        this.length = length;
        this.head = head;
        this.id = id;
    }

    static OpenLogFile open(LogFile file) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(file.path(),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long length = channel.size();
            byte[] head = readHead(channel, file.active());
            BasicFileAttributes attrs = Files.readAttributes(file.path(), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) {
                throw new IOException(file.name() + " is not a regular file");
            }
            return new OpenLogFile(file, channel, length, head,
                    file.key() + "@" + fingerprint(file, attrs, length, head));
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    LogFile file() {
        return file;
    }

    String id() {
        return id;
    }

    /** Length on disk when the file was opened. For the active file, reads never go past it. */
    long length() {
        return length;
    }

    boolean compressed() {
        return file.format() != Format.PLAIN;
    }

    private static byte[] readHead(SeekableByteChannel channel, boolean active) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(channel.size(), HEAD_BYTES));
        channel.position(0);
        while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
            // read until full or end of file
        }
        byte[] bytes = Arrays.copyOf(buffer.array(), buffer.position());
        if (active) {
            int newline = LogText.indexOfNewline(bytes, 0, bytes.length);
            if (newline >= 0) {
                bytes = Arrays.copyOf(bytes, newline + 1);
            }
        }
        return bytes;
    }

    private static String fingerprint(LogFile file, BasicFileAttributes attrs, long length, byte[] head) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
        digest.update((byte) (file.active() ? 'A' : 'R'));
        if (attrs.fileKey() != null) {
            digest.update(attrs.fileKey().toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!file.active()) {
            digest.update(ByteBuffer.allocate(16).putLong(length)
                    .putLong(attrs.lastModifiedTime().toMillis()).array());
        }
        digest.update(head);
        // 48 bits: an id is only ever compared against the one file that now
        // has the same name, so this is ample, and it keeps ids short in the audit log.
        return HexFormat.of().formatHex(digest.digest(), 0, 6);
    }

    /**
     * Confirms, after a read of an uncompressed file, that the bytes read
     * still belong to the file that was opened: it is at least as long as the
     * read reached and still starts the same way. Uses only the open channel,
     * not the path, because a normal POSIX rollover renames the file under an
     * open handle and the handle keeps reading the right bytes.
     */
    void requireUnchanged(long readEnd) throws IOException, LogViewerException {
        if (!isUnchanged(readEnd)) {
            throw new LogViewerException(LogViewerException.Kind.STALE,
                    file.name() + " was rotated while it was being read. Reload the file list.");
        }
    }

    private boolean isUnchanged(long readEnd) throws IOException {
        return channel.size() >= readEnd && Arrays.equals(readHead(channel, file.active()), head);
    }

    /**
     * Reads {@code count} bytes of an uncompressed file from {@code from}. The
     * caller keeps {@code from + count} within {@link #length()}, so running
     * out early means the file was truncated under us.
     */
    byte[] readPlain(long from, int count) throws IOException, LogViewerException {
        ByteBuffer buffer = ByteBuffer.allocate(count);
        channel.position(from);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                throw new LogViewerException(LogViewerException.Kind.STALE,
                        file.name() + " became shorter while it was being read. Reload the file list.");
            }
        }
        return buffer.array();
    }

    /**
     * Counts the line ends that finish before {@code end} in an uncompressed
     * file (see {@link LogText#isLineEnd}). Reads the byte at {@code end} as
     * well, when there is one, because a CR just before {@code end} is a line
     * end only if that byte is not its LF.
     */
    long countLineEndsPlain(long end) throws IOException, LogViewerException {
        byte[] bytes = new byte[IO_BUFFER];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        LogText.LineEndCounter counter = new LogText.LineEndCounter();
        long position = 0;
        channel.position(0);
        while (position < end) {
            buffer.clear();
            buffer.limit((int) Math.min(IO_BUFFER, end - position));
            int n = channel.read(buffer);
            if (n < 0) {
                throw new LogViewerException(LogViewerException.Kind.STALE,
                        file.name() + " became shorter while it was being read. Reload the file list.");
            }
            counter.feed(bytes, 0, n);
            position += n;
        }
        int next = -1;
        if (end < length) {
            ByteBuffer one = ByteBuffer.allocate(1);
            channel.position(end);
            if (channel.read(one) == 1) {
                next = one.get(0) & 0xFF;
            }
        }
        return counter.countBefore(next);
    }

    /** Line ends before a point, and lines in the whole file. */
    record LineCounts(long endsBefore, long lines) {
    }

    /**
     * One pass over the whole of an uncompressed file, to {@link #length()}:
     * the line ends that finish before {@code before} (as
     * {@link #countLineEndsPlain} counts them) and the number of lines in the
     * file, a last line without its terminator included.
     */
    LineCounts countLinesPlain(long before) throws IOException, LogViewerException {
        byte[] bytes = new byte[IO_BUFFER];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        LogText.LineEndCounter counter = new LogText.LineEndCounter();
        Long endsBefore = null;
        int lastByte = -1;
        long position = 0;
        channel.position(0);
        while (position < length) {
            // Stop at before first, so the next read starts with the byte after it.
            long stop = position < before ? before : length;
            buffer.clear();
            buffer.limit((int) Math.min(IO_BUFFER, stop - position));
            int n = channel.read(buffer);
            if (n < 0) {
                throw new LogViewerException(LogViewerException.Kind.STALE,
                        file.name() + " became shorter while it was being read. Reload the file list.");
            }
            if (endsBefore == null && position == before && n > 0) {
                endsBefore = counter.countBefore(bytes[0]);
            }
            counter.feed(bytes, 0, n);
            if (n > 0) {
                lastByte = bytes[n - 1];
            }
            position += n;
        }
        long allLineEnds = counter.countBefore(-1);
        return new LineCounts(endsBefore != null ? endsBefore : allLineEnds,
                LogText.lineCount(allLineEnds, lastByte, length));
    }

    /**
     * The file's content from its first byte: decompressed for an archive,
     * limited to {@link #length()} for an uncompressed file. Closing the
     * stream releases the inflater but leaves this file open.
     *
     * <p>A zip is read with {@code ZipInputStream} over the NIO channel rather
     * than {@code ZipFile}, for the file-sharing reason above. log4j writes a
     * single deflated entry whose name does not track the archive's current
     * name (measured), so the first entry is read whatever it is called.</p>
     */
    InputStream content() throws IOException {
        InputStream raw = new BufferedInputStream(new ChannelStream(), IO_BUFFER);
        switch (file.format()) {
            case PLAIN:
                return raw;
            case GZIP:
                return new GZIPInputStream(raw, IO_BUFFER);
            case ZIP:
                // Entry names are ignored, but java.util.zip still decodes them.
                // ISO-8859-1 decodes any bytes for names without the UTF-8 flag;
                // a name WITH the flag (general purpose bit 11, which
                // java.util.zip and so log4j set on every entry) is decoded as
                // UTF-8 whatever charset is passed, and damaged bytes there throw
                // IllegalArgumentException. Report that as the damaged archive it
                // is, so a page request gets a clean error and a search of all
                // files skips this one with a warning instead of failing.
                ZipInputStream zip = new ZipInputStream(raw, StandardCharsets.ISO_8859_1);
                ZipEntry entry;
                try {
                    entry = zip.getNextEntry();
                } catch (IllegalArgumentException e) {
                    zip.close();
                    ZipException damaged = new ZipException(file.name() + " has a damaged entry name");
                    damaged.initCause(e);
                    throw damaged;
                }
                if (entry == null || entry.isDirectory()) {
                    zip.close();
                    throw new ZipException(file.name() + " holds no log entry");
                }
                return zip;
            default:
                throw new IOException(file.name() + " is compressed in a format the viewer cannot read");
        }
    }

    /**
     * The file's raw bytes for download, from 0 to {@link #length()}: an
     * archive as its zip or gzip bytes, the active file up to its length when
     * opened, so the download ends even though the file keeps growing.
     * Closing the stream closes this file.
     *
     * <p>For the active file, every block is checked against the file's size
     * and first line after it is read and before any of it is handed on, and
     * a mismatch aborts the transfer with an IOException rather than sending
     * bytes of a different file. A plain rename, the normal rollover, passes
     * the check: the open handle follows the renamed file and keeps reading
     * the same bytes. Truncation (log4j's copy-and-truncate fallback) fails
     * it, whether or not the file has grown back past the read position.</p>
     */
    InputStream download() {
        return new InputStream() {
            private final ByteBuffer block = ByteBuffer.allocate(DOWNLOAD_BLOCK).limit(0);
            private long position;
            /**
             * Set once the file is found changed. Every later read throws it
             * again: the position may already be at the end, and a reader
             * that caught the first error must not then see a clean end of
             * stream and take the download for complete.
             */
            private IOException failure;

            @Override
            public int read() throws IOException {
                return fill() ? block.get() & 0xFF : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int n = Math.min(len, block.remaining());
                block.get(b, off, n);
                return n;
            }

            /** Makes sure the block has unread, verified bytes; false at the end. */
            private boolean fill() throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (block.hasRemaining()) {
                    return true;
                }
                if (position >= length) {
                    return false;
                }
                block.clear().limit((int) Math.min(DOWNLOAD_BLOCK, length - position));
                channel.position(position);
                while (block.hasRemaining()) {
                    if (channel.read(block) < 0) {
                        block.limit(0);
                        throw failure = rotatedDuringDownload();
                    }
                }
                block.flip();
                position += block.remaining();
                if (file.active() && !isUnchanged(position)) {
                    block.limit(0);
                    throw failure = rotatedDuringDownload();
                }
                return true;
            }

            @Override
            public void close() throws IOException {
                OpenLogFile.this.close();
            }
        };
    }

    private IOException rotatedDuringDownload() {
        return new IOException(file.name()
                + " was truncated while it was being downloaded (log rotation). Download it again.");
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    /** Sequential reads of {@code [0, length)} of the channel; closing it does not close the channel. */
    private final class ChannelStream extends InputStream {
        private long position;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (position >= length) {
                return -1;
            }
            channel.position(position);
            int n = channel.read(ByteBuffer.wrap(b, off, (int) Math.min(len, length - position)));
            if (n > 0) {
                position += n;
            }
            return n;
        }
    }
}
