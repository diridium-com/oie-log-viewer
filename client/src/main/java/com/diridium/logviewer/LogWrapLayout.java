// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a row of components onto as many lines as the width needs. The last component
 * is placed at the right edge of the row it lands on, so a group of controls that belongs
 * on the right stays together and stays right whether or not the row had to wrap.
 */
final class LogWrapLayout extends FlowLayout {

    LogWrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public void layoutContainer(Container target) {
        synchronized (target.getTreeLock()) {
            Insets insets = target.getInsets();
            int left = insets.left + getHgap();
            int right = target.getWidth() - insets.right - getHgap();
            int max = right - left;
            List<Component> row = new ArrayList<>();
            int rowWidth = 0;
            int y = insets.top + getVgap();
            int count = target.getComponentCount();
            Component last = null;
            for (int i = count - 1; i >= 0 && last == null; i--) {
                if (target.getComponent(i).isVisible()) {
                    last = target.getComponent(i);
                }
            }
            for (int i = 0; i <= count; i++) {
                Component c = i < count ? target.getComponent(i) : null;
                if (c != null && !c.isVisible()) {
                    continue;
                }
                int width = c == null ? 0 : c.getPreferredSize().width;
                if (c == null || (rowWidth + width > max && rowWidth > 0)) {
                    y += placeRow(row, left, right, y, last) + getVgap();
                    row.clear();
                    rowWidth = 0;
                    if (c == null) {
                        break;
                    }
                }
                rowWidth += (rowWidth != 0 ? getHgap() : 0) + width;
                row.add(c);
            }
        }
    }

    /** Places one row from the left edge, its last component (when it is the layout's last) at the right edge. */
    private int placeRow(List<Component> row, int left, int right, int y, Component last) {
        int height = 0;
        for (Component c : row) {
            height = Math.max(height, c.getPreferredSize().height);
        }
        int x = left;
        for (Component c : row) {
            Dimension d = c.getPreferredSize();
            int at = c == last ? Math.max(x, right - d.width) : x;
            c.setBounds(at, y + (height - d.height) / 2, d.width, d.height);
            x += d.width + getHgap();
        }
        return height;
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        // As narrow as its widest component, which is how far it can wrap; it must not hold on to
        // the width it last had, or a window made narrower cannot take the room back from its neighbour.
        Dimension minimum = size(target, false);
        int widest = 0;
        for (int i = 0; i < target.getComponentCount(); i++) {
            if (target.getComponent(i).isVisible()) {
                widest = Math.max(widest, target.getComponent(i).getMinimumSize().width);
            }
        }
        Insets insets = target.getInsets();
        minimum.width = widest + insets.left + insets.right + getHgap() * 2;
        return minimum;
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            Container width = target;
            while (width.getWidth() == 0 && width.getParent() != null) {
                width = width.getParent();
            }
            int available = width.getWidth() == 0 ? Integer.MAX_VALUE : width.getWidth();
            Insets insets = target.getInsets();
            int sides = insets.left + insets.right + getHgap() * 2;
            int max = available - sides;
            Dimension total = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (int i = 0; i < target.getComponentCount(); i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth + d.width > max && rowWidth > 0) {
                    addRow(total, rowWidth, rowHeight);
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += getHgap();
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            addRow(total, rowWidth, rowHeight);
            total.width += sides;
            total.height += insets.top + insets.bottom + getVgap() * 2;
            return total;
        }
    }

    private void addRow(Dimension total, int rowWidth, int rowHeight) {
        total.width = Math.max(total.width, rowWidth);
        if (total.height > 0) {
            total.height += getVgap();
        }
        total.height += rowHeight;
    }
}
