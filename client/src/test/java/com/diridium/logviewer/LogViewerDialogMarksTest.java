// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When the page on screen is read again for a search's marks. Searches are compared by identity:
 * running the same text again is a new search, whose count may differ.
 */
class LogViewerDialogMarksTest {

    private static LogSearchParams search(String query) {
        return new LogSearchParams(query, false, false, null, null);
    }

    @Test
    void aPageReadBeforeTheSearchIsReadAgainOnceItsCountArrives() {
        LogSearchParams open = search("ERROR");
        assertTrue(LogViewerDialog.marksMissing(open, open, null));
        // read while an older search was open
        assertTrue(LogViewerDialog.marksMissing(open, open, search("ERROR")));
    }

    @Test
    void notBeforeTheCountArrives() {
        LogSearchParams open = search("ERROR");
        assertFalse(LogViewerDialog.marksMissing(open, null, null));
        assertFalse(LogViewerDialog.marksMissing(open, search("WARN"), null));
    }

    @Test
    void notWhenThePageHasTheMarksOrThereIsNoSearch() {
        LogSearchParams open = search("ERROR");
        assertFalse(LogViewerDialog.marksMissing(open, open, open));
        assertFalse(LogViewerDialog.marksMissing(null, null, null));
        assertFalse(LogViewerDialog.marksMissing(null, null, open));
    }
}
