// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log4j rollover pattern ({@code filePattern}), split into the fixed
 * directory its archives live under and a regex for their path below it.
 *
 * <p>The pattern can put archives in folders named by the rollover date, for
 * example {@code logs/${date:yyyy-MM}/app-%d{yyyy-MM-dd}-%i.log.gz} (log4j
 * reports the configured {@code $${date:...}} as {@code ${date:...}}), and
 * log4j then keeps a numbered set in each folder. The base is every leading
 * directory with nothing variable in it; the regex covers the rest, with
 * {@code /} between levels, so the catalog can walk the folders below the base
 * and list only paths of the pattern's shape.</p>
 *
 * <p>Built by hand rather than with {@code Paths.get(pattern)}, because a date
 * pattern such as {@code %d{HH:mm}} is not a legal Windows path.</p>
 */
record RolloverPattern(String baseDir, Pattern relative) {

    /** Splits a pattern; the base is "" when the very first directory is variable or there is none. */
    static RolloverPattern parse(String pattern) {
        List<Integer> separators = separatorsOutsideBraces(pattern);
        int restStart = 0;
        for (int separator : separators) {
            if (isVariable(pattern.substring(restStart, separator))) {
                break;
            }
            restStart = separator + 1;
        }
        String base = restStart == 0 ? "" : pattern.substring(0, restStart - 1);
        String rest = pattern.substring(restStart);
        StringBuilder regex = new StringBuilder();
        int from = 0;
        for (int separator : separators) {
            if (separator >= restStart) {
                regex.append(toRegex(rest.substring(from, separator - restStart))).append('/');
                from = separator - restStart + 1;
            }
        }
        regex.append(toRegex(rest.substring(from)));
        return new RolloverPattern(base, Pattern.compile(regex.toString()));
    }

    /**
     * Whether a folder at {@code path} (relative to the base, {@code /}
     * between levels) can hold archives further down: the regex ran out of
     * input there rather than failing.
     */
    boolean couldContain(String path) {
        Matcher matcher = relative.matcher(path + "/");
        return !matcher.matches() && matcher.hitEnd();
    }

    /** True when a path segment holds a conversion other than {@code %%}, or a lookup. */
    private static boolean isVariable(String segment) {
        return segment.replace("%%", "").contains("%") || segment.contains("${");
    }

    /**
     * Indices of the path separators that are not inside a conversion's
     * {@code {...}} options (a date format may contain a slash). Backslash
     * counts only on Windows, where log4j configurations use it.
     */
    static List<Integer> separatorsOutsideBraces(String pattern) {
        List<Integer> separators = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (c == '/' || (c == '\\' && File.separatorChar == '\\'))) {
                separators.add(i);
            }
        }
        return separators;
    }

    /**
     * Converts one path segment of a rollover pattern to a regex: {@code %i}
     * matches digits, {@code %d{format}} and {@code ${date:format}} match what
     * the date format writes ({@link #dateRegex}), any other conversion or
     * lookup matches one path level, {@code %%} is a literal percent, and
     * everything else is literal.
     */
    static String toRegex(String segment) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        int n = segment.length();
        while (i < n) {
            char c = segment.charAt(i);
            if (c == '%' && i + 1 < n && segment.charAt(i + 1) == '%') {
                literal.append('%');
                i += 2;
            } else if (c == '%') {
                int j = i + 1;
                while (j < n && "-.0123456789".indexOf(segment.charAt(j)) >= 0) {
                    j++;
                }
                int nameStart = j;
                while (j < n && Character.isLetter(segment.charAt(j))) {
                    j++;
                }
                String conversion = segment.substring(nameStart, j);
                String option = null;
                boolean unclosed = false;
                // %d{format}{zone}: every brace group belongs to the conversion.
                while (j < n && segment.charAt(j) == '{') {
                    int close = skipBraces(segment, j);
                    if (close < 0) {
                        unclosed = true; // a malformed option: take the rest and trust none of it
                        j = n;
                        break;
                    }
                    if (option == null) {
                        option = segment.substring(j + 1, close - 1);
                    }
                    j = close;
                }
                flushLiteral(regex, literal);
                if (conversion.equals("i") || conversion.equals("index")) {
                    regex.append("\\d+");
                } else if ((conversion.equals("d") || conversion.equals("date")) && option != null && !unclosed) {
                    regex.append(dateRegex(option));
                } else {
                    regex.append(ANY_LEVEL);
                }
                i = j;
            } else if (c == '$' && i + 1 < n && segment.charAt(i + 1) == '{') {
                int close = skipBraces(segment, i + 1);
                String lookup = close < 0 ? "" : segment.substring(i + 2, close - 1);
                flushLiteral(regex, literal);
                regex.append(lookup.startsWith("date:") ? dateRegex(lookup.substring(5)) : ANY_LEVEL);
                i = close < 0 ? n : close;
            } else {
                literal.append(c);
                i++;
            }
        }
        flushLiteral(regex, literal);
        return regex.toString();
    }

    /** Any non-empty text within one path level. */
    private static final String ANY_LEVEL = "[^/]+";

    /**
     * A regex for the text a {@link java.text.SimpleDateFormat}-style format
     * writes: digits for numeric fields, letters for names, quoted text and
     * other characters literally. Deliberately loose about widths (a field
     * may be longer than its letters) and about locales (any letters, any
     * decimal digits). A named format such as {@code ISO8601} matches one path
     * level.
     */
    static String dateRegex(String format) {
        if (format.isEmpty() || format.matches("[A-Z0-9_]+")) {
            return ANY_LEVEL;
        }
        StringBuilder regex = new StringBuilder();
        int i = 0;
        int n = format.length();
        while (i < n) {
            char c = format.charAt(i);
            if (c == '\'') {
                int close = format.indexOf('\'', i + 1);
                if (close == i + 1) {
                    regex.append('\'');
                    i += 2;
                    continue;
                }
                if (close < 0) {
                    close = n;
                }
                regex.append(Pattern.quote(format.substring(i + 1, close)));
                i = close + 1;
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                int j = i;
                while (j < n && format.charAt(j) == c) {
                    j++;
                }
                regex.append(dateField(c, j - i));
                i = j;
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return regex.toString();
    }

    private static String dateField(char letter, int count) {
        switch (letter) {
            case 'y':
            case 'Y':
            case 'u':
                return count == 2 ? "\\p{Nd}{2}" : "\\p{Nd}{" + count + ",}";
            case 'M':
            case 'L':
                return count >= 3 ? "[\\p{L}.]+" : "\\p{Nd}{" + count + ",2}";
            case 'E':
            case 'a':
            case 'G':
                return "[\\p{L}.]+";
            case 'z':
            case 'Z':
            case 'X':
            case 'x':
            case 'O':
                return "[\\w+\\-:]+";
            default:
                // d, H, k, K, h, m, s, S, D, F, w, W and the rest: a number at least that wide.
                return "\\p{Nd}{" + count + ",}";
        }
    }

    /** The index just past the brace that closes the one at {@code open}, or -1 when none does. */
    private static int skipBraces(String s, int open) {
        int depth = 0;
        for (int j = open; j < s.length(); j++) {
            if (s.charAt(j) == '{') {
                depth++;
            } else if (s.charAt(j) == '}' && --depth == 0) {
                return j + 1;
            }
        }
        return -1;
    }

    private static void flushLiteral(StringBuilder regex, StringBuilder literal) {
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }
}
