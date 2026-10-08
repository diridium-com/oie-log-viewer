// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/**
 * A request the service refuses, with a message fit to show the user.
 *
 * <p>Checked so the servlet cannot forget to translate it. {@link Kind} lets
 * the servlet choose a status, and lets a client react to {@link Kind#STALE}
 * by offering a refresh rather than showing an error. The exception itself
 * never crosses the wire; the servlet sends its kind and message as a
 * {@link LogViewerError}. It lives in the shared module because the kinds
 * are part of that contract.</p>
 */
public class LogViewerException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** The file id or position no longer matches a file on disk, usually after a rollover. Refresh. */
        STALE,
        /** The file exists but can only be downloaded (unsupported compression or charset). */
        NOT_VIEWABLE,
        /** Missing or malformed parameters, or an invalid regular expression. */
        BAD_REQUEST,
        /** The search concurrency cap is in use. Try again shortly. */
        BUSY,
        /** Serving this page would mean decompressing more than the per-request cap. Download instead. */
        TOO_LARGE,
        /** The caller's role is limited to specific channels; log files are server-wide. */
        CHANNEL_RESTRICTED,
        /** The file could not be read (permissions, I/O error, corrupt archive). */
        READ_FAILED
    }

    private final Kind kind;

    public LogViewerException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
