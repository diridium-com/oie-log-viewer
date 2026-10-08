# OIE Log Viewer

View, page through, search and download the engine's `mirth.log` and its rotated archives from
the Administrator, without OS access to the engine host. Works in both the Swing Administrator and
the OIE Web Administrator.

## Features

- **Finds the logs itself** from the engine's own log4j configuration: the active `mirth.log` and
  its rotated `.zip` or `.gz` archives, newest first, including archives kept in folders named by
  date. Nobody types a path, and the engine never accepts one from a client.
- **Pages of 1,000 lines** with line numbers and a position line such as "Lines 2,001-3,000 of
  11,905". The active file opens on its newest lines, an archive on its first.
- **Line ends as written.** LF, CRLF and the lone CR between HL7 segments each end a line, so
  segments are never run together. Word wrap and a special-characters view are a click away, and
  control characters and unreadable bytes show as visible symbols. Copying from the viewer gives
  the text with its line ends as written.
- **Search all files** counts the matching lines in each file first, then shows a file's lines
  when you open it, 1,000 at a time. Plain text or Java regular expressions, matched one line at a
  time on the engine, with the matches highlighted on the page shown.
- **Find on this page** for a quick look within the lines on screen.
- **Download** any file exactly as it is on disk; an archive downloads as its zip.
- **Engine time.** Times are shown in the engine's time zone, and the viewer says which.
- **Clear about rotation.** The active file is a snapshot until you load the latest lines, and if
  a rollover renames or removes the file you are reading, the viewer says so instead of quietly
  showing a different file.

## Where to find it

- Swing Administrator: **Other** task pane, **View Log Files**.
- Web Administrator: **Log Files** under Monitor.

## Permissions and auditing

Log files are server-wide and can contain patient data from any channel, so the plugin has
permissions of its own:

| Permission | Allows |
|---|---|
| View Log Files | Listing, reading and searching log files |
| Download Log Files | Downloading whole log files |

On a stock OIE install the default authorization controller allows every operation for every
authenticated user, so both are effectively granted to everyone, as with the rest of the
Administrator. With a role-based authorization controller, such as
[Role Based Access Control](https://github.com/diridium-com/role-based-access-control), grant them
per role: both administrators hide the entry from roles without View Log Files and the Download
button from roles without Download Log Files, and the engine refuses the operations either way.
Roles limited to specific channels are refused outright, because a log file cannot be filtered by
channel reliably.

Every request is recorded in the engine's event log with its parameters (the file, the position,
the search text). Search text is recorded word for word, so anyone who can view events can read
it, and it travels in the request URL, where an HTTP access log on a proxy in front of the engine
would record it too.

## Limits

Searches run on the engine within fixed bounds, so a broad or badly written pattern cannot tie it
up: at most two searches and four page reads at once across the engine, 15 seconds per request (a
count stopped by the limit offers "Count the rest"), and 1,000 matches per batch. A page is at most 256 KB, so a
stretch of very long lines makes a shorter page. Line numbers are given for pages within the first
64 MB of a log file, and the total line count for files up to 64 MB; an archive is numbered
throughout.

## Requirements

| Attribute | Value |
|-----------|-------|
| OIE version | 4.6.0 (the engine loads an extension only on the version it was built for) |
| Java | 17 |
| Web Administrator | The [Web Support](https://github.com/gibson9583/oie-web-support-plugin) plugin, which serves extension UIs from the engine |

## Installation

Install it from the Community Store, or download the zip from the
[Releases](https://github.com/diridium-com/oie-log-viewer/releases) page and install it through
the Administrator's Extensions view. Restart the engine to activate it.

## Design Notes

[docs/design-notes.md](docs/design-notes.md) explains why parts of the plugin are shaped the way
they are, including choices that can look like gaps.

## Building from Source

```
./scripts/install-engine-jars.sh
mvn -B -ntp clean package
```

The script installs the four OIE 4.6.0 engine jars the plugin compiles against into your local
Maven repository, taken from the published OIE 4.6.0 distribution and cached under
`~/.cache/oie-dist`. The first build downloads Node.js to build the web administrator UI. The
installable zip is `package/target/oie-log-viewer-<version>.zip`.

## Credits

The web UI is built on the OIE Web Administrator and its plugin framework by Chris Gibson.

## License

[MPL-2.0](LICENSE). Copyright (c) 2026 Diridium Technologies Inc. Developed with the moral support
of Finnegan the dog.
