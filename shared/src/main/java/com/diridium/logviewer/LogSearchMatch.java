// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;

/**
 * One matching line. To open it, ask for the page {@link LogPageAnchor#AT}
 * {@link #getMatchOffset()} of {@link #getFileId()}.
 */
public class LogSearchMatch implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private long lineNumber;
    private long lineOffset;
    private long matchOffset;
    private String lineText;
    private int matchStart;
    private int matchEnd;
    private boolean truncated;

    public LogSearchMatch() {
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    /** 1-based line number. Search reads every file from its start, so this is always known. */
    public long getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(long lineNumber) {
        this.lineNumber = lineNumber;
    }

    /**
     * Byte offset of the start of the line, or of the searched piece of it
     * when the line was too long to search whole.
     */
    public long getLineOffset() {
        return lineOffset;
    }

    public void setLineOffset(long lineOffset) {
        this.lineOffset = lineOffset;
    }

    /**
     * Byte offset of the first match on the line, exact even when bytes that
     * are not valid in the file's charset come before it.
     */
    public long getMatchOffset() {
        return matchOffset;
    }

    public void setMatchOffset(long matchOffset) {
        this.matchOffset = matchOffset;
    }

    /**
     * The line's text for display, cut to a window around the match when the
     * line is long ({@link #isTruncated()}). Control characters are shown as
     * control pictures, as in {@link LogPage#getText()}.
     */
    public String getLineText() {
        return lineText;
    }

    public void setLineText(String lineText) {
        this.lineText = lineText;
    }

    /** Index in {@link #getLineText()} where the match begins. */
    public int getMatchStart() {
        return matchStart;
    }

    public void setMatchStart(int matchStart) {
        this.matchStart = matchStart;
    }

    /** Index in {@link #getLineText()} just past the match (clipped to the text). */
    public int getMatchEnd() {
        return matchEnd;
    }

    public void setMatchEnd(int matchEnd) {
        this.matchEnd = matchEnd;
    }

    /** True when {@link #getLineText()} is only part of the line. */
    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }
}
