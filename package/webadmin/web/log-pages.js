/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The page side of the view: usePages holds the page on screen and runs the
 * page requests (GET /page) and the four page buttons. What a page request
 * found gone is handed back to the view, which owns the gone state and the
 * file list.
 */

import { platform } from '@oie/web-shell';
import { EXT_PATH, noticeFrom, isStale, parsePage, sameLines } from './log-core.js';
import { highlightKey } from './log-viewer-state.js';
import { canDownloadNow } from './log-download.js';

const React = platform.React;

/*
 * The view hands in what it owns: the liveness flag, the page request gate, setNotice, `marks`
 * (the highlight parameters every page request carries, {} for none), `onGone`, called with
 * the file on screen when a request for it finds it no longer exists, and `onLanded`, called
 * when the newest page request has answered (true when its page is on screen). Returns the page
 * on screen ({ file, data, anchor, offset, reveal, keepScroll, hlKey } or null), its ref,
 * whether a page request runs (and its ref), and the actions.
 */
export function usePages({ alive, gate, setNotice, marks, onGone, onLanded }) {
    const { useState, useRef } = React;
    const api = platform.api;

    const [shown, setShown] = useState(null);
    const [pageBusy, setPageBusy] = useState(false);
    const shownRef = useRef(null);
    const pageBusyRef = useRef(false);

    function updateShown(value) { shownRef.current = value; setShown(value); }
    function updatePageBusy(value) { pageBusyRef.current = value; setPageBusy(value); }

    /*
     * keepScroll: the same page re-read only to add highlights, so the viewer must not move.
     * samePage: that page's data, which stays as it was (see sameLines); only the highlights are new.
     */
    async function loadPage(file, anchor, offset, reveal, keepScroll, samePage) {
        const token = gate.next();
        updatePageBusy(true);
        // While the results are open every page carries the search, and the engine marks its matches.
        const sent = marks();
        let landed = false;
        try {
            const raw = await api.get(EXT_PATH + '/page', { fileId: file.id, anchor, offset, ...sent });
            if (!alive.current || !gate.current(token)) return;
            const data = samePage ? sameLines(samePage, parsePage(raw)) : parsePage(raw);
            updateShown({ file, data, anchor, offset, reveal, keepScroll: keepScroll === true, hlKey: highlightKey(sent) });
            landed = true;
        } catch (e) {
            if (!alive.current || !gate.current(token)) return;
            const n = noticeFrom(e, false, canDownloadNow());
            const on = shownRef.current;
            if (isStale(e) && on && on.file.id === file.id) {
                // The file on screen is gone: the view keeps its page and finds out what holds its name now.
                onGone(on.file);
                return;
            }
            // A rotated file's old text no longer matches any id the server will accept.
            if (n.refresh || !on || on.data.fileId !== file.id) updateShown(null);
            setNotice(n);
        } finally {
            if (alive.current && gate.current(token)) {
                updatePageBusy(false);
                onLanded(landed);
            }
        }
    }

    function navigate(anchor) {
        const on = shownRef.current;
        if (!on) return;
        setNotice(null);
        let offset = null;
        if (anchor === 'BEFORE') offset = on.data.startOffset;
        if (anchor === 'AFTER') offset = on.data.endOffset;
        loadPage(on.file, anchor, offset, null);
    }

    /* Nothing on screen, and a page request still running is ignored when it answers. */
    function clearPage() {
        gate.cancel();
        updatePageBusy(false);
        updateShown(null);
    }

    return { shown, shownRef, pageBusy, pageBusyRef, loadPage, navigate, clearPage };
}
