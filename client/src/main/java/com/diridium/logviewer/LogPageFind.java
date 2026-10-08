// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Find on this page with "Java regular expression" on, and the wording of every find's outcome.
 *
 * <p>Find runs on the event thread, and {@code java.util.regex} backtracks: a pattern such as
 * {@code (.*a){10}x} can run for hours on one long line, which would freeze the whole
 * Administrator. So the pattern is matched with a time limit enforced inside the matcher, as the
 * engine does when it searches (the server's DeadlineCharSequence): every 1,024 characters the
 * matcher reads, the clock is checked, and past the limit the match is abandoned. A pattern that
 * recurses too deeply for the matcher's stack on a line is caught as well. Plain text is found by
 * RSyntaxTextArea's own search, which cannot run long. Pure (no Swing), so tested without a
 * display.</p>
 */
final class LogPageFind {

    /** Time allowed for one find, or one Mark all. */
    static final long TIME_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** How a find ended. */
    enum Outcome {
        /** A match after the caret (or before it, finding backwards). */
        FOUND,
        /** None there, but one from the other end of the page. */
        WRAPPED,
        NOT_FOUND,
        TIME_LIMIT,
        TOO_COMPLEX
    }

    /** How a find ended, and where its match or matches are (start/end pairs, end exclusive). */
    static final class Result {
        final Outcome outcome;
        final List<int[]> ranges;

        Result(Outcome outcome, List<int[]> ranges) {
            this.outcome = outcome;
            this.ranges = ranges;
        }
    }

    private LogPageFind() {
    }

    /** As RSyntaxTextArea's own regular expression search compiles it: ^ and $ at each line, and the case option. */
    static Pattern compile(String regex, boolean matchCase) {
        return Pattern.compile(regex,
                Pattern.MULTILINE | (matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    /**
     * The first match after {@code from} (forward) or the last one before it (backward); when there
     * is none, the first or last in the whole text, from the other end. Empty matches are skipped.
     *
     * @param from forward, the end of the selection; backward, its start
     */
    static Result find(Pattern pattern, String text, int from, boolean forward, LongSupplier nanoClock) {
        Matcher matcher = timed(pattern, text, nanoClock);
        try {
            int[] hit = forward ? first(matcher, from, text.length()) : last(matcher, 0, from);
            if (hit != null) {
                return new Result(Outcome.FOUND, Collections.singletonList(hit));
            }
            hit = forward ? first(matcher, 0, text.length()) : last(matcher, 0, text.length());
            return hit != null ? new Result(Outcome.WRAPPED, Collections.singletonList(hit))
                    : new Result(Outcome.NOT_FOUND, Collections.emptyList());
        } catch (TimedText.Exceeded e) {
            return new Result(Outcome.TIME_LIMIT, Collections.emptyList());
        } catch (StackOverflowError e) {
            return new Result(Outcome.TOO_COMPLEX, Collections.emptyList());
        }
    }

    /** Every non-empty match in the text, for Mark all; none when the limit or the stack stopped it. */
    static Result all(Pattern pattern, String text, LongSupplier nanoClock) {
        Matcher matcher = timed(pattern, text, nanoClock);
        List<int[]> ranges = new ArrayList<>();
        try {
            while (matcher.find()) {
                if (matcher.end() > matcher.start()) {
                    ranges.add(new int[] {matcher.start(), matcher.end()});
                }
            }
        } catch (TimedText.Exceeded e) {
            return new Result(Outcome.TIME_LIMIT, Collections.emptyList());
        } catch (StackOverflowError e) {
            return new Result(Outcome.TOO_COMPLEX, Collections.emptyList());
        }
        return new Result(ranges.isEmpty() ? Outcome.NOT_FOUND : Outcome.FOUND, ranges);
    }

    /** What the find dialog says after Find next or Find previous; empty for a match found without wrapping. */
    static String status(Outcome outcome, boolean forward) {
        switch (outcome) {
            case WRAPPED:
                return "Wrapped to the " + (forward ? "start" : "end") + " of the page.";
            case NOT_FOUND:
                return "Not found on this page.";
            case TIME_LIMIT:
                return "The expression took too long on this page.";
            case TOO_COMPLEX:
                return "The expression is too complex for this page.";
            default:
                return "";
        }
    }

    /** What the find dialog says after Mark all marked {@code count} matches. */
    static String markedStatus(int count) {
        return count == 0 ? "Not found on this page." : count + (count == 1 ? " match" : " matches") + " on this page.";
    }

    /** A matcher over the text that gives up at the time limit; a region's bounds are seen through, not anchored to. */
    private static Matcher timed(Pattern pattern, String text, LongSupplier nanoClock) {
        Matcher matcher = pattern.matcher(new TimedText(text, nanoClock, nanoClock.getAsLong() + TIME_LIMIT_NANOS));
        matcher.useTransparentBounds(true);
        matcher.useAnchoringBounds(false);
        return matcher;
    }

    private static int[] first(Matcher matcher, int from, int to) {
        matcher.region(from, to);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                return new int[] {matcher.start(), matcher.end()};
            }
        }
        return null;
    }

    private static int[] last(Matcher matcher, int from, int to) {
        matcher.region(from, to);
        int[] last = null;
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                last = new int[] {matcher.start(), matcher.end()};
            }
        }
        return last;
    }

    /** The text as the matcher reads it, with a clock check every 1,024 characters it hands out. */
    private static final class TimedText implements CharSequence {

        /** Thrown from inside the matcher when the time is up. No stack trace: it is control flow. */
        static final class Exceeded extends RuntimeException {
            private static final long serialVersionUID = 1L;

            Exceeded() {
                super(null, null, false, false);
            }
        }

        private static final int CLOCK_CHECK_MASK = 1024 - 1;

        private final String text;
        private final LongSupplier nanoClock;
        private final long deadline;
        private int reads;

        TimedText(String text, LongSupplier nanoClock, long deadline) {
            this.text = text;
            this.nanoClock = nanoClock;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & CLOCK_CHECK_MASK) == 0 && nanoClock.getAsLong() - deadline > 0) {
                throw new Exceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
