// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.Serializable;

/**
 * One log file the server is willing to serve, as listed to the client.
 *
 * <p>{@link #getId()} is the only handle the client ever sends back. It is
 * readable on purpose, because the engine's audit log records query
 * parameters verbatim, and it carries a fingerprint so that a request made
 * after the file was rotated is refused rather than answered from whatever
 * file now has that name.</p>
 */
public class LogFileInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String appenderName;
    private boolean active;
    private boolean compressed;
    private boolean viewable;
    private String note;
    private long size;
    private long lastModified;
    private String charset;
    private String timeZoneId;
    private String timeZoneLabel;

    public LogFileInfo() {
    }

    /** Server-issued file id, e.g. {@code fout/mirth.log.3.zip@a03c19e4d2f8}. */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * The file's name. An archive kept in date folders also carries its folders below the archive
     * directory, as in {@code 2026-10-06/mirth.log.3.zip}. Never an absolute path.
     */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Name of the log4j appender that writes (or wrote) this file. */
    public String getAppenderName() {
        return appenderName;
    }

    public void setAppenderName(String appenderName) {
        this.appenderName = appenderName;
    }

    /** True for the file log4j is currently writing, which grows while it is read. */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isCompressed() {
        return compressed;
    }

    public void setCompressed(boolean compressed) {
        this.compressed = compressed;
    }

    /**
     * False when the file can only be downloaded, not paged or searched. The
     * reason is in {@link #getNote()}.
     */
    public boolean isViewable() {
        return viewable;
    }

    public void setViewable(boolean viewable) {
        this.viewable = viewable;
    }

    /** Why the file is not viewable, or a caveat about how it is decoded; otherwise null. */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /** Size on disk in bytes (compressed size for archives). */
    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    /** Last modified time, epoch milliseconds. */
    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    /** Charset the file is decoded with, taken from the appender's layout. */
    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
     * The time zone the file's timestamps are written in, as a Java time zone
     * id (for example {@code America/Denver}). The engine's log layout writes
     * times with no zone: they are in the zone its date pattern names, or
     * else the engine's default zone.
     */
    public String getTimeZoneId() {
        return timeZoneId;
    }

    public void setTimeZoneId(String timeZoneId) {
        this.timeZoneId = timeZoneId;
    }

    /** That zone ready to show, with its current offset, for example {@code MDT (UTC-06:00)}. */
    public String getTimeZoneLabel() {
        return timeZoneLabel;
    }

    public void setTimeZoneLabel(String timeZoneLabel) {
        this.timeZoneLabel = timeZoneLabel;
    }
}
