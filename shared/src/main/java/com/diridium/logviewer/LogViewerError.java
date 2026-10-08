// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;

/**
 * The body of every error response this plugin sends, so both viewers can
 * tell what kind of failure it was without parsing status text.
 *
 * <p>A plain object rather than the exception itself: serializing a
 * Throwable means XStream reaching into {@code java.lang.Throwable}'s private
 * fields, which Java 17 refuses unless the JVM opens {@code java.lang}. The
 * Swing client deserializes a non-exception error body and hands it back as
 * {@code EntityException.getEntity()} on the {@code ClientException}'s cause
 * (4.6.0 {@code ServerConnection.handleResponse}), so the Administrator can
 * read {@link #getKind()} directly. The servlet always sends it as XML, as
 * the engine's own {@code MirthApiException(Throwable)} does.</p>
 */
public class LogViewerError implements Serializable {

    private static final long serialVersionUID = 1L;

    private LogViewerException.Kind kind;
    private String message;

    public LogViewerError() {
    }

    public LogViewerError(LogViewerException.Kind kind, String message) {
        this.kind = kind;
        this.message = message;
    }

    public LogViewerException.Kind getKind() {
        return kind;
    }

    public void setKind(LogViewerException.Kind kind) {
        this.kind = kind;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
