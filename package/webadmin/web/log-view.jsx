/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * OIE Log Viewer - the routed "Log Files" view of the web administrator.
 *
 * The web counterpart of the Swing LogViewerDialog. It talks to the engine
 * plugin's servlet at /extensions/oie-log-viewer through the host's api client:
 *
 *   GET /files     the log files, newest first
 *   GET /page      one window of a file (anchor TAIL, HEAD, BEFORE, AFTER or AT),
 *                  with the open search's matches marked by the engine
 *   GET /count     how many lines match in each file, resumable (what a search runs first)
 *   GET /search    the matching lines of one file, resumable (run when a result group is opened)
 *   GET /download  a file's raw bytes (fetched by hand, see fetchLogFile in log-download.js)
 *
 * Layout: a narrow file list on the left (it can be hidden; Refresh list and
 * Download sit at its top); on the right one toolbar, the viewer (all the
 * remaining height) with its status line directly under it and, after a search,
 * the results docked under that, with their own status line at the bottom.
 *
 * All logic that does not need the DOM is in log-core.js, log-search-core.js and
 * log-display.js (tested with node). This file keeps the state and the file-list
 * requests, and wires the parts together. Each part has its own file:
 *
 *   log-file-pane.jsx    the file list, Refresh list, Download and its progress row
 *   log-toolbar.jsx      the toolbar above the viewer
 *   log-notice.jsx       the message bar
 *   log-viewer-box.jsx   snapshot strip, mid-line note, the viewer and its status line
 *   log-results.jsx      the search results and their status line
 *   log-viewer-state.js  useViewer: the editor and what it holds
 *   log-pages.js         usePages: the page on screen, the page requests and the page buttons
 *   log-search.js        useSearch: the search, its result groups and their requests
 *   log-download.js      useDownload: the raw-bytes fetch and one download's state
 *   log-prefs.js         the remembered Files toggle
 *
 * The parts do not reach into each other. The view owns the shared state (file
 * list, selected file, gone state, notice) and hands each part the values it
 * draws and callbacks for what a click does; the three hooks that make requests
 * (usePages, useSearch, useDownload) are given the refs and callbacks they need
 * and return their own state.
 */

import { platform } from '@oie/web-shell';
import {
    EXT_PATH, createGate, noticeFrom, notViewableText, fileIsGone, goneNotice, goneOpenLabel, goneTooltip,
    navState, activeFirst, fileById, fileByName, parseFileList
} from './log-core.js';
import { LogFilePane } from './log-file-pane.jsx';
import { LogToolbar } from './log-toolbar.jsx';
import { LogNotice } from './log-notice.jsx';
import { LogViewerBox } from './log-viewer-box.jsx';
import { LogResults } from './log-results.jsx';
import { useViewer } from './log-viewer-state.js';
import { usePages } from './log-pages.js';
import { useSearch } from './log-search.js';
import { useDownload, canDownloadNow } from './log-download.js';
import { readFilesHidden, writeFilesHidden } from './log-prefs.js';
import { ensureStyle } from './log-style.js';

const React = platform.React;

/* ---- the view ----------------------------------------------------------------- */

export function LogViewerView() {
    const { useState, useEffect, useRef } = React;
    const api = platform.api;

    const alive = useRef(true);
    const gatesRef = useRef(null);
    if (gatesRef.current === null) {
        gatesRef.current = { list: createGate(), page: createGate(), search: createGate() };
    }
    const gates = gatesRef.current;

    const [files, setFiles] = useState([]);
    const [listWarnings, setListWarnings] = useState([]);
    const [listLoaded, setListLoaded] = useState(false);
    const [current, setCurrent] = useState(null);          // the selected file
    const [wrap, setWrap] = useState(true);
    const [special, setSpecial] = useState(false);
    const [notice, setNotice] = useState(null);            // { message, refresh, action }; action is { label, file } or null
    const [gone, setGone] = useState(null);              // the file on screen no longer exists under its name: { file }
    const [listBusy, setListBusy] = useState(false);
    const [filesHidden, setFilesHidden] = useState(readFilesHidden);
    const [, setTick] = useState(0);

    /* Async continuations need the latest values, not the ones their render saw. */
    const filesRef = useRef([]);
    const currentRef = useRef(null);
    const goneRef = useRef(null);

    function updateFiles(list) { filesRef.current = list; setFiles(list); }
    function updateCurrent(file) { currentRef.current = file; setCurrent(file); }
    function updateGone(value) { goneRef.current = value; setGone(value); }

    /* The pages, the search and the download keep their own state and make their own requests. */
    const pages = usePages({
        alive, gate: gates.page, setNotice, onGone: pageGone,
        marks: () => searching.pageMarks(), onLanded: (landed) => searching.pageLanded(landed)
    });
    const { shown, shownRef, pageBusy, loadPage } = pages;
    const searching = useSearch({
        alive, gates, filesRef, currentRef, goneRef, shownRef, pageBusyRef: pages.pageBusyRef, setNotice, loadPage
    });
    const { search, resultsOpen } = searching;
    const downloader = useDownload({ alive, shownRef, currentRef, goneRef, setNotice, enterGone, loadList });

    /* ---- the file list ------------------------------------------------------- */

    async function loadList(options) {
        const o = options || {};
        const token = gates.list.next();
        setListBusy(true);
        try {
            const raw = await api.get(EXT_PATH + '/files');
            if (!alive.current || !gates.list.current(token)) return;
            const parsed = parseFileList(raw);
            const sorted = activeFirst(parsed.files);
            updateFiles(sorted);
            setListWarnings(parsed.warnings);
            setListLoaded(true);

            if (o.goneFile) {
                // A request for the file on screen failed with STALE; now the list can say whether its name exists.
                if (goneRef.current && goneRef.current.file === o.goneFile) enterGone(o.goneFile, sorted);
            } else if (o.reopenName) {
                // The file the user was reading rotated: reopen the same name, where a file of its kind opens.
                const file = fileByName(sorted, o.reopenName);
                if (file) {
                    openFile(file);
                } else {
                    setNotice({ message: 'The file ' + o.reopenName + ' is no longer listed.', refresh: false });
                }
            } else if (o.openFirst) {
                const first = sorted.find((f) => f.viewable);
                if (first) openFile(first);
            } else if (shownRef.current) {
                // A plain refresh keeps the page on screen. Its file is found by id, never by name:
                // after a rollover the same name is a different file.
                const on = shownRef.current.file;
                if (fileIsGone(on.id, sorted)) {
                    enterGone(on, sorted);
                } else if (!goneRef.current) {
                    updateCurrent(fileById(sorted, on.id));
                }
            } else if (currentRef.current) {
                // No page is on screen; only the selection in the list is restored.
                const same = fileByName(sorted, currentRef.current.name);
                if (same) updateCurrent(same);
            }
        } catch (e) {
            if (!alive.current || !gates.list.current(token)) return;
            if (!o.goneFile) setNotice(noticeFrom(e, false, canDownloadNow()));
        } finally {
            if (alive.current && gates.list.current(token)) setListBusy(false);
        }
    }

    /*
     * The file on screen no longer exists under its name. The page stays; paging and download are off,
     * no row is selected, and the notice offers the file now holding the name when `list` has one.
     */
    function enterGone(file, list) {
        const replacement = list ? fileByName(list, file.name) : null;
        updateGone({ file });
        updateCurrent(null);
        setNotice({
            message: goneNotice(file.name),
            refresh: false,
            action: replacement ? { label: goneOpenLabel(file.name), file: replacement } : null
        });
    }

    function openFile(file) {
        updateGone(null);
        updateCurrent(file);
        if (!file.viewable) {
            clearViewer();
            setNotice({ message: notViewableText(file, canDownloadNow()), refresh: false });
            return;
        }
        setNotice(null);
        // The active file opens on its last page, an archive on its first.
        loadPage(file, file.active ? 'TAIL' : 'HEAD', null, null);
    }

    function clearViewer() {
        updateGone(null);
        pages.clearPage();
    }

    /* ---- pages ---------------------------------------------------------------- */

    /* A page request found the file on screen gone: keep its page, and read the list once to see what holds its name now. */
    function pageGone(file) {
        const already = goneRef.current !== null;
        enterGone(file, null);
        if (!already) loadList({ goneFile: file });
    }

    /* The STALE notice's Refresh: reload the list, then reopen the same file name. */
    function refreshAfterStale() {
        const name = currentRef.current ? currentRef.current.name : null;
        setNotice(null);
        loadList(name ? { reopenName: name } : { openFirst: true });
    }

    /* A result line was clicked: open its file at the match. */
    function openMatch(row, key) {
        searching.setPickedRow(key);
        const match = row.match;
        let file = fileById(filesRef.current, match.fileId);
        if (!file) {
            // Rotated out of the list since the search; the server will say STALE if it is gone.
            file = {
                id: match.fileId, name: row.fileName, size: 0, lastModified: 0, active: false,
                compressed: false, viewable: true, note: null, charset: '', timeZoneId: '', timeZoneLabel: ''
            };
        }
        updateGone(null);
        updateCurrent(file);
        setNotice(null);
        loadPage(file, 'AT', match.matchOffset, { match });
    }

    /* ---- life cycle ---------------------------------------------------------------- */

    useEffect(() => {
        alive.current = true;
        ensureStyle();
        // Role-based access control loads its permissions after the page can already
        // be showing; it pokes these keys when they land, so ask checkTask again.
        const offPlugins = platform.store.subscribe('webPlugins', () => setTick((t) => t + 1));
        const offUser = platform.store.subscribe('user', () => setTick((t) => t + 1));
        loadList({ openFirst: true });
        return () => {
            alive.current = false;
            offPlugins();
            offUser();
            gates.list.cancel();
            gates.page.cancel();
            gates.search.cancel();
            if (downloader.downloadRef.current) downloader.downloadRef.current.abort();
        };
    }, []);

    const { viewerHost, editorRef, view } = useViewer({ shown, special, wrap, resultsOpen, search, alive, notice, setNotice });

    /* ---- derived ----------------------------------------------------------------------- */

    const canDownload = canDownloadNow();
    const nav = navState(shown ? shown.data : null, shown ? shown.file : null, pageBusy, gone !== null);
    const goneTip = gone ? goneTooltip(gone.file.name) : '';
    const filesLocked = listBusy || pageBusy;
    const monaco = editorRef.current !== null && editorRef.current.hasMonaco();

    /* ---- render ------------------------------------------------------------------------- */

    return (
        <div className="view">
            <div className="view-body lv-root" onKeyDown={(e) => {
                // Ctrl+F (Cmd+F) finds in the page, as the button does, but only with Monaco's own find
                // widget to open; the textarea keeps the browser's Find. Monaco handles the key itself
                // while it has focus, which marks the event as handled.
                if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase() === 'f'
                    && !e.defaultPrevented && editorRef.current && editorRef.current.hasMonaco()) {
                    e.preventDefault();
                    editorRef.current.find();
                }
            }}>
                {notice && (
                    <LogNotice notice={notice} listBusy={listBusy} onOpenFile={openFile}
                        onRefresh={refreshAfterStale} onDismiss={() => setNotice(null)} />
                )}

                <div className="lv-main">
                    {!filesHidden && (
                        <LogFilePane files={files} listLoaded={listLoaded} listBusy={listBusy} listWarnings={listWarnings}
                            current={current} gone={gone} goneTip={goneTip} filesLocked={filesLocked}
                            canDownload={canDownload} downloading={downloader.downloading} downloadNote={downloader.downloadNote}
                            onRefresh={() => loadList({})} onDownload={downloader.download}
                            onCancelDownload={downloader.cancelDownload} onOpenFile={openFile} />
                    )}

                    <div className="lv-pane">
                        <LogToolbar filesHidden={filesHidden} fileName={shown ? shown.file.name : null}
                            nav={nav} gone={gone} goneTip={goneTip} wrap={wrap} special={special} monaco={monaco}
                            onToggleFiles={() => { writeFilesHidden(!filesHidden); setFilesHidden(!filesHidden); }}
                            onNavigate={pages.navigate} onWrap={setWrap} onSpecial={setSpecial}
                            onFind={() => { if (editorRef.current) editorRef.current.find(); }}
                            onSearchAll={searching.showSearchDialog} />

                        <LogViewerBox shown={shown} view={view} pageBusy={pageBusy} resultsOpen={resultsOpen}
                            gone={gone} goneTip={goneTip} hostRef={viewerHost} monaco={monaco}
                            onLoadLatest={() => pages.navigate('TAIL')} />

                        {resultsOpen && (
                            <LogResults search={search} groups={searching.groups} searchBusy={searching.searchBusy}
                                searchNote={searching.searchNote} pickedRow={searching.pickedRow}
                                onReopenDialog={searching.reopenSearchDialog} onCountRest={() => searching.runSearch(true)}
                                onClose={searching.closeResults} onToggleGroup={searching.toggleGroup}
                                onOpenMatch={openMatch} onLoadMore={searching.loadMore} />
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}
