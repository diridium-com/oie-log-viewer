// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.LayoutComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.diridium.logviewer.LogAppenderSource.Appender;

/**
 * {@link Log4jAppenderSource} against a real log4j {@link LoggerContext}
 * (log4j-core 2.25.3, the version the engine ships), configured
 * programmatically over a temporary directory. The context is the JVM's
 * global one, which is what the plugin asks for, so each test puts the
 * default configuration back afterwards.
 */
class Log4jAppenderSourceTest {

    @TempDir
    Path dir;

    private LoggerContext context;

    @BeforeEach
    void findContext() {
        assumeTrue(LogManager.getContext(false) instanceof LoggerContext, "log4j-core is not the active backend");
        context = (LoggerContext) LogManager.getContext(false);
    }

    @AfterEach
    void restoreDefaultConfiguration() {
        if (context != null) {
            context.setConfiguration(new DefaultConfiguration());
        }
    }

    private ConfigurationBuilder<BuiltConfiguration> builder() {
        ConfigurationBuilder<BuiltConfiguration> b = ConfigurationBuilderFactory.newConfigurationBuilder();
        b.setStatusLevel(Level.ERROR);
        b.setConfigurationName("log-viewer-test");
        return b;
    }

    private static LayoutComponentBuilder layout(ConfigurationBuilder<BuiltConfiguration> b, String charset) {
        LayoutComponentBuilder layout = b.newLayout("PatternLayout").addAttribute("pattern", "%m%n");
        if (charset != null) {
            layout.addAttribute("charset", charset);
        }
        return layout;
    }

    private static Map<String, Appender> byName(List<Appender> appenders) {
        return appenders.stream().collect(Collectors.toMap(Appender::name, a -> a));
    }

    private Path rollingActive() {
        return dir.resolve("mirth.log");
    }

    private String rollingPattern() {
        return dir.resolve("mirth.log.%i.zip").toString();
    }

    @Test
    void reportsFileAppendersWithTheirFileNamePatternAndCharsetAndSkipsTheConsole() {
        ConfigurationBuilder<BuiltConfiguration> b = builder();
        b.add(b.newAppender("stdout", "Console").add(layout(b, null)));
        b.add(b.newAppender("rolling", "RollingFile")
                .addAttribute("fileName", rollingActive().toString())
                .addAttribute("filePattern", rollingPattern())
                .add(layout(b, "windows-1252"))
                .addComponent(b.newComponent("Policies")
                        .addComponent(b.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", "10MB"))));
        b.add(b.newAppender("plain", "File")
                .addAttribute("fileName", dir.resolve("plain.log").toString())
                .add(layout(b, "UTF-8")));
        b.add(b.newAppender("nocharset", "File")
                .addAttribute("fileName", dir.resolve("nocharset.log").toString())
                .add(layout(b, null)));
        b.add(b.newAppender("fast", "RandomAccessFile")
                .addAttribute("fileName", dir.resolve("fast.log").toString())
                .add(layout(b, "ISO-8859-1")));
        b.add(b.newAppender("fastRolling", "RollingRandomAccessFile")
                .addAttribute("fileName", dir.resolve("fastRolling.log").toString())
                .addAttribute("filePattern", dir.resolve("fastRolling.log.%i.gz").toString())
                .add(layout(b, "UTF-16LE"))
                .addComponent(b.newComponent("Policies")
                        .addComponent(b.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", "10MB"))));
        b.add(b.newRootLogger(Level.INFO).add(b.newAppenderRef("rolling")));
        context.start(b.build());

        List<Appender> appenders = new Log4jAppenderSource().appenders();
        Map<String, Appender> byName = byName(appenders);
        assertEquals(5, appenders.size(), "console excluded: " + byName.keySet());
        assertFalse(byName.containsKey("stdout"), "a console appender has no file");

        Appender rolling = byName.get("rolling");
        assertEquals(rollingActive().toString(), rolling.fileName());
        assertEquals(rollingPattern(), rolling.filePattern());
        assertEquals(Charset.forName("windows-1252"), rolling.charset());

        Appender plain = byName.get("plain");
        assertEquals(dir.resolve("plain.log").toString(), plain.fileName());
        assertNull(plain.filePattern(), "a plain file appender has no rollover pattern");
        assertEquals(Charset.forName("UTF-8"), plain.charset());

        // A layout that names no charset reports the JVM default, which is what log4j writes with.
        assertEquals(Charset.defaultCharset(), byName.get("nocharset").charset());

        assertEquals(dir.resolve("fast.log").toString(), byName.get("fast").fileName());
        assertNull(byName.get("fast").filePattern());
        assertEquals(Charset.forName("ISO-8859-1"), byName.get("fast").charset());

        assertEquals(dir.resolve("fastRolling.log").toString(), byName.get("fastRolling").fileName());
        assertEquals(dir.resolve("fastRolling.log.%i.gz").toString(), byName.get("fastRolling").filePattern());
        assertEquals(Charset.forName("UTF-16LE"), byName.get("fastRolling").charset());
    }

    @Test
    void aRollingAppenderInDirectWriteModeReportsTheFileItIsWritingNow() {
        ConfigurationBuilder<BuiltConfiguration> b = builder();
        // No fileName: log4j writes straight to the pattern's current name.
        b.add(b.newAppender("direct", "RollingFile")
                .addAttribute("filePattern", dir.resolve("direct-%d{yyyy-MM-dd}.log").toString())
                .add(layout(b, null))
                .addComponent(b.newComponent("Policies")
                        .addComponent(b.newComponent("TimeBasedTriggeringPolicy"))));
        b.add(b.newRootLogger(Level.INFO).add(b.newAppenderRef("direct")));
        context.start(b.build());

        List<Appender> appenders = new Log4jAppenderSource().appenders();
        assertEquals(1, appenders.size());
        Appender direct = appenders.get(0);
        assertNotNull(direct.fileName(), "taken from the manager when the appender has no fileName");
        assertTrue(direct.fileName().matches(".*direct-\\d{4}-\\d{2}-\\d{2}\\.log"), direct.fileName());
        assertEquals(dir.resolve("direct-%d{yyyy-MM-dd}.log").toString(), direct.filePattern());
    }

    @Test
    void noFileAppendersGivesAnEmptyList() {
        ConfigurationBuilder<BuiltConfiguration> b = builder();
        b.add(b.newAppender("stdout", "Console").add(layout(b, null)));
        b.add(b.newRootLogger(Level.INFO).add(b.newAppenderRef("stdout")));
        context.start(b.build());

        assertTrue(new Log4jAppenderSource().appenders().isEmpty());
        LogFileList list = new LogViewerService().listFiles();
        assertTrue(list.getFiles().isEmpty());
        assertEquals(1, list.getWarnings().size());
    }

    @Test
    void aConfigurationReloadedAtRuntimeIsSeenByTheNextRequest() {
        ConfigurationBuilder<BuiltConfiguration> first = builder();
        first.add(first.newAppender("one", "File").addAttribute("fileName", dir.resolve("one.log").toString())
                .add(layout(first, null)));
        first.add(first.newRootLogger(Level.INFO).add(first.newAppenderRef("one")));
        context.start(first.build());
        assertEquals(List.of("one"), new Log4jAppenderSource().appenders().stream().map(Appender::name).toList());

        ConfigurationBuilder<BuiltConfiguration> second = builder();
        second.add(second.newAppender("two", "File").addAttribute("fileName", dir.resolve("two.log").toString())
                .add(layout(second, null)));
        second.add(second.newRootLogger(Level.INFO).add(second.newAppenderRef("two")));
        context.start(second.build());
        assertEquals(List.of("two"), new Log4jAppenderSource().appenders().stream().map(Appender::name).toList());
    }

    @Test
    void theRealServiceListsPagesAndSearchesWhatTheRealAppenderWrote() throws Exception {
        ConfigurationBuilder<BuiltConfiguration> b = builder();
        b.add(b.newAppender("rolling", "RollingFile")
                .addAttribute("fileName", rollingActive().toString())
                .addAttribute("filePattern", rollingPattern())
                .add(layout(b, "windows-1252"))
                .addComponent(b.newComponent("Policies")
                        .addComponent(b.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", "10MB"))));
        b.add(b.newRootLogger(Level.INFO).add(b.newAppenderRef("rolling")));
        context.start(b.build());

        String cafe = "caf" + (char) 0xE9;
        org.apache.logging.log4j.Logger logger = context.getLogger("log-viewer-test");
        logger.info("first " + cafe);
        logger.error("second ERROR line");

        LogViewerService service = new LogViewerService();
        LogFileList list = service.listFiles();
        assertEquals(1, list.getFiles().size(), list.getWarnings().toString());
        LogFileInfo info = list.getFiles().get(0);
        assertEquals("mirth.log", info.getName());
        assertEquals("rolling", info.getAppenderName());
        assertEquals("windows-1252", info.getCharset());
        assertTrue(info.isViewable());
        assertTrue(info.isActive());

        // Written as the single byte 0xE9 and read back as the character.
        String text = service.readPage(info.getId(), LogPageAnchor.TAIL, null).getText();
        assertEquals("first " + cafe + System.lineSeparator() + "second ERROR line" + System.lineSeparator(), text);
        LogSearchResult result = service.search(cafe, false, true, null, null, null);
        assertEquals(1, result.getMatches().size());
        assertEquals("first " + cafe, result.getMatches().get(0).getLineText());
        assertTrue(result.isComplete());
    }
}
