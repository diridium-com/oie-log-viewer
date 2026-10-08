// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.EntityException;
import com.mirth.connect.client.core.ForbiddenException;

/**
 * Each error kind the server can send has its own wording. A kind that fell
 * through to the generic message would hide the Refresh button a rotated file
 * needs, and nothing at compile time would say so.
 */
class LogViewerNoticeTest {

    /** What a SwingWorker's get() throws for a typed server error. */
    private static Exception typed(LogViewerException.Kind kind, String message) {
        return new ExecutionException(new ClientException(new EntityException(new LogViewerError(kind, message))));
    }

    @Test
    void staleOffersARefresh() {
        LogViewerNotice notice = LogViewerNotice.from(typed(LogViewerException.Kind.STALE, "whatever"), false, true);
        assertEquals("This file has rotated since it was listed.", notice.getMessage());
        assertTrue(notice.isRefreshOffered());
    }

    @Test
    void busyShowsTheEngineTextWhichStatesTheLimit() {
        LogViewerNotice notice = LogViewerNotice.from(
                typed(LogViewerException.Kind.BUSY, "Two log searches are already running."), false, true);
        assertEquals("Two log searches are already running.", notice.getMessage());
        assertFalse(notice.isRefreshOffered());
        assertEquals("The engine is busy. Try again in a moment.",
                LogViewerNotice.from(typed(LogViewerException.Kind.BUSY, ""), false, true).getMessage());
    }

    @Test
    void tooLargeAndNotViewableSuggestDownloadingToAUserWhoCan() {
        LogViewerNotice large = LogViewerNotice.from(
                typed(LogViewerException.Kind.TOO_LARGE, "This page would need more than 64 MB"), false, true);
        assertEquals("This page would need more than 64 MB. You can download the file instead.", large.getMessage());
        LogViewerNotice notViewable = LogViewerNotice.from(
                typed(LogViewerException.Kind.NOT_VIEWABLE, "Unsupported compression."), false, true);
        assertEquals("Unsupported compression. You can download the file instead.", notViewable.getMessage());
        assertFalse(large.isRefreshOffered());
    }

    @Test
    void aUserWhoCannotDownloadIsNotToldToDownload() {
        assertEquals("This page would need more than 64 MB.", LogViewerNotice.from(
                typed(LogViewerException.Kind.TOO_LARGE, "This page would need more than 64 MB"), false, false)
                .getMessage());
        assertEquals("This file cannot be viewed here.",
                LogViewerNotice.from(typed(LogViewerException.Kind.NOT_VIEWABLE, ""), false, false).getMessage());
    }

    @Test
    void aFileThatCanOnlyBeDownloadedSaysWhyAndOffersTheDownloadOnlyToThoseWhoCan() {
        assertEquals("mirth.log.4.gz cannot be viewed here. Unsupported compression. "
                + "You can download the file instead.",
                LogViewerNotice.notViewableText("mirth.log.4.gz", "Unsupported compression.", true));
        assertEquals("mirth.log.4.gz cannot be viewed here. Unsupported compression.",
                LogViewerNotice.notViewableText("mirth.log.4.gz", "Unsupported compression.", false));
        assertEquals("mirth.log.4.gz cannot be viewed here.",
                LogViewerNotice.notViewableText("mirth.log.4.gz", null, false));
        assertEquals("mirth.log.4.gz cannot be viewed here. You can download the file instead.",
                LogViewerNotice.notViewableText("mirth.log.4.gz", null, true));
    }

    @Test
    void badRequestAndReadFailedShowTheServersMessage() {
        assertEquals("Invalid regular expression: x(",
                LogViewerNotice.from(typed(LogViewerException.Kind.BAD_REQUEST, "Invalid regular expression: x("),
                        false, true).getMessage());
        assertEquals("Could not read the file.",
                LogViewerNotice.from(typed(LogViewerException.Kind.READ_FAILED, "Could not read the file."),
                        false, true).getMessage());
    }

    @Test
    void aForbiddenResponseCoversBothMissingPermissionAndChannelRestriction() {
        String message = LogViewerNotice.from(new ExecutionException(new ForbiddenException("403")), false, true)
                .getMessage();
        assertEquals("You do not have access to log files. Your role needs the View Log Files permission "
                + "and must not be limited to specific channels.", message);
    }

    @Test
    void aForbiddenDownloadNamesTheDownloadPermission() {
        String message = LogViewerNotice.from(new ForbiddenException("403"), true, true).getMessage();
        assertTrue(message.contains("Download Log Files permission"), message);
        assertTrue(message.contains("must not be limited to specific channels"), message);
    }

    @Test
    void anythingElseShowsItsOwnMessage() {
        LogViewerNotice notice = LogViewerNotice.from(
                new ExecutionException(new ClientException("Connection refused")), false, true);
        assertEquals("Connection refused", notice.getMessage());
        assertFalse(notice.isRefreshOffered());
    }

    @Test
    void anErrorWithNoMessageStillSaysSomething() {
        assertEquals("IllegalStateException",
                LogViewerNotice.from(new ExecutionException(new IllegalStateException()), false, true).getMessage());
    }

    @Test
    void theGoneWordingNamesTheFile() {
        assertEquals("mirth.log.5.zip no longer exists under that name: a log rollover renamed or removed it "
                + "after you opened it. The page shown is kept so you can finish reading it, but it can't be "
                + "paged or downloaded.", LogViewerNotice.goneMessage("mirth.log.5.zip"));
        assertEquals("Open the current mirth.log.5.zip", LogViewerNotice.goneOpenLabel("mirth.log.5.zip"));
        assertEquals("mirth.log.5.zip no longer exists under that name. Open a file from the list.",
                LogViewerNotice.goneTooltip("mirth.log.5.zip"));
    }

    @Test
    void onlyAStaleAnswerIsStale() {
        assertTrue(LogViewerNotice.isStale(typed(LogViewerException.Kind.STALE, "x")));
        assertFalse(LogViewerNotice.isStale(typed(LogViewerException.Kind.BUSY, "x")));
        assertFalse(LogViewerNotice.isStale(new ForbiddenException("403")));
    }

    @Test
    void aDownloadCutShortSaysSoInPlainWords() throws Exception {
        InputStream cut = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent++ < 10) {
                    return 'x';
                }
                throw new IOException("Premature end of Content-Length delimited message body (expected: 100; received: 10)");
            }
        };
        Path dir = Files.createTempDirectory("cut");
        Path part = LogDownloadCopier.createPart(dir.resolve("mirth.log"));
        LogDownloadCopier.CutShort thrown = assertThrows(LogDownloadCopier.CutShort.class,
                () -> LogDownloadCopier.copy(cut, part, () -> false, written -> { }));
        assertFalse(Files.exists(part), "the part file is removed");
        assertEquals("The download stopped before the whole file arrived, so nothing was saved. Try again.",
                LogViewerNotice.from(new ExecutionException(thrown), true, true).getMessage());
        Files.delete(dir);
    }
}
