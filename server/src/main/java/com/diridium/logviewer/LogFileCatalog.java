// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the log files the running log4j configuration writes, and turns
 * client-supplied file ids back into files.
 *
 * <p>The client never supplies a path. Every request re-runs discovery and
 * accepts only a file it finds itself: the active file an appender names,
 * and the files below its rollover pattern's base directory whose paths match
 * the pattern ({@link RolloverPattern}), in that directory or in the
 * date-named folders the pattern puts archives in. Each must be a regular
 * file, and folders are entered only when they are real directories, both
 * checked without following symbolic links, so a link planted in the log
 * directory is never served. Nothing is cached, so a rollover or a
 * reconfiguration is seen by the next request.</p>
 *
 * <p>A file id is {@code <appender>/<file name>@<fingerprint>}, for example
 * {@code fout/mirth.log.3.zip@a03c19e4d2f8}; an archive in a folder is named by
 * its path below the base, as in
 * {@code fout/2026-10-06/mirth.log.3.zip@a03c19e4d2f8}. The name part keeps it readable in
 * the engine's audit log, which records only query parameters. The
 * fingerprint ({@link OpenLogFile}) is what makes a stale id detectable: the
 * default rollover strategy renames every archive down one index on every
 * rollover, so a name alone would silently point at a different file.</p>
 */
final class LogFileCatalog {

    private static final Logger log = LoggerFactory.getLogger(LogFileCatalog.class);

    /**
     * Cap on files listed (and so searched when a search covers all files).
     * Listing opens each file to fingerprint it (one 1 KiB read), so 500 files
     * is about 500 small reads per request, and the response is about 500
     * rows of roughly 400 bytes of XML. The shipped configuration keeps 21
     * files; 500 covers well over a year of daily archives. When it bites, the
     * oldest files are left out and the list says so.
     */
    static final int MAX_LISTED_FILES = 500;

    /**
     * Cap on the folders read at each level of one appender's date folders,
     * newest first (a day/hour layout gets up to this many days, then up to
     * this many hours). A folder holds at least one archive, so more folders
     * than files listed could add nothing to the list; it also bounds the work
     * when log4j makes a folder per day or per hour and nothing deletes the
     * old ones.
     */
    static final int MAX_ARCHIVE_FOLDERS = MAX_LISTED_FILES;

    /** Folder levels below the base directory: more than any date layout uses (year/month/day/hour). */
    static final int MAX_FOLDER_DEPTH = 8;

    enum Format { PLAIN, ZIP, GZIP, UNSUPPORTED }

    /** A discovered file, before it is opened. */
    record LogFile(String appenderName, String name, Path path, boolean active, Format format,
                   Charset charset, String note, boolean viewable, long size, long lastModified,
                   String timeZoneId) {

        /** The id without its fingerprint. Unique: one base directory per pattern, paths unique below it. */
        String key() {
            return appenderName + "/" + name;
        }
    }

    record Discovery(List<LogFile> files, List<String> warnings) {
    }

    record Entry(LogFile file, String id) {
    }

    record Listing(List<Entry> entries, List<String> warnings) {
    }

    private final LogAppenderSource source;

    LogFileCatalog(LogAppenderSource source) {
        this.source = source;
    }

    /**
     * All files, newest first, capped at {@link #MAX_LISTED_FILES}, each opened
     * once to compute its id.
     */
    Listing list() {
        Discovery discovery = discover();
        List<String> warnings = new ArrayList<>(discovery.warnings());
        List<LogFile> files = discovery.files();
        if (files.size() > MAX_LISTED_FILES) {
            warnings.add("Only the newest " + MAX_LISTED_FILES + " of " + files.size()
                    + " log files are listed.");
            files = files.subList(0, MAX_LISTED_FILES);
        }
        List<Entry> entries = new ArrayList<>();
        for (LogFile file : files) {
            try (OpenLogFile open = OpenLogFile.open(file)) {
                entries.add(new Entry(file, open.id()));
            } catch (NoSuchFileException e) {
                // Deleted by a rollover between the directory listing and the
                // open. It no longer exists, so leaving it out loses nothing.
            } catch (IOException e) {
                warnings.add(file.name() + " could not be opened: " + describe(e));
            }
        }
        return new Listing(entries, warnings);
    }

    /**
     * The discovered file an id names, without checking its fingerprint;
     * {@link OpenLogFile} does that against the opened file.
     */
    LogFile find(String fileId) throws LogViewerException {
        String key = keyOf(fileId);
        for (LogFile file : discover().files()) {
            if (file.key().equals(key)) {
                return file;
            }
        }
        throw new LogViewerException(LogViewerException.Kind.STALE,
                "Log file " + key + " no longer exists. Reload the file list.");
    }

    static String keyOf(String fileId) throws LogViewerException {
        int at = fileId == null ? -1 : fileId.lastIndexOf('@');
        if (at <= 0 || at == fileId.length() - 1) {
            throw new LogViewerException(LogViewerException.Kind.BAD_REQUEST,
                    "Invalid log file id: " + fileId);
        }
        return fileId.substring(0, at);
    }

    Discovery discover() {
        Found found = new Found();
        List<LogAppenderSource.Appender> appenders = new ArrayList<>(source.appenders());
        // Sorted so that when two appenders name the same file, which one
        // "owns" it (and so its id) does not depend on map iteration order.
        appenders.sort(Comparator.comparing(LogAppenderSource.Appender::name));
        for (LogAppenderSource.Appender appender : appenders) {
            try {
                discoverAppender(appender, found);
            } catch (RuntimeException e) {
                // A malformed name or pattern in one appender must not hide the others.
                log.warn("Could not discover log files for appender {}", appender.name(), e);
                found.warnings.add("Log files of appender " + appender.name() + " could not be listed: "
                        + e.getMessage());
            }
        }
        if (appenders.isEmpty()) {
            found.warnings.add("The running log4j configuration has no file appenders.");
        }

        // Newest first by modification time, not by rollover index: the index
        // order depends on the strategy (fileIndex=max, the default, makes the
        // highest index the newest; fileIndex=min the reverse), and renames
        // keep a file's modification time (measured on 4.6.0).
        found.files.sort(Comparator.comparingLong(LogFile::lastModified).reversed()
                .thenComparing(LogFile::active, Comparator.reverseOrder())
                .thenComparing(LogFile::key));
        return new Discovery(found.files, found.warnings);
    }

    /** What one discovery has found so far, and the paths already taken by an appender. */
    private static final class Found {
        final List<LogFile> files = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Set<Path> seen = new HashSet<>();
    }

    private void discoverAppender(LogAppenderSource.Appender appender, Found found) {
        Path active = null;
        if (appender.fileName() != null) {
            active = Paths.get(appender.fileName()).toAbsolutePath().normalize();
            if (found.seen.add(active)) {
                addIfRegular(found, appender, active, active.getFileName().toString(), true);
            }
        }
        if (appender.filePattern() != null) {
            new ArchiveWalk(appender, RolloverPattern.parse(appender.filePattern()), active, found).run();
        }
    }

    /** A folder below an appender's base directory: its path there ({@code /} between levels), and its time. */
    private record Folder(String path, Path dir, long lastModified) {
    }

    /**
     * One appender's archives: the files below its pattern's base directory
     * whose paths match the pattern. The base is read first; a folder in it is
     * entered only when it is a real directory (not a symbolic link) and its
     * path can still lead to a match, then the folders below it, level by
     * level, newest first and at most {@link #MAX_ARCHIVE_FOLDERS} at each level.
     */
    private static final class ArchiveWalk {
        private final LogAppenderSource.Appender appender;
        private final RolloverPattern pattern;
        private final Path active;
        private final Found found;

        ArchiveWalk(LogAppenderSource.Appender appender, RolloverPattern pattern, Path active, Found found) {
            this.appender = appender;
            this.pattern = pattern;
            this.active = active;
            this.found = found;
        }

        void run() {
            Path base = Paths.get(pattern.baseDir().isEmpty() ? "." : pattern.baseDir()).toAbsolutePath().normalize();
            List<Folder> level = List.of(new Folder("", base, 0));
            boolean capped = false;
            for (int depth = 0; !level.isEmpty(); depth++) {
                List<Folder> next = new ArrayList<>();
                for (Folder folder : level) {
                    read(folder, depth < MAX_FOLDER_DEPTH ? next : null);
                }
                next.sort(Comparator.comparingLong(Folder::lastModified).reversed());
                if (next.size() > MAX_ARCHIVE_FOLDERS) {
                    next = next.subList(0, MAX_ARCHIVE_FOLDERS);
                    capped = true;
                }
                level = next;
            }
            if (capped) {
                found.warnings.add("Archives of appender " + appender.name() + " are listed from its newest "
                        + MAX_ARCHIVE_FOLDERS + " folders only.");
            }
        }

        /** Adds the archives in one folder, and the folders below it that may hold more to {@code next}. */
        private void read(Folder folder, List<Folder> next) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder.dir())) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    String path = folder.path().isEmpty() ? name : folder.path() + "/" + name;
                    Path absolute = entry.toAbsolutePath().normalize();
                    if (pattern.relative().matcher(path).matches()) {
                        if (!absolute.equals(active) && found.seen.add(absolute)) {
                            addIfRegular(found, appender, absolute, path, false);
                        }
                    } else if (next != null && pattern.couldContain(path)) {
                        BasicFileAttributes attrs = attributes(found, absolute, path);
                        if (attrs != null && attrs.isDirectory()) {
                            next.add(new Folder(path, absolute, attrs.lastModifiedTime().toMillis()));
                        }
                    }
                }
            } catch (NoSuchFileException e) {
                // The base: no rollover has happened yet. A folder: removed since it was listed.
            } catch (NotDirectoryException e) {
                found.warnings.add("Archive directory of appender " + appender.name() + " is not a directory.");
            } catch (IOException e) {
                found.warnings.add((folder.path().isEmpty() ? "Archive directory" : "Archive folder " + folder.path())
                        + " of appender " + appender.name() + " could not be read: " + describe(e));
            }
        }
    }

    /**
     * A file's own attributes: a symbolic link reports as a link, not as its
     * target, so it is neither a regular file nor a directory here. Null, with
     * a warning unless the file has simply gone, when they cannot be read.
     */
    private static BasicFileAttributes attributes(Found found, Path path, String name) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null; // not created yet, or removed by a rollover since its folder was read
        } catch (IOException e) {
            found.warnings.add(name + " could not be listed: " + describe(e));
            return null;
        }
    }

    private static void addIfRegular(Found found, LogAppenderSource.Appender appender, Path path, String name,
                                     boolean active) {
        BasicFileAttributes attrs = attributes(found, path, name);
        if (attrs == null || !attrs.isRegularFile()) {
            return;
        }
        Charset charset = appender.charset() != null ? appender.charset() : StandardCharsets.UTF_8;
        String note = appender.charset() != null ? null
                : "The appender's layout does not declare a charset; decoded as UTF-8.";
        Format format = active ? Format.PLAIN : formatOf(name);
        boolean viewable = true;
        if (format == Format.UNSUPPORTED) {
            viewable = false;
            note = "Compressed in a format the viewer cannot read.";
        } else if (!LogText.isNewlineSafe(charset)) {
            viewable = false;
            note = "Written in " + charset.name() + ", which the viewer cannot page.";
        }
        found.files.add(new LogFile(appender.name(), name, path, active, format, charset, note, viewable,
                attrs.size(), attrs.lastModifiedTime().toMillis(), appender.timeZoneId()));
    }

    /** log4j picks the compression from the pattern's extension, so the file name tells us. */
    static Format formatOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            return Format.ZIP;
        }
        if (lower.endsWith(".gz")) {
            return Format.GZIP;
        }
        for (String ext : new String[] {".bz2", ".xz", ".zst", ".deflate", ".pack200", ".7z"}) {
            if (lower.endsWith(ext)) {
                return Format.UNSUPPORTED;
            }
        }
        return Format.PLAIN;
    }

    static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
