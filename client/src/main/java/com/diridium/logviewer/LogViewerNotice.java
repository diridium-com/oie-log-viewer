// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.concurrent.ExecutionException;

import com.mirth.connect.client.core.EntityException;
import com.mirth.connect.client.core.ForbiddenException;

/**
 * What the notice bar says about a failed request, and whether it offers a
 * Refresh. Pure (no Swing), so every kind's wording is covered by a test.
 *
 * <p>A typed error arrives as a {@code ClientException} whose cause is an
 * {@code EntityException} holding a {@link LogViewerError}. A 403 arrives as a
 * {@code ForbiddenException} with no body, so it cannot say whether the role
 * lacks the permission or is limited to specific channels. The wording covers
 * both.</p>
 */
public final class LogViewerNotice {

    /** What the notice bar says when the file on screen is no longer the file the server lists. */
    public static final String STALE_MESSAGE = "This file has rotated since it was listed.";

    /**
     * What the notice bar says when the file on screen is gone: a rollover renamed or removed it
     * after it was opened, so the name no longer leads to the page being read.
     */
    public static String goneMessage(String fileName) {
        return fileName + " no longer exists under that name: a log rollover renamed or removed it after you "
                + "opened it. The page shown is kept so you can finish reading it, but it can't be paged or "
                + "downloaded.";
    }

    /** The notice's button when a file by that name exists now. */
    public static String goneOpenLabel(String fileName) {
        return "Open the current " + fileName;
    }

    /** The tooltip on each control that is disabled while the file on screen is gone. */
    public static String goneTooltip(String fileName) {
        return fileName + " no longer exists under that name. Open a file from the list.";
    }

    /** True when the server refused the request because the file it names was renamed, rotated or removed. */
    public static boolean isStale(Throwable error) {
        return from(error, false, false).isRefreshOffered();
    }

    /**
     * What the notice says when a file that can only be downloaded is opened: the engine's note on
     * why, and that it can be downloaded when the user may download.
     *
     * @param note the engine's note on the file, or null
     */
    public static String notViewableText(String fileName, String note, boolean canDownload) {
        return withDownloadHint(fileName + " cannot be viewed here." + (note != null ? " " + note : ""), canDownload);
    }

    /** A download whose body stopped short. The viewer cannot tell a cut file from a dropped connection. */
    static final String DOWNLOAD_CUT_SHORT =
            "The download stopped before the whole file arrived, so nothing was saved. Try again.";

    private final String message;
    private final boolean refreshOffered;

    private LogViewerNotice(String message, boolean refreshOffered) {
        this.message = message;
        this.refreshOffered = refreshOffered;
    }

    public String getMessage() {
        return message;
    }

    /** True when the notice should carry a Refresh button (the file was rotated). */
    public boolean isRefreshOffered() {
        return refreshOffered;
    }

    /**
     * @param error whatever the request threw, wrapped or not
     * @param download true when the failed request was a download, which needs a different permission
     * @param canDownload the user may download, so a file too large or not viewable here can be saved instead
     */
    public static LogViewerNotice from(Throwable error, boolean download, boolean canDownload) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            if (t instanceof LogDownloadCopier.CutShort) {
                return new LogViewerNotice(DOWNLOAD_CUT_SHORT, false);
            }
            if (t instanceof ForbiddenException) {
                return new LogViewerNotice(forbidden(download), false);
            }
            if (t instanceof EntityException) {
                Object entity = ((EntityException) t).getEntity();
                if (entity instanceof LogViewerError) {
                    return fromServer((LogViewerError) entity, download, canDownload);
                }
            }
        }
        return new LogViewerNotice(fallbackMessage(error), false);
    }

    private static LogViewerNotice fromServer(LogViewerError error, boolean download, boolean canDownload) {
        String text = error.getMessage();
        if (error.getKind() == null) {
            return new LogViewerNotice(blank(text) ? "The server refused the request." : text, false);
        }
        switch (error.getKind()) {
            case STALE:
                return new LogViewerNotice(STALE_MESSAGE, true);
            case BUSY:
                // The engine's text says which limit is in force.
                return new LogViewerNotice(blank(text) ? "The engine is busy. Try again in a moment." : text, false);
            case TOO_LARGE:
                return new LogViewerNotice(withDownloadHint(
                        blank(text) ? "This file is too large to view here." : text, canDownload), false);
            case NOT_VIEWABLE:
                return new LogViewerNotice(withDownloadHint(
                        blank(text) ? "This file cannot be viewed here." : text, canDownload), false);
            case CHANNEL_RESTRICTED:
                return new LogViewerNotice(forbidden(download), false);
            case BAD_REQUEST:
            case READ_FAILED:
            default:
                return new LogViewerNotice(blank(text) ? "The server could not complete the request." : text, false);
        }
    }

    private static String forbidden(boolean download) {
        if (download) {
            return "You do not have access to download log files. Your role needs the Download Log Files "
                    + "permission and must not be limited to specific channels.";
        }
        return "You do not have access to log files. Your role needs the View Log Files permission "
                + "and must not be limited to specific channels.";
    }

    /** Ends the text with a full stop and, only for a user who may download, says the file can be downloaded. */
    private static String withDownloadHint(String text, boolean canDownload) {
        String sentence = text.endsWith(".") ? text : text + ".";
        return canDownload ? sentence + " You can download the file instead." : sentence;
    }

    private static String fallbackMessage(Throwable error) {
        Throwable first = null;
        Throwable t = error;
        for (int depth = 0; t != null && depth < 10; depth++, t = t.getCause()) {
            // The Swing worker wraps everything in an ExecutionException whose message is
            // just the cause's toString, which reads badly in a notice.
            if (t instanceof ExecutionException) {
                continue;
            }
            if (first == null) {
                first = t;
            }
            if (!blank(t.getMessage())) {
                return t.getMessage();
            }
        }
        return first == null ? "The request failed." : first.getClass().getSimpleName();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
