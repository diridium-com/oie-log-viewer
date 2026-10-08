/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * What the viewer holds and when it moves: useViewer owns the one editor for
 * the life of the view, works out the text, line numbers, highlights and jump
 * target for the page on screen in the current mode, and pushes them into the
 * editor, choosing where it scrolls.
 */

import { platform } from '@oie/web-shell';
import { revealTarget, highlightParams, PLAIN_TEXT_NOTICE } from './log-core.js';
import { normal, special as specialText, mapHighlights } from './log-display.js';
import { createLogEditor } from './log-editor.js';

const React = platform.React;

/* The key that says which search a page's highlights belong to; '' for none. */
export function highlightKey(params) {
    return params.highlightQuery == null ? '' : JSON.stringify(params);
}

/*
 * `shown` is the page on screen ({ file, data, anchor, reveal, keepScroll, hlKey } or null);
 * `notice` and `setNotice` are the view's notice bar, where the viewer says once that Monaco did
 * not load. Returns the div ref the editor is mounted in, the editor ref (find, hasMonaco), and
 * `view`, what the viewer holds for that page: { text, raw, firstLine, highlights, highlightsStopped, target }.
 */
export function useViewer({ shown, special, wrap, resultsOpen, search, alive, notice, setNotice }) {
    const { useState, useEffect, useRef, useMemo } = React;
    const [, setTick] = useState(0);
    const [plainText, setPlainText] = useState(false);   // Monaco did not load: the textarea stays
    const plainTextTold = useRef(false);

    /* The viewer: one editor for the life of the view. */
    const viewerHost = useRef(null);
    const editorRef = useRef(null);
    const lastShown = useRef(null);
    useEffect(() => {
        // The tick redraws the toolbar when Monaco arrives, which changes the Find button's tooltip.
        const editor = createLogEditor(platform, () => { if (alive.current) setTick((t) => t + 1); },
            () => { if (alive.current) setPlainText(true); });
        editorRef.current = editor;
        viewerHost.current.appendChild(editor.el);
        return () => {
            editorRef.current = null;
            editor.dispose();
        };
    }, []);

    /* The key of the search whose highlights may be painted: only while the results are open. */
    const activeKey = resultsOpen ? highlightKey(highlightParams(search.params)) : '';

    /* What the viewer holds for the page on screen, in the current mode. */
    const view = useMemo(() => {
        const raw = shown ? shown.data.text : '';
        const text = special ? specialText(raw) : normal(raw);
        const first = shown ? shown.data.firstLineNumber : null;
        const numbered = first != null && first >= 1;
        const marked = shown !== null && activeKey !== '' && shown.hlKey === activeKey;
        return {
            text,
            raw,
            firstLine: numbered ? first : null,
            highlights: marked ? mapHighlights(raw, text, shown.data.highlights) : [],
            highlightsStopped: marked ? shown.data.highlightsStopped : '',
            target: shown && shown.reveal && resultsOpen
                ? revealTarget(raw, text, numbered ? first : null, shown.data.targetIndex, shown.reveal.match) : null
        };
    }, [shown, special, activeKey, resultsOpen]);

    useEffect(() => {
        const editor = editorRef.current;
        if (!editor) return;
        // A new page goes to its end or start; the same page in the other mode keeps its place.
        const newPage = lastShown.current !== shown;
        lastShown.current = shown;
        let scroll = 'keep';
        if (newPage && !(shown && shown.keepScroll)) {
            scroll = shown && (shown.anchor === 'TAIL' || shown.anchor === 'BEFORE') ? 'bottom' : 'top';
        }
        editor.update({
            text: view.text, raw: view.raw, firstLine: view.firstLine, target: view.target, highlights: view.highlights, special
        }, scroll);
    }, [view]);

    useEffect(() => {
        if (editorRef.current) editorRef.current.update({ wrap }, 'keep');
    }, [wrap]);

    /* Once, when Monaco did not load: said in the notice bar when no other notice is showing there. */
    useEffect(() => {
        if (plainText && !plainTextTold.current && notice === null) {
            plainTextTold.current = true;
            setNotice({ message: PLAIN_TEXT_NOTICE, refresh: false });
        }
    }, [plainText, notice]);

    return { viewerHost, editorRef, view };
}
