// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * Cuts one page out of a log file.
 *
 * <p>Every request reads one block of bytes around the requested position
 * and finds the page inside it. An uncompressed file is read at the position
 * directly. An archive cannot be seeked, so it is decompressed from its start
 * up to the block, counting newlines on the way, which is why an archive page
 * always has a line number and costs time proportional to its position.</p>
 *
 * <p>Page boundaries fall just after a line end (LF, CRLF or a lone CR; see
 * {@link LogText#isLineEnd}), so a multi-byte character is never split and a
 * CRLF is never cut in two. The one exception is a line longer than a page,
 * which is split at a character boundary (never between a CR and its LF)
 * and flagged on both sides.</p>
 */
final class LogPager {

    /**
     * Hard cap on a page's bytes: 256 KiB, about 2,000 lines of the engine's
     * typical 130-byte line. The response is buffered several copies deep: a
     * String of up to 512 KiB (UTF-16 once any non-Latin-1 character appears),
     * then for the web administrator's JSON the engine's serializer builds the
     * XML in a byte buffer, a String of it, a byte copy for the StAX parser, a
     * JSON byte buffer, a JSON String and its bytes (read from the 4.6.0
     * ObjectJSONSerializer and JsonXmlUtil). About 3 MiB per request typically;
     * roughly 18 MiB in the worst case of text that is all XML-escaped
     * characters, counting buffer-doubling slack. With about 190 MiB of the
     * default 256 MiB heap free at idle (old gen measured at ~66 MiB), several
     * concurrent worst cases still fit. A 1 MiB page would be four times that
     * for no gain in a viewer.
     */
    static final int PAGE_MAX_BYTES = 256 * 1024;

    /**
     * Lines per page: what the viewers' "Previous page" and "Next page" move
     * by, so a page means the same thing whatever the lines look like. About
     * 130 KB of the engine's typical lines, inside {@link #PAGE_MAX_BYTES},
     * which stays as the safety cap: a stretch of very long lines (a logged
     * document, a payload dump) ends the page at the byte cap first, with
     * fewer lines. A last line without its terminator (end of file, or a long
     * line split at the cap) counts as one of the 1,000.
     */
    static final int PAGE_MAX_LINES = 1000;

    /**
     * Line numbers for an uncompressed file mean counting newlines from its
     * start. Done when the page starts within 64 MiB: one 64 KiB buffer, and
     * measured at under 0.1 s for the tail page of a 50 MiB file, count
     * included. Every active file under the shipped 500 KB rollover size is
     * far inside it; beyond it the number is omitted (null) rather than making
     * a page cost an unbounded scan. The file's total line count follows the
     * same limit, on the whole file's length (an archive's uncompressed
     * length), and is counted in the same pass.
     */
    static final long LINE_NUMBER_SCAN_LIMIT = 64L << 20;

    /**
     * Decompressed bytes one page request may read through before refusing:
     * 1 GiB, a few seconds of one core at typical inflate speed, 2,000 times
     * the shipped 500 KB rollover size. Only an unusually large archive, or a
     * page deep in one, reaches it; the error says a search still works, and
     * the viewers add that the file can be downloaded when the user may.
     */
    static final long ARCHIVE_SCAN_LIMIT = 1L << 30;

    /**
     * Bytes {@code [start, start + bytes.length)} of the content.
     * {@code reachesEnd}: the content ends where the block does.
     * {@code lineEndsBefore}: line ends that finish before {@code start}
     * (see {@link LogText#isLineEnd}), null if not counted.
     * {@code totalLines}: lines in the whole content, null if not counted.
     * {@code knownLength}: an archive's uncompressed length, when it was read
     * through to the end; null otherwise (and always for an uncompressed file,
     * whose length is known from the file).
     */
    private record Block(long start, byte[] bytes, boolean reachesEnd, Long lineEndsBefore,
                         Long totalLines, Long knownLength) {
        long end() {
            return start + bytes.length;
        }
    }

    LogPage page(OpenLogFile file, LogPageAnchor anchor, long offset) throws IOException, LogViewerException {
        return page(file, anchor, offset, null);
    }

    /**
     * @param highlighter finds the search's matches in the page's text, or
     *                    null when the request carried no search
     */
    LogPage page(OpenLogFile file, LogPageAnchor anchor, long offset, LogHighlighter highlighter)
            throws IOException, LogViewerException {
        int cap = PAGE_MAX_BYTES;
        if (anchor != LogPageAnchor.TAIL && anchor != LogPageAnchor.HEAD) {
            // Checked before any offset arithmetic, which also keeps it from overflowing.
            if (offset < 0) {
                throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST, "Offset must not be negative.");
            }
            if (!file.compressed() && offset > file.length()) {
                throw pastEnd(file, offset, file.length());
            }
            if (file.compressed() && offset > ARCHIVE_SCAN_LIMIT) {
                throw tooLarge(file);
            }
        }
        switch (anchor) {
            case TAIL: {
                Block block = readTail(file, cap + 1);
                return backward(file, block, block.end(), highlighter);
            }
            case BEFORE: {
                // One byte past the offset: whether a CR just before it ends a line depends on it.
                Block block = read(file, Math.max(0, offset - cap - 1), offset + 1);
                requireWithin(file, block, offset);
                return backward(file, block, offset, highlighter);
            }
            case HEAD: {
                return forward(file, read(file, 0, cap + 1), 0, highlighter);
            }
            case AFTER: {
                Block block = read(file, Math.max(0, offset - 1), offset + cap + 1);
                requireWithin(file, block, offset);
                return forward(file, block, offset, highlighter);
            }
            case AT: {
                Block block = read(file, Math.max(0, offset - cap / 2 - 1), offset + cap + 1);
                requireWithin(file, block, offset);
                return around(file, block, offset, highlighter);
            }
            default:
                throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST, "Unknown anchor " + anchor);
        }
    }

    // ========================
    // Page selection within a block
    // ========================

    /**
     * The page ending at {@code end}: as many whole lines as fit, or, when the
     * line ending there is longer than a page, its last page-sized piece.
     * The block holds {@code [end - cap - 1, end)}, plus the byte at
     * {@code end} when there is one; the byte before shows whether
     * {@code end - cap} is a line start, the byte after whether a CR just
     * before {@code end} is lone.
     */
    private LogPage backward(OpenLogFile file, Block block, long end, LogHighlighter highlighter) {
        byte[] bytes = block.bytes();
        int endIndex = (int) (end - block.start());
        int startIndex;
        boolean startsMid = false;
        // The line end just before the page: counting back from the page's end,
        // the one after its PAGE_MAX_LINES lines (one fewer whole line when the
        // last line has no terminator, since it counts too).
        boolean openLast = endIndex > 0 && !LogText.isLineEnd(bytes, endIndex - 1, block.reachesEnd());
        int beforePage = endIndex == 0 ? -1 : LogText.lastIndexOfNthLineEnd(bytes, 0, endIndex,
                PAGE_MAX_LINES - (openLast ? 1 : 0) + 1, block.reachesEnd());
        if (beforePage >= 0) {
            startIndex = beforePage + 1;
        } else if (end <= PAGE_MAX_BYTES) {
            startIndex = 0; // the whole prefix of the file fits
        } else {
            // First line end at index <= endIndex - 2, so the page is non-empty.
            int lineEnd = LogText.indexOfLineEnd(bytes, 0, endIndex - 1, block.reachesEnd());
            if (lineEnd >= 0) {
                startIndex = lineEnd + 1;
            } else {
                startIndex = LogText.characterStart(bytes, 1, endIndex, charset(file));
                startsMid = true;
            }
        }
        boolean endsMid = endIndex > startIndex && !LogText.isLineEnd(bytes, endIndex - 1, block.reachesEnd())
                && !(block.reachesEnd() && endIndex == bytes.length);
        return build(file, block, startIndex, endIndex, startsMid, endsMid, highlighter);
    }

    /**
     * The page starting at {@code start}: as many whole lines as fit, or the
     * first page-sized piece of a longer line. The block holds
     * {@code [start - 1, start + cap + 1)}; the byte before shows whether
     * {@code start} is mid-line, the byte after whether more follows.
     */
    private LogPage forward(OpenLogFile file, Block block, long start, LogHighlighter highlighter) {
        byte[] bytes = block.bytes();
        int startIndex = (int) (start - block.start());
        boolean startsMid = startIndex > 0 && !LogText.isLineEnd(bytes, startIndex - 1, block.reachesEnd());
        int available = bytes.length - startIndex;
        int limit = startIndex + Math.min(available, PAGE_MAX_BYTES);
        int lastOfPage = LogText.indexOfNthLineEnd(bytes, startIndex, limit, PAGE_MAX_LINES, block.reachesEnd());
        int endIndex;
        boolean endsMid = false;
        if (lastOfPage >= 0) {
            endIndex = lastOfPage + 1; // PAGE_MAX_LINES whole lines fit within the byte cap
        } else if (available <= PAGE_MAX_BYTES && block.reachesEnd()) {
            endIndex = bytes.length; // the rest of the file fits
        } else {
            int lineEnd = LogText.lastIndexOfLineEnd(bytes, startIndex, limit, block.reachesEnd());
            if (lineEnd >= 0) {
                endIndex = lineEnd + 1;
            } else {
                endIndex = startIndex + LogText.wholeCharacterBytes(bytes, startIndex, limit, charset(file));
                // A long line ending in CRLF can put the cut between the CR and
                // its LF. Cut before the CR instead, so the pair stays together.
                if (endIndex > startIndex + 1 && endIndex < bytes.length
                        && bytes[endIndex - 1] == LogText.CR && bytes[endIndex] == LogText.LF) {
                    endIndex--;
                }
                endsMid = true;
            }
        }
        return build(file, block, startIndex, endIndex, startsMid, endsMid, highlighter);
    }

    /**
     * A page containing {@code target}, preferably starting up to half a page
     * earlier so the viewer shows context above a search match. If that
     * context leaves no room for the target's own line, the page starts at
     * that line instead. The block holds
     * {@code [target - cap/2 - 1, target + cap + 1)}.
     */
    private LogPage around(OpenLogFile file, Block block, long target, LogHighlighter highlighter) {
        byte[] bytes = block.bytes();
        int targetIndex = (int) (target - block.start());
        long windowStart = Math.max(0, target - PAGE_MAX_BYTES / 2);
        int windowIndex = (int) (windowStart - block.start());

        // Start of the target's own line if it begins inside the window;
        // otherwise the target itself (it is deep inside a very long line).
        int ownLineEnd = LogText.lastIndexOfLineEnd(bytes, Math.max(0, windowIndex - 1), targetIndex,
                block.reachesEnd());
        int own;
        if (ownLineEnd >= 0) {
            own = ownLineEnd + 1;
        } else if (windowStart == 0) {
            own = 0;
        } else {
            own = LogText.characterStart(bytes, targetIndex, bytes.length, charset(file));
        }

        // Up to half a page of lines above the target's own line, within the
        // half-page byte window; failing that, the earliest line start in it.
        int first = own;
        int aboveContext = LogText.lastIndexOfNthLineEnd(bytes, Math.max(0, windowIndex - 1), own,
                PAGE_MAX_LINES / 2 + 1, block.reachesEnd());
        if (aboveContext >= 0) {
            first = aboveContext + 1;
        } else if (windowStart == 0) {
            first = 0;
        } else {
            int lineEnd = LogText.indexOfLineEnd(bytes, windowIndex - 1, targetIndex, block.reachesEnd());
            if (lineEnd >= 0) {
                first = lineEnd + 1;
            }
        }

        LogPage page = forward(file, block, block.start() + first, highlighter);
        if (page.getEndOffset() <= target && !page.isAtEnd() && first != own) {
            page = forward(file, block, block.start() + own, highlighter);
        }
        if (page.getStartOffset() <= target && target <= page.getEndOffset()) {
            // The target is a match's offset, the start of a character, so the page's bytes before it
            // decode to the chars before it in the page text (sanitizing is one char for one char).
            int pageIndex = (int) (page.getStartOffset() - block.start());
            page.setTargetIndex(LogText.decode(bytes, pageIndex, targetIndex, charset(file)).length());
        }
        return page;
    }

    private LogPage build(OpenLogFile file, Block block, int startIndex, int endIndex,
                          boolean startsMid, boolean endsMid, LogHighlighter highlighter) {
        byte[] bytes = block.bytes();
        LogPage page = new LogPage();
        page.setFileId(file.id());
        page.setStartOffset(block.start() + startIndex);
        page.setEndOffset(block.start() + endIndex);
        String decoded = LogText.decode(bytes, startIndex, endIndex, charset(file));
        page.setText(LogText.sanitize(decoded));
        if (highlighter != null) {
            // On the decoded text, as search matches it; sanitizing is one char
            // for one char, so the positions hold for the text sent.
            LogHighlighter.Result found = highlighter.find(decoded);
            page.setHighlights(found.positions());
            page.setHighlightsStopped(found.stopped());
        }
        page.setStartsMidLine(startsMid);
        page.setEndsMidLine(endsMid);
        page.setAtEnd(block.reachesEnd() && endIndex == bytes.length);
        if (!file.compressed()) {
            page.setContentLength(file.length());
        } else if (block.knownLength() != null) {
            page.setContentLength(block.knownLength());
        } else if (block.reachesEnd()) {
            page.setContentLength(block.end());
        }
        page.setTotalLines(block.totalLines());
        if (block.lineEndsBefore() != null) {
            page.setFirstLineNumber(block.lineEndsBefore()
                    + LogText.countLineEnds(bytes, 0, startIndex, block.reachesEnd()) + 1);
        }
        return page;
    }

    private static void requireWithin(OpenLogFile file, Block block, long offset) throws LogViewerException {
        if (block.reachesEnd() && offset > block.end()) {
            throw pastEnd(file, offset, block.end());
        }
    }

    private static LogViewerException pastEnd(OpenLogFile file, long offset, long end) {
        return new LogViewerException(LogViewerException.Kind.STALE, "Position " + offset
                + " is past the end of " + file.file().name() + " (" + end
                + " bytes); it may have been rotated. Reload the file list.");
    }

    private static Charset charset(OpenLogFile file) {
        return file.file().charset();
    }

    // ========================
    // Block reads
    // ========================

    /** Bytes {@code [from, to)}, clipped to the end of the content. */
    private Block read(OpenLogFile file, long from, long to) throws IOException, LogViewerException {
        if (!file.compressed()) {
            long end = file.length();
            long lo = Math.min(from, end);
            long hi = Math.min(to, end);
            byte[] bytes = file.readPlain(lo, (int) (hi - lo));
            Long lineEnds = null;
            Long totalLines = null;
            // ponytail: every page of an uncompressed file up to 64 MiB re-counts its lines from the
            // start; a per-file line-count cache (counting only what was appended, for the active
            // file) if large active files make page reads slow.
            if (end <= LINE_NUMBER_SCAN_LIMIT) {
                OpenLogFile.LineCounts counts = file.countLinesPlain(lo);
                lineEnds = counts.endsBefore();
                totalLines = counts.lines();
            } else if (lo <= LINE_NUMBER_SCAN_LIMIT) {
                lineEnds = file.countLineEndsPlain(lo);
            }
            // Checked after reading, so a truncation during the read is caught
            // before any of its bytes reach the client.
            file.requireUnchanged(hi);
            return new Block(lo, bytes, hi == end, lineEnds, totalLines, null);
        }

        if (from > ARCHIVE_SCAN_LIMIT) {
            throw tooLarge(file);
        }
        try (InputStream in = file.content()) {
            byte[] buffer = new byte[OpenLogFile.IO_BUFFER];
            long position = 0;
            LogText.LineEndCounter lineEnds = new LogText.LineEndCounter();
            int lastByte = -1;
            while (position < from) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, from - position));
                if (n < 0) {
                    long lines = LogText.lineCount(lineEnds.countBefore(-1), lastByte, position);
                    return new Block(position, new byte[0], true, lineEnds.countBefore(-1), lines, position);
                }
                lineEnds.feed(buffer, 0, n);
                if (n > 0) {
                    lastByte = buffer[n - 1];
                }
                position += n;
            }
            int want = (int) (to - from);
            byte[] bytes = new byte[want];
            int got = in.readNBytes(bytes, 0, want);
            int next = got < want ? -1 : in.read();
            boolean reachesEnd = next < 0;
            if (got < want) {
                bytes = Arrays.copyOf(bytes, got);
            }
            long lineEndsBefore = lineEnds.countBefore(got > 0 ? bytes[0] : -1);

            // Read on to the end for the total, within the line-number limit:
            // the archive has to be inflated from its start anyway, so going on
            // to its end costs only the rest of it.
            lineEnds.feed(bytes, 0, bytes.length);
            if (bytes.length > 0) {
                lastByte = bytes[bytes.length - 1];
            }
            long length = from + bytes.length;
            boolean complete = next < 0;
            if (next >= 0) {
                lineEnds.feed(new byte[] {(byte) next}, 0, 1);
                lastByte = next;
                length++;
                int n;
                while (length <= LINE_NUMBER_SCAN_LIMIT && (n = in.read(buffer)) >= 0) {
                    lineEnds.feed(buffer, 0, n);
                    if (n > 0) {
                        lastByte = buffer[n - 1];
                    }
                    length += n;
                }
                complete = length <= LINE_NUMBER_SCAN_LIMIT;
            }
            Long totalLines = complete ? LogText.lineCount(lineEnds.countBefore(-1), lastByte, length) : null;
            return new Block(from, bytes, reachesEnd, lineEndsBefore, totalLines, complete ? length : null);
        }
    }

    /** The last {@code count} bytes of the content. */
    private Block readTail(OpenLogFile file, int count) throws IOException, LogViewerException {
        if (!file.compressed()) {
            return read(file, Math.max(0, file.length() - count), file.length());
        }
        // An archive's length is unknown until it is decompressed, so keep the
        // last `count` bytes in a ring buffer on the way through: one pass.
        byte[] ring = new byte[count];
        long total = 0;
        LogText.LineEndCounter lineEnds = new LogText.LineEndCounter();
        try (InputStream in = file.content()) {
            byte[] buffer = new byte[OpenLogFile.IO_BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                lineEnds.feed(buffer, 0, n);
                int skip = Math.max(0, n - count);
                long position = total + skip;
                int offset = skip;
                int remaining = n - skip;
                while (remaining > 0) {
                    int index = (int) (position % count);
                    int chunk = Math.min(remaining, count - index);
                    System.arraycopy(buffer, offset, ring, index, chunk);
                    offset += chunk;
                    position += chunk;
                    remaining -= chunk;
                }
                total += n;
                if (total > ARCHIVE_SCAN_LIMIT) {
                    throw tooLarge(file);
                }
            }
        }
        int length = (int) Math.min(total, count);
        long start = total - length;
        byte[] bytes = new byte[length];
        int first = (int) (start % count);
        int firstChunk = Math.min(length, count - first);
        System.arraycopy(ring, first, bytes, 0, firstChunk);
        System.arraycopy(ring, 0, bytes, firstChunk, length - firstChunk);
        // Every line end in the content, less those that finish inside the tail.
        long allLineEnds = lineEnds.countBefore(-1);
        return new Block(start, bytes, true, allLineEnds - LogText.countLineEnds(bytes, 0, length, true),
                LogText.lineCount(allLineEnds, length > 0 ? bytes[length - 1] : -1, total), total);
    }

    private static LogViewerException tooLarge(OpenLogFile file) {
        return new LogViewerException(LogViewerException.Kind.TOO_LARGE, "This page of "
                + file.file().name() + " is more than " + (ARCHIVE_SCAN_LIMIT >> 30)
                + " GiB into the archive's uncompressed content, too far to page to. Searching the archive"
                + " still works.");
    }
}
