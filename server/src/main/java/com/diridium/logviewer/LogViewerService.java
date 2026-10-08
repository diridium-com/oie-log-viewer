// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

import com.diridium.logviewer.LogFileCatalog.Entry;
import com.diridium.logviewer.LogFileCatalog.Format;
import com.diridium.logviewer.LogFileCatalog.Listing;
import com.diridium.logviewer.LogFileCatalog.LogFile;

/**
 * What the servlet calls: list, page, search and download.
 *
 * <p>Every operation starts from a fresh discovery of the running log4j
 * configuration and accepts only file ids that discovery reproduces, with a
 * matching fingerprint. Authorization (the two permissions, and refusing
 * channel-restricted callers) is the servlet's job and happens before any of
 * this runs.</p>
 *
 * <p>The servlet must share one instance across requests (a static field, as
 * the template servlet holds its search engine), because the search
 * concurrency caps live in it. The class holds no per-request state.</p>
 */
public class LogViewerService {

    /**
     * Page reads allowed at once, engine-wide. A page read is normally a few
     * milliseconds, but one can re-count a large active file's lines, inflate
     * an archive from its start, or run a search's regular expression over
     * the page for its highlights (up to 2 seconds), so the number at once is
     * capped as searches are. A request past the cap is refused, not queued.
     */
    static final int MAX_CONCURRENT_PAGE_READS = 4;

    /** Says the cap in words; a test keeps it in step with the number. */
    static final String PAGES_BUSY = "Four log pages are already being read on the engine, which is as many as it"
            + " allows at once. Try again in a moment.";

    /** Says the search cap and time limit in words; a test keeps it in step with the numbers. */
    static final String SEARCHES_BUSY = "Two log searches are already running on the engine, which is as many as it"
            + " allows at once. A search can take up to 15 seconds. Try again when one finishes.";

    private final LogFileCatalog catalog;
    private final LogPager pager = new LogPager();
    private final LogSearcher searcher;
    private final Semaphore pageReads = new Semaphore(MAX_CONCURRENT_PAGE_READS);
    private final LongSupplier nanoClock;

    public LogViewerService() {
        this(new Log4jAppenderSource(), System::nanoTime);
    }

    /** For tests: appenders without a LoggerContext, and a clock that can be moved. */
    LogViewerService(LogAppenderSource source, LongSupplier nanoClock) {
        this.catalog = new LogFileCatalog(source);
        this.searcher = new LogSearcher(nanoClock);
        this.nanoClock = nanoClock;
    }

    public LogFileList listFiles() {
        Listing listing = catalog.list();
        LogFileList list = new LogFileList();
        for (Entry entry : listing.entries()) {
            list.getFiles().add(toInfo(entry));
        }
        list.getWarnings().addAll(listing.warnings());
        return list;
    }

    /**
     * @param anchor null means {@link LogPageAnchor#TAIL}, the default view
     * @param offset required for BEFORE, AFTER and AT; ignored otherwise
     */
    public LogPage readPage(String fileId, LogPageAnchor anchor, Long offset)
            throws LogViewerException, IOException {
        return readPage(fileId, anchor, offset, null, false, false);
    }

    /**
     * @param highlightQuery when not empty, the page also carries where this
     *                       search matches in its text (see
     *                       {@link LogPage#getHighlights()}); same rules as search
     */
    public LogPage readPage(String fileId, LogPageAnchor anchor, Long offset, String highlightQuery,
                            boolean highlightRegex, boolean highlightCaseSensitive)
            throws LogViewerException, IOException {
        LogPageAnchor where = anchor == null ? LogPageAnchor.TAIL : anchor;
        boolean needsOffset = where != LogPageAnchor.TAIL && where != LogPageAnchor.HEAD;
        if (needsOffset && offset == null) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                    "An offset is required to page " + where + " a position.");
        }
        LogHighlighter highlighter = null;
        if (highlightQuery != null && !highlightQuery.isEmpty()) {
            highlighter = new LogHighlighter(
                    LogSearcher.compile(highlightQuery, highlightRegex, highlightCaseSensitive), nanoClock);
        }
        if (!pageReads.tryAcquire()) {
            throw new LogViewerException(LogViewerException.Kind.BUSY, PAGES_BUSY);
        }
        try (OpenLogFile file = resolve(fileId)) {
            requireViewable(file.file());
            LogPage page = pager.page(file, where, needsOffset ? offset : 0, highlighter);
            page.setReadAt(System.currentTimeMillis());
            return page;
        } finally {
            pageReads.release();
        }
    }

    /**
     * @param fileId       one file to search, or null for every listed file, newest first
     * @param resumeFileId with {@code resumeOffset}, a previous result's resume point; null to start
     */
    public LogSearchResult search(String query, boolean regex, boolean caseSensitive, String fileId,
                                  String resumeFileId, Long resumeOffset) throws LogViewerException, IOException {
        return search(query, regex, caseSensitive, fileId, resumeFileId, resumeOffset, false);
    }

    /** Matching lines per file; see {@link LogViewerServletInterface#count}. */
    public LogSearchResult count(String query, boolean regex, boolean caseSensitive, String fileId,
                                 String resumeFileId, Long resumeOffset) throws LogViewerException, IOException {
        return search(query, regex, caseSensitive, fileId, resumeFileId, resumeOffset, true);
    }

    private LogSearchResult search(String query, boolean regex, boolean caseSensitive, String fileId,
                                   String resumeFileId, Long resumeOffset, boolean countOnly)
            throws LogViewerException, IOException {
        Pattern pattern = LogSearcher.compile(query, regex, caseSensitive);
        if (!searcher.tryAcquire()) {
            throw new LogViewerException(LogViewerException.Kind.BUSY, SEARCHES_BUSY);
        }
        try {
            List<Entry> scope;
            List<String> listingWarnings = List.of();
            if (fileId != null && !fileId.isEmpty()) {
                try (OpenLogFile file = resolve(fileId)) {
                    requireViewable(file.file());
                    scope = List.of(new Entry(file.file(), file.id()));
                }
            } else {
                Listing listing = catalog.list();
                scope = listing.entries();
                listingWarnings = listing.warnings();
            }

            int startIndex = 0;
            long startOffset = 0;
            if (resumeFileId != null && !resumeFileId.isEmpty()) {
                if (resumeOffset == null || resumeOffset < 0) {
                    throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                            "A resume offset is required to resume a search.");
                }
                startIndex = indexOf(scope, resumeFileId);
                startOffset = resumeOffset;
            }

            LogSearchResult result = searcher.searchAll(pattern, scope, startIndex, startOffset, countOnly);
            if (!listingWarnings.isEmpty()) {
                // A file the list could not open was not searched either.
                result.getWarnings().addAll(0, listingWarnings);
                result.setComplete(false);
            }
            return result;
        } finally {
            searcher.release();
        }
    }

    /**
     * A file's raw bytes and how many there will be: the active file's length
     * when it was opened, or an archive's size on disk.
     */
    public record Download(InputStream stream, long length) {
    }

    /**
     * The file's raw bytes. The caller must close the stream (Jersey closes an
     * InputStream entity when it finishes or aborts writing it); closing it
     * releases the file.
     */
    public Download openDownload(String fileId) throws LogViewerException, IOException {
        OpenLogFile file = resolve(fileId);
        return new Download(file.download(), file.length());
    }

    /**
     * Opens the file an id names, refusing it unless discovery still finds a
     * file of that name whose fingerprint matches.
     */
    private OpenLogFile resolve(String fileId) throws LogViewerException, IOException {
        LogFile file = catalog.find(fileId);
        OpenLogFile open;
        try {
            open = OpenLogFile.open(file);
        } catch (NoSuchFileException e) {
            throw new LogViewerException(LogViewerException.Kind.STALE,
                    file.name() + " no longer exists. Reload the file list.");
        }
        if (!open.id().equals(fileId)) {
            open.close();
            throw new LogViewerException(LogViewerException.Kind.STALE,
                    file.name() + " has been rotated since it was listed. Reload the file list.");
        }
        return open;
    }

    private static int indexOf(List<Entry> scope, String resumeFileId) throws LogViewerException {
        String key = LogFileCatalog.keyOf(resumeFileId);
        for (int i = 0; i < scope.size(); i++) {
            if (scope.get(i).file().key().equals(key)) {
                if (!scope.get(i).id().equals(resumeFileId)) {
                    break;
                }
                return i;
            }
        }
        throw new LogViewerException(LogViewerException.Kind.STALE,
                "The log files have rotated since this search started. Run the search again.");
    }

    private static void requireViewable(LogFile file) throws LogViewerException {
        if (!file.viewable()) {
            throw new LogViewerException(LogViewerException.Kind.NOT_VIEWABLE, file.name() + ": " + file.note());
        }
    }

    private static LogFileInfo toInfo(Entry entry) {
        LogFile file = entry.file();
        LogFileInfo info = new LogFileInfo();
        info.setId(entry.id());
        info.setName(file.name());
        info.setAppenderName(file.appenderName());
        info.setActive(file.active());
        info.setCompressed(file.format() != Format.PLAIN);
        info.setViewable(file.viewable());
        info.setNote(file.note());
        info.setSize(file.size());
        info.setLastModified(file.lastModified());
        info.setCharset(file.charset().name());
        TimeZone zone = file.timeZoneId() == null ? TimeZone.getDefault() : TimeZone.getTimeZone(file.timeZoneId());
        info.setTimeZoneId(zone.getID());
        info.setTimeZoneLabel(timeZoneLabel(zone, System.currentTimeMillis()));
        return info;
    }

    /**
     * A zone as people read it, with its offset now: {@code MDT (UTC-06:00)}.
     * When the zone has no short name of its own (an offset id such as
     * {@code GMT-07:00}), just the offset. Resolved the way log4j resolves its
     * date zone option, with {@link TimeZone}, so the label names the zone the
     * timestamps were actually written in.
     */
    static String timeZoneLabel(TimeZone zone, long now) {
        int minutes = zone.getOffset(now) / 60_000;
        String offset = String.format(Locale.ROOT, "UTC%s%02d:%02d",
                minutes < 0 ? "-" : "+", Math.abs(minutes) / 60, Math.abs(minutes) % 60);
        String name = zone.getDisplayName(zone.inDaylightTime(new Date(now)), TimeZone.SHORT, Locale.US);
        if (name.chars().anyMatch(Character::isDigit)) {
            return offset;
        }
        if (minutes == 0 && (name.equals("UTC") || name.equals("GMT"))) {
            return "UTC";
        }
        return name + " (" + offset + ")";
    }
}
