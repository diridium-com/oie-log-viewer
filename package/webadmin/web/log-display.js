/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * What the viewer shows for a page of log text, and how to describe a
 * character. Framework-free on purpose (no DOM, no React): data in, data out,
 * so it is testable with node's own test runner.
 *
 * Ported 1:1 from the Swing viewer's LogDisplayText (client module). The server
 * sends line terminators raw: a line ends at LF, CRLF or a lone CR. Both display
 * modes turn each terminator into exactly one LF, so a page has the same number
 * of lines in either mode and the line numbers never shift when the user
 * toggles special characters. Special mode only adds symbols in front of that LF.
 *
 * This file is pure ASCII: non-ASCII characters are built from their code points.
 */

/** Control picture for carriage return, shown at the end of a line in special mode. */
export const CR_SYMBOL = String.fromCharCode(0x240D);

/** Control picture for line feed, shown at the end of a line in special mode. */
export const LF_SYMBOL = String.fromCharCode(0x240A);

/** The server's stand-in for a neutralized bidirectional override character. */
const BIDI_SUBSTITUTE = 0x2426;

/** The replacement character the server uses for bytes that are not valid text. */
const REPLACEMENT = 0xFFFD;

/** The control picture for the delete character, which sits apart from the others. */
const DELETE_SYMBOL = 0x2421;

/** Each terminator (CRLF, lone CR, LF) becomes one LF. */
export function normal(raw) {
    if (raw == null || raw === '') return '';
    return String(raw).replace(/\r\n?/g, '\n');
}

/**
 * Each line keeps one LF, with the line's real terminator shown in front of it:
 * CRLF as the CR and LF symbols, LF as the LF symbol, a lone CR as the CR
 * symbol. A last line with no terminator gets nothing.
 */
export function special(raw) {
    if (raw == null || raw === '') return '';
    return String(raw).replace(/\r\n|\r|\n/g, (terminator) => {
        if (terminator === '\r\n') return CR_SYMBOL + LF_SYMBOL + '\n';
        if (terminator === '\r') return CR_SYMBOL + '\n';
        return LF_SYMBOL + '\n';
    });
}

/**
 * The number of lines an editor holds for this display text: one more than the
 * number of LF, so empty text is one empty line. This is the count that must be
 * equal in both display modes.
 */
export function lineCount(display) {
    let count = 1;
    let at = display.indexOf('\n');
    while (at >= 0) {
        count++;
        at = display.indexOf('\n', at + 1);
    }
    return count;
}

/**
 * The number of lines that hold text: like lineCount but without the empty line
 * after a final terminator, and 0 for empty text. For the position label.
 */
export function textLineCount(display) {
    if (display.length === 0) return 0;
    const count = lineCount(display);
    return display.charAt(display.length - 1) === '\n' ? count - 1 : count;
}

/**
 * The text the editor is given: the display text without its final LF. An
 * editor shows an empty numbered line after a final line end, which the page
 * does not have. Every position (highlights, the jumped-to match) is a
 * character offset before that LF, since a match never spans a line end, so
 * dropping it moves nothing.
 */
export function withoutFinalLineEnd(display) {
    return display.endsWith('\n') ? display.substring(0, display.length - 1) : display;
}

/**
 * Maps the engine's highlight positions onto the display text.
 *
 * `raw` is the page text exactly as the server sent it (raw CR and LF
 * terminators); `highlights` is the server's flat list of start and end
 * character indices into `raw`, pairs in order; `display` is the text the
 * viewer holds (normal or special). Both texts have the same lines, because
 * each terminator becomes exactly one LF, and special mode only adds symbols
 * in front of that LF. A match never spans a line end, so each position maps by
 * its line index and its column inside that line, which no display mode shifts.
 *
 * Returns [{ start, end }] as character offsets into `display`. A pair whose
 * line is not in the display text, or that is malformed, is left out.
 */
export function mapHighlights(raw, display, highlights) {
    const result = [];
    if (!raw || !display || !highlights || highlights.length < 2) return result;
    const rawStarts = lineStarts(raw, /\r\n|\r|\n/g);
    const shownStarts = lineStarts(display, /\n/g);
    for (let i = 0; i + 1 < highlights.length; i += 2) {
        const start = highlights[i];
        const end = highlights[i + 1];
        if (!(start >= 0) || !(end >= start) || start > raw.length) continue;
        const line = lastAtOrBefore(rawStarts, start);
        if (line >= shownStarts.length) continue;
        const column = start - rawStarts[line];
        const from = shownStarts[line] + column;
        result.push({ start: from, end: from + (end - start) });
    }
    return result;
}

/**
 * Maps one character index in `raw` onto the display text, by its line and its
 * column as mapHighlights does; -1 when the index is not in the text.
 */
export function displayOffset(raw, display, index) {
    if (raw == null || display == null || !(index >= 0) || index > raw.length) return -1;
    const rawStarts = lineStarts(raw, /\r\n|\r|\n/g);
    const shownStarts = lineStarts(display, /\n/g);
    const line = lastAtOrBefore(rawStarts, index);
    if (line >= shownStarts.length) return -1;
    return shownStarts[line] + (index - rawStarts[line]);
}

/**
 * The page text the engine sent for a range of the editor's text, which is what
 * Copy puts on the clipboard: the characters as they are, and each line end as
 * it is in the file (CR, LF or CRLF), never the viewer's own LF or the symbols
 * special mode shows for a line end.
 *
 * `raw` is the page text as the server sent it; `shown` the text the editor
 * holds (the display text without its final line end, see withoutFinalLineEnd);
 * `start` and `end` offsets into `shown`. Both texts have the same lines, and
 * within a line an offset maps by its column: a normal-mode line is the raw line
 * without its terminator, and in special mode the symbols stand one for one for
 * the terminator's characters (CR and LF symbols for CRLF), so a line with its
 * symbols is as long as the raw line with its terminator. Selecting past the end
 * of a line takes its terminator. The page's last line end is not in the editor
 * in normal mode, so it is copied only in special mode, where its symbols are.
 */
export function rawText(raw, shown, start, end) {
    if (!raw || !shown) return '';
    const rawStarts = lineStarts(raw, /\r\n|\r|\n/g);
    const shownStarts = lineStarts(shown, /\n/g);
    const at = (offset) => {
        const o = Math.max(0, Math.min(offset, shown.length));
        const line = lastAtOrBefore(shownStarts, o);
        if (line >= rawStarts.length) return raw.length;
        const next = line + 1 < rawStarts.length ? rawStarts[line + 1] : raw.length;
        return Math.min(rawStarts[line] + (o - shownStarts[line]), next);
    };
    return raw.substring(at(Math.min(start, end)), at(Math.max(start, end)));
}

/** The offset where each line starts: 0, then just after every terminator the pattern finds. */
function lineStarts(text, terminator) {
    const starts = [0];
    terminator.lastIndex = 0;
    let m = terminator.exec(text);
    while (m !== null) {
        starts.push(m.index + m[0].length);
        m = terminator.exec(text);
    }
    return starts;
}

/** Index of the last element that is <= value, in an ascending array whose first element is 0. */
function lastAtOrBefore(sorted, value) {
    let lo = 0;
    let hi = sorted.length - 1;
    while (lo < hi) {
        const mid = (lo + hi + 1) >> 1;
        if (sorted[mid] <= value) lo = mid; else hi = mid - 1;
    }
    return lo;
}

/**
 * The character offset where line `index` (0-based) starts in the display
 * text, or -1 when the text has no such line.
 */
export function lineStartOffset(display, index) {
    if (index < 0) return -1;
    let pos = 0;
    for (let i = 0; i < index; i++) {
        const next = display.indexOf('\n', pos);
        if (next < 0) return -1;
        pos = next + 1;
    }
    return pos;
}

/**
 * True for a character that is in the text but draws nothing a reader can see:
 * zero-width and other format characters, the byte order mark, the soft hyphen,
 * the no-break space and the other non-ASCII spaces, the C1 controls, the line
 * and paragraph separators, and the directional marks.
 */
export function isInvisibleButPresent(cp) {
    if (cp >= 0x80 && cp <= 0x9F) return true;
    if (cp === 0x20) return false;
    return /^[\p{Zs}\p{Cf}\p{Zl}\p{Zp}]$/u.test(String.fromCodePoint(cp));
}

/** A code point as the usual U+XXXX label (at least four hex digits). */
export function codePointLabel(cp) {
    return 'U+' + cp.toString(16).toUpperCase().padStart(4, '0');
}

/**
 * A plain explanation for the characters the server substitutes, and for
 * invisible ones; null for anything else. The browser has no table of Unicode
 * names, so a control picture is explained by the code of the control it
 * stands for, not by its name.
 */
export function explain(cp) {
    if ((cp >= 0x2400 && cp <= 0x241F) || cp === DELETE_SYMBOL) {
        const control = cp === DELETE_SYMBOL ? 0x7F : cp - 0x2400;
        return 'Control character ' + codePointLabel(control) + ' shown as a symbol.';
    }
    if (cp === BIDI_SUBSTITUTE) {
        return 'A bidirectional override character, replaced because it could make the line '
            + 'display in a different order than it is stored.';
    }
    if (cp === REPLACEMENT) {
        return 'Bytes that are not valid text in the file\'s character set, or a U+FFFD character written in the file.';
    }
    if (isInvisibleButPresent(cp)) {
        return 'An invisible character that is present in the text.';
    }
    return null;
}

/** The code point label plus, on a second line, the explanation when there is one. */
export function tooltip(cp) {
    const explanation = explain(cp);
    return explanation == null ? codePointLabel(cp) : codePointLabel(cp) + '\n' + explanation;
}
