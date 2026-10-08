# OIE Log Viewer

A plugin for Open Integration Engine that lets administrators **view, page through, search and
download the engine's `mirth.log` and its rotated archives** from the Administrator, without OS
access to the engine host. Works in both the Swing Administrator and the OIE Web Administrator.

## Features

- Finds the logs from the engine's own log4j configuration: the active `mirth.log` and its rotated
  `.zip` or `.gz` archives, newest first, including archives kept in folders named by date. No
  paths to type.
- Pages of 1,000 lines with line numbers. The active file opens on its newest lines, an archive on
  its first.
- Line ends shown as written, so HL7 segments separated by a lone CR are never run together. Word
  wrap and a special-characters view, and copying gives the text with its line ends as written.
- Search all files: matching lines are counted per file first, then shown file by file. Plain text
  or Java regular expressions, run on the engine within fixed limits.
- Find on this page, download any file as it is on disk, and times shown in engine time.

## Permissions

Log files can contain patient data from any channel, so the plugin publishes two permissions,
**View Log Files** and **Download Log Files**, for use with a role-based authorization controller.
Roles limited to specific channels are refused. Every request is recorded in the engine's event
log.

## Requirements

- Open Integration Engine **4.6.0**.
- An engine restart after install to activate the plugin.
- For the web administrator: the **Web Support** plugin.

## Installing

Install from the Community Store, then **restart the engine**. The viewer then appears in the
Swing Administrator's **Other** pane as **View Log Files**, and in the web administrator as
**Log Files** under Monitor.

See the [project README](https://github.com/diridium-com/oie-log-viewer#readme) for permissions,
limits and building from source.

## Credits

Built by Diridium Technologies Inc. on the OIE Web Administrator plugin framework by Chris Gibson.
Published under the MPL-2.0 license.
