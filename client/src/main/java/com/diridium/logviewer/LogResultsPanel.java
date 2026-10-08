// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static com.diridium.logviewer.LogTooltips.escapeHtml;
import static com.diridium.logviewer.LogTooltips.keepSpaces;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import net.miginfocom.swing.MigLayout;

/**
 * The counts of "Search all files...", docked under the viewer only after a search: a title naming
 * the search (a click on it opens the Search dialog with it), how many lines match in how many
 * files, one collapsed group per file with matching lines, and a status line of their own at the
 * bottom. The dialog runs the requests; this panel keeps the counts and the rows, and asks for a
 * group's lines when the group is expanded or its last row is clicked.
 */
final class LogResultsPanel extends JPanel {

    // The row that loads more matches is a link; on the tree's own background, as the other rows are.
    private static final Color MORE_ROW_FOREGROUND = new Color(0x1A, 0x5C, 0xB8);

    /** What the results ask of the dialog. */
    interface Listener {
        /** The title was clicked: open the Search dialog with the search the results are for. */
        void editSearch(LogSearchParams search);

        /** Count the rest was pressed. */
        void countRest();

        /** Close results was pressed. */
        void close();

        /** A group's lines: its first ones when it is expanded, the next ones when its last row is clicked. */
        void loadMatches(GroupNode group);

        /** A matching line was double-clicked (or Enter was pressed on it): show it in the viewer. */
        void openMatch(LogSearchMatch match, String fileName);

        /** The listed file with this id, which names its group; null when it is no longer listed. */
        LogFileInfo listedFile(String fileId);
    }

    private final LogTooltips tips;
    private final Listener listener;

    private final JPanel resultsTitle;
    private final JLabel lblTitleLead;
    private final JLabel lblTitleQuery;
    private final JLabel lblTitleRest;
    /** The search the results title shows; clicking the title opens the Search dialog with it. */
    private LogSearchParams titleSearch;
    private final JLabel lblCountLine;
    private final JLabel lblResultsActivity;
    private final JLabel lblResultsNotes;
    private final JButton btnCountRest;
    private final JScrollPane resultsScroll;
    private final JTree resultsTree;
    private final DefaultTreeModel resultsModel;
    private final DefaultMutableTreeNode resultsRoot;
    private final Map<String, GroupNode> groups = new LinkedHashMap<>();
    private LogCountTally tally = new LogCountTally();
    private final Set<String> allWarnings = new LinkedHashSet<>();
    // What the results status line says when nothing is running: the summary of the count, or why it failed.
    private String countOutcome = "";

    LogResultsPanel(LogTooltips tips, Listener listener) {
        super(new BorderLayout());
        this.tips = tips;
        this.listener = listener;

        // One line: Search results for "<search text>" in all files (options). When the line is short
        // of room the search text gives way first (a JSON blob can be 1,000 characters), then the
        // scope (a file in a date folder has a long name), each cut with an ellipsis, so Count the
        // rest and Close results stay in the window; the tooltip and a click show the text whole.
        lblTitleLead = new JLabel(LogViewerFormat.SEARCH_TITLE_LEAD);
        lblTitleQuery = new JLabel();
        lblTitleRest = new JLabel();
        resultsTitle = new JPanel(new MigLayout("insets 0, gap 0", "[][][]", ""));
        resultsTitle.add(lblTitleLead);
        resultsTitle.add(lblTitleQuery, "shrinkprio 200");
        resultsTitle.add(lblTitleRest, "wmin 0");
        MouseAdapter reopenSearch = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (titleSearch != null) {
                    listener.editSearch(titleSearch);
                }
            }
        };
        for (JLabel part : new JLabel[] {lblTitleLead, lblTitleQuery, lblTitleRest}) {
            // The search text and a file name are shown as they are, never as HTML.
            part.putClientProperty("html.disable", Boolean.TRUE);
            part.setFont(part.getFont().deriveFont(Font.BOLD));
            part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            part.addMouseListener(reopenSearch);
        }
        btnCountRest = new JButton("Count the rest");
        tips.tip(btnCountRest, "The time limit stopped the count before every file was counted. Counts the files "
                + "that are left and adds them to this list.");
        btnCountRest.setVisible(false);
        btnCountRest.addActionListener(e -> listener.countRest());
        JButton btnClose = new JButton("Close results");
        tips.tip(btnClose, "Closes the results and removes the search marks from the viewer.");
        btnClose.addActionListener(e -> listener.close());
        lblCountLine = new JLabel(" ");
        tips.tip(lblCountLine, "How many lines match, and in how many files.");
        JPanel header = new JPanel(new MigLayout("insets 4 8 4 8, fillx", "[grow][][]", ""));
        header.add(resultsTitle, "growx");
        header.add(btnCountRest);
        header.add(btnClose, "wrap");
        header.add(lblCountLine, "span, growx");
        add(header, BorderLayout.NORTH);

        // The results' status line, under the results: what the search is doing or did, and its notes.
        Color line = UIManager.getColor("Separator.foreground");
        JPanel resultsStatus = new JPanel(new MigLayout("insets 3 8 3 8, fillx", "[grow][]", ""));
        resultsStatus.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, line != null ? line : Color.LIGHT_GRAY));
        lblResultsActivity = new JLabel(" ");
        lblResultsActivity.putClientProperty("html.disable", Boolean.TRUE);
        tips.tip(lblResultsActivity, "What the search is doing, or when it is done how many files were counted, how "
                + "much of the logs the engine read and how long it took.");
        lblResultsNotes = new JLabel(" ");
        lblResultsNotes.putClientProperty("html.disable", Boolean.TRUE);
        tips.tip(lblResultsNotes, "Notes the engine gave about the search, such as a file it could not read.");
        // Both give way with an ellipsis rather than run past the window; the tooltips hold the whole text.
        resultsStatus.add(lblResultsActivity, "growx, wmin 0");
        resultsStatus.add(lblResultsNotes, "gapleft 12, wmin 0");
        add(resultsStatus, BorderLayout.SOUTH);

        resultsRoot = new DefaultMutableTreeNode("Search results");
        // A group is not a leaf even before its lines are fetched, so it shows an expand handle.
        resultsModel = new DefaultTreeModel(resultsRoot, true);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, new JLabel().getFont().getSize());
        resultsTree = new JTree(resultsModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                TreePath path = getPathForLocation(e.getX(), e.getY());
                if (path == null) {
                    return super.getToolTipText(e);
                }
                Object node = path.getLastPathComponent();
                if (node instanceof HitNode) {
                    return ((HitNode) node).match.isTruncated()
                            ? "The line is long and shortened here. Double-click to see it in the viewer."
                            : "Double-click to show this line in the viewer.";
                }
                if (node instanceof MoreNode) {
                    return LogViewerFormat.KEEP_SEARCHING.equals(((MoreNode) node).text)
                            ? "Continues the search in this file from where the time limit stopped it."
                            : "Loads the next matching lines of this file.";
                }
                if (node instanceof GroupNode) {
                    GroupNode group = (GroupNode) node;
                    return LogViewerFormat.count(group.count) + (group.complete ? "" : "+")
                            + (group.count == 1 && group.complete ? " matching line" : " matching lines")
                            + " in " + group.name + (group.complete ? "." : " so far; the count is not finished.")
                            + " Expand to see the lines.";
                }
                return null;
            }
        };
        resultsTree.setRootVisible(false);
        resultsTree.setShowsRootHandles(true);
        resultsTree.setRowHeight(resultsTree.getFontMetrics(mono).getHeight() + 4);
        resultsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        resultsTree.setCellRenderer(new ResultsRenderer(mono));
        resultsTree.setToggleClickCount(0);
        tips.tip(resultsTree, "The files with matching lines. Expand a file to see its lines, and click the last row "
                + "of them for more. Double-click a line to show it in the viewer; the results stay open.");
        ToolTipManager.sharedInstance().registerComponent(resultsTree);
        resultsTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                TreePath path = resultsTree.getPathForLocation(e.getX(), e.getY());
                if (path == null) {
                    return;
                }
                // Matches need a double-click to jump to; the row that loads more takes a single click.
                if (e.getClickCount() == 2 || path.getLastPathComponent() instanceof MoreNode) {
                    activate(path);
                }
            }
        });
        resultsTree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                Object node = e.getPath().getLastPathComponent();
                if (node instanceof GroupNode) {
                    GroupNode group = (GroupNode) node;
                    if (!group.loaded || group.failed) {
                        listener.loadMatches(group);
                    }
                }
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                // What was loaded stays; expanding again shows it without asking the engine.
            }
        });
        resultsTree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openMatch");
        resultsTree.getActionMap().put("openMatch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                TreePath path = resultsTree.getSelectionPath();
                if (path != null) {
                    activate(path);
                }
            }
        });
        resultsScroll = new JScrollPane(resultsTree);
        add(resultsScroll, BorderLayout.CENTER);
        setMinimumSize(new Dimension(300, 120));
    }

    /** A hit opens in the viewer; the last row of a group loads more; a file's header folds or unfolds. */
    private void activate(TreePath path) {
        Object node = path.getLastPathComponent();
        if (node instanceof HitNode) {
            HitNode hit = (HitNode) node;
            listener.openMatch(hit.match, hit.fileName);
        } else if (node instanceof MoreNode) {
            listener.loadMatches((GroupNode) ((MoreNode) node).getParent());
        } else if (node instanceof GroupNode) {
            if (resultsTree.isExpanded(path)) {
                resultsTree.collapsePath(path);
            } else {
                resultsTree.expandPath(path);
            }
        }
    }

    /** Puts a search in the title: its text, then where and how it was searched. */
    void showSearch(LogSearchParams params) {
        titleSearch = params;
        String rest = LogViewerFormat.searchTitleRest(params.fileName, params.regex, params.caseSensitive);
        lblTitleQuery.setText(params.query);
        // It gives way down to 40 pixels when the line is short of room, but a short search is never
        // widened to that, which left a gap before the closing quote.
        Dimension query = lblTitleQuery.getPreferredSize();
        lblTitleQuery.setMinimumSize(new Dimension(Math.min(40, query.width), query.height));
        lblTitleRest.setText(rest);
        // The search text in fixed-width pieces: it may have no space for the tooltip to wrap at.
        StringBuilder html = new StringBuilder("<html><div>Search results for:<br><br><tt>");
        List<String> pieces = LogViewerFormat.pieces(params.query, 50);
        for (int i = 0; i < pieces.size(); i++) {
            html.append(i > 0 ? "<br>" : "").append(keepSpaces(escapeHtml(pieces.get(i))));
        }
        html.append("</tt><br><br>").append(escapeHtml(rest.substring(2)))
                .append(".<br>Click to open the search with this text, to read it whole or change it.</div></html>");
        for (JLabel part : new JLabel[] {lblTitleLead, lblTitleQuery, lblTitleRest}) {
            tips.setTip(part, html.toString());
        }
    }

    /** Forgets the counts, the groups and the notes. */
    void clear() {
        clearGroups();
        allWarnings.clear();
        tally = new LogCountTally();
        countOutcome = "";
        lblCountLine.setText(" ");
        lblResultsNotes.setText(" ");
        btnCountRest.setVisible(false);
    }

    private void clearGroups() {
        groups.clear();
        resultsRoot.removeAllChildren();
        resultsModel.reload();
    }

    /** A count is starting; a new search's count line is blank until it answers. */
    void countStarted(boolean resume) {
        btnCountRest.setVisible(false);
        if (!resume) {
            lblCountLine.setText(" ");
        }
        countOutcome = "";
    }

    /** How long the viewer waited for a count, added to the total the status line gives. */
    void addElapsed(long millis) {
        tally.addElapsed(millis);
    }

    /** A count failed: the status line says so, and a count that was resuming can be tried again. */
    void countFailed(boolean resume) {
        countOutcome = resume ? LogViewerFormat.COUNT_NOT_FINISHED : LogViewerFormat.SEARCH_FAILED;
        if (resume) {
            btnCountRest.setVisible(true);
        }
    }

    /**
     * Adds a count's answer to what is shown: the groups, the count line and the notes.
     *
     * @param stuck the answer to Count the rest has the resume point it was sent: the count made no
     *        progress, so Count the rest is no longer offered
     */
    void showCount(LogSearchResult result, boolean stuck) {
        tally.add(result);
        if (result.getWarnings() != null) {
            allWarnings.addAll(result.getWarnings());
        }
        LogSearchStopReason stop = result.getStopReason();
        boolean resumable = stop == LogSearchStopReason.DEADLINE && result.getResumeFileId() != null
                && result.getResumeOffset() != null;
        String header;
        if (stop == LogSearchStopReason.FILES_ROTATED) {
            // The ids counted so far name files that moved; asking the engine for their lines would be refused.
            clearGroups();
            header = LogViewerFormat.COUNT_ROTATED;
            countOutcome = "";
        } else {
            syncGroups();
            if (stop == LogSearchStopReason.PATTERN_TOO_COMPLEX) {
                header = LogViewerFormat.stopReason(stop);
                countOutcome = "";
            } else {
                int inScope = tally.filesInScope() > 0 ? tally.filesInScope() : tally.filesCounted();
                header = resumable
                        ? LogViewerFormat.countPartialHeader(tally.matchingLines(), tally.filesWithMatches())
                        : LogViewerFormat.countHeader(tally.matchingLines(), tally.filesWithMatches(), inScope);
                if (resumable && stuck) {
                    header += " " + LogViewerFormat.NO_PROGRESS;
                }
                countOutcome = LogViewerFormat.countSummary(tally.filesCompleted(), inScope, tally.bytesSearched(),
                        tally.elapsedMillis(), resumable);
            }
        }
        btnCountRest.setVisible(resumable && !stuck);
        lblCountLine.setText(header);
        tips.tip(lblCountLine, header);
        String warnings = LogViewerFormat.warningsNote(allWarnings);
        lblResultsNotes.setText(warnings.isEmpty() ? " " : warnings);
        tips.tip(lblResultsNotes, warnings.isEmpty() ? "Notes the engine gave about the search, such as a file it could not read."
                : warnings);
    }

    /** One collapsed group per file that has matching lines; a resumed count updates its partial file's group. */
    private void syncGroups() {
        List<Integer> added = new ArrayList<>();
        for (LogFileMatchCount entry : tally.entries()) {
            if (entry.getMatchingLines() <= 0) {
                continue;
            }
            GroupNode group = groups.get(entry.getFileId());
            if (group == null) {
                LogFileInfo file = listener.listedFile(entry.getFileId());
                String name = file != null ? file.getName() : LogViewerFormat.fileNameFromId(entry.getFileId());
                group = new GroupNode(entry.getFileId(), name);
                groups.put(entry.getFileId(), group);
                resultsRoot.add(group);
                added.add(resultsRoot.getChildCount() - 1);
            }
            group.count = entry.getMatchingLines();
            group.complete = entry.isComplete();
            if (!added.contains(resultsRoot.getIndex(group))) {
                resultsModel.nodeChanged(group);
                // Its show-more row counts from the group's count, which may have just grown.
                if (group.getChildCount() > 0 && group.getLastChild() instanceof MoreNode) {
                    resultsModel.nodeChanged((MoreNode) group.getLastChild());
                }
            }
        }
        if (!added.isEmpty()) {
            int[] indices = new int[added.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = added.get(i);
            }
            resultsModel.nodesWereInserted(resultsRoot, indices);
        }
    }

    /**
     * The results' status line: Counting while a count runs, Loading while a group's lines come, and
     * otherwise how the last count went.
     */
    void updateStatus(boolean counting) {
        String text = countOutcome;
        if (counting) {
            text = LogViewerFormat.COUNTING;
        } else {
            for (GroupNode group : groups.values()) {
                if (group.loading) {
                    text = LogViewerFormat.loadingMoreFrom(group.name, group.shown, group.count, group.complete);
                    break;
                }
            }
        }
        lblResultsActivity.setText(text.isEmpty() ? " " : text);
    }

    /** Count the rest is off while a count runs. */
    void setCountRestEnabled(boolean enabled) {
        btnCountRest.setEnabled(enabled);
    }

    // ------------------------------------------------------------------
    // A group's rows
    // ------------------------------------------------------------------

    /**
     * Adds a page of a group's matching lines, then any note, then the row that loads the next ones.
     *
     * @param sentFileId the resume point the request was sent with, null for a group's first lines
     */
    void appendMatches(GroupNode group, LogSearchResult result, String sentFileId, Long sentOffset) {
        List<LogSearchMatch> matches = result.getMatches() == null ? Collections.<LogSearchMatch>emptyList()
                : result.getMatches();
        int first = group.getChildCount();
        int[] inserted = new int[matches.size()];
        for (int i = 0; i < inserted.length; i++) {
            group.add(new HitNode(group.name, matches.get(i)));
            inserted[i] = first + i;
        }
        if (inserted.length > 0) {
            resultsModel.nodesWereInserted(group, inserted);
        }
        group.shown += matches.size();
        group.loaded = true;
        // Asked again from the same point and stopped there again: offering it once more would not help.
        boolean stuck = LogCountTally.sameResumePoint(sentFileId, sentOffset, result);
        boolean more = !stuck && result.getResumeFileId() != null && result.getResumeOffset() != null;
        group.resumeFileId = more ? result.getResumeFileId() : null;
        group.resumeOffset = more ? result.getResumeOffset() : null;
        LogSearchStopReason stop = result.getStopReason();
        boolean none = group.shown == 0;
        if (stuck) {
            addNote(group, LogViewerFormat.NO_PROGRESS);
        } else if (!more && none && stop == LogSearchStopReason.PATTERN_TOO_COMPLEX) {
            addNote(group, LogViewerFormat.PATTERN_TOO_COMPLEX_IN_FILE);
        } else if (!more && stop != null && stop != LogSearchStopReason.MAX_MATCHES) {
            addNote(group, LogViewerFormat.stopReason(stop));
        }
        String warnings = LogViewerFormat.warningsNote(result.getWarnings());
        if (!warnings.isEmpty()) {
            addNote(group, warnings);
        }
        // Last, so it is the row under the lines just loaded: a click on it loads the next ones.
        if (more) {
            addMoreRow(group, none && stop == LogSearchStopReason.DEADLINE ? LogViewerFormat.KEEP_SEARCHING : null);
        }
    }

    /**
     * The clickable last row of a group, which loads the group's next lines.
     *
     * @param text what the row says, or null for how many are shown and how many the next click loads
     */
    void addMoreRow(GroupNode group, String text) {
        MoreNode row = new MoreNode(group, text);
        group.add(row);
        resultsModel.nodesWereInserted(group, new int[] {group.getChildCount() - 1});
    }

    NoteNode addNote(GroupNode group, String text) {
        NoteNode note = new NoteNode(text);
        group.add(note);
        resultsModel.nodesWereInserted(group, new int[] {group.getChildCount() - 1});
        return note;
    }

    void removeNote(GroupNode group, NoteNode note) {
        int index = group.getIndex(note);
        if (index >= 0) {
            group.remove(index);
            resultsModel.nodesWereRemoved(group, new int[] {index}, new Object[] {note});
        }
    }

    /** Removes a group's notes and its load-more row, before its lines are asked for again. */
    void removeNotes(GroupNode group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            if (group.getChildAt(i) instanceof NoteNode) {
                removeNote(group, (NoteNode) group.getChildAt(i));
            } else if (group.getChildAt(i) instanceof MoreNode) {
                MoreNode row = (MoreNode) group.getChildAt(i);
                group.remove(i);
                resultsModel.nodesWereRemoved(group, new int[] {i}, new Object[] {row});
            }
        }
    }

    // ------------------------------------------------------------------
    // Nodes and renderer
    // ------------------------------------------------------------------

    /**
     * A file's header in the results, with the number of matching lines the count found in it.
     * Its lines are fetched when it is first expanded, then a page at a time while there is a resume point.
     */
    static final class GroupNode extends DefaultMutableTreeNode {
        final String fileId;
        final String name;
        long count;
        // Matching lines loaded into the group so far.
        long shown;
        // False while the time limit has cut the count of this file short.
        boolean complete = true;
        boolean loaded;
        boolean loading;
        boolean failed;
        // The group's file was renamed or removed by a rollover since the search.
        boolean gone;
        String resumeFileId;
        Long resumeOffset;

        GroupNode(String fileId, String name) {
            this.fileId = fileId;
            this.name = name;
        }
    }

    /** A line of text in a group that is not a match: "Loading...", a warning, or why the lines stopped. */
    static final class NoteNode extends DefaultMutableTreeNode {
        final String text;

        NoteNode(String text) {
            this.text = text;
            setAllowsChildren(false);
        }
    }

    /** The last row of a group that has more lines to load; clicking it loads them. */
    private static final class MoreNode extends DefaultMutableTreeNode {
        private final GroupNode group;
        private final String text;

        MoreNode(GroupNode group, String text) {
            this.group = group;
            this.text = text;
            setAllowsChildren(false);
        }

        /** Its own words, or how many lines are shown, from the group's count as it is now. */
        String text() {
            return text != null ? text : LogViewerFormat.loadMoreRow(group.shown, group.count, group.complete);
        }
    }

    /** One matching line. */
    private static final class HitNode extends DefaultMutableTreeNode {
        final String fileName;
        final LogSearchMatch match;

        HitNode(String fileName, LogSearchMatch match) {
            this.fileName = fileName;
            this.match = match;
            setAllowsChildren(false);
        }
    }

    /** Group headers in bold with their count; hits as the line number and the line with the match picked out. */
    private static final class ResultsRenderer extends DefaultTreeCellRenderer {

        private final Font mono;
        private final Font bold;
        private final Font italic;

        ResultsRenderer(Font mono) {
            this.mono = mono;
            this.bold = new JLabel().getFont().deriveFont(Font.BOLD);
            this.italic = new JLabel().getFont().deriveFont(Font.ITALIC);
            setOpenIcon(null);
            setClosedIcon(null);
            setLeafIcon(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, "", selected, expanded, leaf, row, hasFocus);
            // A group's name and the notes are shown as they are; only the rows built as HTML here are HTML.
            putClientProperty("html.disable", value instanceof GroupNode || value instanceof NoteNode);
            if (value instanceof GroupNode) {
                GroupNode group = (GroupNode) value;
                setFont(bold);
                setText(LogViewerFormat.groupLabel(group.name, group.count, group.complete));
            } else if (value instanceof NoteNode) {
                setFont(italic);
                setText(((NoteNode) value).text);
            } else if (value instanceof MoreNode) {
                setFont(italic);
                setText("<html><u>" + escapeHtml(((MoreNode) value).text()) + "</u></html>");
                setForeground(selected ? getTextSelectionColor() : MORE_ROW_FOREGROUND);
            } else if (value instanceof HitNode) {
                setFont(mono);
                setText(hitHtml(((HitNode) value).match));
            }
            return this;
        }
    }

    /** The line number, then the line on one row with the match picked out. */
    private static String hitHtml(LogSearchMatch match) {
        String text = match.getLineText() == null ? "" : match.getLineText();
        int start = Math.max(0, Math.min(match.getMatchStart(), text.length()));
        int end = Math.max(start, Math.min(match.getMatchEnd(), text.length()));
        String number = LogViewerFormat.count(match.getLineNumber());
        StringBuilder pad = new StringBuilder();
        for (int i = number.length(); i < 9; i++) {
            pad.append("&nbsp;");
        }
        return "<html><nobr><font color=\"#6B7280\">" + pad + number + "</font>&nbsp;&nbsp;"
                + keepSpaces(escapeHtml(text.substring(0, start)))
                + "<span style=\"background-color:#FFE066;color:#000000\">"
                + keepSpaces(escapeHtml(text.substring(start, end))) + "</span>"
                + keepSpaces(escapeHtml(text.substring(end))) + "</nobr></html>";
    }
}
