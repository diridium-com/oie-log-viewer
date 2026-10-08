/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The plugin's own styles. They sit on top of the host's component classes
 * (.btn, .dt, .check ...), so colors come from the host's design tokens and
 * follow its light and dark themes. Everything is prefixed lv- so nothing here
 * can touch a host element.
 *
 * The view fills the height the host gives it and never scrolls as a whole:
 * the file list, the viewer and the results panel each scroll on their own.
 * Sizes are relative to the viewport (min() against vh), never fixed pixels:
 * the target is 1920 by 1080 and it must stay usable at about 1280 by 650.
 */

const STYLE_ID = 'lv-style';

const CSS = `
.lv-root { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; overflow: hidden; min-height: 0; }

/* A notice sits above the layout, so it is always in view. The tint is laid over a solid
   color so the text under it does not show through. */
.lv-notice {
    flex: none; display: flex; align-items: center; gap: 10px;
    padding: 8px 12px;
    border: 1px solid color-mix(in srgb, var(--warn) 45%, transparent);
    background:
        linear-gradient(color-mix(in srgb, var(--warn) 12%, transparent), color-mix(in srgb, var(--warn) 12%, transparent)),
        var(--bg1);
    border-radius: var(--radius); font-size: 12px;
}
.lv-notice-text { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.lv-notice .btn { flex: none; }

.lv-main { flex: 1; min-height: 0; display: flex; gap: 10px; }

/* Left: the narrow file list. */
.lv-side {
    flex: none; width: clamp(190px, 17vw, 270px); min-height: 0;
    display: flex; flex-direction: column;
    border: 1px solid var(--line); border-radius: var(--radius); background: var(--bg1);
}
.lv-side-head { flex: none; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; padding: 7px 8px; border-bottom: 1px solid var(--line); font-weight: 600; font-size: 12px; }
.lv-side-buttons { margin-left: auto; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 4px 6px; }
/* The download's progress and Cancel, under the buttons; long text is cut, its title holds it all. */
.lv-side-download { flex: none; display: flex; align-items: center; gap: 6px; padding: 4px 8px; border-bottom: 1px solid var(--line); }
.lv-side-warn { flex: none; padding: 6px 8px; border-bottom: 1px solid var(--line); }
.lv-files { flex: 1; min-height: 0; overflow: auto; }
.lv-files table.dt { table-layout: fixed; }
.lv-files td, .lv-files th { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lv-files tbody tr { cursor: pointer; }
.lv-files tbody tr.lv-off td { color: var(--text-faint); }
.lv-files tbody tr.lv-busy { cursor: progress; }
.lv-tag { flex: none; margin-left: 6px; font-size: 10px; color: var(--accent); }
/* A name with a folder gives way at the folder: only the folder shrinks, from its start (right
   to left, so the ellipsis is at its start; the folder text itself stays left to right in its
   bdi), down to its last characters. The file part keeps its width (a shrink of a fraction of a
   pixel would already cost it its last characters to the ellipsis) unless even it does not fit
   beside them. */
.lv-name { display: flex; align-items: baseline; min-width: 0; }
.lv-name > .lv-name-dir, .lv-name > .lv-name-base { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lv-name > .lv-name-dir { flex: 0 1 auto; min-width: 2ch; direction: rtl; }
.lv-name > .lv-name-base { flex: none; max-width: calc(100% - 2ch); }

/* Right: one toolbar, the viewer, the results. */
.lv-pane { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; gap: 6px; }
.lv-toolbar { flex: none; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; }
/* The file name has a row of its own, after the button that hides the file list, and is never cut off: it wraps instead. */
.lv-toolbar .lv-where { flex: 1 1 100%; min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 2px 10px; }
.lv-file-name { flex: none; font-weight: 600; font-size: 12px; overflow-wrap: anywhere; }
.btn.lv-toggle[aria-pressed="true"] { background: var(--accent-glow); border-color: var(--accent); }
.lv-group { display: flex; align-items: center; gap: 6px; }
/* The two searches sit together at the far right; when the toolbar wraps they stay one group. */
.lv-group.lv-group-right { margin-left: auto; }
.lv-faint { color: var(--text-faint); font-size: 11px; }
.lv-ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* The viewer status line, directly under the editor: the position on the left, what is happening
   and the file facts on the right. Long text is cut with an ellipsis (its title holds all of it),
   never pushed off screen. The results status line below uses the same rules. */
.lv-statusbar {
    flex: none; display: flex; align-items: center; gap: 16px; padding: 3px 8px;
    border-top: 1px solid var(--line); font-size: 11px; color: var(--text-dim);
}
.lv-statusbar > span, .lv-results-foot > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lv-statusbar .lv-status-left { flex: 0 1 auto; font-weight: 600; }
.lv-statusbar .lv-status-right { flex: 1 1 0; text-align: right; }
.lv-results-foot {
    flex: none; display: flex; align-items: center; gap: 16px; padding: 3px 8px;
    border-top: 1px solid var(--line); font-size: 11px; color: var(--text-dim);
}
.lv-results-foot .lv-status-left { flex: 0 1 auto; }
.lv-results-foot .lv-status-right { flex: 1 1 0; text-align: right; }

/* Tinted with the theme's accent, as the host tints a selected row, so it stands out in light and
   dark themes alike (the Swing viewer's strip is light blue). */
.lv-strip {
    flex: none; display: flex; align-items: center; gap: 10px; padding: 5px 10px;
    border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent); border-radius: var(--radius);
    background: var(--accent-glow); font-size: 11px;
}
.lv-strip .lv-grow { flex: 1; min-width: 0; }
.lv-grow { flex: 1 1 auto; min-width: 0; }
.lv-notes { flex: none; }

/* The editor and its status line move together, so the results panel's divider is below both. */
.lv-viewbox { flex: 1 1 0; min-height: 120px; display: flex; flex-direction: column; }
/* Nothing here clips, so the tooltips of Monaco's find widget, which open above it, show in full
   over the toolbar. The editor box is sized by the viewer alone (no minimum of its own), so it
   never spills onto the results panel; the host's editor box clips by default, and that is undone
   for Monaco only (Monaco clips its own text inside .overflow-guard). */
.lv-viewer { flex: 1 1 0; min-height: 80px; display: flex; flex-direction: column; }
.lv-viewer > .ce { flex: 1; min-height: 0; }
.lv-viewer > .ce.ce-monaco { min-height: 0; overflow: visible; }

/* The results dock under the viewer once a search has run. */
.lv-results {
    flex: none; height: min(34vh, 340px); min-height: 100px; display: flex; flex-direction: column;
    border: 1px solid var(--line); border-radius: var(--radius); background: var(--bg1);
}
.lv-results-head { flex: none; display: flex; flex-wrap: wrap; align-items: center; gap: 4px 10px; padding: 5px 8px; border-bottom: 1px solid var(--line); }
.lv-results-head .lv-count-head { flex: 1 1 240px; min-width: 0; font-size: 12px; overflow-wrap: anywhere; }
/* In place of Count the rest when carrying on gets nowhere. */
.lv-results-head .lv-stuck { flex: 1 1 240px; min-width: 0; font-size: 11px; color: var(--text-dim); }
/* The search the results are for, on one line of its own. Only the search text gives way when
   the line is short of room (it can be a 1,000-character JSON blob), so the scope and options
   always show; the tooltip and a click show the text whole. */
.lv-search-title {
    flex: 1 1 100%; min-width: 0; display: flex; align-items: baseline; white-space: nowrap;
    padding: 0; border: 0; background: none; color: inherit; font: inherit; font-size: 12px; font-weight: 600;
    text-align: left; cursor: pointer;
}
.lv-search-title:hover .lv-search-query { text-decoration: underline; }
.lv-search-title > span { flex: none; }
.lv-search-title > .lv-search-query { flex: 0 1 auto; min-width: 3ch; overflow: hidden; text-overflow: ellipsis; }
.lv-results-body { flex: 1; min-height: 0; overflow: auto; }
.lv-group-head {
    display: flex; align-items: center; gap: 6px; padding: 3px 8px; cursor: pointer;
    background: var(--bg2); font-weight: 600; font-size: 11px; position: sticky; top: 0;
    border-bottom: 1px solid var(--line);
}
.lv-group-head:hover { background: var(--accent-glow); }
.lv-hit-row { display: flex; gap: 10px; padding: 2px 8px 2px 22px; cursor: pointer; font-family: var(--font-mono); font-size: 11px; line-height: 1.5; }
.lv-hit-row:hover { background: var(--accent-glow); }
.lv-hit-row.selected { background: var(--accent-glow); outline: 1px solid color-mix(in srgb, var(--accent) 35%, transparent); outline-offset: -1px; }
.lv-more-row { padding: 3px 8px 3px 22px; font-size: 11px; color: var(--text-faint); }
.lv-more-row.lv-retry { cursor: pointer; }
.lv-hit-line { flex: none; min-width: 6ch; text-align: right; color: var(--text-faint); }
.lv-hit-text { flex: 1; min-width: 0; white-space: pre; overflow: hidden; text-overflow: ellipsis; }
.lv-results mark { background: #ffe066; color: #000; border-radius: 2px; }

/* The search dialog. */
.lv-dialog { display: flex; flex-direction: column; gap: 12px; min-width: min(460px, 80vw); }
.lv-dialog-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.lv-dialog-row input[type="text"] { flex: 1; min-width: 0; width: 100%; }
.lv-dialog-note { color: var(--text); }
.lv-dialog-foot { display: flex; justify-content: flex-end; gap: 8px; }

/* Decorations inside the editor. Translucent, so they read on both themes. The jumped-to
   match is solid and bold; the others are a light wash. */
.monaco-editor .lv-line-hit { background: rgba(66, 135, 245, 0.22); }
.monaco-editor .lv-hl { background: rgba(255, 214, 64, 0.38); border-radius: 2px; }
.monaco-editor .lv-hit { background: rgba(255, 140, 0, 0.85); color: #000 !important; border-radius: 2px; outline: 1px solid #b85c00; }
`;

/** Adds the stylesheet once; safe to call on every mount. */
export function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = CSS;
    document.head.appendChild(style);
}
