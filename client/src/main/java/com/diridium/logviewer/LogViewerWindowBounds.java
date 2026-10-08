// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

/**
 * Where the dialog opens and how big it is. Pure, so the rules (the default size, centring on
 * the Administrator, whether a remembered position is still on a screen) are tested without a
 * display.
 */
final class LogViewerWindowBounds {

    static final int DEFAULT_WIDTH = 1280;
    static final int DEFAULT_HEIGHT = 800;
    static final int MIN_WIDTH = 900;
    static final int MIN_HEIGHT = 520;

    /** The part of the title bar that must be on a screen for the window to be grabbed. */
    private static final int GRAB_WIDTH = 100;
    private static final int GRAB_HEIGHT = 32;

    private LogViewerWindowBounds() {
    }

    /**
     * 1280 x 800 (smaller when the screen is), centred on the Administrator window, or on the
     * screen when that window has no usable size or place, and kept inside the screen.
     *
     * @param screen the usable area of the screen the dialog opens on
     * @param frame the Administrator window's bounds, or null
     */
    static Rectangle defaultBounds(Rectangle screen, Rectangle frame) {
        int width = Math.min(DEFAULT_WIDTH, screen.width);
        int height = Math.min(DEFAULT_HEIGHT, screen.height);
        int x;
        int y;
        // The engine's own dialogs do the same: an Administrator window at 0,0 or with no size is not placed yet.
        if (frame == null || (frame.width == 0 && frame.height == 0) || (frame.x == 0 && frame.y == 0)) {
            x = screen.x + (screen.width - width) / 2;
            y = screen.y + (screen.height - height) / 2;
        } else {
            x = frame.x + (frame.width - width) / 2;
            y = frame.y + (frame.height - height) / 2;
        }
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - width));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - height));
        return new Rectangle(x, y, width, height);
    }

    /** The bounds as kept in the preferences: {@code x,y,width,height}. */
    static String format(Rectangle bounds) {
        return bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height;
    }

    /** Parses what {@link #format} wrote; null for anything else. */
    static Rectangle parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            return new Rectangle(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The remembered bounds when they are usable: at least the minimum size, and with enough of the
     * title bar on some screen to grab it. Otherwise null, and the default applies.
     *
     * @param screens the bounds of every screen
     */
    static Rectangle usable(Rectangle remembered, List<Rectangle> screens) {
        if (remembered == null || remembered.width < MIN_WIDTH || remembered.height < MIN_HEIGHT) {
            return null;
        }
        Rectangle titleBar = new Rectangle(remembered.x, remembered.y, remembered.width, GRAB_HEIGHT);
        for (Rectangle screen : screens) {
            Rectangle shown = titleBar.intersection(screen);
            if (shown.width >= GRAB_WIDTH && shown.height >= GRAB_HEIGHT) {
                return remembered;
            }
        }
        return null;
    }

    /**
     * The screen that holds the most of {@code bounds}, which the window opens on; the first screen
     * when none holds any of it.
     */
    static Rectangle screenOf(Rectangle bounds, List<Rectangle> screens) {
        Rectangle best = screens.get(0);
        long most = -1;
        for (Rectangle screen : screens) {
            Rectangle shared = bounds.intersection(screen);
            long area = shared.isEmpty() ? 0 : (long) shared.width * shared.height;
            if (area > most) {
                most = area;
                best = screen;
            }
        }
        return best;
    }

    /**
     * Remembered bounds shrunk to fit the screen they open on, when they are wider or taller than it
     * (a window left on a larger screen, or before the resolution was lowered), and moved along that
     * side only as far as it takes to fit. Bounds that fit are returned as they are.
     *
     * @param screen the usable area of the screen the window opens on
     */
    static Rectangle fit(Rectangle bounds, Rectangle screen) {
        Rectangle fitted = new Rectangle(bounds);
        if (fitted.width > screen.width) {
            fitted.width = screen.width;
            fitted.x = Math.max(screen.x, Math.min(fitted.x, screen.x + screen.width - fitted.width));
        }
        if (fitted.height > screen.height) {
            fitted.height = screen.height;
            fitted.y = Math.max(screen.y, Math.min(fitted.y, screen.y + screen.height - fitted.height));
        }
        return fitted;
    }

    /** The smallest the dialog may be on a screen of this size. */
    static Dimension minimumSize(Dimension screen) {
        return new Dimension(Math.min(MIN_WIDTH, screen.width), Math.min(MIN_HEIGHT, screen.height));
    }
}
