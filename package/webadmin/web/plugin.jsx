/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * OIE Log Viewer - web administrator plugin entry. Registrations only; the
 * view is in log-view.jsx and its logic in log-core.js and log-display.js.
 */

import { LogViewerView } from './log-view.jsx';
import { TASK_VIEW } from './log-core.js';

export function register(platform) {
    // The left-rail entry carries the task the server registers with the View Log Files
    // permission, so role-based access control hides it from roles without it. That only
    // hides the entry: the server enforces the permission on every request.
    platform.registerNavItem({
        id: 'log-files',
        label: 'Log Files',
        icon: 'file',
        path: '/log-files',
        section: 'Monitor',
        order: 4,
        task: TASK_VIEW
    });
    platform.registerView('/log-files', platform.reactView(LogViewerView), { title: 'Log Files' });
}
