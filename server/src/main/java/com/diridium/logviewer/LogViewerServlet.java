// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mirth.connect.client.core.api.MirthApiException;
import com.mirth.connect.server.api.MirthServlet;

/**
 * The REST face of {@link LogViewerService}.
 *
 * <p>The engine checks the operation's permission and writes the audit
 * event before any of this runs. What is left here is refusing roles limited
 * to specific channels, and translating refusals into
 * {@link LogViewerErrors}.</p>
 */
public class LogViewerServlet extends MirthServlet implements LogViewerServletInterface {

    private static final Logger log = LoggerFactory.getLogger(LogViewerServlet.class);

    /** Shared by every request: the search concurrency cap lives in it. */
    private static final LogViewerService service = new LogViewerService();

    public LogViewerServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_NAME);
    }

    @Override
    public LogFileList listFiles() {
        refuseChannelRestrictedRoles();
        return service.listFiles();
    }

    @Override
    public LogPage readPage(String fileId, LogPageAnchor anchor, Long offset, String highlightQuery,
                            boolean highlightRegex, boolean highlightCaseSensitive) {
        refuseChannelRestrictedRoles();
        try {
            return service.readPage(fileId, anchor, offset, highlightQuery, highlightRegex, highlightCaseSensitive);
        } catch (LogViewerException e) {
            throw LogViewerErrors.toApiException(e);
        } catch (IOException e) {
            throw readFailed(fileId, e);
        }
    }

    @Override
    public LogSearchResult search(String query, boolean regex, boolean caseSensitive, String fileId,
                                  String resumeFileId, Long resumeOffset) {
        refuseChannelRestrictedRoles();
        try {
            return service.search(query, regex, caseSensitive, fileId, resumeFileId, resumeOffset);
        } catch (LogViewerException e) {
            throw LogViewerErrors.toApiException(e);
        } catch (IOException e) {
            throw readFailed(fileId, e);
        }
    }

    @Override
    public LogSearchResult count(String query, boolean regex, boolean caseSensitive, String fileId,
                                 String resumeFileId, Long resumeOffset) {
        refuseChannelRestrictedRoles();
        try {
            return service.count(query, regex, caseSensitive, fileId, resumeFileId, resumeOffset);
        } catch (LogViewerException e) {
            throw LogViewerErrors.toApiException(e);
        } catch (IOException e) {
            throw readFailed(fileId, e);
        }
    }

    /**
     * Opens the file and answers with its stream; Jersey writes it out and
     * closes it, which releases the file. A failure while opening is reported
     * like any other refusal.
     *
     * <p>The size goes out first, as Content-Length. A failure after
     * streaming has begun (the active file truncated mid-download) cannot
     * change the status any more, and Jersey 2.22 logs it and ends the
     * response normally once it is committed. With the size declared, the
     * short body is still a broken response, so the client reports an error
     * instead of saving a shorter file as if it were complete.</p>
     */
    @Override
    public Response download(String fileId) {
        refuseChannelRestrictedRoles();
        try {
            LogViewerService.Download download = service.openDownload(fileId);
            return Response.ok(download.stream()).header(HttpHeaders.CONTENT_LENGTH, download.length()).build();
        } catch (LogViewerException e) {
            throw LogViewerErrors.toApiException(e);
        } catch (IOException e) {
            throw readFailed(fileId, e);
        }
    }

    /**
     * Log files are server-wide: lines carry no reliable channel, and a
     * role's channel restriction cannot be applied to them, so a restricted
     * role is refused outright rather than shown other channels' data.
     */
    private void refuseChannelRestrictedRoles() {
        if (doesUserHaveChannelRestrictions()) {
            throw LogViewerErrors.toApiException(new LogViewerException(
                    LogViewerException.Kind.CHANNEL_RESTRICTED,
                    "Log files are server-wide and can hold data from any channel, so they are not "
                            + "available to roles limited to specific channels."));
        }
    }

    private static MirthApiException readFailed(String fileId, IOException e) {
        log.warn("Log viewer could not read {}", fileId, e);
        return LogViewerErrors.toApiException(new LogViewerException(LogViewerException.Kind.READ_FAILED,
                "The log file could not be read: " + LogFileCatalog.describe(e)));
    }
}
