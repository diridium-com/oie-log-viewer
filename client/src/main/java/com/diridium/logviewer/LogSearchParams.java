// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/** What a search was asked, kept so Count the rest and each file's lines can ask the same thing. */
final class LogSearchParams {
    final String query;
    final boolean regex;
    final boolean caseSensitive;
    final String fileId;
    final String fileName;

    LogSearchParams(String query, boolean regex, boolean caseSensitive, String fileId, String fileName) {
        this.query = query;
        this.regex = regex;
        this.caseSensitive = caseSensitive;
        this.fileId = fileId;
        this.fileName = fileName;
    }
}
