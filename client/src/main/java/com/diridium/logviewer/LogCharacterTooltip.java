// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static com.diridium.logviewer.LogTooltips.escapeHtml;

import java.awt.Point;
import java.awt.geom.Rectangle2D;
import java.util.List;

import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;

/**
 * The viewer's tooltip for the character under the pointer: what a CR or LF label of special
 * mode stands for, otherwise the character's code point and name (see
 * {@link LogDisplayText#tooltip}), a surrogate pair taken as the one character it is.
 */
final class LogCharacterTooltip {

    private LogCharacterTooltip() {
    }

    /**
     * The tooltip for the character under the point, or null when there is none.
     *
     * @param lineEnds the line end labels in the text, empty outside special mode
     */
    static String at(JTextComponent area, List<LogDisplayText.LineEnd> lineEnds, Point p) {
        try {
            Document doc = area.getDocument();
            int length = doc.getLength();
            int near = area.viewToModel2D(p);
            // The nearest insertion point is the character on one side or the other.
            for (int c : new int[] {near, near - 1}) {
                if (c < 0 || c >= length) {
                    continue;
                }
                Rectangle2D left = area.modelToView2D(c);
                Rectangle2D right = area.modelToView2D(c + 1);
                if (left == null || p.y < left.getY() || p.y >= left.getY() + left.getHeight() || p.x < left.getX()) {
                    continue;
                }
                boolean sameRow = right != null && Math.abs(right.getY() - left.getY()) < 1;
                if (sameRow && p.x >= right.getX()) {
                    continue;
                }
                for (LogDisplayText.LineEnd end : lineEnds) {
                    if (c >= end.getStart() && c < end.getEnd()) {
                        return "<html>" + escapeHtml(end.getMeaning()) + "</html>";
                    }
                }
                String pair = doc.getText(c, Math.min(2, length - c));
                int start = c;
                int cp = pair.codePointAt(0);
                if (Character.isLowSurrogate(pair.charAt(0)) && c > 0) {
                    char previous = doc.getText(c - 1, 1).charAt(0);
                    if (Character.isHighSurrogate(previous)) {
                        cp = Character.toCodePoint(previous, pair.charAt(0));
                        start = c - 1;
                    }
                }
                if (cp == '\n' || start < 0) {
                    return null;
                }
                String text = LogDisplayText.tooltip(cp);
                int newline = text.indexOf('\n');
                if (newline < 0) {
                    return "<html>" + escapeHtml(text) + "</html>";
                }
                return "<html>" + escapeHtml(text.substring(0, newline)) + "<br><div style=\"width:280px\">"
                        + escapeHtml(text.substring(newline + 1)) + "</div></html>";
            }
        } catch (BadLocationException e) {
            // The text changed while the mouse was moving; no tooltip.
        }
        return null;
    }
}
