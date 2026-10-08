// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;

/** One file's part of a count: how many of its lines match. */
public class LogFileMatchCount implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private long matchingLines;
    private boolean complete;

    public LogFileMatchCount() {
    }

    public LogFileMatchCount(String fileId) {
        this.fileId = fileId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    /**
     * Lines with at least one match. A line longer than the search's piece
     * size counts once however many of its pieces match.
     */
    public long getMatchingLines() {
        return matchingLines;
    }

    public void setMatchingLines(long matchingLines) {
        this.matchingLines = matchingLines;
    }

    /**
     * True when the file was counted to its end in this request. False for
     * the file a stopped count will resume in (add the resumed count's first
     * entry to this one) and for a file that could not be read to its end.
     */
    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }
}
