/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The "Search all files..." dialog: search text, Java regular expression,
 * Match case, and the scope (all files or the open file). It is the host's modal
 * (platform.ui.modal) with a body built by platform.ui.h. The Search and Cancel
 * buttons are in the body, not in the modal's own footer, because the footer's
 * buttons cannot carry a tooltip and every control here has one. Search closes
 * the dialog and hands the values back; the search itself is run by the caller.
 */

import { QUERY_MAX, QUERY_CUT_NOTE, pasteIsCut } from './log-search-core.js';

/** The regular expression tooltip, used by the dialog and by anything else that shows the option. */
export const REGEX_TOOLTIP = 'Java regular expression (java.util.regex), matched against one line at a time. '
    + 'Not the same as the JavaScript regular expressions used in channel scripts. '
    + 'Examples: ERROR|WARN finds either word; Caused by: .*Timeout finds a cause that ends in Timeout.';

/**
 * Said on the dialog itself, not only in a tooltip: the engine's Java regular expressions differ
 * from the JavaScript ones used in channel scripts and in the editor's own find box.
 */
export const JAVA_REGEX_NOTE = 'Regular expressions here are Java\'s (java.util.regex), not JavaScript\'s: the engine runs the search.';

export const QUERY_TOOLTIP = 'The text to look for. Each log line is searched on its own, so a match never spans two lines.';
export const CASE_TOOLTIP = 'Tell upper case from lower case letters. Off, "error" also finds "ERROR".';
export const SCOPE_TOOLTIP = 'All files searches every log file in the list, newest first. '
    + 'This file searches only the file open in the viewer.';

/**
 * Opens the dialog.
 *
 * @param platform the host platform (ui.modal and ui.h are used)
 * @param initial { query, regex, caseSensitive, scope } the last values, so the dialog opens as it was left
 * @param canScopeToFile false when no file is open, which leaves only All files
 * @param onSearch called with { query, regex, caseSensitive, scope } when Search is pressed with text
 */
export function openSearchDialog(platform, initial, canScopeToFile, onSearch) {
    const h = platform.ui.h;
    let handle = null;

    const query = h('input', {
        type: 'text', value: initial.query, maxlength: String(QUERY_MAX), placeholder: 'Text or Java regular expression',
        title: QUERY_TOOLTIP, 'aria-label': 'Search text'
    });
    const regex = h('input', { type: 'checkbox', checked: initial.regex, title: REGEX_TOOLTIP });
    const matchCase = h('input', { type: 'checkbox', checked: initial.caseSensitive, title: CASE_TOOLTIP });
    const scope = h('select', { title: SCOPE_TOOLTIP, 'aria-label': 'Search scope' },
        h('option', { value: 'all', title: 'Search every log file in the list.' }, 'All files'),
        h('option', { value: 'selected', title: 'Search only the file open in the viewer.', disabled: !canScopeToFile }, 'This file'));
    scope.value = canScopeToFile && initial.scope === 'selected' ? 'selected' : 'all';

    function search() {
        if (query.value === '') {
            query.focus();
            return;
        }
        const values = {
            query: query.value, regex: regex.checked, caseSensitive: matchCase.checked, scope: scope.value
        };
        handle.close();
        onSearch(values);
    }

    // The box keeps the first QUERY_MAX characters of a longer paste; say so under it.
    const cutNote = h('div.lv-dialog-note', QUERY_CUT_NOTE);
    cutNote.hidden = true;
    query.addEventListener('paste', (e) => {
        const pasted = e.clipboardData ? e.clipboardData.getData('text') : '';
        cutNote.hidden = !pasteIsCut(query.value.length, query.selectionEnd - query.selectionStart, pasted);
    });
    query.addEventListener('input', () => {
        if (query.value.length < QUERY_MAX) cutNote.hidden = true;
    });

    query.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            search();
        }
    });

    const body = h('div.lv-dialog',
        h('div.lv-dialog-row', query),
        cutNote,
        h('div.lv-dialog-row',
            h('label.check', { title: REGEX_TOOLTIP }, regex, 'Java regular expression'),
            h('label.check', { title: CASE_TOOLTIP }, matchCase, 'Match case')),
        h('div.lv-dialog-note', JAVA_REGEX_NOTE),
        h('div.lv-dialog-row',
            h('label', { title: SCOPE_TOOLTIP }, 'Search in'),
            scope),
        h('div.lv-dialog-foot',
            h('button.btn', { title: 'Close without searching.', onClick: () => handle.close() }, 'Cancel'),
            h('button.btn.btn-primary', { title: 'Close this window and search the engine\'s log files.', onClick: search }, 'Search')));

    handle = platform.ui.modal({ title: 'Search all files', body, buttons: [] });
    return handle;
}
