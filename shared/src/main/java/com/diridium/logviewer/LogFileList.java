// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * The log files found in the running log4j configuration, newest first by
 * modification time.
 *
 * <p>Ordered by time rather than by rollover index because the index order
 * depends on the rollover strategy: with log4j's default ({@code fileIndex=max})
 * the highest index is the newest archive, with {@code fileIndex=min} it is the
 * oldest.</p>
 *
 * <p>{@link #getWarnings()} says what could not be listed and why (an
 * unreadable file, an archive pattern the server cannot enumerate, a list cut
 * at its cap), so an empty or short list is never silently incomplete.</p>
 */
public class LogFileList implements Serializable {

    private static final long serialVersionUID = 1L;

    // ArrayList, never List.of(): immutable JDK collections serialize as
    // java.util.CollSer, which the Administrator's XStream refuses to read.
    private List<LogFileInfo> files = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public LogFileList() {
    }

    public List<LogFileInfo> getFiles() {
        return files;
    }

    public void setFiles(List<LogFileInfo> files) {
        this.files = files;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
