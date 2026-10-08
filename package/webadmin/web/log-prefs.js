/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The view's one remembered setting: whether the file list is hidden. It lives
 * in the browser's localStorage.
 */

/* Whether the file list is hidden is remembered in the browser; without storage the list stays shown. */
const FILES_HIDDEN_KEY = 'oie-log-viewer.filesHidden';

export function readFilesHidden() {
    try {
        return window.localStorage.getItem(FILES_HIDDEN_KEY) === '1';
    } catch (e) {
        return false;
    }
}

export function writeFilesHidden(hidden) {
    try {
        window.localStorage.setItem(FILES_HIDDEN_KEY, hidden ? '1' : '0');
    } catch (e) {
        // No storage: the choice lasts until the page is closed.
    }
}
