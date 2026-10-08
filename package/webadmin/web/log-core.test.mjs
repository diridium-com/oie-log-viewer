// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

// Mirrors the Swing LogViewerFormatTest and LogViewerNoticeTest, then covers
// the wire parsing, the request gate, navigation state and match location that
// only the web interface has.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    SEPARATOR, count, bytes, positionLabel, midLineNotes, fileNameFromId,
    num, str, bool, listOf, parseFile, parseFileList, parsePage, parseSearchResult,
    parseErrorBody, noticeFrom, createGate, navState, SNAPSHOT_END_TIP, activeFirst, fileById, fileByName,
    locateMatch, revealTarget, sameLines, markDecision, parseHighlights, clockInZone, modifiedText, snapshotText, zoneText, highlightParams,
    isStale, fileIsGone, goneNotice, goneOpenLabel, goneTooltip, highlightStopNote, PLAIN_TEXT_NOTICE, notViewableText,
    saveName
} from './log-core.js';
import { staleGroupText } from './log-search-core.js';
import { normal, special } from './log-display.js';

// ---- LogViewerFormatTest ---------------------------------------------------------

test('count groups thousands', () => {
    assert.equal(count(0), '0');
    assert.equal(count(999), '999');
    assert.equal(count(1000), '1,000');
    assert.equal(count(1048576), '1,048,576');
});

const DOT = ' · ';

test('position label shows the lines of the page and the lines of the file', () => {
    assert.equal(SEPARATOR, DOT);
    assert.equal(positionLabel(2001, 1000, 11905, 102400, 358912, 1048576, false), 'Lines 2,001-3,000 of 11,905');
});

test('position label names the start, the end and the whole of a file', () => {
    assert.equal(positionLabel(1, 1000, 11905, 0, 4096, 10000, false), 'Lines 1-1,000 of 11,905' + DOT + 'start of file');
    assert.equal(positionLabel(10906, 1000, 11905, 900, 1000, 1000, true), 'Lines 10,906-11,905 of 11,905' + DOT + 'end of file');
    assert.equal(positionLabel(1, 500, 500, 0, 4096, 4096, true), 'Lines 1-500 of 500' + DOT + 'whole file');
});

test('position label drops the file total when the server did not count it', () => {
    assert.equal(positionLabel(2001, 1000, null, 5, 9, null, false), 'Lines 2,001-3,000');
    assert.equal(positionLabel(1, 10, null, 0, 500, null, false), 'Lines 1-10' + DOT + 'start of file');
});

test('position label without line numbers falls back to bytes, with the same endings', () => {
    assert.equal(positionLabel(null, 40, null, 0, 4096, 10000, false), 'Bytes 0-4,096 of 10,000' + DOT + 'start of file');
    assert.equal(positionLabel(null, 40, null, 6000, 10000, 10000, true), 'Bytes 6,000-10,000 of 10,000' + DOT + 'end of file');
    assert.equal(positionLabel(null, 40, null, 10, 20, null, false), 'Bytes 10-20 (total size not known yet)');
});

test('an empty page has no line range', () => {
    assert.equal(positionLabel(1, 0, 0, 0, 0, 0, true), 'Bytes 0-0 of 0' + DOT + 'whole file');
});

test('mid-line notes name the end that is split', () => {
    assert.equal(midLineNotes(false, false), '');
    assert.ok(midLineNotes(true, false).includes('first line continues from the previous'));
    assert.ok(midLineNotes(false, true).includes('last line continues on the next'));
    const both = midLineNotes(true, true);
    assert.ok(both.includes('first line') && both.includes('last line'));
});

test('byte sizes use binary units', () => {
    assert.equal(bytes(0), '0 B');
    assert.equal(bytes(1023), '1023 B');
    assert.equal(bytes(1024), '1.0 KB');
    assert.equal(bytes(1572864), '1.5 MB');
    assert.equal(bytes(2 * 1024 * 1024 * 1024), '2.0 GB');
});

test('the file name is taken from a server id', () => {
    assert.equal(fileNameFromId('fout/mirth.log.3.zip@a03c19e4d2f8'), 'mirth.log.3.zip');
    assert.equal(fileNameFromId('odd-id'), 'odd-id');
    assert.equal(fileNameFromId(null), '');
});

// ---- LogViewerNoticeTest ---------------------------------------------------------

const errorXml = (kind, message) => '<com.diridium.logviewer.LogViewerError><kind>' + kind
    + '</kind><message>' + message + '</message></com.diridium.logviewer.LogViewerError>';

/** What the host's api client throws for a non-2xx response. */
function apiError(status, body) {
    const e = new Error(body);
    e.status = status;
    e.body = body;
    return e;
}

test('stale offers a refresh', () => {
    const notice = noticeFrom(apiError(409, errorXml('STALE', 'whatever')), false);
    assert.equal(notice.message, 'This file has rotated since it was listed.');
    assert.equal(notice.refresh, true);
});

test('a download whose body stopped short says so in plain words', () => {
    const cut = new Error('Failed to fetch');
    cut.cutShort = true;
    assert.equal(noticeFrom(cut, true, true).message,
        'The download stopped before the whole file arrived, so nothing was saved. Try again.');
    assert.equal(noticeFrom(new Error('Failed to fetch'), true, true).message, 'Failed to fetch');
});

test('busy shows the engine text, which states the limit', () => {
    const notice = noticeFrom(apiError(429, errorXml('BUSY', 'Two log searches are already running.')), false, true);
    assert.equal(notice.message, 'Two log searches are already running.');
    assert.equal(notice.refresh, false);
    assert.equal(noticeFrom(apiError(429, errorXml('BUSY', '')), false, true).message,
        'The engine is busy. Try again in a moment.');
});

test('too large and not viewable suggest downloading only to a user who may download', () => {
    const large = noticeFrom(apiError(422, errorXml('TOO_LARGE', 'This page would need more than 64 MB')), false, true);
    assert.equal(large.message, 'This page would need more than 64 MB. You can download the file instead.');
    const notViewable = noticeFrom(apiError(400, errorXml('NOT_VIEWABLE', 'Unsupported compression.')), false, true);
    assert.equal(notViewable.message, 'Unsupported compression. You can download the file instead.');
    assert.equal(large.refresh, false);
    assert.equal(noticeFrom(apiError(422, errorXml('TOO_LARGE', 'Too far')), false, false).message, 'Too far.');
    assert.equal(noticeFrom(apiError(400, errorXml('NOT_VIEWABLE', 'Unsupported compression.')), false, false).message,
        'Unsupported compression.');
});

test('a download is saved under the file name, with an archive folder joined by an underscore', () => {
    assert.equal(saveName('mirth.log.3.zip'), 'mirth.log.3.zip');
    assert.equal(saveName('2026-10-06-1630/mirth.log.5.zip'), '2026-10-06-1630_mirth.log.5.zip');
    assert.equal(saveName('2026/10/app-1.log.gz'), '2026_10_app-1.log.gz');
});

test('opening a file that cannot be viewed says why, and offers the download only when allowed', () => {
    const file = { name: 'mirth.log.3.gz', note: 'Compressed in a format the viewer cannot read.' };
    assert.equal(notViewableText(file, true), 'mirth.log.3.gz cannot be viewed here. Compressed in a format the viewer '
        + 'cannot read. You can download the file instead.');
    assert.equal(notViewableText(file, false),
        'mirth.log.3.gz cannot be viewed here. Compressed in a format the viewer cannot read.');
    assert.equal(notViewableText({ name: 'x.log', note: null }, false), 'x.log cannot be viewed here.');
});

test('bad request and read failed show the server message', () => {
    assert.equal(noticeFrom(apiError(400, errorXml('BAD_REQUEST', 'Invalid regular expression: x(')), false).message,
        'Invalid regular expression: x(');
    assert.equal(noticeFrom(apiError(500, errorXml('READ_FAILED', 'Could not read the file.')), false).message,
        'Could not read the file.');
});

test('a message with XML entities is decoded', () => {
    const notice = noticeFrom(apiError(400, errorXml('BAD_REQUEST', 'Invalid: a&lt;b &amp; c&gt;d &#x41;&#66;')), false);
    assert.equal(notice.message, 'Invalid: a<b & c>d AB');
});

test('a forbidden response covers both a missing permission and a channel restriction', () => {
    const message = noticeFrom(apiError(403, ''), false).message;
    assert.equal(message, 'You do not have access to log files. Your role needs the View Log Files permission '
        + 'and must not be limited to specific channels.');
    // The same wording when the server says channel restricted.
    assert.equal(noticeFrom(apiError(403, errorXml('CHANNEL_RESTRICTED', 'x')), false).message, message);
});

test('a forbidden download names the download permission', () => {
    const message = noticeFrom(apiError(403, ''), true).message;
    assert.ok(message.includes('Download Log Files permission'), message);
    assert.ok(message.includes('must not be limited to specific channels'), message);
});

test('anything else shows its own message', () => {
    const notice = noticeFrom(new Error('Connection refused'), false);
    assert.equal(notice.message, 'Connection refused');
    assert.equal(notice.refresh, false);
});

test('an error with no message still says something', () => {
    assert.equal(noticeFrom(new Error(''), false).message, 'The request failed.');
    assert.equal(noticeFrom(null, false).message, 'The request failed.');
});

test('a missing plugin is reported as not installed', () => {
    assert.ok(noticeFrom(apiError(404, ''), false).message.includes('not installed'));
    assert.ok(noticeFrom(apiError(501, ''), false).message.includes('not installed'));
});

test('an expired session says so, and a download says to download again after signing in', () => {
    assert.equal(noticeFrom(apiError(401, ''), false).message, 'Your session has expired. Sign in again.');
    assert.equal(noticeFrom(apiError(401, '<html>Unauthorized</html>'), true).message,
        'Your session has ended. Sign in again, then download the file.');
});

test('a gateway that could not reach the engine says so, for any request', () => {
    for (const status of [502, 503, 504]) {
        assert.equal(noticeFrom(apiError(status, '<html>Bad Gateway</html>'), false).message, 'The engine could not be reached.');
        assert.equal(noticeFrom(apiError(status, ''), true).message, 'The engine could not be reached.');
    }
});

test('a reply that is not the engine\'s typed error is described by its status, never by its body', () => {
    const page = apiError(500, '<html><body>java.lang.NullPointerException at ...</body></html>');
    assert.equal(noticeFrom(page, false).message, 'The engine returned an error (HTTP 500).');
    assert.equal(noticeFrom(apiError(400, 'Bad Request'), false).message, 'The engine returned an error (HTTP 400).');
    assert.equal(noticeFrom(page, true).message, 'The download failed (HTTP 500).');
    assert.equal(noticeFrom(apiError(418, ''), true).message, 'The download failed (HTTP 418).');
    // The statuses with their own words keep them, downloads included.
    assert.ok(noticeFrom(apiError(403, ''), true).message.includes('Download Log Files permission'));
    assert.ok(noticeFrom(apiError(404, 'Not Found'), true).message.includes('not installed'));
    assert.equal(noticeFrom(apiError(409, 'Conflict'), true).refresh, true);
    // A typed error keeps the engine's words whatever its status.
    assert.equal(noticeFrom(apiError(500, errorXml('READ_FAILED', 'Could not read the file.')), true).message,
        'Could not read the file.');
    // No status, or status 0: the request got no reply, and its own message says why.
    const network = new Error('Failed to fetch');
    assert.equal(noticeFrom(network, true).message, 'Failed to fetch');
    const zero = new Error('Network down');
    zero.status = 0;
    assert.equal(noticeFrom(zero, false).message, 'Network down');
});

test('without a typed body the status still picks the wording', () => {
    assert.equal(noticeFrom(apiError(409, 'Conflict'), false).refresh, true);
    assert.equal(noticeFrom(apiError(429, 'Too Many Requests'), false).message, 'The engine is busy. Try again in a moment.');
    // A bare 503 is a gateway's, not this plugin's: it must not claim the engine is busy.
    assert.ok(!noticeFrom(apiError(503, 'Unavailable'), false).message.includes('busy'));
    assert.ok(noticeFrom(apiError(422, 'x'), false, true).message.includes('download the file instead'));
    assert.ok(!noticeFrom(apiError(422, 'x'), false, false).message.includes('download'));
});

test('the error body parser ignores bodies that are not ours', () => {
    assert.equal(parseErrorBody('<html>Bad gateway</html>'), null);
    assert.equal(parseErrorBody(undefined), null);
    assert.deepEqual(parseErrorBody(errorXml('STALE', 'm')), { kind: 'STALE', message: 'm' });
});

// ---- wire parsing ---------------------------------------------------------------

test('scalars unwrap and booleans compare as text', () => {
    assert.equal(num({ long: 5 }), 5);
    assert.equal(num('7'), 7);
    assert.equal(num(null), null);
    assert.equal(num(''), null);
    assert.equal(num({}), null);
    assert.equal(str(null), '');
    assert.equal(str({ string: 'x' }), 'x');
    assert.equal(bool('true'), true);
    assert.equal(bool('false'), false);
    assert.equal(bool(false), false);
    assert.equal(bool(true), true);
    assert.equal(bool(undefined), false);
});

test('XStream lists unwrap in every shape they arrive in', () => {
    const fqcn = 'com.diridium.logviewer.LogFileInfo';
    assert.deepEqual(listOf('', 'LogFileInfo'), []);
    assert.deepEqual(listOf(null, 'LogFileInfo'), []);
    assert.deepEqual(listOf({}, 'LogFileInfo'), []);
    assert.deepEqual(listOf({ [fqcn]: [{ id: 'a' }, { id: 'b' }] }, 'LogFileInfo'), [{ id: 'a' }, { id: 'b' }]);
    assert.deepEqual(listOf({ [fqcn]: { id: 'a' } }, 'LogFileInfo'), [{ id: 'a' }]);
    assert.deepEqual(listOf({ id: 'a', name: 'n' }, 'LogFileInfo'), [{ id: 'a', name: 'n' }]);
    assert.deepEqual(listOf([{ id: 'a' }], 'LogFileInfo'), [{ id: 'a' }]);
    assert.deepEqual(listOf({ string: ['x', 'y'] }, 'string'), ['x', 'y']);
    assert.deepEqual(listOf({ string: 'x' }, 'string'), ['x']);
});

test('a file list parses with the wrapper the 4.6.0 engine sends', () => {
    const wire = {
        files: {
            'com.diridium.logviewer.LogFileInfo': [
                { id: 'fout/mirth.log@aa', name: 'mirth.log', size: 1234, lastModified: 1700000000000,
                    active: true, compressed: false, viewable: true, charset: 'UTF-8' },
                { id: 'fout/mirth.log.1.zip@bb', name: 'mirth.log.1.zip', size: '99', lastModified: 1690000000000,
                    active: 'false', compressed: 'true', viewable: 'false', note: 'Unsupported.' }
            ]
        },
        warnings: { string: ['one file was skipped'] }
    };
    const list = parseFileList({ 'com.diridium.logviewer.LogFileList': wire });
    assert.equal(list.files.length, 2);
    assert.equal(list.files[0].active, true);
    assert.equal(list.files[0].note, null);
    assert.equal(list.files[1].viewable, false);
    assert.equal(list.files[1].compressed, true);
    assert.equal(list.files[1].size, 99);
    assert.equal(list.files[1].note, 'Unsupported.');
    assert.deepEqual(list.warnings, ['one file was skipped']);
    assert.deepEqual(parseFileList(wire).files.map((f) => f.name), ['mirth.log', 'mirth.log.1.zip']);
});

test('an empty or missing file list parses to nothing', () => {
    assert.deepEqual(parseFileList({ files: '', warnings: '' }), { files: [], warnings: [] });
    assert.deepEqual(parseFileList(null), { files: [], warnings: [] });
});

test('a file with no viewable field stays selectable', () => {
    assert.equal(parseFile({ id: 'x', name: 'n' }).viewable, true);
});

test('a page keeps its raw terminators and reads null as null', () => {
    const page = parsePage({
        fileId: 'f', startOffset: 0, endOffset: 30, text: 'a\r\nb\rc\n',
        startsMidLine: 'false', endsMidLine: false, atEnd: 'true'
    });
    assert.equal(page.text, 'a\r\nb\rc\n');
    assert.equal(page.contentLength, null);
    assert.equal(page.firstLineNumber, null);
    assert.equal(page.atEnd, true);
    assert.equal(page.startsMidLine, false);
    const numbered = parsePage({ startOffset: 5, endOffset: 9, contentLength: 100, firstLineNumber: 42, text: '' });
    assert.equal(numbered.firstLineNumber, 42);
    assert.equal(numbered.contentLength, 100);
    assert.equal(numbered.text, '');
    assert.equal(numbered.totalLines, null);
    assert.equal(parsePage({ startOffset: 0, endOffset: 9, firstLineNumber: 1, totalLines: 11552, text: '' }).totalLines, 11552);
});

test('a count result parses its per-file counts, a single file arrives as a bare object', () => {
    const many = parseSearchResult({
        matches: null,
        fileCounts: {
            'com.diridium.logviewer.LogFileMatchCount': [
                { fileId: 'fout/mirth.log@7422611ebb30', matchingLines: 1641, complete: true },
                { fileId: 'fout/mirth.log.5.zip@bc69cb38c693', matchingLines: '709', complete: 'false' }
            ]
        },
        warnings: null, complete: true, filesInScope: 6, filesSearched: 6
    });
    assert.deepEqual(many.fileCounts, [
        { fileId: 'fout/mirth.log@7422611ebb30', matchingLines: 1641, complete: true },
        { fileId: 'fout/mirth.log.5.zip@bc69cb38c693', matchingLines: 709, complete: false }
    ]);
    assert.deepEqual(many.matches, []);
    const one = parseSearchResult({
        matches: null,
        fileCounts: { 'com.diridium.logviewer.LogFileMatchCount': { fileId: 'f', matchingLines: 3, complete: true } },
        filesInScope: 1
    });
    assert.deepEqual(one.fileCounts, [{ fileId: 'f', matchingLines: 3, complete: true }]);
    assert.deepEqual(parseSearchResult({ matches: null, filesInScope: 1 }).fileCounts, []);
});

test('a search result parses its matches, warnings and resume point', () => {
    const result = parseSearchResult({
        matches: {
            'com.diridium.logviewer.LogSearchMatch': {
                fileId: 'f', lineNumber: 7, lineOffset: 100, matchOffset: 110, lineText: 'x ERROR y',
                matchStart: 2, matchEnd: 7, truncated: 'false'
            }
        },
        warnings: '', complete: 'false', stopReason: 'DEADLINE', resumeFileId: 'g', resumeOffset: 55,
        filesInScope: 4, filesSearched: 1, bytesSearched: 999, splitLineCount: 0
    });
    assert.equal(result.matches.length, 1);
    assert.equal(result.matches[0].lineNumber, 7);
    assert.equal(result.matches[0].truncated, false);
    assert.equal(result.stopReason, 'DEADLINE');
    assert.equal(result.resumeFileId, 'g');
    assert.equal(result.resumeOffset, 55);
    assert.deepEqual(result.warnings, []);
    const done = parseSearchResult({ matches: '', warnings: '', complete: true, filesInScope: 1, filesSearched: 1 });
    assert.equal(done.stopReason, null);
    assert.equal(done.resumeFileId, null);
    assert.equal(done.resumeOffset, null);
    assert.deepEqual(done.matches, []);
});

// ---- request gate -----------------------------------------------------------------

test('only the newest request is current', () => {
    const gate = createGate();
    const first = gate.next();
    assert.equal(gate.current(first), true);
    const second = gate.next();
    assert.equal(gate.current(first), false);
    assert.equal(gate.current(second), true);
    gate.cancel();
    assert.equal(gate.current(second), false);
});

// ---- navigation, list and search state ---------------------------------------------

const page = (over) => Object.assign({ startOffset: 100, endOffset: 200, atEnd: false }, over);

test('navigation is disabled without a page or while busy', () => {
    const none = { first: false, previous: false, next: false, last: false, snapshotEnd: false };
    assert.deepEqual(navState(null, { active: false }, false), none);
    assert.deepEqual(navState(page(), { active: false }, true), none);
    assert.deepEqual(navState(page(), null, false), none);
});

test('the start of a file has nothing before it', () => {
    const nav = navState(page({ startOffset: 0 }), { active: false }, false);
    assert.equal(nav.first, false);
    assert.equal(nav.previous, false);
    assert.equal(nav.next, true);
});

test('the end of a finished file has nothing after it', () => {
    const done = navState(page({ atEnd: true }), { active: false }, false);
    assert.equal(done.next, false);
    assert.equal(done.last, false);
    assert.equal(done.previous, true);
    assert.equal(done.snapshotEnd, false);
});

test('the end of the active file is the end of the snapshot: newer lines come only through Load latest lines', () => {
    const end = navState(page({ atEnd: true }), { active: true }, false);
    assert.equal(end.next, false);
    assert.equal(end.last, false);
    assert.equal(end.previous, true);
    assert.equal(end.snapshotEnd, true);
    // Still the reason while a request runs; not for a page short of the end, nor once the file is gone.
    assert.equal(navState(page({ atEnd: true }), { active: true }, true).snapshotEnd, true);
    const before = navState(page({ atEnd: false }), { active: true }, false);
    assert.equal(before.next, true);
    assert.equal(before.last, true);
    assert.equal(before.snapshotEnd, false);
    assert.equal(navState(page({ atEnd: true }), { active: true }, false, true).snapshotEnd, false);
    assert.equal(SNAPSHOT_END_TIP, 'This is the end of the snapshot. Load latest lines shows anything written since.');
});

test('a re-read for highlights keeps the page as it was read, with the new highlights', () => {
    const page = {
        fileId: 'f', text: 'one ERROR\ntwo\nthree ERR', startOffset: 500, endOffset: 524, atEnd: true,
        readAt: 1000, totalLines: 3, highlights: [], highlightsStopped: '', targetIndex: null
    };
    // The active file grew: the re-read from the same start reads on to its new end.
    const reread = {
        fileId: 'f', text: 'one ERROR\ntwo\nthree ERROR\nfour ERROR\n', startOffset: 500, endOffset: 547, atEnd: true,
        readAt: 2000, totalLines: 4, highlights: [4, 9, 20, 25, 31, 36], highlightsStopped: '', targetIndex: null
    };
    const kept = sameLines(page, reread);
    // The lines written since are left out, with their highlights and the ones cut by the old end.
    assert.equal(kept.text, page.text);
    assert.deepEqual(kept.highlights, [4, 9]);
    assert.equal(kept.readAt, 1000);
    assert.equal(kept.endOffset, 524);
    assert.equal(kept.totalLines, 3);
    assert.equal(kept.highlightsStopped, '');
    // A highlight that ends exactly at the page's end is on the page.
    assert.deepEqual(sameLines({ ...page, text: 'one ERROR' }, { ...reread, highlights: [4, 9] }).highlights, [4, 9]);
    // The same page re-read (an archive, a page short of the end): the same text, the new highlights.
    const same = sameLines(page, { ...page, highlights: [4, 9, 20, 23], highlightsStopped: 'MATCH_LIMIT' });
    assert.deepEqual(same.highlights, [4, 9, 20, 23]);
    assert.equal(same.highlightsStopped, 'MATCH_LIMIT');
    // A file rewritten in place, or another start: the re-read is shown as it is.
    const other = { ...reread, text: 'something else' };
    assert.equal(sameLines(page, other), other);
    const moved = { ...reread, startOffset: 0 };
    assert.equal(sameLines(page, moved), moved);
});

test('a finished search marks the page on screen, but never drops a page request that is running', () => {
    const key = '{"highlightQuery":"ERROR"}';
    const unmarked = { hlKey: '' };
    assert.equal(markDecision(unmarked, key, false, false), 'read');
    // A page request is running: the page it brings is checked when it answers, even when the
    // page on screen already carries the search (the same search run again after closing the results).
    assert.equal(markDecision(unmarked, key, false, true), 'wait');
    assert.equal(markDecision({ hlKey: key }, key, false, true), 'wait');
    assert.equal(markDecision(null, key, false, true), 'wait');
    // Already marked with this search (it went out after the search started): nothing to do.
    assert.equal(markDecision({ hlKey: key }, key, false, false), 'none');
    // Marked with an older search: read again.
    assert.equal(markDecision({ hlKey: '{"highlightQuery":"WARN"}' }, key, false, false), 'read');
    // No page, a gone file, or no open search.
    assert.equal(markDecision(null, key, false, false), 'none');
    assert.equal(markDecision(unmarked, key, true, false), 'none');
    assert.equal(markDecision(unmarked, '', false, false), 'none');
});

test('the active file goes first and the server order is otherwise kept', () => {
    const files = [{ name: 'a', active: false }, { name: 'b', active: false }, { name: 'c', active: true }];
    assert.deepEqual(activeFirst(files).map((f) => f.name), ['c', 'a', 'b']);
    assert.equal(fileById([{ id: '1', name: 'x' }], '1').name, 'x');
    assert.equal(fileById([], '1'), null);
    assert.equal(fileByName([{ id: '1', name: 'x' }], 'x').id, '1');
});

// ---- locating a match on a page ------------------------------------------------------

const match = (over) => Object.assign(
    { lineNumber: 11, lineText: 'ERROR boom here', matchStart: 6, matchEnd: 10 }, over);

test('a match is found by its line number when the page is numbered', () => {
    const display = 'one\ntwo\nERROR boom here\nfour';
    // The page starts at line 9, so line 11 is index 2.
    const found = locateMatch(display, 9, match());
    assert.deepEqual(found, { start: 8 + 6, end: 8 + 10, whole: false });
    assert.equal(display.substring(found.start, found.end), 'boom');
});

test('the same match is found in special mode, where lines carry symbols', () => {
    const raw = 'one\r\ntwo\r\nERROR boom here\r\nfour';
    const found = locateMatch(special(raw), 9, match());
    assert.equal(special(raw).substring(found.start, found.end), 'boom');
    const normalFound = locateMatch(normal(raw), 9, match());
    assert.equal(normal(raw).substring(normalFound.start, normalFound.end), 'boom');
});

test('without line numbers the text is looked for', () => {
    const display = 'one\ntwo\nERROR boom here\nfour';
    const found = locateMatch(display, null, match());
    assert.equal(display.substring(found.start, found.end), 'boom');
});

test('a line that is there but does not hold the match is shown whole', () => {
    const display = 'one\ntwo\nsomething else\nfour';
    const found = locateMatch(display, 9, match());
    assert.deepEqual(found, { start: 8, end: 8 + 'something else'.length, whole: true });
});

test('a line number off the page falls back to the text', () => {
    const display = 'x\nERROR boom here\n';
    const found = locateMatch(display, 1, match({ lineNumber: 500 }));
    assert.equal(display.substring(found.start, found.end), 'boom');
});

test('a match that is not on the page is null', () => {
    assert.equal(locateMatch('nothing here', null, match()), null);
    // An empty match cannot be located inside a numbered line, so the line is shown whole.
    assert.deepEqual(locateMatch('nothing here', 1, match({ lineNumber: 1, matchStart: 0, matchEnd: 0 })),
        { start: 0, end: 'nothing here'.length, whole: true });
    // And without a line count there is nothing to go by.
    assert.equal(locateMatch('nothing here', null, match({ matchStart: 0, matchEnd: 0 })), null);
});

test('a page read at a match lands on the engine\'s own position of it, in both modes', () => {
    // The same text twice on the page: looking for it would find the first; the engine says the second.
    const raw = 'ERROR boom\r\nok\r\nERROR boom\r\n';
    const m = match({ lineNumber: 3, lineText: 'ERROR boom', matchStart: 6, matchEnd: 10 });
    const at = raw.lastIndexOf('boom');
    for (const display of [normal(raw), special(raw)]) {
        const found = revealTarget(raw, display, null, at, m);
        assert.equal(found.whole, false);
        assert.equal(display.substring(found.start, found.end), 'boom');
        assert.equal(display.lastIndexOf('boom'), found.start);
    }
    // Without a target index the line's text is looked for, as before.
    assert.deepEqual(revealTarget(raw, normal(raw), 1, null, m), locateMatch(normal(raw), 1, m));
    // An index off the page falls back too.
    assert.deepEqual(revealTarget(raw, normal(raw), 1, raw.length + 5, m), locateMatch(normal(raw), 1, m));
});

test('a match of no characters marks its whole line', () => {
    const raw = 'one\ntwo\n';
    const m = match({ lineNumber: 2, lineText: 'two', matchStart: 0, matchEnd: 0 });
    assert.deepEqual(revealTarget(raw, normal(raw), 1, 4, m), { start: 4, end: 7, whole: true });
    assert.deepEqual(revealTarget('\nx', '\nx', 1, 0, m), { start: 0, end: 0, whole: true });
});

test('a page carries the target index of an AT read, null otherwise', () => {
    assert.equal(parsePage({ startOffset: 0, endOffset: 9, text: 'abc', targetIndex: 2 }).targetIndex, 2);
    assert.equal(parsePage({ startOffset: 0, endOffset: 9, text: 'abc' }).targetIndex, null);
    assert.equal(parsePage({ startOffset: 0, endOffset: 9, text: 'abc', targetIndex: null }).targetIndex, null);
});

test('a shortened line is looked for as a window around the match', () => {
    // The server cut the line to a window; the window is a piece of the real line.
    const line = 'start of a very long line ' + 'x'.repeat(50) + ' needle ' + 'y'.repeat(50);
    const window = 'xxxxxxxxxx needle yyyyyyyyyy';
    const display = 'first\n' + line + '\nlast';
    const found = locateMatch(display, 1, {
        lineNumber: 2, lineText: window, matchStart: 11, matchEnd: 17
    });
    assert.equal(display.substring(found.start, found.end), 'needle');
});

// ---- engine-computed highlights, time zone, grouped results -------------------------

test('a file carries its time zone id and label', () => {
    const f = parseFile({ id: 'x', name: 'mirth.log', timeZoneId: 'America/Denver', timeZoneLabel: 'MDT (UTC-06:00)' });
    assert.equal(f.timeZoneId, 'America/Denver');
    assert.equal(f.timeZoneLabel, 'MDT (UTC-06:00)');
    assert.equal(parseFile({ id: 'x', name: 'n' }).timeZoneLabel, '');
});

test('page highlights parse from the wire forms, absent and null mean none', () => {
    assert.deepEqual(parseHighlights({ int: [0, 4, 167, 171] }), [0, 4, 167, 171]);
    assert.deepEqual(parseHighlights({ int: 5 }), []);
    assert.deepEqual(parseHighlights({ int: [1, 2, 3] }), [1, 2]);
    assert.deepEqual(parseHighlights(null), []);
    assert.deepEqual(parseHighlights(undefined), []);
    assert.deepEqual(parseHighlights(''), []);
    const page = parsePage({ startOffset: 0, endOffset: 9, text: 'abc', readAt: 1791149816422,
        highlights: { int: [0, 1] }, highlightsStopped: 'TIME_LIMIT' });
    assert.equal(page.readAt, 1791149816422);
    assert.deepEqual(page.highlights, [0, 1]);
    assert.equal(page.highlightsStopped, 'TIME_LIMIT');
    const bare = parsePage({ startOffset: 0, endOffset: 9, text: 'abc' });
    assert.equal(bare.readAt, null);
    assert.deepEqual(bare.highlights, []);
    assert.equal(bare.highlightsStopped, '');
});

test('the highlight note says which limit stopped the highlighting', () => {
    assert.equal(highlightStopNote('MATCH_LIMIT'), 'Only the first 5,000 matches on this page are highlighted.');
    assert.equal(highlightStopNote('TIME_LIMIT'),
        'Highlighting reached its 2-second limit, so only part of this page is highlighted.');
    assert.equal(highlightStopNote('TOO_COMPLEX'),
        'The search pattern is too complex for a line on this page, so only part of it is highlighted.');
    assert.equal(highlightStopNote(''), '');
});

test('the plain-text fallback says what is missing, once, in the notice bar', () => {
    assert.equal(PLAIN_TEXT_NOTICE, 'The full editor did not load, so this page is shown as plain text without '
        + 'highlights or line numbers when wrapped. Reloading the page may help.');
});

test('the snapshot time is shown in the file zone', () => {
    // 2026-10-04 15:30:05 UTC is 09:30:05 in Denver (MDT, UTC-06:00).
    const at = Date.UTC(2026, 9, 4, 15, 30, 5);
    assert.deepEqual(clockInZone(at, 'America/Denver', 'MDT (UTC-06:00)'), { text: '09:30:05', exact: true });
    assert.equal(clockInZone(at, 'Asia/Kolkata', 'IST (UTC+05:30)').text, '21:00:05');
});

test('an unknown zone id falls back to the offset in the label, then to UTC, and neither claims to be exact', () => {
    const at = Date.UTC(2026, 9, 4, 15, 30, 5);
    assert.deepEqual(clockInZone(at, 'Not/AZone', 'MDT (UTC-06:00)'), { text: '09:30:05 UTC-06:00', exact: false });
    assert.deepEqual(clockInZone(at, '', 'IST (UTC+05:30)'), { text: '21:00:05 UTC+05:30', exact: false });
    assert.deepEqual(clockInZone(at, 'Not/AZone', ''), { text: '15:30:05 UTC', exact: false });
    assert.deepEqual(clockInZone(Number.NaN, 'America/Denver', ''), { text: '', exact: false });
    const file = { name: 'mirth.log', timeZoneId: 'Not/AZone', timeZoneLabel: 'MDT (UTC-06:00)' };
    assert.equal(snapshotText(file, at),
        'Snapshot of mirth.log taken at 09:30:05 UTC-06:00 (engine time not known). It does not update by itself.');
});

test('the file list gives the modification time in the engine zone, with its label', () => {
    // 2026-01-05 06:07:08 UTC is 23:07:08 the day before in Denver (MST, UTC-07:00).
    const file = { lastModified: Date.UTC(2026, 0, 5, 6, 7, 8), timeZoneId: 'America/Denver', timeZoneLabel: 'MDT (UTC-06:00)' };
    assert.equal(modifiedText(file), '2026-01-04 23:07:08 MDT (UTC-06:00) (engine time)');
    assert.equal(modifiedText({ ...file, timeZoneLabel: '' }), '2026-01-04 23:07:08 (engine time)');
    // A zone the browser does not know: the label's offset, named, and no claim of engine time.
    assert.equal(modifiedText({ ...file, timeZoneId: 'Not/AZone' }), '2026-01-05 00:07:08 UTC-06:00');
    assert.equal(modifiedText({ ...file, timeZoneId: '', timeZoneLabel: '' }), '2026-01-05 06:07:08 UTC');
    assert.equal(modifiedText({ ...file, lastModified: Number.NaN }), '');
});

test('snapshot and zone sentences', () => {
    const file = { name: 'mirth.log', timeZoneId: 'America/Denver', timeZoneLabel: 'MDT (UTC-06:00)' };
    const at = Date.UTC(2026, 9, 4, 15, 30, 5);
    assert.equal(snapshotText(file, at),
        'Snapshot of mirth.log taken at 09:30:05 (engine time). It does not update by itself.');
    assert.equal(snapshotText(file, null), '');
    assert.equal(zoneText(file), 'Times are engine time: MDT (UTC-06:00)');
    assert.equal(zoneText({ name: 'x', timeZoneLabel: '' }), '');
    assert.equal(zoneText(null), '');
});

test('highlight parameters are sent only for a search with text', () => {
    assert.deepEqual(highlightParams({ query: 'a.c', regex: true, caseSensitive: false }),
        { highlightQuery: 'a.c', highlightRegex: true, highlightCaseSensitive: false });
    assert.deepEqual(highlightParams(null), {});
    assert.deepEqual(highlightParams({ query: '', regex: false, caseSensitive: false }), {});
});

// ---- the file on screen is gone ----------------------------------------------------

test('only a typed 409 STALE counts as stale', () => {
    assert.equal(isStale(apiError(409, errorXml('STALE', 'x'))), true);
    assert.equal(isStale(apiError(429, errorXml('BUSY', 'x'))), false);
    assert.equal(isStale(apiError(409, 'Conflict')), false);
    assert.equal(isStale(new Error('boom')), false);
    assert.equal(isStale(null), false);
});

test('the file on screen is gone when its id is not in the list, whatever its name', () => {
    const list = [{ id: 'fout/mirth.log.3.zip@bbb', name: 'mirth.log.3.zip' }];
    assert.equal(fileIsGone('fout/mirth.log.3.zip@aaa', list), true);
    assert.equal(fileIsGone('fout/mirth.log.3.zip@bbb', list), false);
    assert.equal(fileIsGone('fout/mirth.log.3.zip@aaa', []), true);
    assert.equal(fileIsGone(null, list), false);
});

test('gone texts', () => {
    assert.equal(goneNotice('mirth.log.5.zip'),
        'mirth.log.5.zip no longer exists under that name: a log rollover renamed or removed it after you opened it.'
        + ' The page shown is kept so you can finish reading it, but it can\'t be paged or downloaded.');
    assert.equal(goneOpenLabel('mirth.log.5.zip'), 'Open the current mirth.log.5.zip');
    assert.equal(goneTooltip('mirth.log.5.zip'),
        'mirth.log.5.zip no longer exists under that name. Open a file from the list.');
    assert.equal(staleGroupText('mirth.log.3.zip'),
        'mirth.log.3.zip no longer exists under that name. Search again for current results.');
});

test('paging is off while the file is gone', () => {
    const none = { first: false, previous: false, next: false, last: false, snapshotEnd: false };
    assert.deepEqual(navState(page(), { active: false }, false, true), none);
    assert.equal(navState(page(), { active: false }, false, false).next, true);
});
