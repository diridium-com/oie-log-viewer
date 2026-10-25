# Design notes

Why the plugin is shaped the way it is, including choices that can look like gaps. Each note is
dated; later notes are added below, earlier ones are not rewritten.

## Searching: all files or one file, not a chosen set (2026-10-06)

"Search all files..." searches either every listed log file or the one open in the viewer. There
is no way to pick a subset of files. That is deliberate:

- **It is fast at the sizes OIE keeps.** Counting the matching lines in 15 MB of logs took about
  half a second. The engine searches at roughly 60 to 200 MB per second, depending on the pattern,
  so the 15-second limit on one request covers about 1 to 3 GB. OIE 4.6.0's shipped log4j settings
  roll `mirth.log` at 500 KB and keep 20 archives, about 10 MB in all.
- **The results already narrow it.** A search first counts the matching lines in each file and
  shows one collapsed group per file. No lines are fetched until a group is opened, so files of no
  interest cost nothing.
- **If large logs become a real case** (DEBUG logging, much larger rollover sizes), a count can stop
  at the time limit and offer "Count the rest". The narrowing to add then is a time range (from /
  to), not a file checklist: people look for "yesterday afternoon", not for an archive's number,
  and each archive covers a known span of time, so the engine could skip the files outside the
  range. A file checklist would also need an API change, since a search takes one file or all of
  them.

## Which files, and how they are named (2026-10-06)

- **The engine finds the files; a client never names a path.** The plugin reads the engine's own
  log4j configuration (file and rolling appenders, their file names and rollover patterns) and
  lists what it finds. A client refers to a file only by the id from that list. Nothing a client
  sends is ever turned into a path, so there is nothing to traverse.
- **An id is the file's name plus a fingerprint**: `<appender>/<name>@<12 hex digits>`, taken from
  the file's identity on disk, its first bytes and, for an archive, its length and time. A request
  with an id whose fingerprint no longer matches is refused with 409, and the viewer says that the
  file no longer exists under that name.
- **Rollovers are not followed.** When log4j rolls `mirth.log`, every archive is renamed down one
  number, so the file being read may now have another name. The plugin could look for it by
  fingerprint but deliberately does not: on an engine that rolls every few seconds, the whole
  20-archive window is only a minute or two of history anyway, and the fix there is a larger
  rollover size, not code. The viewer keeps the page on screen readable, says the file is gone,
  and offers to open the file that now has that name.
- **`.1` is the oldest archive.** OIE 4.6.0's log4j settings use the rollover strategy's "max"
  file index, so the highest number is the newest archive. The file list is ordered by time,
  newest first, not by number.

## Archives in date folders (2026-10-07)

- **A rollover pattern can put archives in folders named by the date**, such as
  `logs/$${date:yyyy-MM}/app-%d{yyyy-MM-dd}-%i.log.gz`. log4j then keeps a numbered set in each
  folder: on 4.6.0, with a folder per minute, each folder had its own `.1` to `.5`.
- **Only what the pattern describes is read.** The plugin starts at the pattern's fixed directory
  and enters a folder only when its name has the shape the pattern writes (`yyyy-MM` is four digits,
  a dash and two digits) and it is a real directory, not a symbolic link. Dates in file names are
  matched by shape too, so `app-backup.log` is not taken for an archive of `app-%d{yyyy-MM-dd}.log`.
- **An archive in a folder is named by its path below that directory**, such as
  `2026-10-06/mirth.log.3.zip`, in the list, in search results and in the audit log. A download of
  it is saved as `2026-10-06_mirth.log.3.zip`, since a file name cannot hold a slash.
- **At most 500 folders are read at each folder level, newest first**, with a warning when there
  are more (a day/hour layout reads up to 500 days, then up to 500 hours).
  log4j removes old folders only if the configuration tells it to, and a folder holds at least one
  archive, so older folders could not add to the 500 files listed anyway.
- **A folder or file that cannot be read is named in the list's warnings**, so the list is never
  short without saying so.

## Memory: nothing is held whole (2026-10-06)

The engine's default heap is 256 MB, and every REST response in OIE is normally built in memory
before it is sent. So:

- **Pages** are 1,000 lines and never more than 256 KB.
- **Downloads** stream from the file to the client (measured on 4.6.0: an `InputStream` returned
  by an extension servlet is streamed, not buffered).
- **Searches** read a file through a 64 KB buffer, one line at a time. A line longer than 1 MB is
  searched in 1 MB pieces, and the result says so.
- **A search request** returns at most 1,000 matches, one per line (a line over 1 MB can give one
  per piece), each line cut to 256 characters around the match. **Highlights** stop at 5,000 per page, after 2 seconds, or on a line the pattern is too
  complex for, and the viewer says which.
- **An archive** is read through from its start for each page (zip has no random access), and a
  page more than 1 GiB into an archive's uncompressed content is refused. Searching it still works,
  and the viewer offers the download when the user has Download Log Files.

The web viewer's download is the exception on the client side: the host's `saveFile` takes a
whole file, so the browser holds the file in memory before saving it. Log files at OIE's sizes
make that harmless.

## Searching safely on a production engine (2026-10-06)

- **At most two searches run at once, engine-wide.** A third is refused straight away with 429
  rather than queued, so slow requests cannot pile up, and the message states the limit. An open
  search window holds nothing; a slot is held only while a request runs.
- **At most four page reads run at once, engine-wide**, refused the same way. A page read is
  normally a few milliseconds, but one can re-count a large active file's lines, inflate an archive
  from its start, or spend up to 2 seconds highlighting a search's matches.
- **15 seconds per request**, enforced inside the regular expression engine as well as between
  lines: `java.util.regex` backtracks, and a pattern such as `(.*a){10}x` did not finish within
  20 seconds on a line of just 40 characters (measured on Java 17). The text being matched checks the clock every 1,024 characters it hands the matcher. A
  pattern that overflows the matcher's stack is caught too.
- **Patterns that could loop without reading are refused.** The time limit is checked as the
  matcher reads characters, and a repeated part that matches without reading, such as an empty
  lookahead, never reads: `(?:(?:(?:(?:(?=)){1000}){1000}){1000}){1000}` ran for over 10 seconds
  without a single read (measured). Java ends `*`, `+` and the optional part of `{n,m}` as soon as a
  turn reads nothing, so only the minimum of a count is forced; a pattern whose nested minimums
  multiply past 100,000 is refused with 400 before it runs. The slowest pattern still allowed
  stopped at 15.1 seconds (measured). Comments mode, `(?x)`, is refused too: a `#` comment can hide a
  parenthesis from any reading of the pattern but Java's own.
- **Queries are capped at 1,000 characters.** The cap does not keep the event log short: the
  engine records a request before the plugin sees it, so a longer query is recorded word for word
  and then refused.
- **429, not 503, for "busy".** The web administrator reads 502, 503 and 504 as "engine
  unreachable" and shows a connection banner, which would be wrong for a busy search.
- **Counting comes first.** "Search all files" asks how many lines match in each file, then fetches
  a file's lines when its group is opened, 1,000 at a time and only when asked (a click, not
  scrolling), because every request runs on the engine and lands in the audit log.
- **One dialect for regular expressions.** The engine runs the search and also computes the
  highlights on the page, with the same Java expression, so what is highlighted is exactly what
  matched. The web viewer's own "Find on this page" box is Monaco's and uses JavaScript regular
  expressions; that box only searches the page in the browser, and the Search dialog says plainly
  that its expressions are Java's.

## Showing a log exactly as written (2026-10-06)

- **A line ends at LF, CRLF or a lone CR.** HL7 v2 messages logged by channels separate segments
  with a lone CR, and a viewer that only split on LF would run them together. The same rule numbers
  lines, cuts pages and splits lines for searching, so a match's line number is the line both
  viewers show.
- **Line ends are sent as they are.** Both viewers can show which terminator each line had.
- **Characters that cannot be shown are replaced one for one**, so offsets still line up:
  C0 controls (such as the VT and FS around an MLLP frame) become their Unicode control pictures,
  DEL becomes U+2421, invalid bytes become U+FFFD, and the nine explicit bidirectional control
  characters become U+2426 so that a log line cannot reorder how the text around it displays.
- **XML is listed first in the servlet's `@Produces`.** The Swing client's proxy takes the first
  type listed, and its JSON reader turned every CR into LF. The engine's own servlets list XML
  first too. The web administrator asks for JSON explicitly and keeps its CRs.
- **The active file is a snapshot.** It does not update by itself; "Load latest lines" re-reads its
  end. There is no follow or live-tail mode: every poll would be an audited read, and the engine's
  Server Log tab already shows the live log.

## Permissions and auditing (2026-10-06)

- **Two permissions of the plugin's own**, View Log Files and Download Log Files. Extension
  operations reach the authorization controller as `"<plugin name>#<operation>"`, which can never
  match a core permission such as View Server Settings, so naming a core one would do nothing.
  Download is separate so a role can read logs without being able to take whole files away.
- **Roles limited to specific channels are refused outright.** Thread names in the log carry
  `name (channel id)`, but stack-trace continuation lines, server-wide lines and chained thread
  names make filtering by channel unreliable, and a log can hold any channel's patient data.
- **Every operation is audited**, including listing files, with its parameters.
- Checked live on 2026-10-06 with Role Based Access Control 1.1.2: both administrators hide the
  entry and the Download button as the permissions say, and the engine refuses the operations.

## The Swing entry point (2026-10-06)

"View Log Files" is in the Administrator's **Other** task pane, not the Dashboard. The Dashboard
hides every plugin task after the first each time the selection changes, while the Other pane is
set up once at login and never hidden.

## Accepted limitations (2026-10-06)

- **Opening a page "at" two kinds of offset is one or a few bytes off.** At the LF of a CRLF that
  ends a line longer than half a page, the page starts between the CR and the LF. At an offset
  inside a multi-byte character deep in a very long line, the page starts up to 3 bytes after it.
  Neither viewer can ask for such a position: a search match's offset is the start of a match,
  always on a character boundary and never a line terminator. Two tests in
  `LogBoundaryEdgeTest` pin this behaviour, so a change to it is noticed.
- **A match spanning two 1 MB pieces of a very long line is not found.** The result warns when
  any line was searched in pieces.
- **A count resumed inside a line over 1 MB that already matched** can count that line twice, if a
  later piece of it matches too. It needs the time limit to stop in exactly that place.
- **Counts are a snapshot.** If the active file grows after the count, opening its group can show
  more lines than the count said.
- **Line numbers** are given for pages within the first 64 MB of an uncompressed file, and the
  total line count for files up to 64 MB. Past that, the position is shown in bytes.
- **Archives are read with `ZipInputStream` over the open file rather than `ZipFile`,** so an
  archive being read does not block log4j from deleting or renaming it on Windows. This has not
  been tested on a Windows engine.

## Searches, auditing and trust (2026-10-07)

- **Search text is recorded word for word in the event log**, deliberately: it is what was asked
  of the logs. Anyone who can view events can read it. It also travels in the request URL, so an
  HTTP access log on a proxy in front of the engine would record it too.
- **The event log records success before the plugin decides.** The engine writes the audit event
  before the operation runs, so a request the plugin then refuses (a role limited to specific
  channels, a file that has rotated, a busy engine) still appears as successful.
- **Download Log Files is of no use without View Log Files.** Both administrators show the viewer
  only to roles with View Log Files, and the Download button is inside it. Grant both.
- **An abandoned search runs on.** Closing the results or starting another search does not stop a
  search already running on the engine: the engine does not notice a dropped connection. It keeps
  its slot until it finishes or reaches its 15 seconds. Stopping it early would be a new feature.
- **The time zone label uses the zone's offset now.** A file written before a daylight-saving
  change is labelled with today's offset; its timestamps are as written.
- **An archive's total line count** shows on every page of an archive up to 64 MB uncompressed. For
  a larger one it shows only on the page Last page opens, which reads the archive through.
- **A download cut short by truncation leaves a stack trace in `mirth.log`.** When the active file
  is truncated while it is being downloaded, the plugin's stream fails with "was truncated while it
  was being downloaded", and Jersey logs that as an ERROR with its stack trace (about 90 lines).
  OIE's own rollover renames files rather than truncating them (measured on 4.6.0), so it takes a
  copy-and-truncate setup or a hand truncation. The viewers say the download stopped and save
  nothing.
- **A very long line repaints slowly in the Swing viewer with word wrap on.** A page holding one
  line of about 100,000 characters took about 930 ms per repaint on a test display (the text
  component's wrapped view), so scrolling such a page is sluggish.
- **The log directory is trusted as the engine's administrators set it up.** Beyond never
  following a symbolic link, the plugin does not defend against odd file names, hard links or
  other tricks in that directory: anyone who can write there can already change the logs.

## Expected build output (2026-10-06)

`mvn clean package` prints four warnings that need no action:

- "1 problem was encountered while building the effective model for
  org.javassist:javassist:jar:3.19.0-GA" and "3 problems ... for com.miglayout:miglayout:jar:3.7.4":
  these come from dependencies' own POMs.
- "JAR will be empty - no content was marked for inclusion!" for the `package` module, which only
  assembles the zip.
- "Parameter 'finalName' is read-only, must not be used in configuration" from the assembly plugin.

No tests are skipped.

## What the tests do not cover (2026-10-06)

- **The two user interfaces are not exercised by the build.** Unit tests cover the wording,
  formatting and merging logic (client JUnit tests; web `node --test` suites), but the Swing dialog
  and the web view were checked by hand and with scripted sessions against a running 4.6.0 engine
  during development, not in CI.
- **There is no automated test against a running engine.** The REST wiring, the audit events, the
  streaming download, the 409/429 statuses and the Role Based Access Control behaviour were checked
  live. `PluginXmlWiringTest` checks the plugin's declarations, and the service is tested directly.
- **Windows engines are untested** (file sharing and rename behaviour, see above).
- **Very large logs** were exercised up to tens of megabytes. The 64 MB line-numbering limit has no
  test, and the 1 GB archive limit is tested only for a page offset past it, not by reading an
  archive that large.

## Built for OIE 4.7.0 before its release (2026-10-18)

- **1.1.0 says 4.7.0 in `plugin.xml` but compiles against the 4.6.0 engine jars.** 4.7.0 had not
  been published, and a public build can't download an unpublished engine. `mc.version` and
  `oie.dist.sha256` stay on 4.6.0, and `plugin.supported.mc.versions` names 4.7.0. This is safe
  because the zip holds none of the engine's jars, and because a comparison of the 4.6.0
  distribution with the 4.7.0 build (engine commit `f9ba73e28`, tarball SHA-256
  `ab08558873dc6be1e474afaacc46efe94d29c945cd3381c10d0ae548ecb429de`) found every engine class the
  plugin uses still there. The first release after 4.7.0 is published sets `mc.version` to 4.7.0
  and `plugin.supported.mc.versions` back to `${mc.version}`.
- **log4j is pinned to 4.7.0's 2.26.1** (4.6.0 ships 2.25.3). It is the one library the plugin uses
  that 4.7.0 changed, and log file discovery depends on it. It comes from Maven Central, not the
  engine distribution, so the build compiles and tests against 4.7.0's log4j even while the engine
  jars are 4.6.0's.
- **servlet-api and slf4j moved down, to 3.1.0 and 1.7.30**, the versions both engines ship. 1.0.0
  compiled against 4.0.1 and 1.7.32, newer than anything the plugin runs against; it used nothing
  that the shipped versions lack, which the build now shows.
- **CI's end-to-end tests run on 4.6.0, with the zips relabelled.** CI can't download 4.7.0, so it
  installs copies of the zips whose `plugin.xml` says 4.6.0 on the engine the plugin compiles
  against, and the run summary says which zips were relabelled. Once `mc.version` is 4.7.0 the
  relabelling stops by itself. The same tests ran against the 4.7.0 build above before the
  release: all six databases on Java 17 (Zulu 17.0.17) and 21 (Temurin 21.0.12), 61 checks each,
  none failed, with the 1.0.0 upgrade taken across the engine upgrade from 4.6.0.

## What the end-to-end tests cover (2026-10-18)

`e2e/run.py` installs the zip into the released engine through its own installer and uses the
plugin over its REST API. It replaces "There is no automated test against a running engine"
(2026-10-06).

- **fresh:** known log files are written before the engine first starts, and every endpoint is
  checked against them on disk: the list (found through log4j), a first page (HL7's lone CRs survive
  the XML the Swing Administrator reads), a last page, plain, case-insensitive and regular
  expression searches, a count over every file, both downloads byte for byte, and an event for each
  request.
- **upgrade:** 1.0.0, then this build installed over it. On a newer engine than 1.0.0's, the engine is
  upgraded under 1.0.0 first, and the test checks the engine reports 1.0.0 as not compatible.
- **rbac:** Role Based Access Control installed alongside, with four roles. A role limited to any
  channels is refused by the plugin itself, so that role names a channel id the engine doesn't need
  to have. rbac logs a warning for each request it refuses, and the test checks they are exactly
  the requests a role lacked the permission for.
- **Six databases, though the plugin has no tables:** the engine runs on each, and the plugin must
  load and work on all of them.

Still not covered by any automated test: the two user interfaces ([release-checks.md](release-checks.md)
covers them by hand), the 409 stale and 429 busy statuses, Windows engines, very large logs, and log4j
configurations other than the engine's shipped one (archives in date folders are covered by unit
tests).

## Release review changes (2026-10-18)

- **A tag becomes a draft release only from a commit whose Build run on `main` passed.** The release
  workflow waits for that run, up to an hour, and refuses the tag if any job in it failed, the
  end-to-end tests included, or if the commit never ran on `main`.
- **`--label-as` relabels only the zips built for the target engine.** A release built for an older
  engine keeps its own label, so its upgrade still starts on that engine and still passes through
  the engine upgrade, as users' upgrades do.
- **The end-to-end tests now also check:**
  - the file list and a page as JSON, the form the web administrator asks for, against the XML
    answers, carriage returns included. Search and count are still checked as XML only;
  - that each engine start ran on the Java the run was given, from the version the engine logs;
  - FATAL lines, and engine lines naming the plugin's package.
- **The harness is shared.** `e2e/` is the harness from oie-cache-manager, with the rbac scenario
  from simple-channel-history, renamed for this plugin. A fix to one copy needs carrying to the
  others.

## Compiled against OIE 4.7.0 (2026-10-25)

- **The split 1.1.0 shipped with is over.** OIE 4.7.0 was published on 2026-10-21 from commit
  `f9ba73e28`, and its `oie_unix_4_7_0.tar.gz` has the SHA-256
  `ab08558873dc6be1e474afaacc46efe94d29c945cd3381c10d0ae548ecb429de`: the same commit and tarball
  the 1.1.0 tests ran against ("Built for OIE 4.7.0 before its release", above), so that testing
  applies to the published engine.
  `mc.version` is now 4.7.0, `oie.dist.sha256` that hash, and `plugin.supported.mc.versions` back to
  `${mc.version}`.
- **These pins match the jars in the 4.7.0 tarball**, checked one by one: servlet-api 3.1.0,
  javax.ws.rs-api 2.0.1, xstream 1.4.20, slf4j 1.7.30, log4j 2.26.1, rsyntaxtextarea 2.5.6,
  reflections 0.9.10, commons-lang3 3.20.0, and xpp3 1.1.4c (byte for byte in `server-lib`; the copy
  in `client-lib` is the same jar, signed).
- **CI's end-to-end tests run on 4.7.0 itself.** With `mc.version` and
  `plugin.supported.mc.versions` the same, nothing is relabelled. The upgrade from 1.0.0 still starts
  on 4.6.0 (`e2e/engines.txt`) and upgrades the engine under it; the upgrade from 1.1.0 and the rbac
  scenario run on 4.7.0 throughout.
