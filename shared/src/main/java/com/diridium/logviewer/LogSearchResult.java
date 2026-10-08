// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of a search or a count: the matches (or, for a count, matching lines
 * per file), plus enough bookkeeping that a partial result can never pass for
 * a complete one.
 *
 * <p>Files are searched newest first; within a file, lines are in file order.
 * When a cap stops the search, {@link #getStopReason()} says which, and
 * {@link #getResumeFileId()} / {@link #getResumeOffset()} say where to pick up:
 * passing them back continues the same search from the first line not yet
 * searched.</p>
 */
public class LogSearchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    // ArrayList, never List.of(): see LogFileList.
    private List<LogSearchMatch> matches = new ArrayList<>();
    private List<LogFileMatchCount> fileCounts;
    private List<String> warnings = new ArrayList<>();
    private boolean complete;
    private LogSearchStopReason stopReason;
    private String resumeFileId;
    private Long resumeOffset;
    private int filesInScope;
    private int filesSearched;
    private long bytesSearched;
    private long splitLineCount;

    public LogSearchResult() {
    }

    public List<LogSearchMatch> getMatches() {
        return matches;
    }

    public void setMatches(List<LogSearchMatch> matches) {
        this.matches = matches;
    }

    /**
     * For a count, one entry per file counted, in the order counted, files
     * with no matching lines included; a file not reached has no entry. Null
     * for a search. A count never returns matches.
     */
    public List<LogFileMatchCount> getFileCounts() {
        return fileCounts;
    }

    public void setFileCounts(List<LogFileMatchCount> fileCounts) {
        this.fileCounts = fileCounts;
    }

    /**
     * Anything that makes the result less than a full search of the scope
     * without being a stop: a file that could not be read or is not
     * searchable, or lines long enough to be searched in pieces.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }

    /** True only when every file in scope was searched to its end and there are no warnings. */
    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    /** Null when the search ran to the end of its scope. */
    public LogSearchStopReason getStopReason() {
        return stopReason;
    }

    public void setStopReason(LogSearchStopReason stopReason) {
        this.stopReason = stopReason;
    }

    /** File to resume in, or null when there is nothing to resume. */
    public String getResumeFileId() {
        return resumeFileId;
    }

    public void setResumeFileId(String resumeFileId) {
        this.resumeFileId = resumeFileId;
    }

    /** Byte offset to resume at, or null when there is nothing to resume. */
    public Long getResumeOffset() {
        return resumeOffset;
    }

    public void setResumeOffset(Long resumeOffset) {
        this.resumeOffset = resumeOffset;
    }

    public int getFilesInScope() {
        return filesInScope;
    }

    public void setFilesInScope(int filesInScope) {
        this.filesInScope = filesInScope;
    }

    /** Files searched to their end in this request. */
    public int getFilesSearched() {
        return filesSearched;
    }

    public void setFilesSearched(int filesSearched) {
        this.filesSearched = filesSearched;
    }

    /** Content bytes searched in this request (uncompressed bytes, for archives). */
    public long getBytesSearched() {
        return bytesSearched;
    }

    public void setBytesSearched(long bytesSearched) {
        this.bytesSearched = bytesSearched;
    }

    /**
     * Lines too long to search whole, which were searched in pieces. A match
     * that straddles two pieces is not found; a warning says so when this is
     * non-zero.
     */
    public long getSplitLineCount() {
        return splitLineCount;
    }

    public void setSplitLineCount(long splitLineCount) {
        this.splitLineCount = splitLineCount;
    }
}
