// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/**
 * Where a requested page sits in the file. Offsets are byte offsets into the
 * file's content (the uncompressed content, for archives).
 */
public enum LogPageAnchor {

    /** The last page of the file. The default view. The offset is ignored. */
    TAIL,

    /** The first page of the file. The offset is ignored. */
    HEAD,

    /** The page that ends at the offset, normally a previous page's start offset. */
    BEFORE,

    /** The page that starts at the offset, normally a previous page's end offset. */
    AFTER,

    /**
     * A page that contains the offset, with some lines of context before it.
     * Used to open a search match.
     */
    AT
}
