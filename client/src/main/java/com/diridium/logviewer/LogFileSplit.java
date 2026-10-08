// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JComponent;
import javax.swing.JSplitPane;

/**
 * The split between the file list and the rest of the window. The arrows on the divider fold the
 * file list away and bring it back, as in the Administrator's own split panes (the transformer
 * editor's reference panel, the Dashboard), and whether it is folded is remembered for the next
 * opening.
 */
final class LogFileSplit extends JSplitPane {

    /** The file list is folded away (see {@link #isFilesCollapsed}), as last remembered. */
    private boolean filesCollapsed;

    LogFileSplit(JComponent files, Component rest) {
        super(HORIZONTAL_SPLIT, files, rest);
        // No minimum width, so a folded list stays folded through any relayout.
        files.setMinimumSize(new Dimension(0, 0));
        setOneTouchExpandable(true);
        setContinuousLayout(true);
        setResizeWeight(0);
    }

    /**
     * Places the divider for a window this wide, then folds the list away when the user left it
     * folded last time. Folding it after the divider is placed leaves the divider's expand arrow
     * returning to that place. From then on a fold or unfold is remembered.
     */
    void place(int width) {
        // The split pane lays out lazily; set the divider from the size just chosen.
        setDividerLocation(Math.min(330, width / 4));
        if (LogViewerPreferences.filesHiddenPreference()) {
            setDividerLocation(0);
        }
        filesCollapsed = isFilesCollapsed();
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, e -> {
            boolean collapsed = isFilesCollapsed();
            if (collapsed != filesCollapsed) {
                filesCollapsed = collapsed;
                LogViewerPreferences.rememberFilesHidden(collapsed);
            }
        });
    }

    /** Folded away by the divider's arrow, which puts it at the very edge, or dragged nearly shut. */
    boolean isFilesCollapsed() {
        return getDividerLocation() <= getInsets().left + 10;
    }
}
