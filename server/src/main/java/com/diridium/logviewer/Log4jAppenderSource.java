// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.StringLayout;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

/**
 * Reads the file appenders from the engine's running log4j configuration.
 *
 * <p>Measured on 4.6.0 from a plugin: {@code LogManager.getContext(false)}
 * returns the engine's core {@code LoggerContext}; its {@code fout}
 * RollingFileAppender reports {@code getFileName() = logs/mirth.log}
 * (relative) and {@code getFilePattern() = logs/mirth.log.%i.zip}, and
 * {@code user.dir} is the engine home, so resolving the relative name finds
 * the real file. The same configuration also holds the console appender and
 * the in-memory appender behind the Server Log tab, which are skipped here
 * because they have no file.</p>
 *
 * <p>Asked every time rather than cached, so a configuration reloaded at
 * runtime (log4j watches its file when {@code monitorInterval} is set) is
 * picked up on the next request.</p>
 */
public class Log4jAppenderSource implements LogAppenderSource {

    @Override
    public List<Appender> appenders() {
        List<Appender> result = new ArrayList<>();
        // getContext(false) is typed as the spi interface; only the core
        // implementation exposes a Configuration. Anything else means log4j is
        // bridged to another backend and there is nothing to discover.
        if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
            return result;
        }
        for (org.apache.logging.log4j.core.Appender appender
                : context.getConfiguration().getAppenders().values()) {
            Appender described = describe(appender);
            if (described != null) {
                result.add(described);
            }
        }
        return result;
    }

    private static Appender describe(org.apache.logging.log4j.core.Appender appender) {
        String fileName;
        String filePattern = null;
        if (appender instanceof RollingFileAppender rolling) {
            // In "direct write" mode (no fileName configured) the appender
            // reports null and only the manager knows the file in use.
            fileName = rolling.getFileName() != null
                    ? rolling.getFileName() : rolling.getManager().getFileName();
            filePattern = rolling.getFilePattern();
        } else if (appender instanceof RollingRandomAccessFileAppender rolling) {
            fileName = rolling.getFileName() != null
                    ? rolling.getFileName() : rolling.getManager().getFileName();
            filePattern = rolling.getFilePattern();
        } else if (appender instanceof FileAppender file) {
            fileName = file.getFileName();
        } else if (appender instanceof RandomAccessFileAppender file) {
            fileName = file.getFileName();
        } else {
            // Console, the Server Log tab's in-memory appender, sockets, and so on.
            return null;
        }
        return new Appender(appender.getName(), fileName, filePattern, charsetOf(appender),
                dateZoneOf(appender));
    }

    /**
     * log4j's date conversion takes an optional zone after its pattern,
     * {@code %d{yyyy-MM-dd HH:mm:ss}{UTC}} (also spelled {@code %date}); without
     * one it writes the JVM's default zone, which is what the engine's shipped
     * {@code %d{yyyy-MM-dd HH:mm:ss.SSS}} does.
     */
    private static final Pattern DATE_ZONE = Pattern.compile("%d(?:ate)?\\{[^}]*\\}\\{([^}]+)\\}");

    private static String dateZoneOf(org.apache.logging.log4j.core.Appender appender) {
        return appender.getLayout() instanceof PatternLayout layout ? dateZoneOf(layout.getConversionPattern()) : null;
    }

    /** The zone a conversion pattern's first date conversion names, or null. */
    static String dateZoneOf(String conversionPattern) {
        if (conversionPattern == null) {
            return null;
        }
        Matcher matcher = DATE_ZONE.matcher(conversionPattern);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    /**
     * The layout's own charset, not an assumed UTF-8. A PatternLayout with no
     * charset configured (the engine's shipped {@code fout} sets none) uses
     * {@code Charset.defaultCharset()} (read from the 2.25.3 bytecode), and
     * 4.6.0's vmoptions do not set {@code file.encoding}, so on a Windows
     * engine under Java 17 mirth.log is written in the ANSI code page.
     */
    private static Charset charsetOf(org.apache.logging.log4j.core.Appender appender) {
        return appender.getLayout() instanceof StringLayout layout ? layout.getCharset() : null;
    }
}
