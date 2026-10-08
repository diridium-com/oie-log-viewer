/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The narrow file list on the left: the Refresh list and Download buttons, the
 * download progress row, the list warnings and the files themselves. It only
 * draws; what a click does is decided by the view.
 */

import { platform } from '@oie/web-shell';
import { bytes, modifiedText } from './log-core.js';

const React = platform.React;

/*
 * A file's name in the list. An archive kept in a date folder is named with its folder
 * ("2026-10-06-1630/mirth.log.5.zip"); when the name does not fit, the folder is cut short
 * first, from its start ("...06-1630/mirth.log.5.zip"): the end of a date folder is what tells
 * folders apart. The row's tooltip holds the whole name.
 */
function FileName({ name, active }) {
    const slash = name.lastIndexOf('/');
    return (
        <span className="lv-name">
            {slash >= 0 && <span className="lv-name-dir"><bdi dir="ltr">{name.substring(0, slash + 1)}</bdi></span>}
            <span className="lv-name-base">{name.substring(slash + 1)}</span>
            {active && <span className="lv-tag">active</span>}
        </span>
    );
}

/*
 * `current` is the selected file, `gone` the gone state ({ file } or null), `filesLocked` true
 * while a list or page request runs. canDownload is the Download permission.
 */
export function LogFilePane({
    files, listLoaded, listBusy, listWarnings, current, gone, goneTip, filesLocked,
    canDownload, downloading, downloadNote,
    onRefresh, onDownload, onCancelDownload, onOpenFile
}) {
    return (
        <div className="lv-side">
            <div className="lv-side-head">
                <span title="The log files on the engine, the active file first, then the archives newest first.">Log files</span>
                <div className="lv-side-buttons">
                    <button className="btn btn-sm" disabled={filesLocked} onClick={onRefresh}
                        title="Re-reads which log files exist and their sizes. Does not change what is open.">Refresh list</button>
                    {canDownload && (
                        <button className="btn btn-sm" disabled={!current || downloading !== null || gone !== null} onClick={onDownload}
                            title={gone ? goneTip : 'Saves the open file as it is on disk. An archive is saved as the zip file it is.'}>Download</button>
                    )}
                </div>
            </div>
            {(downloading !== null || downloadNote !== '') && (
                <div className="lv-side-download lv-faint">
                    <span className="lv-grow lv-ellipsis"
                        title={downloading !== null ? 'Downloading ' + downloading + '...' : downloadNote}>
                        {downloading !== null ? 'Downloading ' + downloading + '...' : downloadNote}
                    </span>
                    {downloading !== null && (
                        <button className="btn btn-ghost btn-sm" onClick={onCancelDownload}
                            title="Stops the download.">Cancel</button>
                    )}
                </div>
            )}
            {listWarnings.length > 0 && (
                <div className="lv-side-warn lv-faint">
                    {'Not every log file could be listed: ' + listWarnings.join('; ')}
                </div>
            )}
            <div className="lv-files">
                <table className="dt" aria-label="Log files">
                    <colgroup>
                        <col style={{ width: '62%' }} />
                        <col style={{ width: '38%' }} />
                    </colgroup>
                    <tbody>
                        {files.map((f) => {
                            const classes = [];
                            if (!f.viewable) classes.push('lv-off');
                            if (current && current.id === f.id && !gone) classes.push('selected');
                            if (filesLocked) classes.push('lv-busy');
                            const tip = f.name + (f.active ? ' (the active file)' : '') + '\n'
                                + bytes(f.size) + '\nModified ' + modifiedText(f)
                                + (f.note ? '\n' + f.note : '') + '\nClick to open it.';
                            return (
                                <tr key={f.id} className={classes.join(' ')} tabIndex={0} title={tip}
                                    onClick={() => { if (!filesLocked) onOpenFile(f); }}
                                    onKeyDown={(e) => { if (e.key === 'Enter' && !filesLocked) onOpenFile(f); }}>
                                    <td><FileName name={f.name} active={f.active} /></td>
                                    <td className="num">{bytes(f.size)}</td>
                                </tr>
                            );
                        })}
                        {listLoaded && files.length === 0 && (
                            <tr><td colSpan={2} className="lv-faint">No log files were found.</td></tr>
                        )}
                        {!listLoaded && (
                            <tr><td colSpan={2} className="lv-faint">{listBusy ? 'Loading...' : ''}</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
