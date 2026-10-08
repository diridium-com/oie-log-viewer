// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.diridium.logviewer.LogFileCatalog.Format;

/**
 * Streams log files line by line and collects matching lines, within hard
 * bounds on matches, returned text, time and concurrency.
 *
 * <p>Nothing is ever loaded whole: files are read through a 64 KiB buffer and
 * a line is held only until it has been matched. A line longer than
 * {@link #SEGMENT_BYTES} is searched in pieces, so one enormous line (a
 * logged message with an embedded document) cannot exhaust the heap.</p>
 *
 * <p>The time limit is enforced inside the regex engine as well as between
 * lines. {@code java.util.regex} backtracks, so a pattern such as
 * {@code (a+)+$} on a long run of {@code a} can run for hours on a single
 * line; checking the clock between lines would never fire. The matcher is
 * given a CharSequence that checks the clock every 1,024 character reads and
 * throws once the deadline has passed, which unwinds the match. A pattern
 * that instead overflows the matcher's stack is caught as well.</p>
 *
 * <p>One instance is shared by all requests (the servlet must hold it
 * statically, as the template's servlet does its engine), because the
 * concurrency cap lives in it. All per-search state is in {@link Run}.</p>
 */
final class LogSearcher {

    private static final Logger log = LoggerFactory.getLogger(LogSearcher.class);

    /**
     * Matches returned per request. Each match carries at most
     * {@link #MAX_LINE_CHARS} characters of text, so 1,000 x 256 = 256,000
     * characters: the same text volume as one page, and so the same
     * serialization cost (see {@code LogPager.PAGE_MAX_BYTES}), plus about
     * 250 bytes of XML per match for the other fields. A capped search
     * returns a resume point, so the cap limits a response, not what can be
     * found.
     */
    static final int MAX_MATCHES = 1000;

    /**
     * Characters of a matching line returned, as a window around the match.
     * The full line is one click away on its page; 256 characters show the
     * match in context without letting one long line dominate the response.
     */
    static final int MAX_LINE_CHARS = 256;

    /**
     * Longest piece of a line searched at once: 1 MiB of bytes, plus up to
     * 2 MiB for the decoded text, per concurrent search. Longer lines are
     * searched in 1 MiB pieces and counted in the result, because a match
     * straddling two pieces is missed.
     */
    static final int SEGMENT_BYTES = 1 << 20;

    /**
     * Wall-clock limit per request. Bounds how long a user-supplied regex can
     * keep an engine core busy. Measured on a 50 MiB log-like file (dev VM,
     * Java 17, -Xmx256m): 130-200 MB/s for a case-sensitive literal, about
     * 75 MB/s case-insensitive, 60-130 MB/s for a simple regex. So 15 s covers
     * roughly 1-3 GB of logs; the shipped configuration keeps about 10 MB.
     * Well inside the web administrator's 120 s request timeout.
     */
    static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(15);

    /**
     * Searches allowed at once, engine-wide. Two searches pin at most two
     * cores for {@link #DEADLINE_NANOS} each and hold at most two sets of
     * search buffers (about 3 MiB each) plus two result sets; a third caller
     * is told to retry rather than queued, so requests cannot pile up behind
     * a slow pattern.
     */
    static final int MAX_CONCURRENT_SEARCHES = 2;

    /**
     * Longest query accepted. Plenty for any real search, and it keeps the
     * compiled pattern small and the audit log's record of the query (which
     * logs query parameters verbatim) readable.
     */
    static final int MAX_QUERY_CHARS = 1000;

    private final Semaphore permits = new Semaphore(MAX_CONCURRENT_SEARCHES);
    private final LongSupplier nanoClock;

    LogSearcher(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    boolean tryAcquire() {
        return permits.tryAcquire();
    }

    void release() {
        permits.release();
    }

    static Pattern compile(String query, boolean regex, boolean caseSensitive) throws LogViewerException {
        if (query == null || query.isEmpty()) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST, "Enter something to search for.");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                    "Search text is limited to " + MAX_QUERY_CHARS + " characters.");
        }
        // UNICODE_CASE so a case-insensitive search for a name with accents
        // finds it in either case; plain CASE_INSENSITIVE folds ASCII only.
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex ? query : Pattern.quote(query), flags);
        } catch (PatternSyntaxException e) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                    "Invalid regular expression: " + e.getDescription());
        }
        // The time limit is checked as characters are read; refuse what could loop without reading.
        if (regex && RegexRepeats.usesComments(query)) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                    "Comments mode, (?x), is not supported in log searches.");
        }
        if (regex && RegexRepeats.forcedTurns(query) > RegexRepeats.MAX_FORCED_TURNS) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST, String.format(Locale.ROOT,
                    "The pattern repeats too many times: its nested repeat counts multiply to more than %,d."
                            + " Use smaller counts.", RegexRepeats.MAX_FORCED_TURNS));
        }
        return pattern;
    }

    Run start(Pattern pattern) {
        return start(pattern, false);
    }

    /** {@code countOnly}: count matching lines per file instead of collecting matches. */
    Run start(Pattern pattern, boolean countOnly) {
        return new Run(pattern, nanoClock.getAsLong() + DEADLINE_NANOS, countOnly);
    }

    /** One search request: its deadline, its result so far, and where it stopped. */
    final class Run {
        private final Pattern pattern;
        private final long deadline;
        private final boolean countOnly;
        private final LogSearchResult result = new LogSearchResult();
        private final byte[] buffer = new byte[OpenLogFile.IO_BUFFER];
        /** The file being counted, when counting. */
        private LogFileMatchCount count;

        private Run(Pattern pattern, long deadline, boolean countOnly) {
            this.pattern = pattern;
            this.deadline = deadline;
            this.countOnly = countOnly;
            if (countOnly) {
                result.setFileCounts(new ArrayList<>());
            }
        }

        boolean stopped() {
            return result.getStopReason() != null;
        }

        void warn(String warning) {
            result.getWarnings().add(warning);
        }

        /** Stops without a resume point: resuming would not help. */
        void stop(LogSearchStopReason reason) {
            result.setStopReason(reason);
        }

        private boolean stop(LogSearchStopReason reason, OpenLogFile file, long resumeOffset) {
            result.setStopReason(reason);
            result.setResumeFileId(file.id());
            result.setResumeOffset(resumeOffset);
            return false;
        }

        private boolean expired() {
            return DeadlineCharSequence.expired(nanoClock, deadline);
        }

        /**
         * Searches one file from {@code startOffset} (0, or a resume point) to
         * its end. Returns false when the search must stop; the reason and
         * resume point are then set on the result.
         */
        boolean searchFile(OpenLogFile file, long startOffset) throws IOException {
            int firstMatch = result.getMatches().size();
            if (countOnly) {
                count = new LogFileMatchCount(file.id());
                result.getFileCounts().add(count);
            }
            Charset charset = file.file().charset();
            try (InputStream in = file.content()) {
                long position = 0;
                // Resuming: skip to the resume point, counting line ends so line
                // numbers stay right. Compressed content has to be read through
                // anyway, and this keeps one code path for both kinds.
                LogText.LineEndCounter lineEnds = new LogText.LineEndCounter();
                byte before = LogText.LF;
                while (position < startOffset) {
                    if (expired()) {
                        return stop(LogSearchStopReason.DEADLINE, file, startOffset);
                    }
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, startOffset - position));
                    if (n < 0) {
                        break;
                    }
                    lineEnds.feed(buffer, 0, n);
                    position += n;
                    before = buffer[n - 1];
                }
                // A resume point is a line start or a piece boundary inside a
                // line, never between a CR and its LF, so a CR just before it
                // ended a line: count it as lone.
                long line = 1 + lineEnds.countBefore(-1);
                boolean insideLongLine = before != LogText.LF && before != LogText.CR;

                Segmenter segmenter = new Segmenter(file, charset, position, line, insideLongLine);
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (!segmenter.accept(buffer, n)) {
                        return false;
                    }
                }
                if (!segmenter.finish()) {
                    return false;
                }
            }
            if (file.file().format() == Format.PLAIN) {
                try {
                    file.requireUnchanged(file.length());
                } catch (LogViewerException e) {
                    // Truncated (copy-and-truncate rollover) while being read:
                    // what was read may be a mix of two files, so none of it is kept.
                    result.getMatches().subList(firstMatch, result.getMatches().size()).clear();
                    if (countOnly) {
                        result.getFileCounts().remove(count);
                    }
                    stop(LogSearchStopReason.FILES_ROTATED);
                    return false;
                }
            }
            if (countOnly) {
                count.setComplete(true);
            }
            result.setFilesSearched(result.getFilesSearched() + 1);
            return true;
        }

        LogSearchResult finish(int filesInScope) {
            result.setFilesInScope(filesInScope);
            if (result.getSplitLineCount() > 0) {
                warn(result.getSplitLineCount() + " line(s) longer than " + (SEGMENT_BYTES >> 20)
                        + " MiB were searched in " + (SEGMENT_BYTES >> 20)
                        + " MiB pieces; a match spanning two pieces is not found.");
            }
            if (result.getStopReason() == LogSearchStopReason.DEADLINE
                    && result.getResumeFileId() != null) {
                log.debug("Log search stopped at its time limit in {} at offset {}",
                        result.getResumeFileId(), result.getResumeOffset());
            }
            result.setComplete(result.getStopReason() == null && result.getWarnings().isEmpty());
            return result;
        }

        /**
         * Splits a byte stream into lines (or {@link #SEGMENT_BYTES} pieces of
         * longer lines) and matches each. A line ends at LF, CRLF or a lone CR
         * (see {@link LogText#isLineEnd}), the same rule the pager numbers
         * lines by, so a match's line number is the line both viewers show.
         * The terminator is never part of the matched text.
         */
        private final class Segmenter {
            private final OpenLogFile file;
            private final Charset charset;
            private byte[] segment = new byte[8192];
            private int length;
            private long segmentStart;
            private long line;
            private boolean lineWasSplit;
            /** When counting: the current line has matched, in an earlier piece of it. */
            private boolean lineCounted;
            /**
             * The previous buffer ended with a CR. Whether it was lone (one
             * byte of terminator) or the first half of a CRLF (two) is decided
             * by the next buffer's first byte.
             */
            private boolean pendingCr;

            /** {@code insideLongLine}: resuming at a piece boundary inside a line longer than a piece. */
            Segmenter(OpenLogFile file, Charset charset, long start, long line, boolean insideLongLine) {
                this.file = file;
                this.charset = charset;
                this.segmentStart = start;
                this.line = line;
                if (insideLongLine) {
                    lineWasSplit = true;
                    result.setSplitLineCount(result.getSplitLineCount() + 1);
                }
            }

            boolean accept(byte[] bytes, int count) {
                int i = 0;
                if (pendingCr && count > 0) {
                    pendingCr = false;
                    boolean crlf = bytes[0] == LogText.LF;
                    if (!emitLine(crlf ? 2 : 1)) {
                        return false;
                    }
                    i = crlf ? 1 : 0;
                }
                while (i < count) {
                    int terminator = LogText.indexOfCrOrLf(bytes, i, count);
                    int contentEnd = terminator < 0 ? count : terminator;
                    while (length + (contentEnd - i) > SEGMENT_BYTES) {
                        int room = SEGMENT_BYTES - length;
                        append(bytes, i, room);
                        i += room;
                        if (!emitPiece()) {
                            return false;
                        }
                    }
                    append(bytes, i, contentEnd - i);
                    i = contentEnd;
                    if (terminator < 0) {
                        break;
                    }
                    if (bytes[terminator] == LogText.LF) {
                        if (!emitLine(1)) {
                            return false;
                        }
                        i = terminator + 1;
                    } else if (terminator + 1 < count) {
                        boolean crlf = bytes[terminator + 1] == LogText.LF;
                        if (!emitLine(crlf ? 2 : 1)) {
                            return false;
                        }
                        i = terminator + (crlf ? 2 : 1);
                    } else {
                        pendingCr = true;
                        i = terminator + 1;
                    }
                }
                return true;
            }

            /** The file's last line: ended by a CR with nothing after it, or by no terminator at all. */
            boolean finish() {
                if (pendingCr) {
                    pendingCr = false;
                    return emitLine(1);
                }
                return length == 0 || emitLine(0);
            }

            private void append(byte[] bytes, int from, int count) {
                if (length + count > segment.length) {
                    segment = Arrays.copyOf(segment,
                            Math.min(SEGMENT_BYTES, Math.max(length + count, segment.length * 2)));
                }
                System.arraycopy(bytes, from, segment, length, count);
                length += count;
            }

            private boolean emitLine(int terminatorBytes) {
                String text = LogText.decode(segment, 0, length, charset);
                if (!match(text, length + terminatorBytes, lineWasSplit)) {
                    return false;
                }
                segmentStart += length + terminatorBytes;
                length = 0;
                line++;
                lineWasSplit = false;
                lineCounted = false;
                return true;
            }

            /** Emits the whole characters of a full segment; any cut-off character carries over. */
            private boolean emitPiece() {
                int whole = LogText.wholeCharacterBytes(segment, 0, length, charset);
                String text = LogText.decode(segment, 0, whole, charset);
                if (!lineWasSplit) {
                    result.setSplitLineCount(result.getSplitLineCount() + 1);
                    lineWasSplit = true;
                }
                if (!match(text, whole, true)) {
                    return false;
                }
                System.arraycopy(segment, whole, segment, 0, length - whole);
                length -= whole;
                segmentStart += whole;
                return true;
            }

            private boolean match(String text, int segmentBytes, boolean partOfLongLine) {
                if (expired()) {
                    return stop(LogSearchStopReason.DEADLINE, file, segmentStart);
                }
                Matcher matcher = pattern.matcher(new DeadlineCharSequence(text, nanoClock, deadline));
                boolean found;
                try {
                    found = matcher.find();
                } catch (DeadlineCharSequence.Exceeded e) {
                    // No warning: the time limit falls inside some line on any long search. A
                    // line that cannot be finished shows when resuming here makes no progress.
                    return stop(LogSearchStopReason.DEADLINE, file, segmentStart);
                } catch (StackOverflowError e) {
                    // The matcher recurses per repetition of some constructs;
                    // the stack unwinds to here intact, so this is recoverable.
                    stop(LogSearchStopReason.PATTERN_TOO_COMPLEX);
                    warn("The pattern was too complex for line " + line + " of " + file.file().name() + ".");
                    return false;
                }
                if (found && countOnly) {
                    // A count has no match cap: only the time limit stops it.
                    // A resume inside a long line that already matched counts
                    // that line again if a later piece matches too: it needs a
                    // line over 1 MiB and the time limit to fall inside it.
                    if (!lineCounted) {
                        count.setMatchingLines(count.getMatchingLines() + 1);
                        lineCounted = true;
                    }
                } else if (found) {
                    // Stop on the match past the cap, not at the cap, so a search
                    // with exactly MAX_MATCHES matches is reported complete.
                    if (result.getMatches().size() >= MAX_MATCHES) {
                        return stop(LogSearchStopReason.MAX_MATCHES, file, segmentStart);
                    }
                    result.getMatches().add(toMatch(text, matcher.start(), matcher.end(), partOfLongLine));
                }
                result.setBytesSearched(result.getBytesSearched() + segmentBytes);
                return true;
            }

            private LogSearchMatch toMatch(String text, int start, int end, boolean partOfLongLine) {
                int length = text.length();
                int from = Math.max(0, start - MAX_LINE_CHARS / 4);
                int to = Math.min(length, from + MAX_LINE_CHARS);
                from = Math.max(0, to - MAX_LINE_CHARS);
                // Never cut a surrogate pair: half of one is unrepresentable in XML.
                if (from > 0 && Character.isLowSurrogate(text.charAt(from))) {
                    from++;
                }
                if (to < length && Character.isHighSurrogate(text.charAt(to - 1))) {
                    to--;
                }
                LogSearchMatch match = new LogSearchMatch();
                match.setFileId(file.id());
                match.setLineNumber(line);
                match.setLineOffset(segmentStart);
                // segment still holds the bytes text was decoded from; only those before the match are read.
                match.setMatchOffset(segmentStart + LogText.bytesBefore(segment, length, text, start, charset));
                match.setLineText(LogText.sanitize(text.substring(from, to)));
                match.setMatchStart(Math.max(0, start - from));
                match.setMatchEnd(Math.max(0, Math.min(end, to) - from));
                match.setTruncated(from > 0 || to < length || partOfLongLine);
                return match;
            }
        }
    }

    /** Searches {@code files} in order, starting at {@code startOffset} in the first. */
    LogSearchResult searchAll(Pattern pattern, List<LogFileCatalog.Entry> files, int startIndex, long startOffset) {
        return searchAll(pattern, files, startIndex, startOffset, false);
    }

    /** {@code countOnly}: count matching lines per file instead of collecting matches. */
    LogSearchResult searchAll(Pattern pattern, List<LogFileCatalog.Entry> files, int startIndex, long startOffset,
                              boolean countOnly) {
        Run run = start(pattern, countOnly);
        for (int i = startIndex; i < files.size() && !run.stopped(); i++) {
            LogFileCatalog.Entry entry = files.get(i);
            LogFileCatalog.LogFile file = entry.file();
            if (!file.viewable()) {
                run.warn(file.name() + " was not searched: " + file.note());
                continue;
            }
            try (OpenLogFile open = OpenLogFile.open(file)) {
                if (!open.id().equals(entry.id())) {
                    // The name now holds a different file: a rollover renamed
                    // every archive down one since the list was taken.
                    run.stop(LogSearchStopReason.FILES_ROTATED);
                    break;
                }
                run.searchFile(open, i == startIndex ? startOffset : 0);
            } catch (NoSuchFileException e) {
                // Deleted by a rollover since the list was taken.
                run.stop(LogSearchStopReason.FILES_ROTATED);
            } catch (IOException e) {
                // A corrupt or half-written archive. Matches found in it before
                // the error are real and kept; the warning says it is incomplete.
                run.warn(file.name() + " could not be searched completely: " + LogFileCatalog.describe(e));
            }
        }
        return run.finish(files.size());
    }
}
