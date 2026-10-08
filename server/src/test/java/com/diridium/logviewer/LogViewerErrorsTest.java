// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.Xpp3Driver;

class LogViewerErrorsTest {

    @Test
    void everyKindHasItsDocumentedStatus() {
        Map<LogViewerException.Kind, Integer> expected = new HashMap<>();
        expected.put(LogViewerException.Kind.STALE, 409);
        expected.put(LogViewerException.Kind.BUSY, 429);
        expected.put(LogViewerException.Kind.BAD_REQUEST, 400);
        expected.put(LogViewerException.Kind.NOT_VIEWABLE, 400);
        expected.put(LogViewerException.Kind.TOO_LARGE, 422);
        expected.put(LogViewerException.Kind.CHANNEL_RESTRICTED, 403);
        expected.put(LogViewerException.Kind.READ_FAILED, 500);
        // Fails when a kind is added without deciding (and documenting) its status.
        assertEquals(LogViewerException.Kind.values().length, expected.size());
        for (LogViewerException.Kind kind : LogViewerException.Kind.values()) {
            assertEquals(expected.get(kind), LogViewerErrors.statusOf(kind), kind.name());
        }
    }

    @Test
    void errorBodySurvivesTheAdministratorsXStream() {
        // The Swing client deserializes an error body with the same driver and
        // hands it back as EntityException.getEntity(); a plain object, not a
        // Throwable, so no java.lang internals are needed.
        XStream xstream = new XStream(new Xpp3Driver());
        xstream.allowTypesByWildcard(new String[] {"com.diridium.logviewer.**"});
        LogViewerError error = new LogViewerError(LogViewerException.Kind.STALE,
                "mirth.log has been rotated since it was listed. Reload the file list.");

        LogViewerError back = (LogViewerError) xstream.fromXML(xstream.toXML(error));
        assertEquals(LogViewerException.Kind.STALE, back.getKind());
        assertEquals(error.getMessage(), back.getMessage());
    }
}
