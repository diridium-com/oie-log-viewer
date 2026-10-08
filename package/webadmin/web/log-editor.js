/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The read-only viewer for a page of log text.
 *
 * It is the host's code editor (platform.createCodeEditor). The host starts it
 * as a plain textarea and upgrades it IN PLACE to Monaco when Monaco loads, so
 * there are two states and both must work:
 *
 *   Monaco     line numbers starting at the page's first line, word wrap,
 *              the search highlights (every match lightly, the jumped-to match
 *              strongly, both marked in the overview ruler), whitespace and
 *              invisible-character marking in show-special mode, a hover with
 *              the code point. Monaco's own Ctrl+F finds in the page.
 *   textarea   the air-gapped fallback: the same text, a gutter numbered from
 *              the page's first line, wrap, and the jumped-to match selected.
 *              No highlights, no hover.
 *
 * In both, Copy puts the page text the engine sent for the selection on the
 * clipboard (rawText in log-display.js): the file's own line ends, without the
 * symbols special mode shows for them.
 *
 * Nothing Monaco-only runs unless `editor.monaco` exists. The host gives no
 * event for the upgrade (it sets `editor.monaco` when it lands), so this polls
 * for it briefly.
 *
 * Monaco options used (all checked against monaco-editor 0.56.0, the version
 * the web administrator ships): wordWrap, lineNumbers (function form),
 * lineNumbersMinChars, renderWhitespace, renderControlCharacters,
 * unicodeHighlight, unusualLineTerminators, renderFinalNewline, folding,
 * glyphMargin, stickyScroll; createDecorationsCollection with className,
 * inlineClassName, isWholeLine and overviewRuler ({ color, position },
 * OverviewRulerLane.Center / Right); setSelection, revealRangeInCenter,
 * setScrollTop; languages.register and registerHoverProvider.
 */

import { lineCount, tooltip, withoutFinalLineEnd, rawText } from './log-display.js';
import { ensureStyle } from './log-style.js';

/* A language id of our own, so the hover provider below applies to this viewer
   only and not to every plain-text editor in the web administrator. */
const LANGUAGE_ID = 'oie-log-viewer';
let hoverReady = false;
let hoverTried = false;

const UPGRADE_POLL_MS = 150;
const UPGRADE_GIVE_UP_MS = 20000;

/* True when Monaco's own unicode highlighter has marked this range (its decoration is
   named 'unicode-highlight'), which is exactly when Monaco shows its own hover. */
function monacoMarks(model, range) {
    try {
        return model.getDecorationsInRange(range).some((d) => d.options && d.options.className === 'unicode-highlight');
    } catch (e) {
        return false;
    }
}

function registerHover(monaco) {
    if (hoverTried) return;
    hoverTried = true;
    try {
        monaco.languages.register({ id: LANGUAGE_ID });
        monaco.languages.registerHoverProvider(LANGUAGE_ID, {
            provideHover(model, position) {
                const line = model.getLineContent(position.lineNumber);
                let i = position.column - 1;
                if (i < 0 || i >= line.length) return null;
                let cp = line.codePointAt(i);
                // On the second half of a surrogate pair, step back onto the pair.
                if (i > 0 && cp >= 0xDC00 && cp <= 0xDFFF && line.codePointAt(i - 1) > 0xFFFF) {
                    i--;
                    cp = line.codePointAt(i);
                }
                if (cp < 0x80) return null;
                const range = {
                    startLineNumber: position.lineNumber, startColumn: i + 1,
                    endLineNumber: position.lineNumber, endColumn: i + 1 + (cp > 0xFFFF ? 2 : 1)
                };
                // In show-special mode Monaco marks invisible and look-alike characters and its
                // own hover explains them. One explanation is enough, so say nothing here.
                if (monacoMarks(model, range)) return null;
                return {
                    range,
                    contents: [{ value: tooltip(cp).replace('\n', '\n\n') }]
                };
            }
        });
        hoverReady = true;
    } catch (e) {
        console.warn('[log-viewer] hover provider not registered:', e);
    }
}

/**
 * Creates the viewer. Mount `.el`; call `update()` to show a page and when a
 * display option changes; call `dispose()` when done.
 *
 * update(patch, scroll): `patch` may hold
 *   text       display text (already converted by log-display.js)
 *   raw        the page text as the server sent it, which Copy gives
 *   firstLine  1-based number of the first line, or null for no line numbers
 *   target     { start, end, whole } character offsets of the jumped-to match, or null
 *   highlights [{ start, end }] character offsets of every search match on the page
 *   wrap       word wrap on or off
 *   special    show-special mode on or off
 * `scroll` is 'top', 'bottom' or 'keep'; a target is scrolled to the center
 * unless the scroll is 'keep'.
 *
 * The editor never shows the empty numbered line an editor adds after a final
 * line end (see withoutFinalLineEnd), so its last gutter number is the page's
 * last line.
 *
 * `onUpgrade` is called once when the textarea has been upgraded to Monaco, and
 * `onPlainText` once when Monaco has not arrived after UPGRADE_GIVE_UP_MS, so the
 * textarea stays. find() opens Monaco's find widget, or focuses the textarea (the
 * browser's own find then searches it); hasMonaco() says which of the two this is.
 */
export function createLogEditor(platform, onUpgrade, onPlainText) {
    ensureStyle();
    const editor = platform.createCodeEditor({ readOnly: true, language: 'text' });

    const state = { text: '', raw: '', firstLine: null, target: null, highlights: [], wrap: true, special: false };
    let decorations = null;
    let shownText = null;
    let languageSet = false;
    let disposed = false;
    let rafId = 0;
    let lastFallbackTarget = null;   // the same for the textarea: character offsets of the match
    let lastTargetRange = null;   // the match range last drawn, to tell whether the user's selection is on it
    let lastScroll = 'top';   // where the page was last asked to go, for the moment Monaco takes over

    /* ---- Monaco ---------------------------------------------------------- */

    function renderMonaco(scroll) {
        const m = editor.monaco;
        const model = m.getModel();
        if (!model) return;
        const monaco = window.monaco;

        if (monaco && !languageSet) {
            registerHover(monaco);
            if (hoverReady) {
                try { monaco.editor.setModelLanguage(model, LANGUAGE_ID); } catch (e) { /* plain text stays */ }
            }
            languageSet = true;
        }

        const first = state.firstLine;
        const widest = first == null ? 3 : String(first + lineCount(state.text)).length;
        m.updateOptions({
            wordWrap: state.wrap ? 'on' : 'off',
            lineNumbers: first == null ? 'off' : (n) => String(first + n - 1),
            lineNumbersMinChars: Math.max(3, widest),
            renderFinalNewline: 'off',
            renderWhitespace: state.special ? 'all' : 'none',
            renderControlCharacters: true,
            // The server counts a line end at LF, CRLF or a lone CR only. With 'off' Monaco
            // neither prompts about nor rewrites U+2028, U+2029 or U+0085; they stay in their line.
            unusualLineTerminators: 'off',
            // Monaco's own invisible and look-alike character marking, in show-special mode only.
            unicodeHighlight: {
                invisibleCharacters: state.special,
                ambiguousCharacters: state.special,
                nonBasicASCII: false
            },
            folding: false,
            glyphMargin: false,
            stickyScroll: { enabled: false }
        });

        const top = m.getScrollTop();
        // setValue empties the selection. If the selection was the matched text, put it back
        // on the match in the new text (the same match, now at different offsets).
        const before = m.getSelection();
        const onOldMatch = lastTargetRange !== null && before != null
            && before.startLineNumber === lastTargetRange.startLineNumber
            && before.startColumn === lastTargetRange.startColumn
            && before.endLineNumber === lastTargetRange.endLineNumber
            && before.endColumn === lastTargetRange.endColumn;
        const keepMatchSelected = shownText !== state.text && onOldMatch;
        if (shownText !== state.text) {
            editor.setValue(state.text);
            shownText = state.text;
        }

        const decs = [];
        const lane = monaco && monaco.editor.OverviewRulerLane ? monaco.editor.OverviewRulerLane : { Center: 2, Right: 4 };
        let targetRange = null;
        if (state.target) {
            const from = model.getPositionAt(state.target.start);
            const to = model.getPositionAt(state.target.end);
            targetRange = {
                startLineNumber: from.lineNumber, startColumn: from.column,
                endLineNumber: to.lineNumber, endColumn: to.column
            };
        }
        // Every match the engine found, lightly. The jumped-to match is drawn strongly below
        // instead of lightly, so it is not drawn twice.
        for (const hit of state.highlights) {
            if (state.target && !state.target.whole && hit.start === state.target.start && hit.end === state.target.end) continue;
            const a = model.getPositionAt(hit.start);
            const b = model.getPositionAt(hit.end);
            decs.push({
                range: { startLineNumber: a.lineNumber, startColumn: a.column, endLineNumber: b.lineNumber, endColumn: b.column },
                options: {
                    inlineClassName: 'lv-hl',
                    overviewRuler: { color: '#d9a400', position: lane.Center }
                }
            });
        }
        if (targetRange) {
            if (state.target.whole) {
                // Only the line could be found, not the match inside it: tint the line.
                decs.push({
                    range: { startLineNumber: targetRange.startLineNumber, startColumn: 1, endLineNumber: targetRange.startLineNumber, endColumn: 1 },
                    options: { isWholeLine: true, className: 'lv-line-hit' }
                });
            } else {
                decs.push({
                    range: targetRange,
                    options: { inlineClassName: 'lv-hit', overviewRuler: { color: '#ff6a00', position: lane.Right } }
                });
            }
        }
        lastTargetRange = targetRange;
        if (decorations) decorations.set(decs);
        else decorations = m.createDecorationsCollection(decs);

        // Wrapping is laid out after the value is set, so scroll on the next frame.
        cancelAnimationFrame(rafId);
        rafId = requestAnimationFrame(() => {
            if (disposed || !editor.monaco) return;
            const immediate = monaco && monaco.editor.ScrollType ? monaco.editor.ScrollType.Immediate : 1;
            if (scroll === 'keep') {
                if (keepMatchSelected && targetRange) m.setSelection(targetRange);
                // The jumped-to match is no longer marked (the results were closed): drop its selection too.
                if (onOldMatch && !targetRange) m.setPosition({ lineNumber: before.startLineNumber, column: before.startColumn });
                m.setScrollTop(top, immediate);
            } else if (targetRange) {
                m.setSelection(targetRange);
                m.revealRangeInCenter(targetRange, immediate);
            } else if (scroll === 'bottom') {
                m.setScrollTop(m.getScrollHeight(), immediate);
            } else {
                m.setScrollTop(0, immediate);
            }
        });
    }

    /* ---- textarea fallback ------------------------------------------------ */

    function renderFallback(scroll) {
        const area = editor.area;
        if (!area) return;
        const top = area.scrollTop;
        const textChanged = shownText !== state.text;
        // setValue moves the caret to the end; if the match was selected, select it again below.
        const onOldMatch = lastFallbackTarget !== null
            && area.selectionStart === lastFallbackTarget.start && area.selectionEnd === lastFallbackTarget.end;
        const keepMatchSelected = textChanged && onOldMatch;
        if (textChanged) {
            editor.setValue(state.text);
            shownText = state.text;
        }
        lastFallbackTarget = state.target ? { start: state.target.start, end: state.target.end } : null;
        area.style.whiteSpace = state.wrap ? 'pre-wrap' : 'pre';

        // The host's gutter counts from 1. Number it from the page's first line, and hide it
        // when there is no line count, or when wrapping would make it disagree with the lines.
        const gutter = editor.gutter;
        if (gutter) {
            if (state.firstLine != null && !state.wrap) {
                const n = lineCount(state.text);
                const numbers = new Array(n);
                for (let i = 0; i < n; i++) numbers[i] = String(state.firstLine + i);
                gutter.textContent = numbers.join('\n');
                // The host's gutter lets white space collapse, which put every number in one row.
                // Keep one number per line, and make the gutter as wide as its longest number.
                gutter.style.whiteSpace = 'pre';
                gutter.style.minWidth = (String(state.firstLine + n - 1).length + 1) + 'ch';
                gutter.style.paddingLeft = '7px';
                gutter.style.display = '';
            } else {
                gutter.style.display = 'none';
            }
        }

        if (scroll === 'keep') {
            if (keepMatchSelected && state.target) area.setSelectionRange(state.target.start, state.target.end);
            if (onOldMatch && !state.target) area.setSelectionRange(area.selectionStart, area.selectionStart);
            area.scrollTop = top;
        } else if (state.target) {
            let lineIndex = 0;
            for (let at = state.text.indexOf('\n'); at >= 0 && at < state.target.start; at = state.text.indexOf('\n', at + 1)) {
                lineIndex++;
            }
            const lineHeight = parseFloat(getComputedStyle(area).lineHeight) || 16;
            area.setSelectionRange(state.target.start, state.target.end);
            area.scrollTop = Math.max(0, lineIndex * lineHeight - area.clientHeight / 2);
        } else if (scroll === 'bottom') {
            area.scrollTop = area.scrollHeight;
        } else {
            area.scrollTop = 0;
        }
        // The host moves the gutter only on a scroll event, and none comes when the gutter
        // has just been shown or filled. Line it up with the text now.
        if (gutter) gutter.scrollTop = area.scrollTop;
    }

    function render(scroll) {
        if (disposed) return;
        if (editor.monaco) renderMonaco(scroll);
        else renderFallback(scroll);
    }

    /* ---- Copy ------------------------------------------------------------- */

    /*
     * The raw text of what is selected, or null to leave the copy to the browser or Monaco (the
     * copy is not from the text, or nothing is selected in the textarea). Monaco copies the whole
     * line when nothing is selected, so that is done here too, as the file has it.
     */
    function selectedRaw(target) {
        const m = editor.monaco;
        if (m) {
            const model = m.getModel();
            // Only the text itself: Monaco's find box has its own copy.
            if (!model || !m.hasTextFocus()) return null;
            const ranges = (m.getSelections() || []).filter((s) => !s.isEmpty())
                .map((s) => [model.getOffsetAt(s.getStartPosition()), model.getOffsetAt(s.getEndPosition())])
                .sort((a, b) => a[0] - b[0]);
            if (ranges.length === 0) {
                const p = m.getPosition();
                if (!p) return null;
                const lineEnd = p.lineNumber < model.getLineCount()
                    ? model.getOffsetAt({ lineNumber: p.lineNumber + 1, column: 1 }) : state.text.length;
                ranges.push([model.getOffsetAt({ lineNumber: p.lineNumber, column: 1 }), lineEnd]);
            }
            return ranges.map((r) => rawText(state.raw, state.text, r[0], r[1])).join('\n');
        }
        const area = editor.area;
        if (!area || target !== area || area.selectionStart === area.selectionEnd) return null;
        return rawText(state.raw, state.text, area.selectionStart, area.selectionEnd);
    }

    /* Ctrl+C and the context menu's Copy both fire a copy event inside the editor; this one runs first. */
    function onCopy(e) {
        const text = selectedRaw(e.target);
        if (text == null || !e.clipboardData) return;
        e.clipboardData.setData('text/plain', text);
        e.preventDefault();
        e.stopPropagation();
    }
    editor.el.addEventListener('copy', onCopy, true);

    /* The host upgrades the textarea to Monaco in place when Monaco loads. */
    const startedAt = Date.now();
    const poll = setInterval(() => {
        if (disposed) {
            clearInterval(poll);
        } else if (editor.monaco) {
            clearInterval(poll);
            shownText = null;   // the new editor started from the textarea's value; set it afresh
            render(lastScroll);
            if (onUpgrade) onUpgrade();
        } else if (Date.now() - startedAt > UPGRADE_GIVE_UP_MS) {
            clearInterval(poll);
            if (onPlainText) onPlainText();
        }
    }, UPGRADE_POLL_MS);

    return {
        el: editor.el,

        hasMonaco() {
            return !!editor.monaco;
        },

        find() {
            const m = editor.monaco;
            if (m) {
                m.focus();
                const action = m.getAction('actions.find');
                if (action) action.run();
            } else if (editor.area) {
                editor.area.focus();
            }
        },

        update(patch, scroll) {
            Object.assign(state, patch);
            if (patch.text != null) state.text = withoutFinalLineEnd(patch.text);
            if (scroll && scroll !== 'keep') lastScroll = scroll;
            render(scroll || 'keep');
        },

        dispose() {
            disposed = true;
            clearInterval(poll);
            editor.el.removeEventListener('copy', onCopy, true);
            cancelAnimationFrame(rafId);
            try { if (decorations) decorations.clear(); } catch (e) { /* the editor may already be gone */ }
            editor.dispose();
        }
    };
}
