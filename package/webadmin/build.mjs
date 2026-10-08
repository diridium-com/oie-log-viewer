/*
 * SPDX-License-Identifier: MPL-2.0
 * Copyright (c) 2026 Diridium Technologies Inc.
 *
 * Build the OIE Log Viewer web administrator plugin: compiles web/plugin.jsx
 * (and the modules it imports) to the single plain-ESM file plugin.json points
 * to (web/plugin.js). The browser cannot run JSX.
 *
 * The Maven build runs this before packaging, so web/plugin.js is a build
 * artifact and is not committed.
 *
 * The @oie/* packages stay EXTERNAL: the host's import map resolves them at
 * runtime, and React comes from platform.React. Never bundle either.
 */

import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));

await build({
    entryPoints: [resolve(here, 'web/plugin.jsx')],
    outfile: resolve(here, 'web/plugin.js'),
    bundle: true,
    format: 'esm',
    target: 'es2022',
    jsx: 'transform',
    jsxFactory: 'React.createElement',
    jsxFragment: 'React.Fragment',
    // Keep the output pure ASCII so it survives any transport or editor
    // (non-ASCII characters in sources are written as escapes anyway).
    charset: 'ascii',
    external: ['@oie/web-api', '@oie/web-ui', '@oie/web-shell']
});

console.log('built web/plugin.js');
