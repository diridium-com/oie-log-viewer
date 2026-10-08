// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import net.miginfocom.swing.MigLayout;

/**
 * The notice bar across the top of the window. Routine conditions (a rotated file, a busy server,
 * no access, a file on screen that is gone) show here rather than in a popup, with Refresh or Open
 * when one of them is the way on, and Dismiss. A long notice wraps in the room the buttons leave.
 * Whether a notice may be hidden is the dialog's call; Dismiss always hides it.
 */
final class LogNoticeBar extends JPanel {

    private static final Color NOTICE_BACKGROUND = new Color(0xFF, 0xF3, 0xC4);
    private static final Color NOTICE_FOREGROUND = new Color(0x3B, 0x2F, 0x00);

    private final JTextArea txtNotice;
    private String noticeMessage = "";
    private int noticeFitWidth;
    private final JButton btnNoticeRefresh;
    private String noticeReopenName;
    private final JButton btnNoticeOpen;
    private final JButton btnNoticeDismiss;
    private LogFileInfo noticeOpenFile;

    /**
     * @param refresh Refresh was pressed: re-read the list, and open the file with the name given
     *        again when it is not null
     * @param open Open was pressed: open the file that has the gone file's name now
     */
    LogNoticeBar(LogTooltips tips, Consumer<String> refresh, Consumer<LogFileInfo> open) {
        super(new MigLayout("insets 4 8 4 8, fillx, hidemode 3", "[grow][][][]", ""));
        setBackground(NOTICE_BACKGROUND);
        // A text area rather than a label, so a long notice wraps instead of pushing the buttons off the edge.
        txtNotice = new JTextArea();
        txtNotice.setEditable(false);
        txtNotice.setFocusable(false);
        txtNotice.setOpaque(false);
        txtNotice.setLineWrap(true);
        txtNotice.setWrapStyleWord(true);
        txtNotice.setBorder(null);
        txtNotice.setFont(new JLabel().getFont());
        txtNotice.setForeground(NOTICE_FOREGROUND);
        btnNoticeRefresh = new JButton("Refresh");
        tips.tip(btnNoticeRefresh, "Re-reads the list of log files and opens the file again.");
        btnNoticeRefresh.addActionListener(e -> refresh.accept(noticeReopenName));
        btnNoticeOpen = new JButton("Open");
        // It names the file, and a name is shown as it is, never as HTML.
        btnNoticeOpen.putClientProperty("html.disable", Boolean.TRUE);
        tips.tip(btnNoticeOpen, "Opens the file that has that name now: the active file on its last page, "
                + "an archive on its first.");
        btnNoticeOpen.setVisible(false);
        btnNoticeOpen.addActionListener(e -> {
            LogFileInfo file = noticeOpenFile;
            if (file != null) {
                open.accept(file);
            }
        });
        btnNoticeDismiss = new JButton("Dismiss");
        tips.tip(btnNoticeDismiss, "Hides this message.");
        btnNoticeDismiss.addActionListener(e -> {
            setVisible(false);
            noticeReopenName = null;
        });
        add(txtNotice, "growx");
        add(btnNoticeRefresh);
        add(btnNoticeOpen);
        add(btnNoticeDismiss);
        setVisible(false);
        // The longer notices wrap in the room the buttons leave, instead of pushing them off the edge.
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getWidth() != noticeFitWidth) {
                    fitNotice();
                }
            }
        });
    }

    void showNotice(String message, boolean offerRefresh, String reopenName) {
        btnNoticeRefresh.setVisible(offerRefresh);
        btnNoticeOpen.setVisible(false);
        noticeOpenFile = null;
        noticeReopenName = reopenName;
        setNoticeText(message);
        setVisible(true);
        revalidate();
    }

    /**
     * The gone notice: the file on screen no longer exists under its name.
     *
     * @param now the file that has that name in the fresh list, which Open opens, or null when none does
     */
    void showGone(String name, LogFileInfo now) {
        btnNoticeRefresh.setVisible(false);
        noticeReopenName = null;
        noticeOpenFile = now;
        btnNoticeOpen.setText(LogViewerNotice.goneOpenLabel(name));
        btnNoticeOpen.setVisible(now != null);
        setNoticeText(LogViewerNotice.goneMessage(name));
        setVisible(true);
        revalidate();
    }

    void hideNotice() {
        setVisible(false);
        noticeReopenName = null;
    }

    /** A file was opened: Open no longer has anything to open. */
    void forgetOpenFile() {
        noticeOpenFile = null;
    }

    private void setNoticeText(String message) {
        noticeMessage = message;
        fitNotice();
    }

    /** Sizes the notice to the width the buttons leave, wrapped. */
    private void fitNotice() {
        noticeFitWidth = getWidth();
        int buttons = 0;
        for (JButton button : new JButton[] {btnNoticeRefresh, btnNoticeOpen, btnNoticeDismiss}) {
            if (button.isVisible()) {
                buttons += button.getPreferredSize().width + 6;
            }
        }
        int available = Math.max(240, noticeFitWidth - buttons - 30);
        txtNotice.setText(noticeMessage);
        txtNotice.setPreferredSize(null);
        txtNotice.setSize(available, Short.MAX_VALUE);
        txtNotice.setPreferredSize(new Dimension(available, txtNotice.getPreferredSize().height));
        revalidate();
    }
}
