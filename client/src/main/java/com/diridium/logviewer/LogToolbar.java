// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import net.miginfocom.swing.MigLayout;

/**
 * The one toolbar above the viewer: the file shown, then the page controls on the left and the two
 * searches on the right. It says what was pressed; the dialog decides what follows.
 */
final class LogToolbar extends JPanel {

    /** What a page is, said the same way in the four navigation tooltips. */
    static final String PAGE_NOTE = " A page is 1,000 lines, fewer when lines are very long.";

    private static final String FIRST_TIP = "Shows the first page of the file." + PAGE_NOTE;
    private static final String PREVIOUS_TIP = "Shows the page before this one." + PAGE_NOTE;
    private static final String NEXT_TIP = "Shows the page after this one." + PAGE_NOTE;
    private static final String LAST_TIP = "Shows the last page of the file." + PAGE_NOTE;

    /** What the toolbar's controls ask for. */
    interface Listener {
        /** A page button: First page (HEAD), Previous page (BEFORE), Next page (AFTER) or Last page (TAIL). */
        void turnPage(LogPageAnchor anchor);

        void wordWrap(boolean on);

        void specialCharacters(boolean on);

        /** Find on this page... */
        void find();

        /** Search all files... */
        void searchAll();
    }

    private final LogTooltips tips;
    private final JLabel lblViewing;
    private final JButton btnFirst;
    private final JButton btnPrevious;
    private final JButton btnNext;
    private final JButton btnLast;
    private final JCheckBox chkWrap;
    private final JCheckBox chkSpecial;
    private final JButton btnFind;
    private final JButton btnSearchAll;

    LogToolbar(LogTooltips tips, Listener listener) {
        this.tips = tips;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        lblViewing = new JLabel(" ");
        // A file name is shown as it is, never as HTML.
        lblViewing.putClientProperty("html.disable", Boolean.TRUE);
        lblViewing.setFont(lblViewing.getFont().deriveFont(Font.BOLD));
        tips.tip(lblViewing, "The file shown in the viewer.");
        JPanel first = new JPanel(new MigLayout("insets 4 6 0 6, fillx", "[grow]", ""));
        // A long name (an archive in a date folder) is cut with an ellipsis; the tooltip has it whole.
        first.add(lblViewing, "growx, wmin 0");

        btnFirst = new JButton("First page");
        tips.tip(btnFirst, FIRST_TIP);
        btnFirst.addActionListener(e -> listener.turnPage(LogPageAnchor.HEAD));
        btnPrevious = new JButton("Previous page");
        tips.tip(btnPrevious, PREVIOUS_TIP);
        btnPrevious.addActionListener(e -> listener.turnPage(LogPageAnchor.BEFORE));
        btnNext = new JButton("Next page");
        tips.tip(btnNext, NEXT_TIP);
        btnNext.addActionListener(e -> listener.turnPage(LogPageAnchor.AFTER));
        btnLast = new JButton("Last page");
        tips.tip(btnLast, LAST_TIP);
        btnLast.addActionListener(e -> listener.turnPage(LogPageAnchor.TAIL));

        chkWrap = new JCheckBox("Word wrap", true);
        tips.tip(chkWrap, "Wraps long lines at the edge of the viewer instead of scrolling sideways. "
                + "The line numbers still count the file's own lines.");
        chkWrap.addActionListener(e -> listener.wordWrap(chkWrap.isSelected()));
        chkSpecial = new JCheckBox("Show special characters", false);
        tips.tip(chkSpecial, "Shows line ends (CR and LF), tabs and spaces, and marks characters that are invisible "
                + "or that the font cannot draw.");
        chkSpecial.addActionListener(e -> listener.specialCharacters(chkSpecial.isSelected()));

        btnFind = new JButton("Find on this page...");
        tips.tip(btnFind, "Finds text in the page shown (up to 1,000 lines), in this window. "
                + "To search every log file on the engine, use Search all files...");
        btnFind.addActionListener(e -> listener.find());

        btnSearchAll = new JButton("Search all files...");
        tips.tip(btnSearchAll, "Counts the matching lines in each of the engine's log files for text or a regular "
                + "expression. The engine runs the search. The counts open under the viewer, and the matches are "
                + "marked in the page shown.");
        btnSearchAll.addActionListener(e -> listener.searchAll());

        // The two searches stay together, at the right end of the last row.
        JPanel searches = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        searches.add(btnFind);
        searches.add(btnSearchAll);

        JPanel second = new JPanel(new LogWrapLayout(FlowLayout.LEFT, 6, 3));
        second.add(btnFirst);
        second.add(btnPrevious);
        second.add(btnNext);
        second.add(btnLast);
        second.add(chkWrap);
        second.add(chkSpecial);
        second.add(searches);

        add(first);
        add(second);
        first.setAlignmentX(Component.LEFT_ALIGNMENT);
        second.setAlignmentX(Component.LEFT_ALIGNMENT);
        // A wrapping row only knows its height once it knows its width.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                SwingUtilities.invokeLater(() -> {
                    second.revalidate();
                    revalidate();
                });
            }
        });
    }

    /** The name of the file shown, or a single space when none is. */
    void setViewing(String text) {
        lblViewing.setText(text);
        tips.tip(lblViewing, text.trim().isEmpty() ? "The file shown in the viewer."
                : "The file shown in the viewer: " + text);
    }

    /**
     * First page and Previous page go back; Next page and Last page go forward. Their tooltips say
     * why they are off when that is not plain: every one, while the file shown is gone; Next page
     * and Last page, at the end of an active file's snapshot.
     *
     * @param snapshotEnd the page shown reached the end of the active file when it was read
     * @param goneTip the tooltip while the file shown is gone, or null
     */
    void setPaging(boolean back, boolean forward, boolean snapshotEnd, String goneTip) {
        btnFirst.setEnabled(back);
        btnPrevious.setEnabled(back);
        btnNext.setEnabled(forward);
        btnLast.setEnabled(forward);
        tips.tip(btnFirst, goneTip != null ? goneTip : FIRST_TIP);
        tips.tip(btnPrevious, goneTip != null ? goneTip : PREVIOUS_TIP);
        String endTip = goneTip != null ? goneTip : snapshotEnd ? LogViewerFormat.SNAPSHOT_END : null;
        tips.tip(btnNext, endTip != null ? endTip : NEXT_TIP);
        tips.tip(btnLast, endTip != null ? endTip : LAST_TIP);
    }

    /** Search all files... is off while a count runs. */
    void setSearchEnabled(boolean enabled) {
        btnSearchAll.setEnabled(enabled);
    }
}
