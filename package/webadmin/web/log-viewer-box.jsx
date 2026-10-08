/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The viewer and what sits around it: the snapshot strip with Load latest lines,
 * the mid-line note, the box the editor is mounted in, and the viewer status
 * line under it (where the page sits in its file; what is happening and the
 * facts about the file).
 */

import { platform } from '@oie/web-shell';
import {
    SEPARATOR, bytes, positionLabel, midLineNotes, zoneText, snapshotText, highlightStopNote
} from './log-core.js';
import { textLineCount } from './log-display.js';

const React = platform.React;

/*
 * `shown` is the page on screen (or null) and `view` what the viewer holds for it (useViewer);
 * `hostRef` is the div the editor is mounted in; `monaco` whether the editor is Monaco, the only
 * one that paints highlights.
 */
export function LogViewerBox({ shown, view, pageBusy, resultsOpen, gone, goneTip, hostRef, monaco, onLoadLatest }) {
    const lines = textLineCount(view.text);
    const position = shown
        ? positionLabel(view.firstLine, lines, shown.data.totalLines, shown.data.startOffset, shown.data.endOffset,
            shown.data.contentLength, shown.data.atEnd)
        : '';
    const midLine = shown ? midLineNotes(shown.data.startsMidLine, shown.data.endsMidLine) : '';
    // The right of the viewer status line: what is happening, then the facts about the file.
    const happening = [
        pageBusy ? 'Loading...' : '',
        monaco ? highlightStopNote(view.highlightsStopped) : '',
        shown && shown.reveal && resultsOpen && !view.target ? 'The match could not be located on this page.' : ''
    ];
    // An uncompressed file's size as of the page, which "Load latest lines" keeps current;
    // the list's size, read when the list was, would lag behind the active file.
    const facts = shown ? [
        shown.file.charset,
        bytes(!shown.file.compressed && shown.data.contentLength != null ? shown.data.contentLength : shown.file.size),
        zoneText(shown.file)
    ] : [];
    const statusRight = happening.concat(facts).filter((part) => part !== '').join(SEPARATOR);
    const snapshot = shown && shown.file.active ? snapshotText(shown.file, shown.data.readAt) : '';

    return (
        <>
            {snapshot !== '' && (
                <div className="lv-strip">
                    <span className="lv-grow" title="The active file keeps growing, but this page was read once and stays as it was read.">{snapshot}</span>
                    <button className="btn btn-sm" disabled={pageBusy || gone !== null} onClick={onLoadLatest}
                        title={gone ? goneTip : 'Re-reads the end of ' + shown.file.name + ' now, so lines written since the snapshot appear.'}>
                        Load latest lines
                    </button>
                </div>
            )}
            {midLine !== '' && <div className="lv-notes lv-faint">{midLine}</div>}

            <div className="lv-viewbox">
                <div className="lv-viewer" ref={hostRef} />
                <div className="lv-statusbar">
                    <span className="lv-status-left" title={position === '' ? 'Where the page shown sits in its file.' : 'Where the page shown sits in its file: ' + position}>{position}</span>
                    <span className="lv-status-right" title={statusRight}>{statusRight}</span>
                </div>
            </div>
        </>
    );
}
