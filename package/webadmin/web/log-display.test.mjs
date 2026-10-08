// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

// Mirrors the Swing LogDisplayTextTest case by case. The two display modes must
// hold the same number of lines for every kind of terminator, or the gutter's
// line numbers jump when the user toggles special characters. The symbols are
// written as constants rather than literals so a tool that rewrites escapes
// cannot change what is asserted.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    CR_SYMBOL as CR, LF_SYMBOL as LF, normal, special, lineCount, textLineCount, mapHighlights,
    lineStartOffset, isInvisibleButPresent, tooltip, explain, codePointLabel, withoutFinalLineEnd, displayOffset,
    rawText
} from './log-display.js';

const cp = (codePoint) => String.fromCodePoint(codePoint);

function assertSameLines(raw, expectedLines) {
    const n = normal(raw);
    const s = special(raw);
    assert.equal(lineCount(n), expectedLines, 'normal mode: ' + JSON.stringify(n));
    assert.equal(lineCount(s), expectedLines, 'special mode: ' + JSON.stringify(s));
    assert.equal(textLineCount(n), textLineCount(s));
}

test('the symbols are the control pictures for CR and LF', () => {
    assert.equal(CR.codePointAt(0), 0x240D);
    assert.equal(LF.codePointAt(0), 0x240A);
});

test('LF, CRLF and a lone CR each make one line break in both modes', () => {
    assertSameLines('a\nb\nc', 3);
    assertSameLines('a\r\nb\r\nc', 3);
    assertSameLines('a\rb\rc', 3);
    assertSameLines('a\nb\r\nc\rd', 4);
});

test('CR CRLF is two terminators', () => {
    // A lone CR followed by a CRLF: two line ends, never one.
    assertSameLines('a\r\r\nb', 3);
    assert.equal(normal('a\r\r\nb'), 'a\n\nb');
    assert.equal(special('a\r\r\nb'), 'a' + CR + '\n' + CR + LF + '\nb');
});

test('text without a trailing terminator keeps its last line', () => {
    assertSameLines('one\ntwo', 2);
    assert.equal(normal('one\ntwo'), 'one\ntwo');
    assert.equal(special('one\ntwo'), 'one' + LF + '\ntwo');
    assert.equal(textLineCount(special('one\ntwo')), 2);
});

test('text ending in a terminator has no extra text line', () => {
    assertSameLines('one\ntwo\n', 3);
    assert.equal(textLineCount(normal('one\ntwo\n')), 2);
    assert.equal(textLineCount(special('one\ntwo\n')), 2);
});

test('empty text is one empty line and no text lines', () => {
    assertSameLines('', 1);
    assertSameLines(null, 1);
    assert.equal(normal(null), '');
    assert.equal(special(null), '');
    assert.equal(textLineCount(''), 0);
});

test('normal mode never shows a symbol for a line end', () => {
    assert.equal(normal('a\r\nb\rc\nd'), 'a\nb\nc\nd');
});

test('special mode shows the real terminator before each newline', () => {
    assert.equal(special('a\r\nb'), 'a' + CR + LF + '\nb');
    assert.equal(special('a\nb'), 'a' + LF + '\nb');
    assert.equal(special('a\rb'), 'a' + CR + '\nb');
});

test('tabs and spaces are left for the viewer to draw', () => {
    assert.equal(normal('a\tb c'), 'a\tb c');
    assert.equal(special('a\tb c'), 'a\tb c');
});

test('the Unicode line and paragraph separators and NEL are not line ends', () => {
    // The server counts only LF, CRLF and lone CR, so these stay inside their line.
    const raw = 'a' + cp(0x2028) + 'b' + cp(0x2029) + 'c' + cp(0x0085) + 'd';
    assertSameLines(raw, 1);
    assert.equal(normal(raw), raw);
    assert.equal(special(raw), raw);
});

// ---- search highlight positions ---------------------------------------------------

/* The matched text each pair selects, read back from the display text. */
function picked(text, ranges) {
    return ranges.map((r) => text.substring(r.start, r.end));
}

test('highlight positions on LF text map to the same offsets', () => {
    const raw = 'INFO one\nERROR two\nINFO three';
    const display = normal(raw);
    const ranges = mapHighlights(raw, display, [0, 4, 9, 14, 19, 23]);
    assert.deepEqual(picked(display, ranges), ['INFO', 'ERROR', 'INFO']);
});

test('highlight positions after CRLF line ends shift left by one per line', () => {
    const raw = 'a1\r\nb22\r\nc333';
    const display = normal(raw);
    // In the raw text "b22" is at 4-7 and "c333" at 9-13; the display holds one LF per line end.
    const ranges = mapHighlights(raw, display, [4, 7, 9, 13]);
    assert.deepEqual(picked(display, ranges), ['b22', 'c333']);
});

test('highlight positions after lone CR line ends (HL7 segments) map by line and column', () => {
    const raw = 'MSH|^~\\&|A\rPID|1||X\rPV1|2';
    const display = normal(raw);
    const at = raw.indexOf('PID');
    const ranges = mapHighlights(raw, display, [at, at + 3, raw.indexOf('PV1'), raw.indexOf('PV1') + 3]);
    assert.deepEqual(picked(display, ranges), ['PID', 'PV1']);
});

test('special mode keeps columns: the symbols sit at the end of each line', () => {
    const raw = 'one two\r\nthree two\rfour two\nfive';
    const display = special(raw);
    const hits = [];
    const flat = [];
    for (let i = raw.indexOf('two'); i >= 0; i = raw.indexOf('two', i + 1)) { hits.push('two'); flat.push(i, i + 3); }
    const ranges = mapHighlights(raw, display, flat);
    assert.equal(ranges.length, 3);
    assert.deepEqual(picked(display, ranges), hits);
});

test('both display modes agree on which line a match is on', () => {
    const raw = 'x\r\ny\rz needle\nneedle w';
    const at = [raw.indexOf('needle'), raw.lastIndexOf('needle')];
    const flat = [at[0], at[0] + 6, at[1], at[1] + 6];
    for (const display of [normal(raw), special(raw)]) {
        const ranges = mapHighlights(raw, display, flat);
        assert.deepEqual(picked(display, ranges), ['needle', 'needle']);
        const lineOfStart = display.substring(0, ranges[1].start).split('\n').length;
        assert.equal(lineOfStart, 4);
    }
});

test('a match at the very end of a line, and on an unterminated last line, maps', () => {
    const raw = 'abc\r\ndef';
    const display = normal(raw);
    const ranges = mapHighlights(raw, display, [1, 3, 6, 8]);
    assert.deepEqual(picked(display, ranges), ['bc', 'ef']);
});

test('no highlights, an empty page and malformed pairs give nothing', () => {
    assert.deepEqual(mapHighlights('abc', 'abc', null), []);
    assert.deepEqual(mapHighlights('abc', 'abc', []), []);
    assert.deepEqual(mapHighlights('', '', [0, 1]), []);
    assert.deepEqual(mapHighlights('abc', 'abc', [2, 1]), []);
    assert.deepEqual(mapHighlights('abc', 'abc', [-1, 1]), []);
    assert.deepEqual(mapHighlights('abc', 'abc', [0, 1, 2]), [{ start: 0, end: 1 }]);
});

test('astral characters count as two code units on both sides', () => {
    const raw = 'a' + cp(0x1F600) + 'b\r\n' + cp(0x1F600) + 'needle';
    const display = normal(raw);
    const at = raw.indexOf('needle');
    const ranges = mapHighlights(raw, display, [at, at + 6]);
    assert.deepEqual(picked(display, ranges), ['needle']);
});

test('lineStartOffset finds where a line begins', () => {
    const text = 'ab\ncd\nef';
    assert.equal(lineStartOffset(text, 0), 0);
    assert.equal(lineStartOffset(text, 1), 3);
    assert.equal(lineStartOffset(text, 2), 6);
    assert.equal(lineStartOffset(text, 3), -1);
    assert.equal(lineStartOffset(text, -1), -1);
});

// ---- invisible characters -----------------------------------------------------

test('invisible characters are recognised', () => {
    const invisible = [0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x00A0, 0x0080, 0x009F,
        0x2028, 0x2029, 0x200E, 0x200F, 0x061C, 0x2003, 0x3000, 0xE0001];
    for (const c of invisible) {
        assert.equal(isInvisibleButPresent(c), true, codePointLabel(c));
    }
});

test('visible characters are not marked invisible', () => {
    const visible = [0x61, 0x20, 0x09, 0x2D, 0x00E9, 0x2400, 0x2426, 0xFFFD, 0x1F600, 0x4E2D];
    for (const c of visible) {
        assert.equal(isInvisibleButPresent(c), false, codePointLabel(c));
    }
});

// ---- hover text -----------------------------------------------------------------

test('a code point is labelled with at least four hex digits', () => {
    assert.equal(codePointLabel(0x41), 'U+0041');
    assert.equal(codePointLabel(0x1F600), 'U+1F600');
});

test('an ordinary character is just labelled', () => {
    assert.equal(tooltip(0x00E9), 'U+00E9');
    assert.equal(explain(0x00E9), null);
});

test('an astral character shows its full code point', () => {
    assert.equal(tooltip(0x1F600), 'U+1F600');
});

test('a control picture says which control it stands for', () => {
    // U+240B is the picture for the vertical tab that frames an MLLP message
    const t = tooltip(0x240B);
    assert.ok(t.startsWith('U+240B\n'), t);
    assert.ok(t.includes('Control character U+000B shown as a symbol.'), t);
});

test('the delete symbol is explained too', () => {
    assert.ok(tooltip(0x2421).includes('Control character U+007F shown as a symbol.'));
});

test('the bidirectional substitute is explained', () => {
    const t = tooltip(0x2426);
    assert.ok(t.startsWith('U+2426\n'), t);
    assert.ok(t.includes('bidirectional override character'), t);
    assert.ok(t.includes('display in a different order than it is stored'), t);
});

test('the replacement character is explained', () => {
    const t = tooltip(0xFFFD);
    assert.ok(t.startsWith('U+FFFD\n'), t);
    assert.ok(t.includes('not valid text in the file\'s character set'), t);
});

test('an invisible character says so', () => {
    const t = tooltip(0x200B);
    assert.ok(t.startsWith('U+200B\n'), t);
    assert.ok(t.includes('invisible'), t);
});

test('the editor text has no empty last line after a final line end', () => {
    for (const raw of ['a\nb\n', 'a\r\nb\r\n', 'a\nb\rc\n', 'a\nb']) {
        for (const display of [normal(raw), special(raw)]) {
            const shown = withoutFinalLineEnd(display);
            assert.equal(lineCount(shown), textLineCount(display), JSON.stringify(display));
        }
    }
    assert.equal(withoutFinalLineEnd('a\nb\n'), 'a\nb');
    assert.equal(withoutFinalLineEnd('a\nb'), 'a\nb');
    assert.equal(withoutFinalLineEnd('\n'), '');
    assert.equal(withoutFinalLineEnd(''), '');
    // Line starts, and so every gutter number and highlight, stay where they were.
    const display = normal('one\ntwo\nthree\n');
    const shown = withoutFinalLineEnd(display);
    for (let i = 0; i < 3; i++) assert.equal(lineStartOffset(shown, i), lineStartOffset(display, i));
});

test('one raw index maps onto either display text by line and column', () => {
    const raw = 'ab\r\ncd\re\nf';
    assert.equal(displayOffset(raw, normal(raw), raw.indexOf('d')), normal(raw).indexOf('d'));
    assert.equal(displayOffset(raw, special(raw), raw.indexOf('d')), special(raw).indexOf('d'));
    assert.equal(displayOffset(raw, special(raw), raw.indexOf('f')), special(raw).indexOf('f'));
    assert.equal(displayOffset(raw, normal(raw), 0), 0);
    assert.equal(displayOffset(raw, normal(raw), raw.length), normal(raw).length);
    assert.equal(displayOffset(raw, normal(raw), raw.length + 1), -1);
    assert.equal(displayOffset(raw, normal(raw), -1), -1);
    assert.equal(displayOffset('', '', 0), 0);
});

// ---- Copy gives the page text -------------------------------------------------------

/* What the editor holds for a page in a mode, and the raw text of a range of it. */
const copied = (raw, mode, from, to) => {
    const shown = withoutFinalLineEnd(mode(raw));
    return rawText(raw, shown, shown.indexOf(from), to == null ? shown.length : shown.indexOf(to) + to.length);
};

test('copying lines gives their real line ends, in both modes', () => {
    const raw = 'MSH|a\rPID|b\r\nINFO c\nlast\r\n';
    // From the start of the first line to the end of the third: CR, CRLF and LF as they are.
    assert.equal(copied(raw, normal, 'MSH', 'INFO c'), 'MSH|a\rPID|b\r\nINFO c');
    assert.equal(copied(raw, special, 'MSH', 'INFO c'), 'MSH|a\rPID|b\r\nINFO c');
    // Across the line end into the next line: the terminator is taken whole.
    assert.equal(copied(raw, normal, 'PID', 'INFO'), 'PID|b\r\nINFO');
    assert.equal(copied(raw, special, 'PID', 'INFO'), 'PID|b\r\nINFO');
});

test('special mode never puts its symbols on the clipboard', () => {
    const raw = 'a\r\nb\rc\nd';
    const shown = special(raw);
    assert.ok(shown.includes(CR) && shown.includes(LF));
    const all = rawText(raw, withoutFinalLineEnd(shown), 0, withoutFinalLineEnd(shown).length);
    assert.equal(all, raw);
    assert.ok(!all.includes(CR) && !all.includes(LF));
    // The symbols stand for the terminator's characters one for one: a selection ending after the
    // CR symbol of a CRLF takes the CR, and after both, the CRLF.
    const firstLineEnd = shown.indexOf('\n');
    assert.equal(rawText(raw, shown, 0, firstLineEnd - 1), 'a\r');
    assert.equal(rawText(raw, shown, 0, firstLineEnd), 'a\r\n');
});

test('select all copies the page; its last line end only where special mode shows it', () => {
    const raw = 'one\r\ntwo\r\n';
    assert.equal(copied(raw, special, 'one'), raw);
    assert.equal(copied(raw, normal, 'one'), 'one\r\ntwo');
    // A page whose last line has no terminator is the same in both.
    assert.equal(copied('one\ntwo', normal, 'one'), 'one\ntwo');
    assert.equal(copied('one\ntwo', special, 'one'), 'one\ntwo');
});

test('a selection inside a line copies just those characters, whichever way it was made', () => {
    const raw = 'INFO 2026-10-07 boom\r\n';
    const shown = withoutFinalLineEnd(normal(raw));
    assert.equal(rawText(raw, shown, 5, 15), '2026-10-07');
    assert.equal(rawText(raw, shown, 15, 5), '2026-10-07');
    assert.equal(rawText(raw, shown, 3, 3), '');
    assert.equal(rawText('', '', 0, 0), '');
});
