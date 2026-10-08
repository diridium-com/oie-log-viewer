// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.text.NumberFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * The wording and number formats the dialog shows. Pure, so the sentences a
 * user reads can be tested without a display. Numbers are grouped the same way
 * for every locale: log positions are technical figures, and fixed formatting
 * keeps them identical in screenshots and bug reports.
 */
public final class LogViewerFormat {

    private LogViewerFormat() {
    }

    /** A whole number with thousands separators. */
    public static String count(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /** A byte count as B, KB, MB, GB or TB (1024 based) with one decimal. */
    public static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = n;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    /** The status bar's items are separated by a middle dot. */
    static final String STATUS_SEPARATOR = " \u00B7 ";

    /**
     * Where the page sits in its file, for the status bar, for example
     * {@code Lines 2,001-3,000 of 11,905}, with a suffix when the page touches an end of the file:
     * start of file, end of file, or whole file. Without line numbers it falls back to byte
     * offsets, and without a line total it leaves out {@code of N}.
     *
     * @param firstLine 1-based number of the first line, or null when the server did not count
     * @param lineCount lines that hold text on the page, 0 for an empty page
     * @param totalLines lines in the whole file, or null when the server did not count them
     * @param atEnd true when the page reaches the end of the file
     */
    public static String statusPosition(Long firstLine, int lineCount, Long totalLines, long startOffset,
            long endOffset, Long contentLength, boolean atEnd) {
        StringBuilder label = new StringBuilder();
        if (firstLine != null && lineCount > 0) {
            label.append("Lines ").append(count(firstLine)).append('-')
                    .append(count(firstLine + lineCount - 1));
            if (totalLines != null) {
                label.append(" of ").append(count(totalLines));
            }
        } else {
            label.append("Bytes ").append(count(startOffset)).append('-').append(count(endOffset));
            if (contentLength != null) {
                label.append(" of ").append(count(contentLength));
            } else {
                label.append(" (total size not known yet)");
            }
        }
        boolean atStart = startOffset <= 0;
        if (atStart && atEnd) {
            label.append(STATUS_SEPARATOR).append("whole file");
        } else if (atStart) {
            label.append(STATUS_SEPARATOR).append("start of file");
        } else if (atEnd) {
            label.append(STATUS_SEPARATOR).append("end of file");
        }
        return label.toString();
    }

    /** Joins the status bar items that have text, leaving out the empty ones. */
    public static String statusItems(String... items) {
        StringBuilder joined = new StringBuilder();
        for (String item : items) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(STATUS_SEPARATOR);
            }
            joined.append(item);
        }
        return joined.toString();
    }

    /** What to say when a page starts or ends inside a line; empty when it does neither. */
    public static String midLineNotes(boolean startsMidLine, boolean endsMidLine) {
        if (startsMidLine && endsMidLine) {
            return "The first line continues from the previous page and the last line continues on the next page.";
        }
        if (startsMidLine) {
            return "The first line continues from the previous page.";
        }
        if (endsMidLine) {
            return "The last line continues on the next page.";
        }
        return "";
    }

    /**
     * The name a download is suggested under: the file's name, with an archive's date folder joined
     * on by an underscore ({@code 2026-10-06-1630/mirth.log.5.zip} becomes
     * {@code 2026-10-06-1630_mirth.log.5.zip}), since a file name cannot hold a slash. The web
     * viewer's rule.
     */
    public static String saveName(String fileName) {
        return fileName.replace('/', '_');
    }

    /**
     * A name with a folder, cut to fit {@code width}: the file part whole, and as much of the end of
     * the folder as fits after an ellipsis ({@code 2026-10-06-1630/mirth.log.5.zip} becomes, say,
     * {@code \u202606-1630/mirth.log.5.zip}), since the end of a date folder is what tells folders
     * apart. A name that fits, or has no folder, is returned as it is.
     */
    public static String fitName(String name, int width, ToIntFunction<String> measure) {
        int slash = name.lastIndexOf('/');
        if (slash < 0 || measure.applyAsInt(name) <= width) {
            return name;
        }
        String folder = name.substring(0, slash);
        String rest = name.substring(slash);
        for (int start = 1; start < folder.length(); start++) {
            if (Character.isLowSurrogate(folder.charAt(start))) {
                continue;
            }
            String cut = "\u2026" + folder.substring(start) + rest;
            if (measure.applyAsInt(cut) <= width) {
                return cut;
            }
        }
        return "\u2026" + rest;
    }

    /** Progress of a download, for example {@code 1.2 MB of 5.0 MB}. */
    public static String progress(long done, long total) {
        if (total <= 0) {
            return bytes(done);
        }
        return bytes(done) + " of " + bytes(total);
    }

    /** Why a search of one file stopped early, as a sentence. */
    public static String stopReason(LogSearchStopReason reason) {
        switch (reason) {
            case MAX_MATCHES:
                return "Stopped: the match limit was reached.";
            case DEADLINE:
                return "Stopped: the time limit was reached.";
            case PATTERN_TOO_COMPLEX:
                return "Stopped: the regular expression was too complex for one of the lines. Simplify the pattern.";
            case FILES_ROTATED:
                return "The log files rotated during the search. Refresh the list and search again.";
            default:
                return "Stopped: " + reason + ".";
        }
    }

    /** The results header while the count runs. */
    public static final String COUNTING = "Counting matching lines...";

    /** The results header when the log files rotated under a count. */
    public static final String COUNT_ROTATED =
            "The log files rotated while counting. Refresh the list and search again.";

    /**
     * The results header of a finished count, for example {@code 1,641 matching lines in 6 files}.
     *
     * @param matchingLines matching lines in all the files counted
     * @param filesWithMatches files with at least one matching line
     * @param filesCounted files the count covered, which is what an empty result names
     */
    public static String countHeader(long matchingLines, int filesWithMatches, int filesCounted) {
        if (matchingLines == 0) {
            return "No matching lines in " + count(filesCounted) + (filesCounted == 1 ? " file" : " files");
        }
        return count(matchingLines) + (matchingLines == 1 ? " matching line" : " matching lines") + " in "
                + count(filesWithMatches) + (filesWithMatches == 1 ? " file" : " files");
    }

    /** The results header of a count that the time limit stopped. */
    public static String countPartialHeader(long matchingLines, int filesWithMatches) {
        return count(matchingLines) + (matchingLines == 1 ? " matching line" : " matching lines") + " so far in "
                + count(filesWithMatches) + (filesWithMatches == 1 ? " file" : " files") + ".";
    }

    /** The results status line when the search could not be run. */
    public static final String SEARCH_FAILED = "The search failed.";

    /** The results status line when Count the rest could not be finished. */
    public static final String COUNT_NOT_FINISHED = "The count could not be finished.";

    /**
     * The results status line of a finished count, for example
     * {@code Counted 7 of 7 files, 12.0 MB searched in 0.9 s}.
     *
     * @param filesCompleted files whose count reached their end
     * @param filesInScope files the search covers
     * @param bytesSearched what the engine read, summed over every count request
     * @param millis the time spent waiting for those requests
     * @param timeLimit true when the time limit stopped the count
     */
    public static String countSummary(int filesCompleted, int filesInScope, long bytesSearched, long millis,
            boolean timeLimit) {
        return "Counted " + count(filesCompleted) + " of " + count(filesInScope) + " files"
                + (timeLimit ? " before the time limit" : "") + ", " + bytes(bytesSearched) + " searched in "
                + String.format(Locale.US, "%.1f", millis / 1000.0) + " s";
    }

    /** How many matching lines one click on a group's last row loads. */
    public static final int LOAD_MORE_BATCH = 1000;

    /**
     * How many lines the next load of a group will bring, or -1 when the group's count cannot say:
     * the count is partial, or the lines shown already reach it (the file grew after the count).
     */
    private static long nextBatch(long shown, long total, boolean countComplete) {
        if (!countComplete || shown >= total) {
            return -1;
        }
        return Math.min(LOAD_MORE_BATCH, total - shown);
    }

    /** The clickable last row of a group with more lines to load. */
    public static String loadMoreRow(long shown, long total, boolean countComplete) {
        long next = nextBatch(shown, total, countComplete);
        if (next < 0) {
            return "Click to show more.";
        }
        return count(shown) + " of " + count(total) + " shown. Click to show the next " + count(next) + ".";
    }

    /** The last row of a group while its next lines load. */
    public static String loadingMoreRow(long shown, long total, boolean countComplete) {
        long next = nextBatch(shown, total, countComplete);
        return next < 0 ? "Loading more..." : "Loading the next " + count(next) + "...";
    }

    /** The results status line while a group's lines load. */
    public static String loadingMoreFrom(String fileName, long shown, long total, boolean countComplete) {
        long next = nextBatch(shown, total, countComplete);
        return next < 0 ? "Loading more from " + fileName + "..."
                : "Loading the next " + count(next) + " from " + fileName + "...";
    }

    /** The last row of a group whose file was renamed or removed by a rollover since the search. */
    public static String fileGoneRow(String fileName) {
        return fileName + " no longer exists under that name. Search again for current results.";
    }

    /** The last row of a group whose next lines could not be loaded. */
    public static final String LOAD_MORE_FAILED = "Could not load more. Click to try again.";

    /** The clickable last row of a group whose first lines the time limit stopped before any was found. */
    public static final String KEEP_SEARCHING =
            "The search reached its time limit before finding a line in this file. Click to keep searching.";

    /** The last row of a group whose first lines a pattern too deep for a line stopped before any was found. */
    public static final String PATTERN_TOO_COMPLEX_IN_FILE =
            "The search pattern is too complex for a line in this file. Try a simpler pattern.";

    /**
     * What replaces Count the rest, or a group's row that loads more, when asking from the resume point
     * answered with the same resume point.
     */
    public static final String NO_PROGRESS = "The search can't get past this point within its time limit "
            + "(a very long line or a complex pattern). Try a simpler pattern.";

    /** The warnings the server gave, as a sentence to append to a header; empty when there are none. */
    public static String warningsNote(Collection<String> warnings) {
        return warnings == null || warnings.isEmpty() ? "" : "Warnings: " + String.join("; ", warnings);
    }

    /**
     * The file name inside a server-issued id such as
     * {@code fout/mirth.log.3.zip@a03c19e4d2f8}, for a result whose file is no
     * longer in the list. Falls back to the id as it is.
     */
    public static String fileNameFromId(String id) {
        if (id == null) {
            return "";
        }
        int slash = id.indexOf('/');
        int at = id.lastIndexOf('@');
        if (slash < 0 || at < slash) {
            return id;
        }
        return id.substring(slash + 1, at);
    }

    /**
     * An engine time (epoch milliseconds) written in the engine's own time zone,
     * never the Administrator's. A missing or unknown zone id falls back to UTC,
     * which is at least a stated zone, so the label next to it stays honest.
     *
     * @param pattern a {@link DateTimeFormatter} pattern, for example {@code yyyy-MM-dd HH:mm:ss}
     */
    public static String engineTime(long millis, String zoneId, String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withZone(zone(zoneId)).format(Instant.ofEpochMilli(millis));
    }

    private static ZoneId zone(String zoneId) {
        if (zoneId == null || zoneId.isEmpty()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            return ZoneId.of("UTC");
        }
    }

    /** The status bar text that says which zone every shown time is in. */
    public static String timeZoneNotice(String zoneLabel) {
        return zoneLabel == null || zoneLabel.isEmpty() ? "Times are engine time"
                : "Times are engine time: " + zoneLabel;
    }

    /** The tooltip on Next page and Last page at the end of an active file's snapshot, where they are off. */
    public static final String SNAPSHOT_END =
            "This is the end of the snapshot. Load latest lines shows anything written since.";

    /** The strip above a viewed active file: when the engine read it, and that it stays as read. */
    public static String snapshotNotice(String fileName, String time) {
        return "Snapshot of " + fileName + " taken at " + time + " (engine time). It does not update by itself.";
    }

    /**
     * A results group header, for example {@code mirth.log (12 matching lines)}.
     * A file whose count the time limit cut short reads {@code (245+ matching lines)}.
     */
    public static String groupLabel(String fileName, long matchingLines, boolean complete) {
        return fileName + " (" + count(matchingLines) + (complete ? "" : "+")
                + (matchingLines == 1 && complete ? " matching line)" : " matching lines)");
    }

    /** The start of the results title, up to the opening quote of the search text. */
    public static final String SEARCH_TITLE_LEAD = "Search results for \"";

    /**
     * The end of the results title from the closing quote of the search text, for example
     * {@code " in all files (Java regular expression, match case)}. The title is one line; when it
     * does not fit, only the search text is cut short, so this part always shows.
     */
    public static String searchTitleRest(String fileName, boolean regex, boolean caseSensitive) {
        StringBuilder rest = new StringBuilder("\" in ").append(fileName != null ? fileName : "all files");
        if (regex && caseSensitive) {
            rest.append(" (Java regular expression, match case)");
        } else if (regex) {
            rest.append(" (Java regular expression)");
        } else if (caseSensitive) {
            rest.append(" (match case)");
        }
        return rest.toString();
    }

    /**
     * {@code text} cut into pieces of at most {@code width} characters, for a tooltip that
     * shows a search text whole: a JSON blob can be one long run with no space for a tooltip
     * to wrap at. A surrogate pair is never split.
     */
    public static List<String> pieces(String text, int width) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + width);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            pieces.add(text.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /**
     * What the viewer says about the search marks on the page, or empty when there is no search.
     *
     * @param stopped which limit stopped the engine marking the page before its end, or null
     */
    public static String highlightNote(int[] highlights, LogHighlightStop stopped) {
        if (highlights == null) {
            return "";
        }
        if (stopped == LogHighlightStop.MATCH_LIMIT) {
            return "Only the first 5,000 matches on this page are highlighted.";
        }
        if (stopped == LogHighlightStop.TIME_LIMIT) {
            return "Highlighting reached its 2-second limit, so only part of this page is highlighted.";
        }
        if (stopped == LogHighlightStop.TOO_COMPLEX) {
            return "The search pattern is too complex for a line on this page, so only part of it is highlighted.";
        }
        int matches = highlights.length / 2;
        return count(matches) + (matches == 1 ? " match" : " matches") + " highlighted on this page.";
    }
}
