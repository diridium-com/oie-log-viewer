// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;

import javax.swing.JComponent;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

/**
 * The strip beside the viewer's scroll bar: a tick where each match is, in proportion to the page
 * (so a match a third of the way down the text is a third of the way down the strip), and a wider
 * darker one for the match jumped to. Clicking it scrolls there.
 *
 * <p>RSyntaxTextArea 2.5.6's ErrorStrip only shows what its own parsers and its own mark-all and
 * mark-occurrences features report, and a parser runs after a delay. The engine's positions arrive
 * with the page, so this strip paints them at once.</p>
 */
final class LogMatchStrip extends JComponent {

    private final JTextComponent area;
    // Where the matches are in the text, as start/end pairs; empty without a search.
    private int[] matches = new int[0];
    // Where the match jumped to starts in the text, or -1.
    private int strongStart = -1;

    LogMatchStrip(JTextComponent area, LogTooltips tips) {
        this.area = area;
        setPreferredSize(new Dimension(12, 0));
        tips.tip(this, "Marks where the open search's matches fall in this page. Click to scroll there.");
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                scrollToFraction(e.getY() / (double) Math.max(1, getHeight()));
            }
        });
    }

    /** The matches to mark, as start/end pairs in the text, and where the match jumped to starts (-1 for none). */
    void show(int[] matches, int strongStart) {
        this.matches = matches;
        this.strongStart = strongStart;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        if (matches.length == 0 && strongStart < 0) {
            return;
        }
        int height = getHeight();
        double total = Math.max(1, area.getHeight());
        int lastY = -1;
        g.setColor(LogViewerPane.HIGHLIGHT_LIGHT.darker());
        for (int i = 0; i < matches.length; i += 2) {
            int y = tickY(matches[i], total, height);
            if (y >= 0 && y != lastY) {
                g.fillRect(2, y, getWidth() - 4, 2);
                lastY = y;
            }
        }
        if (strongStart >= 0) {
            int y = tickY(strongStart, total, height);
            if (y >= 0) {
                g.setColor(LogViewerPane.HIGHLIGHT_STRONG);
                g.fillRect(0, y - 1, getWidth(), 4);
            }
        }
    }

    private int tickY(int offset, double total, int height) {
        try {
            Rectangle2D r = area.modelToView2D(offset);
            return r == null ? -1 : (int) (r.getY() / total * height);
        } catch (BadLocationException e) {
            // The text changed while painting; the next paint redoes it.
            return -1;
        }
    }

    private void scrollToFraction(double fraction) {
        Rectangle visible = area.getVisibleRect();
        int y = (int) (fraction * area.getHeight()) - visible.height / 2;
        area.scrollRectToVisible(new Rectangle(0, Math.max(0, y), 1, Math.max(1, visible.height)));
    }
}
