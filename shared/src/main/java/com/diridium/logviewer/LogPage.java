// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;

/**
 * A window of a log file's text.
 *
 * <p>Pages start and end on line boundaries, except where a single line is
 * longer than the page cap: that line is split and the split is flagged by
 * {@link #isStartsMidLine()} / {@link #isEndsMidLine()}. To page backwards ask
 * for {@link LogPageAnchor#BEFORE} {@link #getStartOffset()}; to page forwards
 * ask for {@link LogPageAnchor#AFTER} {@link #getEndOffset()}.</p>
 *
 * <p>The text is for display. Line terminators arrive exactly as in the file
 * (LF, CRLF, or a lone CR as between HL7 segments), and a line ends at any of
 * the three, which is how {@link #getFirstLineNumber()} counts. Control
 * characters other than tab, CR and LF are shown as their Unicode control
 * pictures (for example U+240B for the vertical tab that frames an MLLP
 * message), because XML 1.0 cannot carry them and the engine's JSON
 * serializer fails on them; the delete character becomes U+2421, the nine
 * bidirectional override characters U+2426, and invalid bytes U+FFFD, all
 * one char for one char. Downloads are always the raw bytes.</p>
 */
public class LogPage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private long startOffset;
    private long endOffset;
    private Long contentLength;
    private Long firstLineNumber;
    private Long totalLines;
    private String text;
    private boolean startsMidLine;
    private boolean endsMidLine;
    private boolean atEnd;
    private long readAt;
    private int[] highlights;
    private LogHighlightStop highlightsStopped;
    private Integer targetIndex;

    public LogPage() {
    }

    /** The id of the file this page was read from, echoed back. */
    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    /** Byte offset of the first byte of the page, inclusive. */
    public long getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(long startOffset) {
        this.startOffset = startOffset;
    }

    /** Byte offset just past the last byte of the page, exclusive. */
    public long getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(long endOffset) {
        this.endOffset = endOffset;
    }

    /**
     * Length of the file's content in bytes when it is known cheaply: always
     * for an uncompressed file (its length when the page was read, since the
     * active file keeps growing), and for an archive only when this read
     * reached its end. Otherwise null.
     */
    public Long getContentLength() {
        return contentLength;
    }

    public void setContentLength(Long contentLength) {
        this.contentLength = contentLength;
    }

    /**
     * 1-based line number of the first line on the page, counting LF, CRLF
     * and a lone CR each as one line end. Null when counting would have meant
     * reading too much of the file for one page.
     */
    public Long getFirstLineNumber() {
        return firstLineNumber;
    }

    public void setFirstLineNumber(Long firstLineNumber) {
        this.firstLineNumber = firstLineNumber;
    }

    /**
     * Lines in the whole file when the page was read, counted the same way as
     * {@link #getFirstLineNumber()}, a last line without its terminator
     * included. Null under the same limit as the line numbers, which applies
     * here to the whole file's length.
     */
    public Long getTotalLines() {
        return totalLines;
    }

    public void setTotalLines(Long totalLines) {
        this.totalLines = totalLines;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    /** True when the page's first line is the continuation of a line longer than the page cap. */
    public boolean isStartsMidLine() {
        return startsMidLine;
    }

    public void setStartsMidLine(boolean startsMidLine) {
        this.startsMidLine = startsMidLine;
    }

    /** True when the page's last line continues on the next page. */
    public boolean isEndsMidLine() {
        return endsMidLine;
    }

    public void setEndsMidLine(boolean endsMidLine) {
        this.endsMidLine = endsMidLine;
    }

    /**
     * True when nothing followed the page at the time it was read. The active
     * file may have grown since; asking for {@code AFTER endOffset} again
     * picks up new lines.
     */
    public boolean isAtEnd() {
        return atEnd;
    }

    public void setAtEnd(boolean atEnd) {
        this.atEnd = atEnd;
    }

    /**
     * When the engine read the page, in milliseconds since the epoch on the
     * engine's clock. For the active file this is the moment the snapshot was
     * taken: the page does not update by itself.
     */
    public long getReadAt() {
        return readAt;
    }

    public void setReadAt(long readAt) {
        this.readAt = readAt;
    }

    /**
     * Where the requested search matches in {@link #getText()}, as consecutive
     * start/end pairs of char indices (start inclusive, end exclusive), in
     * order. Null when the request carried no search. Computed by the engine
     * with the same Java regular expression the search used, one line at a
     * time, so a match never spans a line end.
     */
    public int[] getHighlights() {
        return highlights;
    }

    public void setHighlights(int[] highlights) {
        this.highlights = highlights;
    }

    /** Which limit stopped the highlighting before the end of the page; null when it reached the end. */
    public LogHighlightStop getHighlightsStopped() {
        return highlightsStopped;
    }

    public void setHighlightsStopped(LogHighlightStop highlightsStopped) {
        this.highlightsStopped = highlightsStopped;
    }

    /**
     * For a page read {@link LogPageAnchor#AT} an offset: the char index in
     * {@link #getText()} of the character that starts at that offset (the
     * text's length when the offset is the end of the file), so a viewer can
     * go straight to a search match, whose offset is always a character's
     * start. An offset inside a character gives an index just past it. Null
     * for the other anchors, and when the offset is not on the page.
     */
    public Integer getTargetIndex() {
        return targetIndex;
    }

    public void setTargetIndex(Integer targetIndex) {
        this.targetIndex = targetIndex;
    }
}
