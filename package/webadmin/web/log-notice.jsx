/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * The message bar at the top of the view: what went wrong or changed, with the
 * buttons that go with it (open the file now holding a name, Refresh, Dismiss).
 */

import { platform } from '@oie/web-shell';

const React = platform.React;

/* `notice` is { message, refresh, action }; action is { label, file } or null. */
export function LogNotice({ notice, listBusy, onOpenFile, onRefresh, onDismiss }) {
    return (
        <div className="lv-notice" role="alert">
            <span className="lv-notice-text">{notice.message}</span>
            {notice.action && (
                <button className="btn" onClick={() => onOpenFile(notice.action.file)}
                    title={'Opens ' + notice.action.file.name + ' as it is listed now.'}>{notice.action.label}</button>
            )}
            {notice.refresh && (
                <button className="btn" disabled={listBusy} onClick={onRefresh}
                    title="Re-read the list of log files and reopen this file under its current name.">Refresh</button>
            )}
            <button className="btn btn-ghost btn-sm" onClick={onDismiss}
                title="Hide this message.">Dismiss</button>
        </div>
    );
}
