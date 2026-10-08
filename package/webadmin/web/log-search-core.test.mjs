// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

// The search's bookkeeping and wording: the count accumulator, the result
// groups and what the results panel says.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    emptySearch, addCountResult, addWarnings, countHeader, countGroups, groupTitle, canCountRest, isPartial,
    resultRows, resumePoint, SEARCH_TITLE_LEAD, searchTitleRest, pieces, excerpt,
    searchNotes, resultsSummary, nextBatch, loadMoreText, loadingFromText,
    QUERY_MAX, QUERY_CUT_NOTE, pasteIsCut, reopenScope, staleGroupText,
    stoppedAtLimit, sameResumePoint, groupAfterLoad, groupEndRow,
    KEEP_SEARCHING_TEXT, GROUP_TOO_COMPLEX_TEXT, STUCK_TEXT, pageMarksFor
} from './log-search-core.js';

// ---- count results ------------------------------------------------------------------

const fc = (fileId, matchingLines, complete = true) => ({ fileId, matchingLines, complete });
const countResult = (over) => Object.assign({
    matches: [], fileCounts: [], warnings: [], complete: true, stopReason: null,
    resumeFileId: null, resumeOffset: null, filesInScope: 6, filesSearched: 6, bytesSearched: 0
}, over);
const counted = (over, files = []) => addCountResult(emptySearch(), { query: 'q' }, countResult(over), files);

test('the count header gives the totals, singular and plural', () => {
    const acc = counted({ fileCounts: [fc('a/x@1', 1641), fc('a/y@2', 0), fc('a/z@3', 578)] });
    assert.equal(countHeader(acc), '2,219 matching lines in 2 files');
    assert.equal(countHeader(counted({ fileCounts: [fc('a/x@1', 1)], filesInScope: 1 })), '1 matching line in 1 file');
});

test('a count that finds nothing says so, with the files it looked in', () => {
    assert.equal(countHeader(counted({ fileCounts: [fc('a/x@1', 0), fc('a/y@2', 0)] })), 'No matching lines in 6 files');
    assert.equal(countHeader(counted({ fileCounts: [fc('a/x@1', 0)], filesInScope: 1 })), 'No matching lines in 1 file');
});

test('a count stopped by the time limit says how far it got', () => {
    const acc = counted({
        fileCounts: [fc('a/x@1', 100), fc('a/y@2', 0), fc('a/z@3', 145, false)],
        stopReason: 'DEADLINE', complete: false, resumeFileId: 'a/z@3', resumeOffset: 777, filesSearched: 2
    });
    // The count line holds only the totals; how far it got is in resultsSummary.
    assert.equal(countHeader(acc), '245 matching lines so far in 2 files.');
    assert.equal(canCountRest(acc), true);
});

test('a rotation and a pattern that is too complex have their own words', () => {
    assert.equal(countHeader(counted({ stopReason: 'FILES_ROTATED', complete: false })),
        'The log files rotated while counting. Refresh the list and search again.');
    assert.equal(countHeader(counted({
        stopReason: 'PATTERN_TOO_COMPLEX', complete: false, warnings: ['mirth.log: pattern too complex.']
    })), 'mirth.log: pattern too complex.');
    assert.ok(countHeader(counted({ stopReason: 'PATTERN_TOO_COMPLEX', complete: false })).includes('Simplify'));
});

test('warnings are not in the header; they are the notes of the results status line', () => {
    const acc = counted({ fileCounts: [fc('a/x@1', 3)], warnings: ['one', 'two'] });
    assert.equal(countHeader(acc), '3 matching lines in 1 file');
    assert.equal(searchNotes(acc), 'Warnings: one; two');
    assert.equal(searchNotes(counted({ fileCounts: [fc('a/x@1', 3)] })), '');
    // The engine's own words for a too complex pattern are the header, not repeated in the notes.
    const complex = counted({
        stopReason: 'PATTERN_TOO_COMPLEX', complete: false, warnings: ['mirth.log: pattern too complex.', 'other']
    });
    assert.equal(countHeader(complex), 'mirth.log: pattern too complex. other');
    assert.equal(searchNotes(complex), '');
});

test('the results summary adds up the size and time of every count request', () => {
    assert.equal(resultsSummary(emptySearch()), '');
    const params = { query: 'q' };
    let acc = addCountResult(emptySearch(), params, countResult({
        fileCounts: [fc('a/x@1', 100), fc('a/y@2', 0), fc('a/z@3', 145, false)],
        stopReason: 'DEADLINE', complete: false, resumeFileId: 'a/z@3', resumeOffset: 777, filesInScope: 6,
        bytesSearched: 4299161
    }), [], 15003);
    assert.equal(resultsSummary(acc), 'Counted 2 of 6 files before the time limit, 4.1 MB searched in 15.0 s');
    acc = addCountResult(acc, params, countResult({
        fileCounts: [fc('a/z@3', 5), fc('a/v@4', 1), fc('a/w@5', 0), fc('a/u@6', 2)], filesInScope: 6,
        bytesSearched: 8283750
    }), [], 900);
    assert.equal(resultsSummary(acc), 'Counted 6 of 6 files, 12.0 MB searched in 15.9 s');
    // No time given counts as none.
    assert.equal(resultsSummary(counted({ fileCounts: [fc('a/x@1', 1)], filesInScope: 1 })),
        'Counted 1 of 1 files, 0 B searched in 0.0 s');
});

test('the load-more row says how many are shown and how many the click adds', () => {
    assert.equal(nextBatch(1000, 2991, false), 1000);
    assert.equal(nextBatch(2000, 2991, false), 991);
    assert.equal(loadMoreText(1000, 2991, false, 'idle'), '1,000 of 2,991 shown. Click to show the next 1,000.');
    assert.equal(loadMoreText(2000, 2991, false, 'idle'), '2,000 of 2,991 shown. Click to show the next 991.');
    assert.equal(loadMoreText(1000, 2991, false, 'loading'), 'Loading the next 1,000...');
    assert.equal(loadMoreText(2000, 2991, false, 'loading'), 'Loading the next 991...');
    assert.equal(loadMoreText(1000, 2991, false, 'failed'), 'Could not load more. Click to try again.');
});

test('the load-more row cannot say how many are left for a partial count or a file that grew', () => {
    assert.equal(nextBatch(1000, 1500, true), null);
    assert.equal(loadMoreText(1000, 1500, true, 'idle'), 'Click to show more.');
    assert.equal(loadMoreText(1000, 1500, true, 'loading'), 'Loading more...');
    // The count said 1,000 and the file grew: the group already shows as many as the count said.
    assert.equal(nextBatch(1000, 1000, false), null);
    assert.equal(loadMoreText(1000, 1000, false, 'idle'), 'Click to show more.');
    assert.equal(loadMoreText(1200, 1000, false, 'loading'), 'Loading more...');
});

test('the results status line names the file a group is loading from', () => {
    assert.equal(loadingFromText(1000, 2991, false, 'mirth.log.3.zip'), 'Loading the next 1,000 from mirth.log.3.zip...');
    assert.equal(loadingFromText(0, 400, false, 'mirth.log'), 'Loading the next 400 from mirth.log...');
    assert.equal(loadingFromText(1000, 1500, true, 'mirth.log'), 'Loading more from mirth.log...');
});

test('no text mentions Continue search', () => {
    const acc = counted({
        fileCounts: [fc('a/x@1', 3, false)], stopReason: 'DEADLINE', resumeFileId: 'a/x@1', resumeOffset: 1
    });
    assert.ok(!countHeader(acc).includes('Continue'));
});

test('counting the rest adds to the file the time limit cut off and appends the others', () => {
    const files = [{ id: 'a/z@3', name: 'mirth.log.2.zip' }];
    const params = { query: 'q' };
    let acc = addCountResult(emptySearch(), params, countResult({
        fileCounts: [fc('a/x@1', 100), fc('a/z@3', 145, false)],
        stopReason: 'DEADLINE', complete: false, resumeFileId: 'a/z@3', resumeOffset: 777, filesSearched: 1
    }), files);
    assert.equal(isPartial(acc, acc.entries[1]), true);
    assert.equal(isPartial(acc, acc.entries[0]), false);
    assert.equal(groupTitle(acc.entries[1], true), 'mirth.log.2.zip (145+ matching lines)');
    acc = addCountResult(acc, params, countResult({
        fileCounts: [fc('a/z@3', 55), fc('gone/old.log.9.zip@ab', 7), fc('a/w@4', 0)], warnings: ['w']
    }), files);
    assert.deepEqual(acc.entries.map((e) => [e.fileName, e.matchingLines, e.complete]),
        [['x', 100, true], ['mirth.log.2.zip', 200, true], ['old.log.9.zip', 7, true], ['w', 0, true]]);
    assert.equal(canCountRest(acc), false);
    assert.equal(isPartial(acc, acc.entries[1]), false);
    assert.deepEqual(acc.warnings, ['w']);
    assert.equal(countHeader(acc), '307 matching lines in 3 files');
    assert.equal(groupTitle(acc.entries[1], false), 'mirth.log.2.zip (200 matching lines)');
    // Only files with a matching line get a group.
    assert.deepEqual(countGroups(acc).map((e) => e.fileName), ['x', 'mirth.log.2.zip', 'old.log.9.zip']);
});

test('a resumed count whose first file is another file appends it', () => {
    const params = { query: 'q' };
    let acc = addCountResult(emptySearch(), params, countResult({ fileCounts: [fc('a/x@1', 4, false)] }), []);
    acc = addCountResult(acc, params, countResult({ fileCounts: [fc('a/y@2', 5)] }), []);
    assert.deepEqual(acc.entries.map((e) => e.matchingLines), [4, 5]);
});

test('a group title is singular for one line', () => {
    assert.equal(groupTitle({ fileName: 'mirth.log', matchingLines: 1 }, false), 'mirth.log (1 matching line)');
});

test('the rows of a group carry the file name, and a resume point is read from the result', () => {
    const files = [{ id: 'a/x@1', name: 'mirth.log' }];
    const rows = resultRows(countResult({ matches: [{ fileId: 'a/x@1', lineNumber: 4 }] }), files);
    assert.deepEqual(rows.map((r) => r.fileName), ['mirth.log']);
    assert.equal(resumePoint(countResult({})), null);
    assert.deepEqual(resumePoint(countResult({ resumeFileId: 'a/x@1', resumeOffset: 9 })),
        { fileId: 'a/x@1', offset: 9 });
});

test('warnings are added without repeats', () => {
    assert.deepEqual(addWarnings(['a'], ['a', 'b']), ['a', 'b']);
});

test('an excerpt keeps the match visible in a narrow column', () => {
    assert.deepEqual(excerpt('abc needle def', 4, 10, 40), { text: 'abc needle def', start: 4, end: 10 });
    const long = 'x'.repeat(100) + 'needle' + 'y'.repeat(20);
    const e = excerpt(long, 100, 106, 10);
    assert.equal(e.text.substring(e.start, e.end), 'needle');
    assert.equal(e.text.startsWith('...'), true);
    assert.equal(excerpt('', 0, 0).text, '');
});

test('an excerpt never starts on the second half of a surrogate pair', () => {
    // An emoji is two UTF-16 units; a cut that would fall between them starts after it instead.
    const line = 'ab\uD83D\uDE00' + 'x'.repeat(10) + 'needle';
    const e = excerpt(line, 14, 20, 11);
    assert.equal(e.text, '...' + 'x'.repeat(10) + 'needle');
    assert.equal(e.text.substring(e.start, e.end), 'needle');
    // A cut before the pair keeps it whole.
    const whole = excerpt(line, 14, 20, 12);
    assert.equal(whole.text, '...\uD83D\uDE00' + 'x'.repeat(10) + 'needle');
    assert.equal(whole.text.substring(whole.start, whole.end), 'needle');
});

test('the search title names the scope and only the options that are on', () => {
    assert.equal(searchTitleRest(null, false, false), '" in all files');
    assert.equal(searchTitleRest('mirth.log.3.zip', true, false), '" in mirth.log.3.zip (Java regular expression)');
    assert.equal(searchTitleRest(null, false, true), '" in all files (match case)');
    assert.equal(searchTitleRest(null, true, true), '" in all files (Java regular expression, match case)');
    assert.equal(SEARCH_TITLE_LEAD + 'ERROR' + searchTitleRest(null, false, false), 'Search results for "ERROR" in all files');
});

test('a long search is cut into pieces without splitting a character', () => {
    const json = '{"patientId":"12345","encounter":"E-998"}';
    assert.deepEqual(pieces(json, 14), ['{"patientId":"', '12345","encoun', 'ter":"E-998"}']);
    assert.equal(pieces(json, 14).join(''), json);
    assert.deepEqual(pieces('ab', 50), ['ab']);
    assert.deepEqual(pieces('', 50), []);
    // An emoji is two UTF-16 units; a piece ends before it rather than between its halves.
    assert.deepEqual(pieces('ab\uD83D\uDE00c', 3), ['ab', '\uD83D\uDE00c']);
});

test('a paste past 1,000 characters is cut, and the dialog says so', () => {
    assert.equal(QUERY_MAX, 1000);
    assert.equal(QUERY_CUT_NOTE, 'Searches are limited to 1,000 characters.');
    assert.equal(pasteIsCut(0, 0, 'x'.repeat(1000)), false);
    assert.equal(pasteIsCut(0, 0, 'x'.repeat(1001)), true);
    assert.equal(pasteIsCut(990, 0, 'x'.repeat(11)), true);
    // The paste replaces the selection.
    assert.equal(pasteIsCut(990, 10, 'x'.repeat(20)), false);
    // Line breaks are dropped by a one-line box, so they do not count.
    assert.equal(pasteIsCut(0, 0, 'x'.repeat(999) + '\r\n'), false);
    assert.equal(pasteIsCut(5, 0, ''), false);
});

test('the search reopened from its title searches this file only while it is still the one open', () => {
    const scoped = { query: 'q', fileId: 'fout/mirth.log.3.zip@aa', fileName: 'mirth.log.3.zip' };
    assert.equal(reopenScope(scoped, { id: 'fout/mirth.log.3.zip@aa' }), 'selected');
    assert.equal(reopenScope(scoped, { id: 'fout/mirth.log@bb' }), 'all');
    assert.equal(reopenScope(scoped, null), 'all');
    // The same name now holding another file (a rollover since) is another file.
    assert.equal(reopenScope(scoped, { id: 'fout/mirth.log.3.zip@cc' }), 'all');
    assert.equal(reopenScope({ query: 'q', fileId: null }, { id: 'fout/mirth.log@bb' }), 'all');
});

// ---- searches that cannot make progress -------------------------------------------

const STUCK = 'The search can\'t get past this point within its time limit (a very long line or a complex pattern). '
    + 'Try a simpler pattern.';

test('a Count the rest that comes back with the point it was sent from is stuck and is not offered again', () => {
    const params = { query: 'q' };
    const limit = { stopReason: 'DEADLINE', complete: false, resumeFileId: 'a/z@3', resumeOffset: 777 };
    let acc = addCountResult(emptySearch(), params, countResult({ fileCounts: [fc('a/z@3', 145, false)], ...limit }), []);
    assert.equal(acc.stuck, false);
    assert.equal(canCountRest(acc), true);
    acc = addCountResult(acc, params, countResult({ fileCounts: [fc('a/z@3', 0, false)], ...limit }), []);
    assert.equal(acc.stuck, true);
    assert.equal(canCountRest(acc), false);
    // Still stopped at the time limit: the cut-off file's number is still a minimum.
    assert.equal(stoppedAtLimit(acc), true);
    assert.equal(isPartial(acc, acc.entries[0]), true);
    assert.equal(groupTitle(acc.entries[0], isPartial(acc, acc.entries[0])), 'z (145+ matching lines)');
    assert.ok(resultsSummary(acc).includes('before the time limit'));
    assert.equal(STUCK_TEXT, STUCK);
    // Progress, even a little, keeps it offered.
    let moved = addCountResult(emptySearch(), params, countResult({ fileCounts: [fc('a/z@3', 1, false)], ...limit }), []);
    moved = addCountResult(moved, params, countResult({ fileCounts: [fc('a/z@3', 0, false)], ...limit, resumeOffset: 778 }), []);
    assert.equal(moved.stuck, false);
    assert.equal(canCountRest(moved), true);
});

test('only a continuation can be stuck', () => {
    const result = countResult({ resumeFileId: 'f', resumeOffset: 5 });
    assert.equal(sameResumePoint(null, result), false);
    assert.equal(sameResumePoint({ fileId: 'f', offset: 5 }, result), true);
    assert.equal(sameResumePoint({ fileId: 'f', offset: 4 }, result), false);
    assert.equal(sameResumePoint({ fileId: 'g', offset: 5 }, result), false);
    assert.equal(sameResumePoint({ fileId: 'f', offset: 5 }, countResult({})), false);
});

const entry = { fileId: 'a/x@1', fileName: 'mirth.log.3.zip', matchingLines: 578, complete: true };
const loaded = (over, rows = [], sent = null, before = { open: true, rows: [] }) =>
    Object.assign({}, before, groupAfterLoad(before, countResult(over), rows, sent));

test('a group whose first load found no line before the time limit offers to keep searching', () => {
    const group = loaded({ stopReason: 'DEADLINE', resumeFileId: 'a/x@1', resumeOffset: 4096 });
    assert.deepEqual(group.resume, { fileId: 'a/x@1', offset: 4096 });
    const row = groupEndRow(group, entry, false);
    assert.equal(row.text, 'The search reached its time limit before finding a line in this file. Click to keep searching.');
    assert.equal(row.text, KEEP_SEARCHING_TEXT);
    assert.equal(row.click, true);
    assert.ok(row.title.includes('mirth.log.3.zip'));
});

test('a group stopped by a pattern too complex for a line says so and offers nothing', () => {
    const none = loaded({ stopReason: 'PATTERN_TOO_COMPLEX' });
    assert.deepEqual(groupEndRow(none, entry, false),
        { text: 'The search pattern is too complex for a line in this file. Try a simpler pattern.', title: '', click: false });
    assert.equal(GROUP_TOO_COMPLEX_TEXT, groupEndRow(none, entry, false).text);
    // Also after the lines it did find: the list stops there, and says why.
    const some = loaded({ stopReason: 'PATTERN_TOO_COMPLEX' }, [{ match: {} }, { match: {} }]);
    assert.equal(groupEndRow(some, entry, false).text, GROUP_TOO_COMPLEX_TEXT);
});

test('a group whose file is gone says so, from a 409 or from a rotation during the search', () => {
    assert.deepEqual(groupEndRow({ open: true, rows: [], gone: true }, entry, false),
        { text: staleGroupText('mirth.log.3.zip'), title: '', click: false });
    const rotated = loaded({ stopReason: 'FILES_ROTATED' });
    assert.equal(rotated.gone, true);
    assert.equal(groupEndRow(rotated, entry, false).text, staleGroupText('mirth.log.3.zip'));
});

test('a continuation that comes back with the point it was sent from stops being offered', () => {
    const sent = { fileId: 'a/x@1', offset: 4096 };
    const before = { open: true, rows: [], resume: sent };
    const group = loaded({ stopReason: 'DEADLINE', resumeFileId: 'a/x@1', resumeOffset: 4096 }, [], sent, before);
    assert.equal(group.stuck, true);
    assert.equal(group.resume, null);
    assert.deepEqual(groupEndRow(group, entry, false), { text: STUCK, title: '', click: false });
    // The same after lines were shown: load-more is replaced.
    const withRows = { open: true, rows: new Array(1000).fill({ match: {} }), resume: sent };
    const after = loaded({ stopReason: 'DEADLINE', resumeFileId: 'a/x@1', resumeOffset: 4096 }, [], sent, withRows);
    assert.equal(groupEndRow(after, entry, false).text, STUCK);
    // A continuation that got further is offered again.
    const further = loaded({ stopReason: 'DEADLINE', resumeFileId: 'a/x@1', resumeOffset: 9000 }, [], sent, before);
    assert.equal(further.stuck, false);
    assert.equal(groupEndRow(further, entry, false).text, KEEP_SEARCHING_TEXT);
});

test('the other last rows of a group are as before', () => {
    const rows = new Array(1000).fill({ match: {} });
    const big = { ...entry, matchingLines: 2991 };
    assert.equal(groupEndRow({ open: true, rows: [], loading: true }, big, false).text, 'Loading matches...');
    assert.equal(groupEndRow({ open: true, rows, loading: true }, big, false).text, 'Loading the next 1,000...');
    const failedFirst = groupEndRow({ open: true, rows: [], failed: true }, big, false);
    assert.equal(failedFirst.text, 'The matches could not be loaded. Click here to try again.');
    assert.equal(failedFirst.click, true);
    assert.equal(groupEndRow({ open: true, rows, failed: true }, big, false).text, 'Could not load more. Click to try again.');
    const more = groupEndRow({ open: true, rows, resume: { fileId: 'a/x@1', offset: 1 } }, big, false);
    assert.equal(more.text, '1,000 of 2,991 shown. Click to show the next 1,000.');
    assert.equal(more.click, true);
    assert.equal(groupEndRow({ open: true, rows, resume: { fileId: 'a/x@1', offset: 1 } }, big, true).text,
        'Click to show more.');
    // Everything loaded: no last row.
    assert.equal(groupEndRow(loaded({}, rows), big, false), null);
});

test('pages carry the search only while its results are open and the engine accepted it', () => {
    const search = Object.assign(emptySearch(), { params: { query: '(unclosed', regex: true, caseSensitive: false } });
    assert.deepEqual(pageMarksFor(true, search),
        { highlightQuery: '(unclosed', highlightRegex: true, highlightCaseSensitive: false });
    assert.deepEqual(pageMarksFor(false, search), {});
    assert.deepEqual(pageMarksFor(true, Object.assign({}, search, { refused: true })), {});
    assert.deepEqual(pageMarksFor(true, emptySearch()), {});
});
