// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/** Why a search stopped before covering every file in scope. */
public enum LogSearchStopReason {

    /** The match cap was reached. More matches may follow the resume point. */
    MAX_MATCHES,

    /** The time limit expired. The rest of the scope, from the resume point on, was not searched. */
    DEADLINE,

    /**
     * The regular expression exhausted the matcher's stack on a line (deeply
     * nested or repeated alternation over a long line). Simplify the pattern.
     */
    PATTERN_TOO_COMPLEX,

    /**
     * The log files rotated while the search ran, so the remaining files no
     * longer hold what was listed when it started. Run the search again.
     */
    FILES_ROTATED
}
