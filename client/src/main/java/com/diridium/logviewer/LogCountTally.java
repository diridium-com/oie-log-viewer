// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running total of a count that may take several requests. When the time limit
 * stops a count, the next request resumes inside the file it stopped in, and the
 * first entry of that result continues the file's partial count: it is added to
 * it, and the entries after it are appended. Pure, so the merge is tested without
 * a display.
 */
final class LogCountTally {

    private final Map<String, LogFileMatchCount> files = new LinkedHashMap<>();
    private int filesInScope;
    private long bytesSearched;
    private long elapsedMillis;

    /**
     * True when a search or count that was sent a resume point answered with that same point: the
     * time limit stopped it before it got anywhere, so asking again would stop there again.
     *
     * @param sentFileId the resume point the request carried, or null for a request that did not resume
     */
    static boolean sameResumePoint(String sentFileId, Long sentOffset, LogSearchResult result) {
        return sentFileId != null && sentOffset != null && sentFileId.equals(result.getResumeFileId())
                && sentOffset.equals(result.getResumeOffset());
    }

    /** Adds one count result. Entries are copied, so the result itself is never changed. */
    void add(LogSearchResult result) {
        filesInScope = Math.max(filesInScope, result.getFilesInScope());
        bytesSearched += result.getBytesSearched();
        if (result.getFileCounts() == null) {
            return;
        }
        for (LogFileMatchCount entry : result.getFileCounts()) {
            LogFileMatchCount known = files.get(entry.getFileId());
            if (known != null && !known.isComplete()) {
                known.setMatchingLines(known.getMatchingLines() + entry.getMatchingLines());
                known.setComplete(entry.isComplete());
            } else {
                LogFileMatchCount copy = new LogFileMatchCount(entry.getFileId());
                copy.setMatchingLines(entry.getMatchingLines());
                copy.setComplete(entry.isComplete());
                files.put(entry.getFileId(), copy);
            }
        }
    }

    /** Adds the time the viewer waited for one count request. */
    void addElapsed(long millis) {
        elapsedMillis += millis;
    }

    /** Every file counted so far, in the order the server returned them (newest first). */
    List<LogFileMatchCount> entries() {
        return new ArrayList<>(files.values());
    }

    long matchingLines() {
        long total = 0;
        for (LogFileMatchCount entry : files.values()) {
            total += entry.getMatchingLines();
        }
        return total;
    }

    int filesWithMatches() {
        int n = 0;
        for (LogFileMatchCount entry : files.values()) {
            if (entry.getMatchingLines() > 0) {
                n++;
            }
        }
        return n;
    }

    /** Files with an entry, whether or not the count of each reached its end. */
    int filesCounted() {
        return files.size();
    }

    /** Files counted to their end. */
    int filesCompleted() {
        int n = 0;
        for (LogFileMatchCount entry : files.values()) {
            if (entry.isComplete()) {
                n++;
            }
        }
        return n;
    }

    int filesInScope() {
        return filesInScope;
    }

    /** What the engine read, summed over every count request. */
    long bytesSearched() {
        return bytesSearched;
    }

    /** The time the viewer waited for the count requests, summed. */
    long elapsedMillis() {
        return elapsedMillis;
    }
}
