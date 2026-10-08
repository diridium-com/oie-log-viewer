// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.UndoableEditEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;

import net.miginfocom.swing.MigLayout;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTATextTransferHandler;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.RUndoManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The viewer: the page shown, read only, with the file's line numbers in the gutter and a strip
 * beside the scroll bar marking where the search's matches fall. Above it, the strip that says an
 * active file is a snapshot and the note about a line split across pages; under it, the viewer's
 * one-line status line. It shows a page plainly or with its special characters, paints the match
 * positions the engine sent with the page, keeps the place in the page through a change of display
 * mode, and scrolls to a match that was jumped to.
 */
final class LogViewerPane extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(LogViewerPane.class);

    // These sit on RSyntaxTextArea's own light background whatever the look and feel is,
    // and the text on it is dark, so the same colours read in light and dark themes.
    private static final Color INVISIBLE_MARK = new Color(0x2F, 0x80, 0xED, 0x70);
    private static final Color UNDRAWABLE_MARK = new Color(0xD6, 0x2F, 0xB5, 0x80);
    // Every search match on the page is painted lightly, the one jumped to strongly.
    static final Color HIGHLIGHT_LIGHT = new Color(0xFF, 0xE9, 0x8A);
    static final Color HIGHLIGHT_STRONG = new Color(0xFF, 0x8C, 0x1A);
    private static final Color SNAPSHOT_BACKGROUND = new Color(0xE3, 0xEE, 0xFB);
    private static final Color SNAPSHOT_FOREGROUND = new Color(0x10, 0x2A, 0x4C);

    private static final String LOAD_LATEST_TIP = "Reads the end of the active file again and shows its newest lines, "
            + "including anything written since the snapshot. Does not refresh the list of files.";

    /** What the viewer needs from the dialog, which holds the state it shares with the other parts. */
    interface Host {
        /** The file the status line gives the facts of: the one viewed, else the one selected, else the first listed. */
        LogFileInfo statusFile();

        /** True while a page is being read. */
        boolean pageLoading();

        /** True while the page shown was read with the open search, so it carries that search's match positions. */
        boolean searchMarksOn();

        /** Load latest lines was pressed. */
        void loadLatest();

        /** The text was replaced (a new page, or a change of display mode), which drops every mark on it. */
        void textReplaced();
    }

    private final LogTooltips tips;
    private final Host host;

    private final JPanel snapshotStrip;
    private final JLabel lblSnapshot;
    private final JButton btnLoadLatest;
    private final JPanel noteRow;
    private final JLabel lblMidLine;
    private final RSyntaxTextArea area;
    private final RTextScrollPane scroll;
    private final LogMatchStrip matchStrip;

    // The viewer's status line, directly under the viewer
    private JLabel lblPosition;
    private JLabel lblStatus;
    private String positionExtra = "";

    private final List<Object> markTags = new ArrayList<>();
    private final List<Object> highlightTags = new ArrayList<>();
    private LogPage page;
    private String rawText = "";
    private String displayText = "";
    private List<LogDisplayText.LineEnd> lineEnds = new ArrayList<>();
    // Show special characters is on.
    private boolean special;
    // Where the search matches are in displayText, as start/end pairs; empty without a search.
    private int[] mappedHighlights = new int[0];
    // The match jumped to, as a line of the page and a column on it, so it survives a change of display mode.
    private int strongLine;
    private int strongColumn;
    private int strongLength;

    LogViewerPane(LogTooltips tips, Host host) {
        super(new BorderLayout());
        this.tips = tips;
        this.host = host;

        // The strip that says an active file is a snapshot, and the notes about the page.
        snapshotStrip = new JPanel(new MigLayout("insets 3 8 3 8, fillx", "[grow][]", ""));
        snapshotStrip.setBackground(SNAPSHOT_BACKGROUND);
        lblSnapshot = new JLabel(" ");
        lblSnapshot.putClientProperty("html.disable", Boolean.TRUE);
        lblSnapshot.setForeground(SNAPSHOT_FOREGROUND);
        tips.tip(lblSnapshot, "The active file keeps growing, but the page shown is a copy taken at this time.");
        btnLoadLatest = new JButton("Load latest lines");
        tips.tip(btnLoadLatest, LOAD_LATEST_TIP);
        btnLoadLatest.addActionListener(e -> host.loadLatest());
        snapshotStrip.add(lblSnapshot, "growx, wmin 0");
        snapshotStrip.add(btnLoadLatest);
        snapshotStrip.setVisible(false);

        lblMidLine = new JLabel(" ");
        lblMidLine.setForeground(new Color(0xB8, 0x6E, 0x00));
        tips.tip(lblMidLine, "A line longer than one page is split across pages.");
        noteRow = new JPanel(new MigLayout("insets 2 8 2 8, fillx", "[grow]", ""));
        noteRow.add(lblMidLine, "growx");
        noteRow.setVisible(false);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        snapshotStrip.setAlignmentX(Component.LEFT_ALIGNMENT);
        noteRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(snapshotStrip);
        top.add(noteRow);
        add(top, BorderLayout.NORTH);

        area = new RSyntaxTextArea() {
            @Override
            public String getToolTipText(MouseEvent e) {
                return LogCharacterTooltip.at(this, LogViewerPane.this.lineEnds, e.getPoint());
            }

            // Read only, so it keeps no undo history: RSyntaxTextArea's would hold about 100
            // earlier pages in memory, one for each time the text was replaced.
            @Override
            protected RUndoManager createUndoManager() {
                return new RUndoManager(this) {
                    @Override
                    public void undoableEditHappened(UndoableEditEvent e) {
                    }
                };
            }
        };
        // Copy (the keys and the menu) gives the page's own text for the selection: the file's
        // line ends (CR, CRLF or LF), not the LF the viewer shows each of them as, and none of
        // the CR and LF labels of special mode.
        area.setTransferHandler(new RTATextTransferHandler() {
            @Override
            protected Transferable createTransferable(JComponent c) {
                int start = area.getSelectionStart();
                int end = area.getSelectionEnd();
                return start == end ? null
                        : new StringSelection(LogDisplayText.rawText(rawText, displayText, start, end));
            }
        });
        area.setEditable(false);
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        // Plain text, but with surrogate pairs kept whole so an emoji is drawn, not two boxes.
        ((RSyntaxDocument) area.getDocument()).setSyntaxStyle(new AstralSafeTokenMaker());
        area.setCodeFoldingEnabled(false);
        area.setBracketMatchingEnabled(false);
        area.setHighlightCurrentLine(false);
        area.setHyperlinksEnabled(false);
        area.setLineWrap(true);
        // The logical font falls back to other installed fonts one character at a time,
        // where a physical one (Consolas, Menlo) would show a box for anything it lacks.
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize());
        area.setFont(mono);
        ToolTipManager.sharedInstance().registerComponent(area);

        scroll = new RTextScrollPane(area);
        scroll.getGutter().setLineNumberFont(mono);

        matchStrip = new LogMatchStrip(area, tips);
        area.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                matchStrip.repaint();
            }
        });
        JPanel viewer = new JPanel(new BorderLayout());
        viewer.add(scroll, BorderLayout.CENTER);
        viewer.add(matchStrip, BorderLayout.EAST);
        add(viewer, BorderLayout.CENTER);
        // Under the viewer and inside it, so it moves with the divider above the results.
        add(createStatusLine(), BorderLayout.SOUTH);
    }

    /** The viewer's status line: where the page is on the left, what is happening and the file's facts on the right. */
    private JPanel createStatusLine() {
        Color line = UIManager.getColor("Separator.foreground");
        // Wraps, so the facts on the right drop to a second line rather than being cut off when the viewer is narrow.
        JPanel bar = new JPanel(new LogWrapLayout(FlowLayout.LEFT, 8, 2));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, line != null ? line : Color.LIGHT_GRAY));
        lblPosition = new JLabel(" ");
        tips.tip(lblPosition, "Which lines of the file the page shown holds, out of all the file's lines, and whether it "
                + "touches the start or the end of the file." + LogToolbar.PAGE_NOTE);
        lblStatus = new JLabel(" ");
        bar.add(lblPosition);
        bar.add(lblStatus);
        // A wrapping row only knows its height once it knows its width.
        bar.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(bar::revalidate);
            }
        });
        return bar;
    }

    /** The page shown, or null. */
    LogPage page() {
        return page;
    }

    /** The text area, which Find on this page searches and marks. */
    RSyntaxTextArea textArea() {
        return area;
    }

    /**
     * Load latest lines on or off; while the file shown is gone, its tooltip says so.
     *
     * @param goneTip the tooltip while the file shown is gone, or null
     */
    void setLoadLatestEnabled(boolean enabled, String goneTip) {
        btnLoadLatest.setEnabled(enabled);
        tips.tip(btnLoadLatest, goneTip != null ? goneTip : LOAD_LATEST_TIP);
    }

    void setWordWrap(boolean on) {
        int topLine = topLine();
        area.setLineWrap(on);
        SwingUtilities.invokeLater(() -> scrollToLine(topLine));
        matchStrip.repaint();
    }

    void setSpecialCharacters(boolean on) {
        special = on;
        if (page != null) {
            int topLine = topLine();
            applyDisplay();
            SwingUtilities.invokeLater(() -> scrollToLine(topLine));
        } else {
            area.setWhitespaceVisible(on);
        }
    }

    // ------------------------------------------------------------------
    // Showing a page
    // ------------------------------------------------------------------

    /**
     * Shows a page of {@code file}.
     *
     * @param reveal the search match to scroll to and paint strongly, or null
     * @param keepView keep the scroll position, for a page that is only re-read to add the search marks
     */
    void showPage(LogPage p, LogFileInfo file, LogPageAnchor anchor, LogSearchMatch reveal, boolean keepView) {
        final int topLine = keepView ? topLine() : 0;
        page = p;
        rawText = p.getText() == null ? "" : p.getText();
        strongLength = 0;
        applyDisplay();

        // Line numbers start where the server counted from. Without a count they would be
        // wrong, so they are hidden and the position label carries byte offsets instead.
        Long first = p.getFirstLineNumber();
        boolean numbered = first != null && first >= 1 && first <= Integer.MAX_VALUE;
        scroll.setLineNumbersEnabled(numbered);
        if (numbered) {
            scroll.getGutter().setLineNumberingStartIndex(first.intValue());
        }

        positionExtra = "";
        lblMidLine.setText(LogViewerFormat.midLineNotes(p.isStartsMidLine(), p.isEndsMidLine()));
        updateSnapshotStrip(file);
        updateNoteRow();
        updateStatusLine();

        if (reveal != null) {
            SwingUtilities.invokeLater(() -> revealMatch(p, reveal, numbered));
        } else if (keepView) {
            SwingUtilities.invokeLater(() -> scrollToLine(topLine));
        } else if (anchor == LogPageAnchor.TAIL || anchor == LogPageAnchor.BEFORE) {
            // Reading backwards or following the end of the log: the bottom is where the eye goes.
            SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
        } else {
            SwingUtilities.invokeLater(() -> area.setCaretPosition(0));
        }
    }

    /** Empties the viewer: no page, no line numbers, no notes. */
    void clear() {
        page = null;
        rawText = "";
        strongLength = 0;
        applyDisplay();
        scroll.setLineNumbersEnabled(false);
        positionExtra = "";
        lblMidLine.setText(" ");
        updateSnapshotStrip(null);
        updateNoteRow();
        updateStatusLine();
    }

    /** The search was closed or replaced: the match jumped to and the engine's marks come off the page. */
    void clearSearchMarks() {
        strongLength = 0;
        refreshHighlights();
        updateNoteRow();
    }

    /** The strip above an active file: it is a copy taken at a time, not a live view. */
    private void updateSnapshotStrip(LogFileInfo viewed) {
        boolean show = page != null && viewed != null && viewed.isActive();
        if (show) {
            String time = LogViewerFormat.engineTime(page.getReadAt(), viewed.getTimeZoneId(), "HH:mm:ss");
            lblSnapshot.setText(LogViewerFormat.snapshotNotice(viewed.getName(), time));
        }
        snapshotStrip.setVisible(show);
    }

    /** The note under the strip: a line that is split across pages. */
    private void updateNoteRow() {
        String mid = lblMidLine.getText() == null ? "" : lblMidLine.getText().trim();
        noteRow.setVisible(!mid.isEmpty());
        noteRow.revalidate();
    }

    /**
     * The viewer's status line. Left: where the page sits in the file. Right: whether a page is
     * loading, what the search marks on the page amount to, then the file's encoding and size and
     * the time zone every time on screen is in (the viewed file's). The search's own progress is
     * on the results' status line.
     */
    void updateStatusLine() {
        String position = " ";
        if (page != null) {
            Long first = page.getFirstLineNumber();
            boolean numbered = first != null && first >= 1 && first <= Integer.MAX_VALUE;
            position = LogViewerFormat.statusPosition(numbered ? first : null,
                    rawText.isEmpty() ? 0 : LogDisplayText.lineCount(displayText), page.getTotalLines(),
                    page.getStartOffset(), page.getEndOffset(), page.getContentLength(), page.isAtEnd())
                    + positionExtra;
        }
        lblPosition.setText(position);

        LogFileInfo source = host.statusFile();
        String marks = host.searchMarksOn() && page != null
                ? LogViewerFormat.highlightNote(page.getHighlights(), page.getHighlightsStopped())
                : "";
        String encoding = source == null ? "" : source.getCharset();
        // An uncompressed file's size as of the page, which Load latest lines keeps current (the
        // list's size lags behind the active file); an archive's size on disk, as in the list.
        String size = "";
        if (source != null && !source.isCompressed() && page != null && page.getContentLength() != null) {
            size = LogViewerFormat.bytes(page.getContentLength());
        } else if (source != null) {
            size = LogViewerFormat.bytes(source.getSize());
        }
        String zone = source == null ? "" : LogViewerFormat.timeZoneNotice(source.getTimeZoneLabel());
        String text = LogViewerFormat.statusItems(host.pageLoading() ? "Loading..." : "", marks, encoding, size, zone);
        lblStatus.setText(text.isEmpty() ? " " : text);
        String id = source == null ? null : source.getTimeZoneId();
        tips.tip(lblStatus, "Whether a page is loading, the matches of the open search that are marked on this page "
                + "(the engine finds them; the viewer only paints them), then the file's character encoding and "
                + "size (an archive's size is its size on disk, as in the file list), and the time zone of every time this "
                + "window shows" + (id != null ? " (" + id + ")" : "")
                + ", which is the engine's, not that of this computer.");
    }

    /** Fills the text area from {@code rawText} in the mode the toggle says, and redoes the marks. */
    private void applyDisplay() {
        if (special) {
            LogDisplayText.Special shown = LogDisplayText.special(rawText);
            displayText = shown.getText();
            lineEnds = shown.getLineEnds();
        } else {
            displayText = LogDisplayText.normal(rawText);
            lineEnds = new ArrayList<>();
        }

        Highlighter highlighter = area.getHighlighter();
        for (Object tag : markTags) {
            highlighter.removeHighlight(tag);
        }
        markTags.clear();
        for (Object tag : highlightTags) {
            highlighter.removeHighlight(tag);
        }
        highlightTags.clear();
        area.clearMarkAllHighlights();

        area.setText(displayText);
        area.setWhitespaceVisible(special);

        if (special) {
            // The CR and LF labels are text in the viewer; a painter boxes each one. Copy gives the
            // file's own line ends in their place (see the transfer handler).
            LogLineEndPainter lineEndPainter = new LogLineEndPainter();
            for (LogDisplayText.LineEnd end : lineEnds) {
                try {
                    markTags.add(highlighter.addHighlight(end.getStart(), end.getEnd(), lineEndPainter));
                } catch (BadLocationException e) {
                    log.debug("No such range for a line end label: {}", end.getStart());
                }
            }
            Font font = area.getFont();
            addMarks(LogDisplayText.invisibleRanges(displayText), INVISIBLE_MARK);
            addMarks(LogDisplayText.undisplayableRanges(displayText, font::canDisplay), UNDRAWABLE_MARK);
        }
        refreshHighlights();
        host.textReplaced();
        area.setCaretPosition(0);
    }

    /**
     * Paints the engine's search matches on the page: all of them lightly, the one jumped to
     * strongly. Only while the results are open; the positions are the page's own, mapped from
     * the raw text onto the displayed text.
     */
    void refreshHighlights() {
        Highlighter highlighter = area.getHighlighter();
        for (Object tag : highlightTags) {
            highlighter.removeHighlight(tag);
        }
        highlightTags.clear();

        mappedHighlights = !host.searchMarksOn() || page == null ? new int[0]
                : LogDisplayText.mapHighlights(rawText, displayText, page.getHighlights());
        // The highlighter paints the highlight added first on top of the ones added after it,
        // so the match jumped to goes in first and is not covered by its light twin.
        if (host.searchMarksOn() && strongLength > 0) {
            try {
                int start = area.getLineStartOffset(strongLine) + strongColumn;
                highlightTags.add(highlighter.addHighlight(start, start + strongLength,
                        new DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_STRONG)));
            } catch (BadLocationException e) {
                log.debug("No such range for the match jumped to: line {}", strongLine);
            }
        }
        DefaultHighlighter.DefaultHighlightPainter light = new DefaultHighlighter.DefaultHighlightPainter(HIGHLIGHT_LIGHT);
        for (int i = 0; i + 1 < mappedHighlights.length; i += 2) {
            try {
                highlightTags.add(highlighter.addHighlight(mappedHighlights[i], mappedHighlights[i + 1], light));
            } catch (BadLocationException e) {
                log.debug("No such range for a search match: {}", mappedHighlights[i]);
            }
        }
        int strongStart = -1;
        if (strongLength > 0) {
            try {
                strongStart = area.getLineStartOffset(strongLine) + strongColumn;
            } catch (BadLocationException e) {
                log.debug("No such line for the match jumped to: {}", strongLine);
            }
        }
        matchStrip.show(mappedHighlights, strongStart);
    }

    private void addMarks(List<int[]> ranges, Color color) {
        Highlighter highlighter = area.getHighlighter();
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(color);
        for (int[] range : ranges) {
            try {
                markTags.add(highlighter.addHighlight(range[0], range[1], painter));
            } catch (BadLocationException e) {
                log.debug("No such range to mark: {}-{}", range[0], range[1]);
            }
        }
    }

    /**
     * Scrolls to a search match in the page that was just opened at it, and paints it strongly. The
     * engine says where the match starts in the page text; only without that is the line looked for.
     * The keyboard stays where it was, so stepping through the results goes on there.
     */
    private void revealMatch(LogPage p, LogSearchMatch match, boolean numbered) {
        String lineText = match.getLineText() == null ? "" : match.getLineText();
        int matchStart = Math.max(0, Math.min(match.getMatchStart(), lineText.length()));
        int matchEnd = Math.max(matchStart, Math.min(match.getMatchEnd(), lineText.length()));
        String hit = lineText.substring(matchStart, matchEnd);

        int start = p.getTargetIndex() == null ? -1
                : LogDisplayText.displayIndex(rawText, displayText, p.getTargetIndex());
        try {
            if (start < 0 && numbered && p.getFirstLineNumber() != null) {
                long index = match.getLineNumber() - p.getFirstLineNumber();
                if (index >= 0 && index < area.getLineCount()) {
                    int lineStart = area.getLineStartOffset((int) index);
                    int lineEnd = Math.min(area.getLineEndOffset((int) index), displayText.length());
                    String line = displayText.substring(lineStart, lineEnd);
                    int rel = locateInLine(line, lineText, matchStart, hit);
                    start = lineStart + Math.max(rel, 0);
                    if (rel < 0) {
                        // The line is there but the match is not where it should be: show the line.
                        area.select(lineStart, Math.max(lineStart, lineEnd - 1));
                        area.getCaret().setSelectionVisible(true);
                        scrollToSelection();
                        return;
                    }
                }
            }
            if (start < 0) {
                // No line count, or the number is off the page: look for the text instead.
                int rel = locateInLine(displayText, lineText, matchStart, hit);
                if (rel < 0) {
                    positionExtra = " - the match could not be located on this page";
                    updateStatusLine();
                    return;
                }
                start = rel;
            }
            strongLine = area.getLineOfOffset(start);
            strongColumn = start - area.getLineStartOffset(strongLine);
            strongLength = hit.length();
            refreshHighlights();
        } catch (BadLocationException e) {
            return;
        }
        area.setCaretPosition(start);
        scrollToSelection();
    }

    /**
     * Where the match starts inside {@code haystack}, or -1. The server's line text is
     * the whole line unless it was cut to a window around the match, in which case the
     * window is looked for first and the match position is taken inside it.
     */
    private static int locateInLine(String haystack, String lineText, int matchStart, String hit) {
        if (hit.isEmpty()) {
            return -1;
        }
        int window = haystack.indexOf(lineText);
        if (window >= 0) {
            int rel = window + matchStart;
            if (haystack.startsWith(hit, rel)) {
                return rel;
            }
        }
        return haystack.indexOf(hit);
    }

    private void scrollToSelection() {
        try {
            Rectangle2D r = area.modelToView2D(area.getSelectionStart());
            if (r == null) {
                return;
            }
            Rectangle visible = area.getVisibleRect();
            // Centre the match instead of leaving it on the edge of the view.
            Rectangle target = new Rectangle(0, Math.max(0, (int) r.getY() - visible.height / 2),
                    1, Math.max(visible.height, 1));
            area.scrollRectToVisible(target);
        } catch (BadLocationException e) {
            // Selection was just made; nothing to scroll to.
        }
    }

    private int topLine() {
        try {
            Point position = scroll.getViewport().getViewPosition();
            return area.getLineOfOffset(area.viewToModel2D(position));
        } catch (BadLocationException e) {
            return 0;
        }
    }

    private void scrollToLine(int line) {
        try {
            int index = Math.max(0, Math.min(line, area.getLineCount() - 1));
            Rectangle2D r = area.modelToView2D(area.getLineStartOffset(index));
            if (r != null) {
                scroll.getViewport().setViewPosition(new Point(0, (int) r.getY()));
            }
        } catch (BadLocationException e) {
            // The text changed under us; leave the view where it is.
        }
    }
}
