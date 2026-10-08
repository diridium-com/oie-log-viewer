// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;

/**
 * The log viewer's tooltips, and the two small helpers for the HTML that they and the search
 * results' rows are written in. The dialog makes one and hands it to each of its parts: it remembers
 * the Administrator's tooltip dismiss delay from when the dialog opened, so the delay can be put back.
 */
final class LogTooltips {

    private final int dismissDelayAtStart = ToolTipManager.sharedInstance().getDismissDelay();

    /**
     * Sets a tooltip made of one or more paragraphs, wrapped to a readable width. The
     * longer ones need more than the default four seconds, so the dismiss delay is
     * lengthened while the pointer is over the control and put back when it leaves.
     */
    void tip(JComponent component, String... paragraphs) {
        int length = 0;
        for (String paragraph : paragraphs) {
            length += paragraph.length();
        }
        // A short tip stays on one line; a long one is wrapped to a readable width.
        StringBuilder html = new StringBuilder(length > 70 || paragraphs.length > 1
                ? "<html><div style=\"width:330px\">" : "<html><div>");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) {
                html.append("<br><br>");
            }
            html.append(escapeHtml(paragraphs[i]));
        }
        setTip(component, html.append("</div></html>").toString());
    }

    /** Sets a tooltip already written as HTML, with the longer dismiss delay of {@link #tip}. */
    void setTip(JComponent component, String html) {
        component.setToolTipText(html);
        if (component.getClientProperty("lv.tip") != null) {
            return;
        }
        component.putClientProperty("lv.tip", Boolean.TRUE);
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(30000);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                ToolTipManager.sharedInstance().setDismissDelay(dismissDelayAtStart);
            }
        });
    }

    /** Puts back the dismiss delay the Administrator had when the dialog opened. */
    void restoreDismissDelay() {
        ToolTipManager.sharedInstance().setDismissDelay(dismissDelayAtStart);
    }

    static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** HTML collapses runs of spaces; a log line's spacing is part of what is being read. */
    static String keepSpaces(String escaped) {
        return escaped.replace("\t", "    ").replace(" ", "&nbsp;");
    }
}
