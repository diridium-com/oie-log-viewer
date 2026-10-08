// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The turns a pattern's repeat counts force on the matcher, and the patterns that use comments mode. */
class RegexRepeatsTest {

    private static long turns(String regex) {
        return RegexRepeats.forcedTurns(regex);
    }

    @Test
    void nestedCountsMultiply() {
        assertEquals(1, turns("ERROR|WARN"));
        assertEquals(1000, turns("(?:(?=)){1000}"));
        assertEquals(100_000, turns("(?:(?:(?=)){100}){1000}"));
        assertEquals(6, turns("(?:a{2}){3}"));
        // The largest path counts, not the sum of the parts.
        assertEquals(50, turns("a{5}b{50}(?:c{10})"));
        assertEquals(500, turns("(?:x{5}|y{50}){10}"));
    }

    @Test
    void onlyTheMinimumOfACountIsForced() {
        // Java ends a *, + or the optional part of {n,m} as soon as a turn reads nothing.
        assertEquals(1, turns("(?:(?=))*"));
        assertEquals(1, turns("(?:(?=)){0,1000000}"));
        assertEquals(3, turns("(?:(?=)){3,1000000}"));
        assertEquals(7, turns("(?:(?=)){7,}"));
        assertEquals(8, turns("(?:a{2}+){4}?"));
    }

    @Test
    void aCountOverTheLimitReadsAsOneMoreThanTheLimit() {
        // The count stops at the limit, so the product of the attack pattern cannot overflow.
        assertEquals(RegexRepeats.MAX_FORCED_TURNS + 1, turns("(?:(?:(?:(?:(?=)){1000}){1000}){1000}){1000}"));
        assertEquals(RegexRepeats.MAX_FORCED_TURNS + 1, turns("(?:(?=)){99999999999999999999}"));
        assertEquals(RegexRepeats.MAX_FORCED_TURNS + 1, turns("(?:(?:(?=)){100}){1001}"));
    }

    @Test
    void bracesThatAreNotCountsAreIgnored() {
        assertEquals(1, turns("[{]{1}"));                 // a class holding a brace, then a count of 1
        assertEquals(1, turns("\\{1000\\}"));             // escaped braces
        assertEquals(1, turns("\\p{Alpha}+\\x{1F600}"));  // braces of escapes
        assertEquals(1, turns("\\Q(?:a){1000}\\E"));      // quoted text
        assertEquals(1, turns("[a-z&&[^bc]]+[\\[{(]"));   // nested classes, escaped bracket
        assertEquals(1000, turns("(?<name>a){1000}\\k<name>"));
        assertEquals(1000, turns("(?<=x)(?<!y)(?>z){1000}(?i:q)"));
    }

    @Test
    void groupsWithFlagsAndLookaroundsNestLikeAnyOther() {
        assertEquals(100_000, turns("(?i)(?=(?:(?=)){100}){1000}"));
        assertEquals(100_000, turns("(?s:(?:(?=)){100}){1000}"));
    }

    @Test
    void commentsModeIsDetectedWhereverItIsSwitchedOn() {
        assertTrue(RegexRepeats.usesComments("(?x)a # note"));
        assertTrue(RegexRepeats.usesComments("a(?ix:b)"));
        assertTrue(RegexRepeats.usesComments("(?i-s)a(?x)b"));
        assertFalse(RegexRepeats.usesComments("(?i)x"));
        assertFalse(RegexRepeats.usesComments("(?-x)a"));
        assertFalse(RegexRepeats.usesComments("\\(\\?x\\)"));
        assertFalse(RegexRepeats.usesComments("[(?x)]"));
    }
}
