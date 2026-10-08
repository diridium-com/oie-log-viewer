// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.nio.charset.Charset;
import java.util.List;

/**
 * The seam between this plugin and log4j: the file appenders in the running
 * configuration, reduced to what discovery needs.
 *
 * <p>Production uses {@link Log4jAppenderSource}. Tests supply appenders
 * pointing at a temporary directory, so none of the file handling needs a
 * live {@code LoggerContext}.</p>
 */
public interface LogAppenderSource {

    /**
     * @param name        appender name, the first part of every file id
     * @param fileName    the active file as configured, possibly relative (to
     *                    the engine's working directory, which is where log4j
     *                    resolves it too); null if the appender has none
     * @param filePattern the rollover pattern as configured, e.g.
     *                    {@code logs/mirth.log.%i.zip}; null for a plain file
     *                    appender
     * @param charset     the layout's charset, or null when the layout is not
     *                    a text layout
     * @param timeZoneId  the zone the layout's date pattern names (log4j's
     *                    {@code %d{pattern}{zone}}), or null when it names none
     *                    and times are in the engine's default zone
     */
    record Appender(String name, String fileName, String filePattern, Charset charset, String timeZoneId) {

        /** An appender whose dates are in the engine's default zone. */
        Appender(String name, String fileName, String filePattern, Charset charset) {
            this(name, fileName, filePattern, charset, null);
        }
    }

    /** Called once per request; discovery is never cached. */
    List<Appender> appenders();
}
