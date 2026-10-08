// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import java.util.function.LongSupplier;

/**
 * Text as the regex engine sees it, with a clock check as it reads.
 *
 * <p>{@code java.util.regex} backtracks, so a pattern such as
 * {@code (.*a){10}x} can run for hours on one line, and checking the clock
 * between lines would never fire. Every 1,024 character reads this checks the
 * deadline and throws {@link Exceeded} once it has passed, which unwinds the
 * match. Shared by search and by page highlighting, which both run a
 * user-supplied pattern on the engine.</p>
 */
final class DeadlineCharSequence implements CharSequence {

    /** How many character reads pass between clock checks. */
    private static final int CLOCK_CHECK_MASK = 1024 - 1;

    /** Thrown from inside the regex engine when the deadline passes. No stack trace: it is control flow. */
    static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exceeded() {
            super(null, null, false, false);
        }
    }

    private final String text;
    private final LongSupplier nanoClock;
    private final long deadline;
    private int reads;

    DeadlineCharSequence(String text, LongSupplier nanoClock, long deadline) {
        this.text = text;
        this.nanoClock = nanoClock;
        this.deadline = deadline;
    }

    static boolean expired(LongSupplier nanoClock, long deadline) {
        return nanoClock.getAsLong() - deadline > 0;
    }

    @Override
    public char charAt(int index) {
        if ((++reads & CLOCK_CHECK_MASK) == 0 && expired(nanoClock, deadline)) {
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
