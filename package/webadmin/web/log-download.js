/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * Saving a log file to the user's disk: the raw-bytes fetch the host's api
 * client cannot do, and useDownload, the hook that runs one download for the
 * view (progress text, Cancel, and what to do when the file has gone).
 */

import { platform } from '@oie/web-shell';
import { EXT_PATH, TASK_DOWNLOAD, bytes, noticeFrom, isStale, saveName } from './log-core.js';

const React = platform.React;

/*
 * Whether the user may download log files, asked each time it matters: role-based access
 * control can load its permissions after the view is already showing.
 */
export function canDownloadNow() {
    return platform.checkTask('other', TASK_DOWNLOAD);
}

/* The engine API root, as the host reads it (client/core/deployment.ts). */
function apiBase() {
    const meta = document.querySelector('meta[name="oie-webadmin-api-base"]');
    let base = ((meta && meta.content) || '/api').trim();
    if (!base.startsWith('/')) base = '/' + base;
    base = base.replace(/\/+$/, '');
    return base === '' ? '/api' : base;
}

/*
 * Fetches a file's raw bytes.
 *
 * The host's api client cannot do this: it reads every body with
 * response.text(), which would corrupt a zip, and it gives up after 120
 * seconds. The host's engine fetch (the one that carries the session fence) is
 * not part of the public plugin surface, so this is a plain fetch with the
 * same session cookie, the same anti-CSRF header and no timeout. It is a GET,
 * which the web administrator's proxy accepts without the tab's context header.
 *
 * The whole file is buffered in memory as a Blob before it is saved. That is
 * what platform.ui.saveFile takes; a streaming save is not available to a
 * plugin. A log file of hundreds of megabytes therefore needs that much browser
 * memory while it downloads.
 */
async function fetchLogFile(file, signal) {
    const url = apiBase() + EXT_PATH + '/download?fileId=' + encodeURIComponent(file.id);
    const response = await fetch(url, {
        method: 'GET',
        credentials: 'same-origin',
        cache: 'no-store',
        signal,
        headers: { 'Accept': '*/*', 'X-Requested-With': 'OpenIntegrationEngine-WebAdmin' }
    });
    if (!response.ok) {
        const text = await response.text().catch(() => '');
        const error = new Error(text || (response.status + ' ' + response.statusText));
        error.status = response.status;
        error.body = text;
        throw error;
    }
    try {
        return await response.blob();
    } catch (e) {
        if (signal.aborted) throw e;
        // The reply began but its body stopped before its Content-Length: the file was cut
        // while it was sent, or the connection dropped. The browser only says "Failed to fetch".
        const cut = new Error(e && e.message);
        cut.cutShort = true;
        throw cut;
    }
}

/*
 * One download at a time. The view hands in what it owns: the liveness flag, the refs to the
 * file on screen, the selected file and the gone state, setNotice, and the two things a gone
 * file needs (enterGone, loadList). Returns the progress state, the two actions, and the
 * AbortController ref so the view can stop a download when it unmounts.
 */
export function useDownload({ alive, shownRef, currentRef, goneRef, setNotice, enterGone, loadList }) {
    const { useState, useRef } = React;
    const [downloading, setDownloading] = useState(null);  // the file name while a download runs
    const [downloadNote, setDownloadNote] = useState('');
    const downloadRef = useRef(null);

    async function download() {
        // The file on screen, never the list's selection, though the button sits by the list:
        // a plain Refresh list re-points the selection by name, and after a rollover that name
        // is a different file than the one being read. With no page shown, the selected file.
        const file = shownRef.current ? shownRef.current.file : currentRef.current;
        if (!file || downloadRef.current || goneRef.current) return;
        const controller = new AbortController();
        downloadRef.current = controller;
        setDownloading(file.name);
        setDownloadNote('');
        setNotice(null);
        let fetched = null;
        try {
            // saveFile asks where to save first (inside the click), then calls this for the bytes.
            await platform.ui.saveFile(saveName(file.name), 'application/octet-stream', async () => {
                const blob = await fetchLogFile(file, controller.signal);
                fetched = blob;
                return blob;
            }, () => {
                if (!alive.current || controller.signal.aborted) throw new Error('Download cancelled.');
            });
            // saveFile returns quietly when the user closes the save dialog; only a fetched file was saved.
            if (alive.current) {
                setDownloadNote(fetched ? 'Saved ' + file.name + ' (' + bytes(fetched.size) + ').' : '');
            }
        } catch (e) {
            if (!alive.current) return;
            if (controller.signal.aborted) {
                setDownloadNote('Download cancelled.');
            } else if (isStale(e) && shownRef.current && shownRef.current.file === file) {
                // The file on screen is gone: the same state as when a page request finds it so.
                enterGone(file, null);
                loadList({ goneFile: file });
            } else {
                setNotice(noticeFrom(e, true, true));
                // The host signs the user in again when one of its own requests is refused with 401; this
                // download's plain fetch never went through it, so one ordinary request does.
                if (e && e.status === 401) platform.api.get(EXT_PATH + '/files').catch(() => {});
            }
        } finally {
            downloadRef.current = null;
            if (alive.current) setDownloading(null);
        }
    }

    function cancelDownload() {
        if (downloadRef.current) downloadRef.current.abort();
    }

    return { downloading, downloadNote, downloadRef, download, cancelDownload };
}
