# Release checks

The build tests the engine code and the viewers' logic, but not the two user interfaces against a
running engine (see [design-notes.md](design-notes.md), "What the tests do not cover"). Run these by
hand before each release, on a test engine of the release's OIE version, in **both** the Swing
Administrator and the web administrator unless a line says otherwise. Each check names what you
should see.

## Setup

- A test engine with the release zip installed, the Web Support plugin, and a role-based
  authorization controller (such as Role Based Access Control) with four users: no log
  permissions; View Log Files only; View and Download Log Files; View and Download but limited to
  one channel.
- Logs worth reading: `scripts/make-test-logs.py --dir <a scratch logs directory>` makes an active
  file and archives with stack traces, HL7 (lone CR line ends) and long lines. It overwrites
  `mirth.log` and `mirth.log.N.zip` there, so never point it at logs that matter.
- A browser window of 1280x650 for the web administrator, and a full-size one.

## Opening and paging

- The active file opens on its last page, an archive on its first. The status line shows a
  position such as "Lines 2,001-3,000 of 11,905", the charset, the size and the engine's time zone.
- First, Previous, Next and Last page move one page at a time with no gap or overlap between pages.
- At the end of the active file, Next page and Last page are off, with the tooltip "This is the end
  of the snapshot. Load latest lines shows anything written since." Load latest lines shows lines
  written since, and the snapshot time changes.
- Word wrap and Show special characters change only the display. HL7 segments separated by a lone
  CR show on separate lines; special characters show each line's terminator.
- Copy a selection with CRLF and lone-CR lines into a hex viewer or editor: the bytes are the
  file's own, with no CR/LF labels, in both display modes.

## Searching

- Search all files with plain text: one collapsed group per file with its count. Opening a group
  shows its lines, 1,000 at a time, with a row to load more. A line opens its file at the match,
  and the matches on the page are highlighted.
- The same with a Java regular expression (`ERROR|WARN`). The dialog says the expressions are
  Java's.
- A search for a backtracking pattern, `(.*a){10}x`, on a file with long lines stops at its time
  limit and offers Count the rest; where it cannot get further, it says "The search can't get past
  this point within its time limit (a very long line or a complex pattern). Try a simpler pattern."
- Highlighting: a search matching nearly every character shows "Only the first 5,000 matches on
  this page are highlighted."
- Find on this page: next, previous and Mark all, plain and regular expression. Swing: an invalid
  expression says "Not a valid regular expression."; `(.*a){10}x` on a long line says "The
  expression took too long on this page." within about 2 seconds, and the Administrator stays
  responsive.
- Busy: start three searches at once (two browser tabs and the Swing viewer): the third says "Two
  log searches are already running on the engine, which is as many as it allows at once. A search
  can take up to 15 seconds. Try again when one finishes."

## Downloading

- Download an archive and the active file. The archive's SHA-256 equals the file on the engine;
  the active file equals the file's first bytes as of the download. Swing shows progress against
  the size the engine sends.
- An archive in a date folder (`2026-10-06/mirth.log.3.zip`) is saved as
  `2026-10-06_mirth.log.3.zip`.
- Truncate the active file on the engine while a large download of it runs (copy-and-truncate):
  both viewers report a failed download, and no partial file is kept.
- Swing: Cancel mid-way leaves no file and no error; closing the viewer during a download asks
  "A download is in progress. Cancel it and close?"; a file named `<name>.part` already in the
  folder is left alone.

## Rotation

- Open an archive, then rename two archives on the engine (a simulated rollover) and press Refresh
  list: the viewer says the file no longer exists under that name, keeps the page, turns paging and
  Download off, and offers to open the current file of that name.
- The same reached through Next page instead of Refresh list.
- A result group whose file has rotated says so in its last row.

## Archives in date folders

- With a rollover pattern such as `${log.dir}/$${date:yyyy-MM-dd-HHmm}/mirth.log.%i.zip` and a small
  size limit, let the engine roll over across a few minutes. The list shows each archive by its
  folder and name, newest first; a folder of another name or shape beside them is not listed; each
  archive pages, searches and downloads.

## Permissions

- No log permissions: no Log Files entry in either administrator.
- View only: the viewer opens, Download is not offered.
- View and Download: both work.
- Limited to one channel: the viewer says "You do not have access to log files. Your role needs the
  View Log Files permission and must not be limited to specific channels."

- Each request appears in the engine's event log with its parameters.

## Layout

- Web administrator at 1280x650 and full size: nothing outside the window, no horizontal page
  scroll; long names (date folders) are cut with an ellipsis and shown whole in the tooltip.
- Swing at a small window size: the toolbar and the results title keep their buttons inside the
  window.
- Swing: open the viewer right after signing in, a few times. It opens where it was last closed
  (centred the first time), never in the screen's top-left corner. (Seen only on a test display
  without a window manager so far.)
