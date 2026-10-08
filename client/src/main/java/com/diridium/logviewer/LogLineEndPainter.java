// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;

/** Draws the CR and LF labels of special mode as small boxes, as Notepad++ does. */
final class LogLineEndPainter extends DefaultHighlighter.DefaultHighlightPainter {

    // The boxed CR and LF labels: pale fill, darker outline, on RSyntaxTextArea's own light background.
    private static final Color LINE_END_FILL = new Color(0xDC, 0xE3, 0xF0);
    private static final Color LINE_END_OUTLINE = new Color(0x5B, 0x6B, 0x8C);

    LogLineEndPainter() {
        super(LINE_END_FILL);
    }

    @Override
    public Shape paintLayer(Graphics g, int offs0, int offs1, Shape bounds, JTextComponent c, View view) {
        try {
            Rectangle2D start = c.modelToView2D(offs0);
            Rectangle2D lastChar = c.modelToView2D(offs1 - 1);
            Rectangle2D end = c.modelToView2D(offs1);
            if (start == null || lastChar == null || end == null) {
                return null;
            }
            // A label is never split by wrapping in practice; if it were, box what is on the first row.
            boolean oneRow = Math.abs(end.getY() - start.getY()) < 1;
            double right = oneRow ? end.getX()
                    : lastChar.getX() + c.getFontMetrics(c.getFont()).charWidth('M');
            // One pixel wider each side, so the outline sits beside the letters, not on them.
            Rectangle box = new Rectangle((int) start.getX() - 1, (int) start.getY() + 1,
                    Math.max(1, (int) Math.round(right - start.getX())) + 2, (int) start.getHeight() - 2);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(LINE_END_FILL);
                g2.fillRoundRect(box.x, box.y, box.width, box.height, 4, 4);
                g2.setColor(LINE_END_OUTLINE);
                g2.drawRoundRect(box.x, box.y, box.width - 1, box.height - 1, 4, 4);
            } finally {
                g2.dispose();
            }
            return box;
        } catch (BadLocationException e) {
            return null;
        }
    }
}
