/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The logic of the log viewer that does not touch the DOM, React or the host:
 * wire parsing, wording, number formats, the notice for a failed request and
 * the request sequence gate. The search's bookkeeping and wording are in
 * log-search-core.js. Framework-free so it is testable with node's own test runner.
 *
 * Ported from the Swing viewer's LogViewerFormat, LogViewerNotice and the
 * state handling in LogViewerDialog, so both interfaces say the same things.
 *
 * This file is pure ASCII: non-ASCII characters are built from their code points.
 */

import { lineCount, lineStartOffset, displayOffset } from './log-display.js';

/** Where the engine plugin's servlet lives, under the engine API root. */
export const EXT_PATH = '/extensions/oie-log-viewer';

/** Client task names the server registers with the permissions (role-based access control hides by these). */
export const TASK_VIEW = 'viewLogFiles';
export const TASK_DOWNLOAD = 'downloadLogFiles';

/* ---- wording and number formats (LogViewerFormat) --------------------------- */

/** A whole number with thousands separators, the same for every locale. */
export function count(n) {
    const whole = Math.trunc(Number(n) || 0);
    return String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** A byte count as B, KB, MB, GB or TB (1024 based) with one decimal. */
export function bytes(n) {
    const total = Number(n) || 0;
    if (total < 1024) return Math.trunc(total) + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let value = total;
    let unit = -1;
    while (value >= 1024 && unit < units.length - 1) {
        value /= 1024;
        unit++;
    }
    return value.toFixed(1) + ' ' + units[unit];
}

/** The separator of the status bar's parts: a middle dot between spaces. */
export const SEPARATOR = ' ' + String.fromCharCode(0xB7) + ' ';

/**
 * Where a page sits in its file, for the status bar, for example
 * "Lines 2,001-3,000 of 11,905", or "Lines 1-1,000 of 11,905 (middle dot) start of file".
 * Without the file's line count the " of N" is left out; with no line numbers
 * it falls back to bytes. The ending says "start of file" at offset 0, "end of
 * file" at the end and "whole file" when the page is both.
 *
 * @param firstLine 1-based number of the first line, or null when the server did not count
 * @param lines lines that hold text on the page, 0 for an empty page
 * @param totalLines lines in the whole file, or null when the server did not count them
 * @param atEnd true when the page reaches the end of the file
 */
export function positionLabel(firstLine, lines, totalLines, startOffset, endOffset, contentLength, atEnd) {
    let label;
    if (firstLine != null && lines > 0) {
        label = 'Lines ' + count(firstLine) + '-' + count(firstLine + lines - 1);
        if (totalLines != null) label += ' of ' + count(totalLines);
    } else {
        label = 'Bytes ' + count(startOffset) + '-' + count(endOffset);
        label += contentLength != null ? ' of ' + count(contentLength) : ' (total size not known yet)';
    }
    const atStart = startOffset <= 0;
    if (atStart && atEnd) return label + SEPARATOR + 'whole file';
    if (atStart) return label + SEPARATOR + 'start of file';
    if (atEnd) return label + SEPARATOR + 'end of file';
    return label;
}

/** What to say when a page starts or ends inside a line; empty when it does neither. */
export function midLineNotes(startsMidLine, endsMidLine) {
    if (startsMidLine && endsMidLine) {
        return 'The first line continues from the previous page and the last line continues on the next page.';
    }
    if (startsMidLine) return 'The first line continues from the previous page.';
    if (endsMidLine) return 'The last line continues on the next page.';
    return '';
}

/**
 * The file name inside a server-issued id such as
 * "fout/mirth.log.3.zip@a03c19e4d2f8", for a result whose file is no longer in
 * the list. Falls back to the id as it is.
 */
export function fileNameFromId(id) {
    if (id == null) return '';
    const slash = id.indexOf('/');
    const at = id.lastIndexOf('@');
    if (slash < 0 || at < slash) return id;
    return id.substring(slash + 1, at);
}

/**
 * An instant's date and time in the engine's time zone: { date, time, zone } with
 * the date as yyyy-MM-dd and the time as HH:MM:SS, or null for an instant that is
 * not a number. The zone id is an IANA name such as America/Denver; the label is
 * the display text the server also sends, such as "MDT (UTC-06:00)". When the
 * browser knows the id, `zone` is ''. When it does not, the offset in the label
 * is used and `zone` is that offset ("UTC-06:00"): the zone's offset now, which
 * a time before a daylight-saving change did not have. With neither, the time
 * is UTC and `zone` is "UTC".
 */
function inEngineZone(millis, zoneId, zoneLabel) {
    const when = new Date(millis);
    if (Number.isNaN(when.getTime())) return null;
    if (zoneId) {
        try {
            const parts = {};
            new Intl.DateTimeFormat('en-GB', {
                timeZone: zoneId, year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
            }).formatToParts(when).forEach((p) => { parts[p.type] = p.value; });
            return {
                date: parts.year + '-' + parts.month + '-' + parts.day,
                time: parts.hour + ':' + parts.minute + ':' + parts.second,
                zone: ''
            };
        } catch (e) {
            // Not a zone name this browser knows; fall back to the label's offset.
        }
    }
    const offset = /UTC([+-])(\d{2}):(\d{2})/.exec(zoneLabel || '');
    const minutes = offset ? (offset[1] === '-' ? -1 : 1) * (Number(offset[2]) * 60 + Number(offset[3])) : 0;
    const shifted = new Date(when.getTime() + minutes * 60000);
    const pad = (n) => String(n).padStart(2, '0');
    return {
        date: shifted.getUTCFullYear() + '-' + pad(shifted.getUTCMonth() + 1) + '-' + pad(shifted.getUTCDate()),
        time: pad(shifted.getUTCHours()) + ':' + pad(shifted.getUTCMinutes()) + ':' + pad(shifted.getUTCSeconds()),
        zone: offset ? offset[0] : 'UTC'
    };
}

/**
 * The clock time of an instant in the engine's time zone, as HH:MM:SS (see
 * inEngineZone). Returns { text, exact }: `exact` is true only when the browser
 * knows the engine's zone; otherwise the text names the offset or UTC it is in.
 */
export function clockInZone(millis, zoneId, zoneLabel) {
    const at = inEngineZone(millis, zoneId, zoneLabel);
    if (at === null) return { text: '', exact: false };
    return at.zone === '' ? { text: at.time, exact: true } : { text: at.time + ' ' + at.zone, exact: false };
}

/**
 * A file's modification time for the file list, in the engine's time zone, for
 * example "2026-10-06 16:30:12 MDT (UTC-06:00) (engine time)". When the browser
 * does not know the zone, the text names the offset or UTC it is in instead.
 */
export function modifiedText(file) {
    const at = inEngineZone(file.lastModified, file.timeZoneId, file.timeZoneLabel);
    if (at === null) return '';
    if (at.zone !== '') return at.date + ' ' + at.time + ' ' + at.zone;
    return at.date + ' ' + at.time + (file.timeZoneLabel ? ' ' + file.timeZoneLabel : '') + ' (engine time)';
}

/** The sentence of the snapshot strip for the active file, or '' when the page has no read time. */
export function snapshotText(file, readAt) {
    if (!file || readAt == null) return '';
    const clock = clockInZone(readAt, file.timeZoneId, file.timeZoneLabel);
    if (clock.text === '') return '';
    return 'Snapshot of ' + file.name + ' taken at ' + clock.text
        + (clock.exact ? ' (engine time)' : ' (engine time not known)') + '. It does not update by itself.';
}

/** The toolbar's time zone label for a file, or '' when the server did not say. */
export function zoneText(file) {
    return file && file.timeZoneLabel ? 'Times are engine time: ' + file.timeZoneLabel : '';
}

/* ---- wire shapes ------------------------------------------------------------- */

/*
 * The engine's JSON forms are XStream-wrapped: the host's api client strips
 * the single root key, but a list arrives as {"<FQCN>": [..]} (or a bare
 * object for one element, or "" when empty), a scalar can arrive wrapped, and
 * a boolean can arrive as the string "false". These helpers are tolerant of
 * all of that.
 */

function scalar(value) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
        const keys = Object.keys(value).filter((k) => !k.startsWith('@'));
        if (keys.length === 1) return scalar(value[keys[0]]);
    }
    return value;
}

/** A number, or null when absent, blank or not a number. */
export function num(value) {
    const v = scalar(value);
    if (v == null || v === '' || typeof v === 'object') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
}

/** Text; absent becomes the empty string. */
export function str(value) {
    const v = scalar(value);
    if (v == null || typeof v === 'object') return '';
    return String(v);
}

/** True only for true or the string "true". */
export function bool(value) {
    return String(scalar(value)) === 'true';
}

/**
 * The elements of an XStream list. `itemName` is the element's simple class
 * name ("LogFileInfo") or "string"; a wrapper key matches it by its last
 * dot-separated segment, so the FQCN form works.
 */
export function listOf(value, itemName) {
    if (value == null || value === '') return [];
    if (Array.isArray(value)) return value;
    if (typeof value !== 'object') return [value];
    const keys = Object.keys(value).filter((k) => !k.startsWith('@'));
    if (keys.length === 0) return [];
    if (keys.length === 1 && keys[0].split('.').pop().toLowerCase() === itemName.toLowerCase()) {
        const inner = value[keys[0]];
        if (inner == null || inner === '') return [];
        return Array.isArray(inner) ? inner : [inner];
    }
    return [value];
}

/** The object itself, or the one inside a single wrapper key when `field` is not at the top. */
function root(raw, field) {
    if (!raw || typeof raw !== 'object') return {};
    if (field in raw) return raw;
    const keys = Object.keys(raw).filter((k) => !k.startsWith('@'));
    if (keys.length === 1 && raw[keys[0]] && typeof raw[keys[0]] === 'object') return raw[keys[0]];
    return raw;
}

export function parseFile(raw) {
    const f = raw && typeof raw === 'object' ? raw : {};
    const note = str(f.note);
    return {
        id: str(f.id),
        name: str(f.name),
        size: num(f.size) || 0,
        lastModified: num(f.lastModified) || 0,
        active: bool(f.active),
        compressed: bool(f.compressed),
        // Absent means "let the server decide": an unviewable file answers NOT_VIEWABLE.
        viewable: String(scalar(f.viewable)) !== 'false',
        note: note === '' ? null : note,
        charset: str(f.charset),
        timeZoneId: str(f.timeZoneId),
        timeZoneLabel: str(f.timeZoneLabel)
    };
}

/** LogFileList -> { files, warnings }. */
export function parseFileList(raw) {
    const r = root(raw, 'files');
    return {
        files: listOf(r.files, 'LogFileInfo').map(parseFile).filter((f) => f.id !== ''),
        warnings: listOf(r.warnings, 'string').map(str).filter((w) => w !== '')
    };
}

/** LogPage -> the page with plain types. */
export function parsePage(raw) {
    const r = root(raw, 'startOffset');
    return {
        fileId: str(r.fileId),
        text: str(r.text),
        startOffset: num(r.startOffset) || 0,
        endOffset: num(r.endOffset) || 0,
        contentLength: num(r.contentLength),
        firstLineNumber: num(r.firstLineNumber),
        // Lines in the whole file; null for a file past 64 MiB, which the server does not count.
        totalLines: num(r.totalLines),
        startsMidLine: bool(r.startsMidLine),
        endsMidLine: bool(r.endsMidLine),
        atEnd: bool(r.atEnd),
        readAt: num(r.readAt),
        // Absent (no search was sent) and null (no matches on the page) both mean none to paint.
        highlights: parseHighlights(r.highlights),
        // Which limit stopped the highlighting before the page's end: MATCH_LIMIT, TIME_LIMIT,
        // TOO_COMPLEX, or '' when it reached the end.
        highlightsStopped: str(r.highlightsStopped),
        // For a page read AT an offset (a search match), the index in `text` of the character
        // starting at that offset; null for other pages, or when the offset is not on the page.
        targetIndex: num(r.targetIndex)
    };
}

/**
 * The flat start/end list of a page's search highlights, as plain numbers. The
 * wire form is {"int": [start, end, ...]}; a single number can arrive bare and a
 * missing or empty value means no highlights. A trailing unpaired number is dropped.
 */
export function parseHighlights(value) {
    const numbers = listOf(value, 'int').map(num).filter((n) => n !== null);
    return numbers.length % 2 === 0 ? numbers : numbers.slice(0, numbers.length - 1);
}

export function parseMatch(raw) {
    const m = raw && typeof raw === 'object' ? raw : {};
    return {
        fileId: str(m.fileId),
        lineNumber: num(m.lineNumber) || 0,
        lineOffset: num(m.lineOffset) || 0,
        matchOffset: num(m.matchOffset) || 0,
        lineText: str(m.lineText),
        matchStart: num(m.matchStart) || 0,
        matchEnd: num(m.matchEnd) || 0,
        truncated: bool(m.truncated)
    };
}

/** One LogFileMatchCount: how many lines of one file match, and whether its count reached the end. */
export function parseFileCount(raw) {
    const c = raw && typeof raw === 'object' ? raw : {};
    return { fileId: str(c.fileId), matchingLines: num(c.matchingLines) || 0, complete: bool(c.complete) };
}

/** LogSearchResult -> the result with plain types; a stopReason of '' is null. */
export function parseSearchResult(raw) {
    const r = root(raw, 'filesInScope');
    const stop = str(r.stopReason);
    return {
        matches: listOf(r.matches, 'LogSearchMatch').map(parseMatch),
        // Only a count has these; a count of one file arrives as a bare object, not a list.
        fileCounts: listOf(r.fileCounts, 'LogFileMatchCount').map(parseFileCount),
        warnings: listOf(r.warnings, 'string').map(str).filter((w) => w !== ''),
        complete: bool(r.complete),
        stopReason: stop === '' ? null : stop,
        resumeFileId: str(r.resumeFileId) || null,
        resumeOffset: num(r.resumeOffset),
        filesInScope: num(r.filesInScope) || 0,
        filesSearched: num(r.filesSearched) || 0,
        bytesSearched: num(r.bytesSearched) || 0,
        splitLineCount: num(r.splitLineCount) || 0
    };
}

/* ---- errors (LogViewerNotice) ------------------------------------------------ */

function decodeEntities(text) {
    return text.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);/g, (all, e) => {
        switch (e) {
            case 'lt': return '<';
            case 'gt': return '>';
            case 'amp': return '&';
            case 'quot': return '"';
            case 'apos': return '\'';
            default: {
                const code = e.charAt(1) === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
                return Number.isFinite(code) && code <= 0x10FFFF ? String.fromCodePoint(code) : all;
            }
        }
    });
}

/**
 * Reads the error body the servlet always sends as XML:
 * <com.diridium.logviewer.LogViewerError><kind>STALE</kind><message>..</message>.
 * A small fixed shape, so two patterns do; the browser's XML parser is not
 * available to a framework-free module and a failed parse must never throw.
 * Returns { kind, message } (either can be null), or null when the body is not
 * a log viewer error.
 */
export function parseErrorBody(body) {
    if (typeof body !== 'string' || body.indexOf('LogViewerError') < 0) return null;
    const kind = /<kind>\s*([A-Za-z_]+)\s*<\/kind>/.exec(body);
    const message = /<message>([\s\S]*?)<\/message>/.exec(body);
    if (!kind && !message) return null;
    return {
        kind: kind ? kind[1] : null,
        message: message ? decodeEntities(message[1]) : null
    };
}

function blank(s) {
    return s == null || String(s).trim() === '';
}

function forbidden(download) {
    if (download) {
        return 'You do not have access to download log files. Your role needs the Download Log Files '
            + 'permission and must not be limited to specific channels.';
    }
    return 'You do not have access to log files. Your role needs the View Log Files permission '
        + 'and must not be limited to specific channels.';
}

function withDownloadHint(text, canDownload) {
    const sentence = text.endsWith('.') ? text : text + '.';
    return canDownload ? sentence + ' You can download the file instead.' : sentence;
}

/**
 * The name a download is saved under: the file's name, with the folder of an archive kept in a
 * date folder joined on by an underscore, since a saved file name cannot hold a slash.
 */
export function saveName(name) {
    return name.split('/').join('_');
}

/** What the notice says when a file that can only be downloaded is opened. */
export function notViewableText(file, canDownload) {
    return withDownloadHint(file.name + ' cannot be viewed here.' + (file.note ? ' ' + file.note : ''), canDownload);
}

/** True when the error is the engine's 409 STALE: the file the request named is gone, or is a different file now. */
export function isStale(error) {
    const parsed = parseErrorBody(error && error.body);
    return parsed !== null && parsed.kind === 'STALE';
}

/** Whether the file whose page is on screen is missing from a freshly loaded list. Compared by id, never by name. */
export function fileIsGone(shownFileId, files) {
    return shownFileId != null && !files.some((f) => f.id === shownFileId);
}

/** The notice for a file on screen that no longer exists under its name. */
export function goneNotice(name) {
    return name + ' no longer exists under that name: a log rollover renamed or removed it after you opened it.'
        + ' The page shown is kept so you can finish reading it, but it can\'t be paged or downloaded.';
}

/** The label of the notice's button that opens the file now holding the name. */
export function goneOpenLabel(name) {
    return 'Open the current ' + name;
}

/** The tooltip of every control that is disabled because the file on screen is gone. */
export function goneTooltip(name) {
    return name + ' no longer exists under that name. Open a file from the list.';
}

/** A download whose body stopped short. The viewer cannot tell a cut file from a dropped connection. */
export const DOWNLOAD_CUT_SHORT = 'The download stopped before the whole file arrived, so nothing was saved. Try again.';

/** A busy refusal that came without the engine's own text. */
const BUSY_MESSAGE = 'The engine is busy. Try again in a moment.';

/**
 * What the notice bar says about a failed request, and whether it offers a
 * Refresh. `error` is whatever the request threw: the host's ApiError carries
 * `status`, `body` (raw text) and `message`, and so does the download's own
 * fetch (log-download.js). Returns { message, refresh }.
 *
 * Only the engine's typed error (LogViewerError) is shown in its own words. A
 * reply without one is described by its status, never by its body, which can be
 * a gateway's or a proxy's page. An error with no status (the request never got
 * a reply) is shown by its message.
 *
 * A 403 for a missing permission has no body, so it cannot say whether the role
 * lacks the permission or is limited to specific channels; the wording covers
 * both.
 *
 * @param download    true when the failed request was a download, which needs a different permission
 * @param canDownload true when the user may download, so a file that cannot be paged can be offered that way
 */
export function noticeFrom(error, download, canDownload) {
    if (error && error.cutShort) return { message: DOWNLOAD_CUT_SHORT, refresh: false };
    const status = error && typeof error.status === 'number' && error.status > 0 ? error.status : null;
    const parsed = parseErrorBody(error && error.body);
    const kind = parsed ? parsed.kind : null;
    const text = parsed ? parsed.message : null;

    switch (kind) {
        case 'STALE':
            return { message: 'This file has rotated since it was listed.', refresh: true };
        case 'BUSY':
            // The engine's text says which limit is in force.
            return { message: blank(text) ? BUSY_MESSAGE : text, refresh: false };
        case 'TOO_LARGE':
            return {
                message: withDownloadHint(blank(text) ? 'This file is too large to view here.' : text, canDownload),
                refresh: false
            };
        case 'NOT_VIEWABLE':
            return {
                message: withDownloadHint(blank(text) ? 'This file cannot be viewed here.' : text, canDownload),
                refresh: false
            };
        case 'CHANNEL_RESTRICTED':
            return { message: forbidden(download), refresh: false };
        case 'BAD_REQUEST':
        case 'READ_FAILED':
            return { message: blank(text) ? 'The server could not complete the request.' : text, refresh: false };
        default:
            break;
    }
    if (kind != null) {
        return { message: blank(text) ? 'The server refused the request.' : text, refresh: false };
    }

    // No typed body: go by the status, then by whatever message there is.
    if (status === 403) return { message: forbidden(download), refresh: false };
    if (status === 401) {
        // A download is a plain fetch, so the host's own sign-in prompt has not run for it (see log-download.js).
        return {
            message: download ? 'Your session has ended. Sign in again, then download the file.'
                : 'Your session has expired. Sign in again.',
            refresh: false
        };
    }
    if (status === 404 || status === 501) {
        return {
            message: 'The OIE Log Viewer plugin is not installed on this engine.',
            refresh: false
        };
    }
    if (status === 409) return { message: 'This file has rotated since it was listed.', refresh: true };
    // The server sends 429 when its search or page-read limit is reached. A bare 502, 503 or 504
    // comes from the gateway or proxy in front of the engine, not from this plugin.
    if (status === 429) return { message: BUSY_MESSAGE, refresh: false };
    if (status === 422) {
        return { message: withDownloadHint('This file is too large to view here', canDownload), refresh: false };
    }
    if (status === 502 || status === 503 || status === 504) {
        return { message: 'The engine could not be reached.', refresh: false };
    }
    if (status !== null) {
        return {
            message: (download ? 'The download failed' : 'The engine returned an error') + ' (HTTP ' + status + ').',
            refresh: false
        };
    }
    const own = error && error.message;
    return { message: blank(own) ? 'The request failed.' : String(own), refresh: false };
}

/* ---- request sequencing ------------------------------------------------------ */

/**
 * A sequence gate: take a token when a request starts, and ignore its response
 * unless the token is still current. A newer request, or cancel(), makes every
 * older token stale.
 */
export function createGate() {
    let n = 0;
    return {
        next() { return ++n; },
        current(token) { return token === n; },
        cancel() { n++; }
    };
}

/* ---- navigation, files and search -------------------------------------------- */

/**
 * Which navigation buttons are enabled. `page` is the shown page (or null),
 * `file` the file it came from. A page that reached the end of its file when it
 * was read has nothing after it, the active file's too: the lines written since
 * come only through Load latest lines, so the snapshot changes only there.
 * `snapshotEnd` is true when Next and Last are off for that reason on the active
 * file, which their tooltip then says (SNAPSHOT_END_TIP).
 */
export function navState(page, file, busy, gone) {
    const have = page != null && file != null && !busy && gone !== true;
    const atStart = have && page.startOffset <= 0;
    const atEnd = have && page.atEnd;
    return {
        first: have && !atStart,
        previous: have && !atStart,
        next: have && !atEnd,
        last: have && !atEnd,
        snapshotEnd: page != null && file != null && file.active === true && page.atEnd === true && gone !== true
    };
}

/** The tooltip of Next page and Last page at the end of the active file. */
export const SNAPSHOT_END_TIP = 'This is the end of the snapshot. Load latest lines shows anything written since.';

/** The list the server sent, with the active file first (a stable sort, otherwise the server's order). */
export function activeFirst(files) {
    return files
        .map((file, index) => ({ file, index }))
        .sort((a, b) => (Number(b.file.active) - Number(a.file.active)) || (a.index - b.index))
        .map((entry) => entry.file);
}

export function fileById(files, id) {
    return files.find((f) => f.id === id) || null;
}

export function fileByName(files, name) {
    return files.find((f) => f.name === name) || null;
}

/**
 * The page on screen with the highlights of a re-read of it: the same file read
 * again from the page's start offset (anchor AFTER) with a search attached, so
 * the engine marks the matches. A page that reached the end of the active file
 * reads on to the file's current end when it is re-read, so the re-read can hold
 * lines written since; they are left out, with their highlights, and the page
 * stays as it was read: the snapshot changes only through Load latest lines.
 * When the re-read does not start with the page's text (the file was rewritten
 * in place), the re-read is shown as it is.
 */
export function sameLines(page, reread) {
    if (reread.startOffset !== page.startOffset || !reread.text.startsWith(page.text)) return reread;
    const end = page.text.length;
    const highlights = [];
    for (let i = 0; i + 1 < reread.highlights.length; i += 2) {
        if (reread.highlights[i + 1] <= end) highlights.push(reread.highlights[i], reread.highlights[i + 1]);
    }
    return Object.assign({}, page, { highlights, highlightsStopped: reread.highlightsStopped });
}

/**
 * What a search whose count has finished does about the page on screen: 'wait'
 * while a page request runs (it may be one the user started, and a new page
 * request would drop it; the page it brings is checked when it answers, whatever
 * the page on screen now carries), 'read' the page again with the search attached
 * (see sameLines), or 'none': no search is open, no page, its file is gone, or
 * the page already carries this search's highlights. `shown` is the page on
 * screen (or null), `key` the search's highlight key.
 */
export function markDecision(shown, key, gone, busy) {
    if (key === '') return 'none';
    if (busy) return 'wait';
    if (shown == null || gone || shown.hlKey === key) return 'none';
    return 'read';
}

/**
 * The page request's highlight parameters for a search, or an empty object when
 * there is none. Only the engine runs the search; the browser sends the text.
 */
export function highlightParams(params) {
    if (!params || params.query == null || params.query === '') return {};
    return {
        highlightQuery: params.query,
        highlightRegex: params.regex,
        highlightCaseSensitive: params.caseSensitive
    };
}

/**
 * The notice when Monaco did not load and the viewer stays the plain textarea. It paints no
 * highlights, so the highlight notes are not shown with it either.
 */
export const PLAIN_TEXT_NOTICE = 'The full editor did not load, so this page is shown as plain text without highlights '
    + 'or line numbers when wrapped. Reloading the page may help.';

/** The note shown when the engine stopped highlighting a page early, by why it stopped; '' when it did not. */
export function highlightStopNote(stopped) {
    switch (stopped) {
        case 'MATCH_LIMIT': return 'Only the first 5,000 matches on this page are highlighted.';
        case 'TIME_LIMIT': return 'Highlighting reached its 2-second limit, so only part of this page is highlighted.';
        case 'TOO_COMPLEX':
            return 'The search pattern is too complex for a line on this page, so only part of it is highlighted.';
        default: return '';
    }
}

/**
 * Where the match starts inside `haystack`, or -1. The server's line text is
 * the whole line unless it was cut to a window around the match, in which case
 * the window is looked for first and the match position is taken inside it.
 */
function locateInLine(haystack, lineText, matchStart, hit) {
    if (hit === '') return -1;
    const window = haystack.indexOf(lineText);
    if (window >= 0) {
        const rel = window + matchStart;
        if (haystack.startsWith(hit, rel)) return rel;
    }
    return haystack.indexOf(hit);
}

/**
 * Where the match a page was opened at is in the display text, as locateMatch
 * returns it. The engine says where on the page the match starts (the page's
 * targetIndex, an index into the raw page text `raw`), which maps onto the
 * display text exactly; only a page without one is searched for the line's
 * text. A match of no characters marks its whole line.
 */
export function revealTarget(raw, display, firstLine, targetIndex, match) {
    const at = targetIndex == null ? -1 : displayOffset(raw, display, targetIndex);
    if (at < 0) return locateMatch(display, firstLine, match);
    const length = Math.max(0, (match.matchEnd || 0) - (match.matchStart || 0));
    if (length > 0) return { start: at, end: at + length, whole: false };
    const newline = display.indexOf('\n', at);
    const lineStart = at > 0 ? display.lastIndexOf('\n', at - 1) + 1 : 0;
    return { start: lineStart, end: newline < 0 ? display.length : newline, whole: true };
}

/**
 * Finds a search match on the page that was opened at it. `display` is the
 * display text the viewer holds; `firstLine` the 1-based number of its first
 * line (null when the server did not count). Returns { start, end, whole }
 * as character offsets into `display`, where `whole` means only the line could
 * be found, not the match inside it; or null when the match is not on the page.
 *
 * Line index = lineNumber - firstLine. Without a line count, or when that
 * number is off the page, the text is looked for instead.
 */
export function locateMatch(display, firstLine, match) {
    const lineText = match.lineText || '';
    const matchStart = Math.max(0, Math.min(match.matchStart, lineText.length));
    const matchEnd = Math.max(matchStart, Math.min(match.matchEnd, lineText.length));
    const hit = lineText.substring(matchStart, matchEnd);

    if (firstLine != null) {
        const index = match.lineNumber - firstLine;
        if (index >= 0 && index < lineCount(display)) {
            const lineStart = lineStartOffset(display, index);
            const newline = display.indexOf('\n', lineStart);
            const lineEnd = newline < 0 ? display.length : newline;
            const line = display.substring(lineStart, lineEnd);
            const rel = locateInLine(line, lineText, matchStart, hit);
            if (rel < 0) {
                // The line is there but the match is not where it should be: show the line.
                return { start: lineStart, end: lineEnd, whole: true };
            }
            return { start: lineStart + rel, end: lineStart + rel + hit.length, whole: false };
        }
    }
    const rel = locateInLine(display, lineText, matchStart, hit);
    if (rel < 0) return null;
    return { start: rel, end: rel + hit.length, whole: false };
}
