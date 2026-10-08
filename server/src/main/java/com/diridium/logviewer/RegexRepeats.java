// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

/**
 * How many turns a regular expression's repeat counts can force on the
 * matcher, worked out from the pattern's text before it runs.
 *
 * <p>The time limit on a search is checked as the matcher reads characters
 * ({@link DeadlineCharSequence}). A repeated part that matches without reading
 * anything, such as an empty lookahead, never reads, so the limit never stops
 * it: {@code (?:(?:(?:(?:(?=)){1000}){1000}){1000}){1000}} goes round about a
 * trillion times between two reads (measured: well over 10 seconds without
 * one). Java ends a {@code *}, a {@code +} and the optional part of
 * {@code {n,m}} as soon as a turn reads nothing, so only the minimum of a count
 * is forced, and nested counts multiply. Anything that reads characters is
 * left to the time limit.</p>
 *
 * <p>Called only on a pattern that compiled, so the text is well formed.
 * Comments mode is reported separately ({@link #usesComments}): a {@code #}
 * comment can hide a parenthesis from any reading but Java's own.</p>
 */
final class RegexRepeats {

    /**
     * The most forced turns a pattern may have. At the measured pace (about 15 ns a turn) that
     * is 1.5 ms at each place a match is tried, and the clock is read every 1,024 characters,
     * so the 15-second limit is overshot by about 1.5 seconds at worst.
     */
    static final long MAX_FORCED_TURNS = 100_000;

    private final String s;
    private int i;

    private RegexRepeats(String regex) {
        this.s = regex;
    }

    /** The largest number of turns the counts in {@code regex} force, along any path through it. */
    static long forcedTurns(String regex) {
        RegexRepeats reader = new RegexRepeats(regex);
        long most = 1;
        while (reader.i < regex.length()) {
            // A stray ')' cannot occur in a pattern that compiled; step over it rather than stop.
            most = Math.max(most, reader.sequence());
            reader.i++;
        }
        return most;
    }

    /** True when the pattern switches on comments mode anywhere: (?x), (?ix), (?x:...) and the like. */
    static boolean usesComments(String regex) {
        RegexRepeats reader = new RegexRepeats(regex);
        int n = regex.length();
        while (reader.i < n) {
            char c = regex.charAt(reader.i);
            if (c == '\\') {
                reader.skipEscape();
            } else if (c == '[') {
                reader.skipClass();
            } else if (c == '(' && reader.i + 1 < n && regex.charAt(reader.i + 1) == '?') {
                int j = reader.i + 2;
                boolean off = false;
                while (j < n && (Character.isLetter(regex.charAt(j)) || regex.charAt(j) == '-')) {
                    if (regex.charAt(j) == '-') {
                        off = true;
                    } else if (regex.charAt(j) == 'x' && !off) {
                        return true;
                    }
                    j++;
                }
                reader.i++;
            } else {
                reader.i++;
            }
        }
        return false;
    }

    /** The largest forced count among the alternatives of a sequence, up to its closing ')' or the end. */
    private long sequence() {
        long most = 1;
        while (i < s.length() && s.charAt(i) != ')') {
            if (s.charAt(i) == '|') {
                i++;
                continue;
            }
            long turns = atom();
            long times;
            while ((times = quantifier()) > 0) {
                turns = times(turns, times);
            }
            most = Math.max(most, turns);
        }
        return most;
    }

    /** Reads one element; a group's value is the most its insides force. */
    private long atom() {
        char c = s.charAt(i);
        if (c == '(') {
            i++;
            skipGroupPrefix();
            long inside = sequence();
            if (i < s.length()) {
                i++; // the ')'
            }
            return inside;
        }
        if (c == '[') {
            skipClass();
        } else if (c == '\\') {
            skipEscape();
        } else {
            i++;
        }
        return 1;
    }

    /** The forced count of a quantifier at {@code i} (at least 1), or 0 when there is none. */
    private long quantifier() {
        if (i >= s.length()) {
            return 0;
        }
        char c = s.charAt(i);
        long min;
        if (c == '*' || c == '?' || c == '+') {
            i++;
            min = 1;
        } else if (c == '{' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1))) {
            int j = i + 1;
            long value = 0;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                value = Math.min(MAX_FORCED_TURNS + 1, value * 10 + (s.charAt(j) - '0'));
                j++;
            }
            int close = s.indexOf('}', j);
            i = close < 0 ? s.length() : close + 1;
            min = Math.max(1, value);
        } else {
            return 0;
        }
        // A lazy or possessive mark belongs to the same quantifier.
        if (i < s.length() && (s.charAt(i) == '?' || s.charAt(i) == '+')) {
            i++;
        }
        return min;
    }

    /** Steps over what follows a group's '(': ?:, ?=, ?!, ?>, ?<=, ?<!, ?<name>, or inline flags. */
    private void skipGroupPrefix() {
        int n = s.length();
        if (i >= n || s.charAt(i) != '?') {
            return;
        }
        i++;
        if (i >= n) {
            return;
        }
        char c = s.charAt(i);
        if (c == ':' || c == '=' || c == '!' || c == '>') {
            i++;
        } else if (c == '<') {
            if (i + 1 < n && (s.charAt(i + 1) == '=' || s.charAt(i + 1) == '!')) {
                i += 2;
            } else {
                int close = s.indexOf('>', i);
                i = close < 0 ? n : close + 1;
            }
        } else {
            while (i < n && (Character.isLetter(s.charAt(i)) || s.charAt(i) == '-')) {
                i++;
            }
            if (i < n && s.charAt(i) == ':') {
                i++;
            }
        }
    }

    /** Steps over a character class, nested classes and escapes included. */
    private void skipClass() {
        int depth = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                skipEscape();
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                i++;
                return;
            }
            i++;
        }
    }

    /** Steps over an escape, with the braces of \p{..}, \P{..}, \x{..}, \N{..}, the name of \k<..>, and \Q..\E. */
    private void skipEscape() {
        int n = s.length();
        if (i + 1 >= n) {
            i = n;
            return;
        }
        char c = s.charAt(i + 1);
        if (c == 'Q') {
            int end = s.indexOf("\\E", i + 2);
            i = end < 0 ? n : end + 2;
        } else if ((c == 'p' || c == 'P' || c == 'x' || c == 'N') && i + 2 < n && s.charAt(i + 2) == '{') {
            int close = s.indexOf('}', i + 2);
            i = close < 0 ? n : close + 1;
        } else if (c == 'k' && i + 2 < n && s.charAt(i + 2) == '<') {
            int close = s.indexOf('>', i + 2);
            i = close < 0 ? n : close + 1;
        } else {
            i += 2;
        }
    }

    private static long times(long a, long b) {
        return a > MAX_FORCED_TURNS / b ? MAX_FORCED_TURNS + 1 : a * b;
    }
}
