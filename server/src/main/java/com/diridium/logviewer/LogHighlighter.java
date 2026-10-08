// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where a search pattern matches inside one page, so the viewers can
 * highlight the matches without running a regular expression themselves.
 *
 * <p>The engine does this, not the viewers, so a page is highlighted with the
 * same Java regular expression engine that searched for it: the web
 * administrator would otherwise have to use JavaScript's, which is close to
 * Java's but not the same. Lines are matched one at a time under the search's
 * line-end rule (LF, CRLF or a lone CR), so {@code ^} and {@code $} mean the
 * same thing in both, and a match never spans a line end.</p>
 *
 * <p>Positions are char indices into the page text (UTF-16 code units, which
 * is what both a Java String and a JavaScript string index by), as
 * consecutive start/end pairs. The page text is sanitized one char for one
 * char, so a position in the decoded text matched here is the same position
 * in the text the viewer receives.</p>
 */
final class LogHighlighter {

    /**
     * Matches returned per page. A page is at most 1,000 lines, so this
     * covers five hits on every line, and stops a pattern that matches
     * nearly every character from growing the response: at the cap the
     * positions are about 10,000 numbers, roughly 120 KB of XML, under the
     * page text's own worst case.
     */
    static final int MAX_MATCHES = 5000;

    /**
     * Time allowed per page. A page is at most 256 KiB, which an ordinary
     * pattern matches in a few milliseconds (60-200 MB/s measured for search);
     * this only stops one that backtracks catastrophically. Shorter than the
     * search limit because it runs on every page turn while results are open.
     */
    static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** The positions found, and which limit cut the list short (null when none did). */
    record Result(int[] positions, LogHighlightStop stopped) {
    }

    private final Pattern pattern;
    private final LongSupplier nanoClock;

    LogHighlighter(Pattern pattern, LongSupplier nanoClock) {
        this.pattern = pattern;
        this.nanoClock = nanoClock;
    }

    Result find(String text) {
        long deadline = nanoClock.getAsLong() + DEADLINE_NANOS;
        int[] positions = new int[64];
        int count = 0;
        int n = text.length();
        int lineStart = 0;
        while (lineStart <= n) {
            if (DeadlineCharSequence.expired(nanoClock, deadline)) {
                return new Result(Arrays.copyOf(positions, count * 2), LogHighlightStop.TIME_LIMIT);
            }
            int lineEnd = lineStart;
            while (lineEnd < n && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            if (lineEnd > lineStart) {
                Matcher matcher = pattern.matcher(
                        new DeadlineCharSequence(text.substring(lineStart, lineEnd), nanoClock, deadline));
                try {
                    while (matcher.find()) {
                        if (matcher.end() == matcher.start()) {
                            continue; // nothing to paint; find() moves past it by itself
                        }
                        if (count == MAX_MATCHES) {
                            return new Result(Arrays.copyOf(positions, count * 2), LogHighlightStop.MATCH_LIMIT);
                        }
                        if (count * 2 == positions.length) {
                            positions = Arrays.copyOf(positions, positions.length * 2);
                        }
                        positions[count * 2] = lineStart + matcher.start();
                        positions[count * 2 + 1] = lineStart + matcher.end();
                        count++;
                    }
                } catch (DeadlineCharSequence.Exceeded e) {
                    // Too slow for this page: show what was found.
                    return new Result(Arrays.copyOf(positions, count * 2), LogHighlightStop.TIME_LIMIT);
                } catch (StackOverflowError e) {
                    // Too deep for the matcher on this line: show what was found.
                    return new Result(Arrays.copyOf(positions, count * 2), LogHighlightStop.TOO_COMPLEX);
                }
            }
            if (lineEnd >= n) {
                break;
            }
            boolean crlf = text.charAt(lineEnd) == '\r' && lineEnd + 1 < n && text.charAt(lineEnd + 1) == '\n';
            lineStart = lineEnd + (crlf ? 2 : 1);
        }
        return new Result(Arrays.copyOf(positions, count * 2), null);
    }
}
