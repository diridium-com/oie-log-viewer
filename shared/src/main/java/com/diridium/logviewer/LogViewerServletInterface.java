// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * List, page, search and download the engine's log files.
 *
 * <p>Every operation is audited and runs asynchronously, so a long search or
 * download never blocks the Administrator's other requests. Clients refer to
 * files only by the ids {@link #listFiles()} returns; there is no way to name
 * a path. Errors come back as a {@link LogViewerError} body with a status:
 * 409 stale (rotated, refresh), 429 busy, 400 bad request or not viewable,
 * 422 too large to page, 403 channel-restricted role, 500 read failed.</p>
 *
 * <p>XML is listed first on purpose, as on every engine servlet interface.
 * The Swing Administrator's proxy asks for whatever this list offers, and the
 * server answers in the first format listed. The Administrator decodes JSON by
 * converting it to XML text first, and XML parsing turns every carriage return
 * into a line feed, so with JSON first a page's CRLF and lone-CR line ends
 * (HL7 segments) arrive as plain LF (measured on 4.6.0). XML keeps them: the
 * server writes CR as {@code &#xd;}. The web administrator asks for JSON
 * explicitly and its JSON parser keeps carriage returns, so it is unaffected.</p>
 */
@Path("/extensions/oie-log-viewer")
@Tag(name = "OIE Log Viewer")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface LogViewerServletInterface extends BaseServletInterface {

    /** Must equal the {@code <name>} in plugin.xml: the engine keys this plugin's operations by it. */
    String PLUGIN_NAME = "OIE Log Viewer";

    /**
     * Permission for listing, reading and searching log files.
     *
     * <p>The plugin's own permissions rather than a core one such as
     * {@code Permissions.SERVER_SETTINGS_VIEW}: extension servlet operations
     * reach the authorization controller as {@code "<pluginName>#<operationName>"},
     * which can never match a core permission, so naming one here would have
     * no effect at all.</p>
     */
    String PERMISSION_VIEW = "View Log Files";

    /** Permission for downloading whole log files, separate so a role can read without bulk export. */
    String PERMISSION_DOWNLOAD = "Download Log Files";

    /** Client task names, registered with the permissions so role-based access control can hide them. */
    String TASK_VIEW = "viewLogFiles";
    String TASK_DOWNLOAD = "downloadLogFiles";

    @GET
    @Path("/files")
    @Operation(summary = "List the engine's log files, newest first")
    @MirthOperation(name = "listFiles", display = "List log files",
            permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = true)
    LogFileList listFiles() throws ClientException;

    @GET
    @Path("/page")
    @Operation(summary = "Read one page of a log file")
    @MirthOperation(name = "readPage", display = "Read a log file page",
            permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = true)
    LogPage readPage(
            @Param("fileId") @Parameter(description = "A file id from the file list", required = true)
            @QueryParam("fileId") String fileId,
            @Param("anchor") @Parameter(description = "TAIL (default), HEAD, BEFORE, AFTER or AT")
            @QueryParam("anchor") LogPageAnchor anchor,
            @Param("offset") @Parameter(description = "Byte offset, required for BEFORE, AFTER and AT")
            @QueryParam("offset") Long offset,
            @Param("highlightQuery") @Parameter(description = "A search to mark in the page's text; omit for none")
            @QueryParam("highlightQuery") String highlightQuery,
            @Param("highlightRegex") @Parameter(description = "Treat highlightQuery as a regular expression")
            @QueryParam("highlightRegex") boolean highlightRegex,
            @Param("highlightCaseSensitive") @Parameter(description = "Match case for highlightQuery")
            @QueryParam("highlightCaseSensitive") boolean highlightCaseSensitive
    ) throws ClientException;

    @GET
    @Path("/search")
    @Operation(summary = "Search log files line by line, newest file first")
    @MirthOperation(name = "search", display = "Search log files",
            permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = true)
    LogSearchResult search(
            @Param("query") @Parameter(description = "Text or regular expression", required = true)
            @QueryParam("query") String query,
            @Param("regex") @Parameter(description = "Treat the query as a regular expression")
            @QueryParam("regex") boolean regex,
            @Param("caseSensitive") @Parameter(description = "Match case")
            @QueryParam("caseSensitive") boolean caseSensitive,
            @Param("fileId") @Parameter(description = "Search only this file; omit for every listed file")
            @QueryParam("fileId") String fileId,
            @Param("resumeFileId") @Parameter(description = "Resume point from a previous result")
            @QueryParam("resumeFileId") String resumeFileId,
            @Param("resumeOffset") @Parameter(description = "Resume point from a previous result")
            @QueryParam("resumeOffset") Long resumeOffset
    ) throws ClientException;

    /**
     * Counts the lines that match in each file, without returning them, so a
     * viewer can show where the matches are before fetching any. The same
     * matching, limits and resume rules as {@link #search}, sharing its
     * concurrency limit, but no cap on matches: only the time limit stops it.
     * When a count resumes inside a file, the first entry of the resumed
     * result continues that file's partial count.
     */
    @GET
    @Path("/count")
    @Operation(summary = "Count matching lines in each log file, newest file first")
    @MirthOperation(name = "count", display = "Count matching lines in log files",
            permission = PERMISSION_VIEW, type = ExecuteType.ASYNC, auditable = true)
    LogSearchResult count(
            @Param("query") @Parameter(description = "Text or regular expression", required = true)
            @QueryParam("query") String query,
            @Param("regex") @Parameter(description = "Treat the query as a regular expression")
            @QueryParam("regex") boolean regex,
            @Param("caseSensitive") @Parameter(description = "Match case")
            @QueryParam("caseSensitive") boolean caseSensitive,
            @Param("fileId") @Parameter(description = "Count only this file; omit for every listed file")
            @QueryParam("fileId") String fileId,
            @Param("resumeFileId") @Parameter(description = "Resume point from a previous result")
            @QueryParam("resumeFileId") String resumeFileId,
            @Param("resumeOffset") @Parameter(description = "Resume point from a previous result")
            @QueryParam("resumeOffset") Long resumeOffset
    ) throws ClientException;

    /**
     * The file's raw bytes: an archive as its zip, the active file up to its
     * length when opened. The reply carries Content-Length, so a client can
     * show progress and a body cut short is an error. The method-level
     * {@code @Produces} matters: without it the stream still arrives raw
     * (measured on 4.6.0) but labelled as JSON. Clients must call it with
     * {@code ExecuteType.ASYNC} and read the entity as an InputStream.
     */
    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "Download a log file as it is on disk")
    @MirthOperation(name = "download", display = "Download a log file",
            permission = PERMISSION_DOWNLOAD, type = ExecuteType.ASYNC, auditable = true)
    Response download(
            @Param("fileId") @Parameter(description = "A file id from the file list", required = true)
            @QueryParam("fileId") String fileId
    ) throws ClientException;
}
