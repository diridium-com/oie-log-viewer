/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The search side of the view: useSearch holds the search, its result groups and
 * their status, and runs the requests (GET /count, then GET /search per group
 * when a group is opened). It never touches the viewer itself; when a search's
 * count has finished it asks for the page on screen to be read again (loadPage),
 * so the engine marks the matches.
 */

import { platform } from '@oie/web-shell';
import { EXT_PATH, noticeFrom, isStale, parseSearchResult, highlightParams, markDecision } from './log-core.js';
import {
    emptySearch, addCountResult, addWarnings, canCountRest, resultRows, groupAfterLoad, reopenScope
} from './log-search-core.js';
import { openSearchDialog } from './log-search-dialog.js';
import { canDownloadNow } from './log-download.js';
import { highlightKey } from './log-viewer-state.js';

const React = platform.React;

/*
 * The view hands in what it owns: the request gates and liveness flag, the refs to the file
 * list, the selected file, the gone state, the page on screen and whether a page request runs,
 * setNotice, and loadPage. Returns the search state and its actions; pageMarks is what every
 * page request carries, and the view calls pageLanded when a page request has answered.
 */
export function useSearch({ alive, gates, filesRef, currentRef, goneRef, shownRef, pageBusyRef, setNotice, loadPage }) {
    const { useState, useRef } = React;
    const api = platform.api;

    const [searchBusy, setSearchBusy] = useState(false);
    const [search, setSearch] = useState(emptySearch());
    const [resultsOpen, setResultsOpen] = useState(false);
    const [searchNote, setSearchNote] = useState('');
    const [pickedRow, setPickedRow] = useState('');        // "<file id>#<row number>" of the match picked
    const [groups, setGroups] = useState({});              // file id -> { open, rows, loaded, loading, failed, gone, resume, stop, stuck } of a result group

    const searchRef = useRef(search);
    const resultsOpenRef = useRef(false);
    const groupsRef = useRef({});
    const epochRef = useRef(0);   // bumped when the results are replaced or closed, so a group's late answer is ignored
    const markWanted = useRef(false);   // the page on screen is to be marked once the page request running answers
    const dialogRef = useRef({ query: '', regex: false, caseSensitive: false, scope: 'all' });

    function updateSearch(value) { searchRef.current = value; setSearch(value); }
    function updateResultsOpen(value) { resultsOpenRef.current = value; setResultsOpen(value); }
    function updateGroup(fileId, patch) {
        const next = { ...groupsRef.current, [fileId]: { ...groupsRef.current[fileId], ...patch } };
        groupsRef.current = next;
        setGroups(next);
    }
    function resetGroups() {
        epochRef.current++;
        groupsRef.current = {};
        setGroups({});
    }

    /*
     * A search first COUNTS the matching lines of each file (GET /count); the lines
     * themselves are fetched per file when its group is opened (loadGroup).
     * A new search takes `values` ({ query, regex, caseSensitive, scope }); Count the
     * rest takes none and carries on from where a count stopped at the time limit.
     */
    async function runSearch(resume, values) {
        const previous = searchRef.current;
        let params;
        let resumeFileId = null;
        let resumeOffset = null;
        let base;
        if (resume) {
            if (!canCountRest(previous)) return;
            params = previous.params;
            resumeFileId = previous.last.resumeFileId;
            resumeOffset = previous.last.resumeOffset;
            base = previous;
        } else {
            if (!values || values.query === '') return;
            let fileId = null;
            let fileName = null;
            if (values.scope === 'selected') {
                if (!currentRef.current) {
                    setNotice({ message: 'Open a file to search only that file.', refresh: false });
                    return;
                }
                fileId = currentRef.current.id;
                fileName = currentRef.current.name;
            }
            params = { query: values.query, regex: values.regex, caseSensitive: values.caseSensitive, fileId, fileName };
            base = Object.assign(emptySearch(), { params });
            updateSearch(base);
            setPickedRow('');
            resetGroups();
            markWanted.current = false;
        }

        if (!goneRef.current) setNotice(null);
        updateResultsOpen(true);
        const token = gates.search.next();
        setSearchBusy(true);
        setSearchNote('');
        const started = performance.now();
        try {
            const raw = await api.get(EXT_PATH + '/count', {
                query: params.query,
                regex: params.regex,
                caseSensitive: params.caseSensitive,
                fileId: params.fileId,
                resumeFileId,
                resumeOffset
            });
            if (!alive.current || !gates.search.current(token)) return;
            const next = addCountResult(base, params, parseSearchResult(raw), filesRef.current, performance.now() - started);
            updateSearch(next);
            setSearchNote('');
            if (!resume) markPage();
        } catch (e) {
            if (!alive.current || !gates.search.current(token)) return;
            setSearchNote(resume ? 'The count could not be finished.' : 'The search failed.');
            setNotice(noticeFrom(e, false, canDownloadNow()));
        } finally {
            if (alive.current && gates.search.current(token)) setSearchBusy(false);
        }
    }

    /* The highlight parameters every page request carries: the search's while the results are open, else none. */
    function pageMarks() {
        return resultsOpenRef.current ? highlightParams(searchRef.current.params) : {};
    }

    /*
     * Marks the page on screen with the search: reads the same page again (same file, anchor AFTER
     * at its start offset, see sameLines) with the search attached, and leaves the scroll position
     * alone. Not while a page request runs: it may be one the user started, and a new page request
     * would drop it. That request is checked when it answers (pageLanded) instead.
     */
    function markPage() {
        const on = shownRef.current;
        const decision = markDecision(on, highlightKey(pageMarks()), goneRef.current !== null, pageBusyRef.current);
        if (decision === 'wait') markWanted.current = true;
        if (decision === 'read') loadPage(on.file, 'AFTER', on.data.startOffset, null, true, on.data);
    }

    /*
     * A page request answered; `landed` is true when its page is on screen. If a search finished
     * while it ran and it went out without that search, its page is marked the same way, once.
     */
    function pageLanded(landed) {
        const wanted = markWanted.current;
        markWanted.current = false;
        if (wanted && landed) markPage();
    }

    function showSearchDialog() {
        openSearchDialog(platform, dialogRef.current, currentRef.current !== null, (values) => {
            dialogRef.current = values;
            runSearch(false, values);
        });
    }

    /*
     * The results title was clicked: the Search dialog, filled in with the search the results are for.
     * This file only while the file it searched is still the one open.
     */
    function reopenSearchDialog() {
        const params = searchRef.current.params;
        if (!params) return;
        dialogRef.current = {
            query: params.query, regex: params.regex, caseSensitive: params.caseSensitive,
            scope: reopenScope(params, currentRef.current)
        };
        showSearchDialog();
    }

    /* Removes the panel, stops a search still running, and with them the highlights in the viewer. */
    function closeResults() {
        gates.search.cancel();
        markWanted.current = false;
        setSearchBusy(false);
        updateSearch(emptySearch());
        setSearchNote('');
        setPickedRow('');
        resetGroups();
        updateResultsOpen(false);
    }

    /*
     * Fetches the next 1,000 matching lines of one file (GET /search scoped to it)
     * and appends them to its group; `resume` is null for the first 1,000.
     */
    async function loadGroup(fileId, resume) {
        const params = searchRef.current.params;
        if (!params) return;
        const epoch = epochRef.current;
        updateGroup(fileId, { loading: true, failed: false, gone: false });
        try {
            const raw = await api.get(EXT_PATH + '/search', {
                query: params.query,
                regex: params.regex,
                caseSensitive: params.caseSensitive,
                fileId,
                resumeFileId: resume ? resume.fileId : null,
                resumeOffset: resume ? resume.offset : null
            });
            if (!alive.current || epoch !== epochRef.current) return;
            const result = parseSearchResult(raw);
            updateGroup(fileId, groupAfterLoad(groupsRef.current[fileId], result, resultRows(result, filesRef.current), resume));
            if (result.warnings.length > 0) {
                const current = searchRef.current;
                updateSearch({ ...current, warnings: addWarnings(current.warnings, result.warnings) });
            }
        } catch (e) {
            if (!alive.current || epoch !== epochRef.current) return;
            if (isStale(e)) {
                // No retry: it would fail the same way. The group says so in its last row.
                updateGroup(fileId, { loading: false, failed: false, gone: true });
                return;
            }
            updateGroup(fileId, { loading: false, failed: true });
            setNotice(noticeFrom(e, false, canDownloadNow()));
        }
    }

    /* A group's header opens or folds it; the first opening fetches its matches, later ones reuse them. */
    function toggleGroup(fileId) {
        const group = groupsRef.current[fileId];
        if (group && group.open) {
            updateGroup(fileId, { open: false });
        } else if (group && (group.loaded || group.loading || group.gone)) {
            updateGroup(fileId, { open: true });
        } else {
            updateGroup(fileId, { open: true, rows: [], loaded: false });
            loadGroup(fileId, null);
        }
    }

    /* The last row of an open group was clicked: load the next 1,000 or keep searching, or try the failed load again. */
    function loadMore(fileId) {
        const group = groupsRef.current[fileId];
        if (group && group.open && !group.loading && !group.gone && (group.resume || group.failed)) loadGroup(fileId, group.resume || null);
    }

    return {
        search, resultsOpen, searchBusy, searchNote, pickedRow, setPickedRow, groups,
        pageMarks, pageLanded, runSearch, showSearchDialog, reopenSearchDialog, closeResults, toggleGroup, loadMore
    };
}
