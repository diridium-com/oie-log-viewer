// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.JFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the dialog remembers from one opening to the next, in the Administrator's per-user
 * preferences: whether the file list is folded away, the window's last size and position, and the
 * folder the last download was saved in. Also where the window opens. A preference that cannot be
 * read or saved is skipped: the dialog works without it. The rules for the bounds themselves are in
 * {@link LogViewerWindowBounds}.
 */
final class LogViewerPreferences {

    private static final Logger log = LoggerFactory.getLogger(LogViewerPreferences.class);

    /** The Administrator's per-user preferences; the dialog remembers whether the file list is hidden. */
    private static final String PREF_FILES_HIDDEN = "filesHidden";

    /** The window's last size and position, as {@code x,y,width,height}. */
    private static final String PREF_BOUNDS = "windowBounds";

    /** The folder the last download was saved in. */
    private static final String PREF_DOWNLOAD_FOLDER = "downloadFolder";

    private LogViewerPreferences() {
    }

    /**
     * Where the user left the window last time, if that is still on a screen, shrunk to fit that
     * screen; otherwise 1280 x 800 centred on the Administrator window.
     */
    static Rectangle openingBounds(JFrame parent) {
        Rectangle bounds = LogViewerWindowBounds.usable(
                LogViewerWindowBounds.parse(boundsPreference()), screenBounds());
        if (bounds == null) {
            return LogViewerWindowBounds.defaultBounds(usableScreen(parent), frameBounds(parent));
        }
        return LogViewerWindowBounds.fit(bounds, screenAt(bounds));
    }

    /** The usable area (less the task bar and the like) of the screen that holds the most of {@code bounds}. */
    static Rectangle screenAt(Rectangle bounds) {
        List<Rectangle> screens = new ArrayList<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens.add(usableArea(device.getDefaultConfiguration()));
        }
        return LogViewerWindowBounds.screenOf(bounds, screens);
    }

    static boolean filesHiddenPreference() {
        try {
            return Preferences.userNodeForPackage(LogViewerDialog.class).getBoolean(PREF_FILES_HIDDEN, false);
        } catch (RuntimeException e) {
            log.debug("Could not read the file list preference", e);
            return false;
        }
    }

    static void rememberFilesHidden(boolean hidden) {
        try {
            Preferences.userNodeForPackage(LogViewerDialog.class).putBoolean(PREF_FILES_HIDDEN, hidden);
        } catch (RuntimeException e) {
            log.debug("Could not save the file list preference", e);
        }
    }

    private static String boundsPreference() {
        try {
            return Preferences.userNodeForPackage(LogViewerDialog.class).get(PREF_BOUNDS, null);
        } catch (RuntimeException e) {
            log.debug("Could not read the window bounds preference", e);
            return null;
        }
    }

    /** Saves the size and position the user leaves the window at. */
    static void rememberBounds(Rectangle bounds) {
        try {
            Preferences.userNodeForPackage(LogViewerDialog.class).put(PREF_BOUNDS,
                    LogViewerWindowBounds.format(bounds));
        } catch (RuntimeException e) {
            log.debug("Could not save the window bounds preference", e);
        }
    }

    /** The folder the last download was saved in, when it still exists; null otherwise. */
    static File downloadFolder() {
        try {
            String path = Preferences.userNodeForPackage(LogViewerDialog.class).get(PREF_DOWNLOAD_FOLDER, null);
            File folder = path == null ? null : new File(path);
            return folder != null && folder.isDirectory() ? folder : null;
        } catch (RuntimeException e) {
            log.debug("Could not read the download folder preference", e);
            return null;
        }
    }

    static void rememberDownloadFolder(File folder) {
        if (folder == null) {
            return;
        }
        try {
            Preferences.userNodeForPackage(LogViewerDialog.class).put(PREF_DOWNLOAD_FOLDER, folder.getAbsolutePath());
        } catch (RuntimeException e) {
            log.debug("Could not save the download folder preference", e);
        }
    }

    /** The bounds of every screen, for checking that a remembered position can still be reached. */
    private static List<Rectangle> screenBounds() {
        List<Rectangle> screens = new ArrayList<>();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens.add(device.getDefaultConfiguration().getBounds());
        }
        return screens;
    }

    /** The screen the Administrator window is on, less the task bar and the like; the default screen without one. */
    private static Rectangle usableScreen(JFrame frame) {
        return usableArea(frame != null && frame.isShowing() ? frame.getGraphicsConfiguration()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration());
    }

    /** A screen's area less the task bar and the like. */
    private static Rectangle usableArea(GraphicsConfiguration configuration) {
        Rectangle area = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(area.x + insets.left, area.y + insets.top, area.width - insets.left - insets.right,
                area.height - insets.top - insets.bottom);
    }

    /** The Administrator window's bounds, or null when it is not showing. */
    private static Rectangle frameBounds(JFrame frame) {
        return frame != null && frame.isShowing() ? frame.getBounds() : null;
    }
}
