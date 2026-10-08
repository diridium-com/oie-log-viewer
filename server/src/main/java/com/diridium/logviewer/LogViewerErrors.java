// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import com.mirth.connect.client.core.api.MirthApiException;

/**
 * Turns a refused request into the response both viewers understand: a
 * status plus a {@link LogViewerError} body.
 *
 * <p>Kept apart from the servlet so the mapping can be tested without
 * loading the engine's servlet classes.</p>
 */
final class LogViewerErrors {

    /** JAX-RS 2.0's {@code Status} enum has no constant for 422 Unprocessable Entity. */
    private static final int UNPROCESSABLE_ENTITY = 422;

    /**
     * 429 Too Many Requests for a busy search, not 503: the web administrator
     * treats 502, 503 and 504 from any request as the engine being down and
     * shows "Engine unreachable" until its next probe (oie-web-client
     * {@code core/api.ts}, {@code GATEWAY_STATUSES}; seen live). JAX-RS 2.0's
     * {@code Status} enum has no constant for 429 either.
     */
    private static final int TOO_MANY_REQUESTS = 429;

    private LogViewerErrors() {
    }

    /**
     * Note for {@link LogViewerException.Kind#CHANNEL_RESTRICTED}: the Swing
     * client turns every 403 into a {@code ForbiddenException} without
     * reading the body (4.6.0 {@code ServerConnection.handleResponse}), so the
     * Administrator cannot tell it from a missing permission and must word its
     * message to cover both. The web administrator does get the body.
     */
    static int statusOf(LogViewerException.Kind kind) {
        return switch (kind) {
            case STALE -> Status.CONFLICT.getStatusCode();
            case BUSY -> TOO_MANY_REQUESTS;
            case BAD_REQUEST, NOT_VIEWABLE -> Status.BAD_REQUEST.getStatusCode();
            case TOO_LARGE -> UNPROCESSABLE_ENTITY;
            case CHANNEL_RESTRICTED -> Status.FORBIDDEN.getStatusCode();
            case READ_FAILED -> Status.INTERNAL_SERVER_ERROR.getStatusCode();
        };
    }

    /** Always XML, as the engine's own {@code MirthApiException(Throwable)} sends error bodies. */
    static MirthApiException toApiException(LogViewerException e) {
        return new MirthApiException(Response.status(statusOf(e.getKind()))
                .type(MediaType.APPLICATION_XML_TYPE)
                .entity(new LogViewerError(e.getKind(), e.getMessage()))
                .build());
    }
}
