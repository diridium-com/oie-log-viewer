// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The task name is load-bearing in two directions at once, and neither
 * failure shows up at compile time.
 *
 * <p>The engine binds the task to a method on this plugin looked up by the
 * task name, so a name with no matching method fails only when the user clicks
 * it. The server registers the same name as the permission's task name, so a
 * name that does not match what the client registered silently stops the
 * permission from applying to the task.</p>
 */
class LogViewerClientPluginTaskTest {

    @Test
    void theTaskNameResolvesToACallbackMethod() {
        assertDoesNotThrow(
                () -> LogViewerClientPlugin.class.getMethod(LogViewerServletInterface.TASK_VIEW),
                "no public no-argument method matches the task name the engine will invoke");
    }

    @Test
    void theTwoTaskNamesAreDistinct() {
        assertNotEquals(LogViewerServletInterface.TASK_VIEW, LogViewerServletInterface.TASK_DOWNLOAD);
    }
}
