// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/** Why the engine stopped highlighting a page before its end (see {@link LogPage#getHighlightsStopped()}). */
public enum LogHighlightStop {
    /** The page had more matches than the engine marks on one page (5,000). */
    MATCH_LIMIT,
    /** Matching ran past the time allowed for one page (2 seconds). */
    TIME_LIMIT,
    /** The pattern recursed too deeply for the regular expression engine on a line of the page. */
    TOO_COMPLEX
}
