/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The search's logic that does not touch the DOM, React or the host: the count
 * accumulator (a count and every Count the rest after it), the result groups,
 * and what the results panel says. Framework-free so it is testable with node's
 * own test runner.
 *
 * Ported from the Swing viewer's LogViewerFormat and the search handling in
 * LogViewerDialog, so both interfaces say the same things.
 *
 * This file is pure ASCII: non-ASCII characters are built from their code points.
 */

import { count, bytes, fileById, fileNameFromId } from './log-core.js';

/* ---- wording ------------------------------------------------------------------ */

/** What the header says when the log files rotated while a count ran. */
export const ROTATED_TEXT = 'The log files rotated while counting. Refresh the list and search again.';

/** What the header says when the engine gave up on a regular expression and sent no words of its own. */
export const TOO_COMPLEX_TEXT = 'The regular expression was too complex for one of the lines. Simplify the pattern.';

/** "1 matching line" or "5 matching lines", with thousands separators. */
export function matchingLinesText(n) {
    return count(n) + (n === 1 ? ' matching line' : ' matching lines');
}

function filesText(n) {
    return count(n) + (n === 1 ? ' file' : ' files');
}

/**
 * The results panel's header for a count: the totals, or why the count stopped.
 * `acc` is the count accumulator (see emptySearch and addCountResult). How far
 * the count got and the warnings are not here; see resultsSummary and searchNotes.
 */
export function countHeader(acc) {
    const stop = acc.last ? acc.last.stopReason : null;
    const totals = countTotals(acc);
    if (stop === 'FILES_ROTATED') {
        return ROTATED_TEXT;
    } else if (stop === 'PATTERN_TOO_COMPLEX') {
        // The engine's own words; searchNotes leaves them out.
        const own = acc.last.warnings;
        return own.length > 0 ? own.join(' ') : TOO_COMPLEX_TEXT;
    } else if (stop === 'DEADLINE') {
        return matchingLinesText(totals.lines) + ' so far in ' + filesText(totals.filesWithMatches) + '.';
    } else if (stop != null) {
        return 'Stopped: ' + stop + '.';
    } else if (totals.lines === 0) {
        return 'No matching lines in ' + filesText(acc.filesInScope);
    }
    return matchingLinesText(totals.lines) + ' in ' + filesText(totals.filesWithMatches);
}

/**
 * The right of the results status line: the engine's warnings joined, or '' when
 * there are none. The engine's own words for a pattern that is too complex are
 * already the header, so they are left out here.
 */
export function searchNotes(acc) {
    let shown = acc.warnings;
    if (acc.last && acc.last.stopReason === 'PATTERN_TOO_COMPLEX') {
        shown = acc.warnings.filter((w) => acc.last.warnings.indexOf(w) < 0);
    }
    return shown.length > 0 ? 'Warnings: ' + shown.join('; ') : '';
}

/**
 * The left of the results status line when the search is idle, for example
 * "Counted 7 of 7 files, 12.0 MB searched in 0.9 s"; '' before any count.
 * The size and the time add up the first count and every Count the rest.
 */
export function resultsSummary(acc) {
    if (acc.last == null) return '';
    const done = acc.entries.filter((e) => e.complete).length;
    const limit = stoppedAtLimit(acc) ? ' before the time limit' : '';
    return 'Counted ' + count(done) + ' of ' + count(acc.filesInScope) + ' files' + limit + ', '
        + bytes(acc.bytesSearched) + ' searched in ' + (acc.millis / 1000).toFixed(1) + ' s';
}

/** How many matching lines one click on a result group's last row loads (the engine's page of them). */
export const GROUP_PAGE = 1000;

/**
 * How many lines the next click loads, or null when that cannot be said: the
 * group's count is partial, or more are shown than the count said (the active
 * file grew after the count).
 */
export function nextBatch(shown, total, partial) {
    if (partial || shown >= total) return null;
    return Math.min(GROUP_PAGE, total - shown);
}

/**
 * The text of the last row of an open result group that has more to load.
 * `state` is 'idle', 'loading' or 'failed'.
 */
export function loadMoreText(shown, total, partial, state) {
    const next = nextBatch(shown, total, partial);
    if (state === 'failed') return 'Could not load more. Click to try again.';
    if (state === 'loading') return next === null ? 'Loading more...' : 'Loading the next ' + count(next) + '...';
    if (next === null) return 'Click to show more.';
    return count(shown) + ' of ' + count(total) + ' shown. Click to show the next ' + count(next) + '.';
}

/** The results status line while a group loads, for example "Loading the next 1,000 from mirth.log.3.zip...". */
export function loadingFromText(shown, total, partial, fileName) {
    const next = nextBatch(shown, total, partial);
    return (next === null ? 'Loading more' : 'Loading the next ' + count(next)) + ' from ' + fileName + '...';
}

/** The start of the results title, up to the opening quote of the search text. */
export const SEARCH_TITLE_LEAD = 'Search results for "';

/**
 * The end of the results title from the closing quote of the search text, for
 * example '" in all files (Java regular expression, match case)'. The title is one
 * line; when it does not fit, only the search text is cut short, so this part
 * always shows.
 */
export function searchTitleRest(fileName, regex, caseSensitive) {
    const options = [regex ? 'Java regular expression' : '', caseSensitive ? 'match case' : ''].filter((o) => o !== '');
    return '" in ' + (fileName || 'all files') + (options.length > 0 ? ' (' + options.join(', ') + ')' : '');
}

/**
 * `text` cut into pieces of at most `width` characters, for a tooltip that shows
 * a search text whole: a JSON blob can be one long run with no space for a
 * tooltip to wrap at. A surrogate pair is never split.
 */
export function pieces(text, width) {
    const out = [];
    let start = 0;
    while (start < text.length) {
        let end = Math.min(text.length, start + width);
        const last = text.charCodeAt(end - 1);
        if (end < text.length && last >= 0xD800 && last <= 0xDBFF) end--;
        out.push(text.substring(start, end));
        start = end;
    }
    return out;
}

/** The longest search text the dialog takes; the engine refuses a longer one. */
export const QUERY_MAX = 1000;

/** Said under the search box when a paste was cut to QUERY_MAX characters. */
export const QUERY_CUT_NOTE = 'Searches are limited to 1,000 characters.';

/**
 * Whether pasting `pasted` into the search box goes past QUERY_MAX, so the box keeps
 * only the start of it. The box holds `length` characters, `selected` of them
 * selected (the paste replaces them). A one-line box drops line breaks from a
 * paste, so they are not counted.
 */
export function pasteIsCut(length, selected, pasted) {
    return length - selected + String(pasted || '').replace(/[\r\n]/g, '').length > QUERY_MAX;
}

/**
 * The scope the Search dialog opens with from the results title: This file
 * ('selected') while the file the search was scoped to is still the one open
 * (`currentFile`, the file This file would search now), otherwise All files.
 */
export function reopenScope(params, currentFile) {
    return params.fileId != null && currentFile != null && currentFile.id === params.fileId ? 'selected' : 'all';
}

/** The header of one result group, for example "mirth.log.3.zip (578 matching lines)"; "245+" while its count is unfinished. */
export function groupTitle(entry, partial) {
    const lines = matchingLinesText(entry.matchingLines);
    return entry.fileName + ' (' + (partial ? lines.replace(' matching', '+ matching') : lines) + ')';
}

/** The last row of a result group whose file is gone; it is not clickable. */
export function staleGroupText(name) {
    return name + ' no longer exists under that name. Search again for current results.';
}

/** The last row of a group whose search stopped at the time limit before its first matching line; a click searches on. */
export const KEEP_SEARCHING_TEXT = 'The search reached its time limit before finding a line in this file. Click to keep searching.';

/** The last row of a group whose search stopped on a line the pattern is too complex for; it is not clickable. */
export const GROUP_TOO_COMPLEX_TEXT = 'The search pattern is too complex for a line in this file. Try a simpler pattern.';

/**
 * Said in place of Count the rest, or of a group's last row, when carrying on came back with the
 * point it was sent from: it got nowhere, and would get nowhere again.
 */
export const STUCK_TEXT = 'The search can\'t get past this point within its time limit (a very long line or a complex pattern). '
    + 'Try a simpler pattern.';

/* ---- the count and the result groups ------------------------------------------ */

/** The name to show for a file id: from the list when it is there, else cut out of the id. */
function nameOf(files, id) {
    const file = fileById(files, id);
    return file ? file.name : fileNameFromId(id);
}

/**
 * A fresh count accumulator: the per-file counts of a started count and every "Count the rest" after it.
 * `stuck` is true when the last Count the rest came back with the resume point it was sent.
 */
export function emptySearch() {
    return { entries: [], filesInScope: 0, warnings: [], params: null, last: null, bytesSearched: 0, millis: 0, stuck: false };
}

/**
 * True when a reply to a continuation (sent from `sent`, { fileId, offset }, or null for a first
 * request) carries the same resume point again: the search made no progress.
 */
export function sameResumePoint(sent, result) {
    return sent != null && result.resumeFileId === sent.fileId && result.resumeOffset === sent.offset;
}

/** `warnings` plus the ones in `more` it does not hold yet, in first-seen order. */
export function addWarnings(warnings, more) {
    const merged = warnings.slice();
    for (const w of more) {
        if (merged.indexOf(w) < 0) merged.push(w);
    }
    return merged;
}

/**
 * Adds one count result to the accumulator. When the previous count stopped
 * inside a file (its last entry is not complete), the new result's first entry
 * is that same file's count continued, so it is added to it; the other entries
 * are appended. Entries carry the file name for the group header. `elapsedMs` is
 * the time the viewer waited for this request; the bytes and the time add up.
 * A Count the rest that comes back with the resume point it was sent is `stuck`.
 */
export function addCountResult(acc, params, result, files, elapsedMs) {
    const sent = canCountRest(acc) ? { fileId: acc.last.resumeFileId, offset: acc.last.resumeOffset } : null;
    const entries = acc.entries.slice();
    const fresh = result.fileCounts.slice();
    const lastIndex = entries.length - 1;
    if (lastIndex >= 0 && !entries[lastIndex].complete && fresh.length > 0
        && fresh[0].fileId === entries[lastIndex].fileId) {
        const rest = fresh.shift();
        entries[lastIndex] = Object.assign({}, entries[lastIndex], {
            matchingLines: entries[lastIndex].matchingLines + rest.matchingLines,
            complete: rest.complete
        });
    }
    for (const c of fresh) {
        entries.push({ fileId: c.fileId, fileName: nameOf(files, c.fileId), matchingLines: c.matchingLines, complete: c.complete });
    }
    return {
        entries,
        filesInScope: result.filesInScope,
        warnings: addWarnings(acc.warnings, result.warnings),
        params,
        last: result,
        bytesSearched: acc.bytesSearched + result.bytesSearched,
        millis: acc.millis + (elapsedMs || 0),
        stuck: sameResumePoint(sent, result)
    };
}

/** True when the count stopped at the time limit and left a point to count the rest from. */
export function stoppedAtLimit(acc) {
    return acc.last != null && acc.last.stopReason === 'DEADLINE'
        && acc.last.resumeFileId != null && acc.last.resumeOffset != null;
}

/** True when Count the rest is offered: the count stopped at the time limit, and carrying on last got somewhere. */
export function canCountRest(acc) {
    return stoppedAtLimit(acc) && !acc.stuck;
}

/** True for the entry the time limit cut off: its number is a minimum, the rest of the file not counted. */
export function isPartial(acc, entry) {
    const last = acc.entries[acc.entries.length - 1];
    return stoppedAtLimit(acc) && last === entry && !entry.complete;
}

/**
 * The totals of a count: matching lines, files with at least one, and files
 * counted to their end (a file the time limit cut off is not counted yet).
 */
export function countTotals(acc) {
    let lines = 0;
    let filesWithMatches = 0;
    for (const e of acc.entries) {
        lines += e.matchingLines;
        if (e.matchingLines > 0) filesWithMatches++;
    }
    return {
        lines,
        filesWithMatches,
        counted: acc.entries.length - (stoppedAtLimit(acc) ? 1 : 0)
    };
}

/** The result groups to show: the files with at least one matching line, in the order the engine counted them. */
export function countGroups(acc) {
    return acc.entries.filter((e) => e.matchingLines > 0);
}

/** A search result's matches as result rows, each with the file name for its group. */
export function resultRows(result, files) {
    return result.matches.map((match) => ({ fileName: nameOf(files, match.fileId), match }));
}

/** Where to continue a search of one file from, or null when it reached the end. */
export function resumePoint(result) {
    if (result.resumeFileId == null || result.resumeOffset == null) return null;
    return { fileId: result.resumeFileId, offset: result.resumeOffset };
}

/**
 * A result group after a load of its matching lines (GET /search) answered: `result` is the reply,
 * `rows` its result rows, `sent` the resume point the load was sent from (null for the first).
 * Returns the fields to change. A reply with the resume point it was sent is `stuck` and offers no
 * more; a search stopped by a rotation means the file is gone.
 */
export function groupAfterLoad(group, result, rows, sent) {
    const stuck = sameResumePoint(sent, result);
    return {
        rows: (group.rows || []).concat(rows),
        loaded: true,
        loading: false,
        resume: stuck ? null : resumePoint(result),
        stop: result.stopReason,
        stuck,
        gone: result.stopReason === 'FILES_ROTATED'
    };
}

/**
 * The last row of an open result group: { text, title, click }, where `click` is true when a click
 * (or Enter or Space) loads more from the resume point or tries a failed load again; null when the
 * group needs no last row. `group` is the group's state (see useSearch), `entry` its count entry,
 * `partial` whether that entry's count is a minimum (isPartial).
 */
export function groupEndRow(group, entry, partial) {
    const shown = (group.rows || []).length;
    const name = entry.fileName;
    if (group.loading) {
        return {
            text: shown === 0 ? 'Loading matches...' : loadMoreText(shown, entry.matchingLines, partial, 'loading'),
            title: '', click: false
        };
    }
    if (group.gone) return { text: staleGroupText(name), title: '', click: false };
    if (group.failed) {
        return {
            text: shown === 0 ? 'The matches could not be loaded. Click here to try again.'
                : loadMoreText(shown, entry.matchingLines, partial, 'failed'),
            title: 'Tries again to load the matches.', click: true
        };
    }
    if (group.stuck) return { text: STUCK_TEXT, title: '', click: false };
    if (group.stop === 'PATTERN_TOO_COMPLEX') return { text: GROUP_TOO_COMPLEX_TEXT, title: '', click: false };
    if (!group.resume) return null;
    if (shown === 0) {
        return {
            text: KEEP_SEARCHING_TEXT,
            title: 'Continues the search in ' + name + ' from where the time limit stopped it.', click: true
        };
    }
    return {
        text: loadMoreText(shown, entry.matchingLines, partial, 'idle'),
        title: 'Loads the next matching lines of ' + name + ' and adds them to this list.', click: true
    };
}

/**
 * The part of a result line to show: the whole line when the match is near
 * its start, otherwise an ellipsis and the text from `lead` characters before the
 * match, so the match is always visible in a narrow column. The cut never
 * leaves half of a surrogate pair at the start. Returns { text, start, end }
 * with the match range inside `text`.
 */
export function excerpt(lineText, matchStart, matchEnd, lead) {
    const text = lineText || '';
    const keep = lead == null ? 40 : lead;
    const start = Math.max(0, Math.min(matchStart, text.length));
    const end = Math.max(start, Math.min(matchEnd, text.length));
    if (start <= keep) return { text, start, end };
    let from = start - keep;
    const unit = text.charCodeAt(from);
    if (from < start && unit >= 0xDC00 && unit <= 0xDFFF) from++;
    const ellipsis = '...';
    return {
        text: ellipsis + text.substring(from),
        start: ellipsis.length + (start - from),
        end: ellipsis.length + (end - from)
    };
}
