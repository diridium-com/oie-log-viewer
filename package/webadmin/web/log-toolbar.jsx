/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The one toolbar above the viewer: the Files toggle and file name, the four
 * page buttons, the Word wrap and Show special characters checkboxes, and the
 * Find on this page and Search all files buttons. It only draws.
 */

import { platform } from '@oie/web-shell';
import { SNAPSHOT_END_TIP } from './log-core.js';

const React = platform.React;

/*
 * `nav` says which page buttons are on, and whether Next and Last are off at the end of the active
 * file's snapshot (navState in log-core.js); `gone` is the gone state
 * ({ file } or null) and goneTip the tooltip the page buttons then carry; `fileName` is the name of
 * the file shown, null for none; `monaco` is whether
 * the editor's own find widget exists, which changes the Find button's tooltip.
 */
export function LogToolbar({
    filesHidden, fileName, nav, gone, goneTip, wrap, special, monaco,
    onToggleFiles, onNavigate, onWrap, onSpecial, onFind, onSearchAll
}) {
    return (
        <div className="lv-toolbar">
            <div className="lv-where">
                <button className="btn lv-toggle" aria-pressed={!filesHidden}
                    onClick={onToggleFiles}
                    title="Shows or hides the list of log files.">Files</button>
                <span className="lv-file-name"
                    title={fileName == null ? 'The file shown in the viewer.' : 'The file shown in the viewer: ' + fileName + '.'}>
                    {fileName == null ? 'No file open' : fileName}
                </span>
            </div>
            <div className="lv-group">
                <button className="btn" disabled={!nav.first} onClick={() => onNavigate('HEAD')}
                    title={gone ? goneTip : 'Shows the first page of the file. A page is 1,000 lines, fewer when lines are very long.'}>First page</button>
                <button className="btn" disabled={!nav.previous} onClick={() => onNavigate('BEFORE')}
                    title={gone ? goneTip : 'Shows the page before this one. A page is 1,000 lines, fewer when lines are very long.'}>Previous page</button>
                <button className="btn" disabled={!nav.next} onClick={() => onNavigate('AFTER')}
                    title={gone ? goneTip : nav.snapshotEnd ? SNAPSHOT_END_TIP
                        : 'Shows the page after this one. A page is 1,000 lines, fewer when lines are very long.'}>Next page</button>
                <button className="btn" disabled={!nav.last} onClick={() => onNavigate('TAIL')}
                    title={gone ? goneTip : nav.snapshotEnd ? SNAPSHOT_END_TIP
                        : 'Shows the last page of the file. A page is 1,000 lines, fewer when lines are very long.'}>Last page</button>
            </div>
            <div className="lv-group">
                <label className="check" title="Wraps long lines at the edge of the viewer. Off, long lines scroll sideways.">
                    <input type="checkbox" checked={wrap} onChange={(e) => onWrap(e.target.checked)}
                        title="Wraps long lines at the edge of the viewer. Off, long lines scroll sideways." />
                    Word wrap
                </label>
                <label className="check"
                    title="Shows line ends, tabs and spaces, and marks characters that are invisible or easy to confuse with others.">
                    <input type="checkbox" checked={special} onChange={(e) => onSpecial(e.target.checked)}
                        title="Shows line ends, tabs and spaces, and marks characters that are invisible or easy to confuse with others." />
                    Show special characters
                </label>
            </div>
            <div className="lv-group lv-group-right">
                <button className="btn" onClick={onFind}
                    title={'Finds text in the page shown (up to 1,000 lines), in this window. To search every log file on the engine, use Search all files...'
                        + (monaco ? '' : ' Use your browser\'s Find (Ctrl+F).')}>
                    Find on this page...
                </button>
                <button className="btn" onClick={onSearchAll}
                    title="Opens a window to search the log files for text or a regular expression. The engine runs the search.">
                    Search all files...
                </button>
            </div>
        </div>
    );
}
