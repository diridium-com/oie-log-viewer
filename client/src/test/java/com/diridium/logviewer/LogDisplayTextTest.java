// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The two display modes must hold the same number of lines for every kind of
 * terminator, or the gutter's line numbers jump when the user toggles special
 * characters, and the page must hold exactly its own lines (no empty line after
 * the last terminator).
 */
class LogDisplayTextTest {

    private static final String CR = LogDisplayText.CR_LABEL;
    private static final String LF = LogDisplayText.LF_LABEL;

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static void assertSameLines(String raw, int expectedLines) {
        String normal = LogDisplayText.normal(raw);
        String special = LogDisplayText.special(raw).getText();
        assertEquals(expectedLines, LogDisplayText.lineCount(normal), "normal mode: " + normal);
        assertEquals(expectedLines, LogDisplayText.lineCount(special), "special mode: " + special);
    }

    @Test
    void lfCrlfAndLoneCrEachMakeOneLineBreakInBothModes() {
        assertSameLines("a\nb\nc", 3);
        assertSameLines("a\r\nb\r\nc", 3);
        assertSameLines("a\rb\rc", 3);
        assertSameLines("a\nb\r\nc\rd", 4);
    }

    @Test
    void crCrLfIsTwoTerminators() {
        // A lone CR followed by a CRLF: two line ends, never one.
        assertSameLines("a\r\r\nb", 3);
        assertEquals("a\n\nb", LogDisplayText.normal("a\r\r\nb"));
        assertEquals("a" + CR + "\n" + CR + LF + "\nb", LogDisplayText.special("a\r\r\nb").getText());
    }

    @Test
    void textWithoutATrailingTerminatorKeepsItsLastLine() {
        assertSameLines("one\ntwo", 2);
        assertEquals("one\ntwo", LogDisplayText.normal("one\ntwo"));
        assertEquals("one" + LF + "\ntwo", LogDisplayText.special("one\ntwo").getText());
    }

    @Test
    void textEndingInATerminatorHasNoEmptyLineAfterIt() {
        // The viewer holds exactly the page's lines: the last terminator ends the last line.
        assertSameLines("one\ntwo\n", 2);
        assertSameLines("one\r\ntwo\r\n", 2);
        assertSameLines("one\rtwo\r", 2);
        assertEquals("one\ntwo", LogDisplayText.normal("one\ntwo\n"));
        assertEquals("one" + LF + "\ntwo" + LF, LogDisplayText.special("one\ntwo\n").getText());
        assertEquals("one" + CR + LF + "\ntwo" + CR + LF, LogDisplayText.special("one\r\ntwo\r\n").getText());
        assertEquals("one" + CR + "\ntwo" + CR, LogDisplayText.special("one\rtwo\r").getText());
    }

    @Test
    void aPageOfOneEmptyLineKeepsThatLine() {
        assertSameLines("\n", 1);
        assertEquals("", LogDisplayText.normal("\n"));
        assertEquals(LF, LogDisplayText.special("\n").getText());
        assertSameLines("\n\n", 2);
    }

    @Test
    void emptyTextIsOneEmptyLineAndNoTextLines() {
        assertSameLines("", 1);
        assertSameLines(null, 1);
        assertEquals("", LogDisplayText.normal(null));
        assertTrue(LogDisplayText.special("").getLineEnds().isEmpty());
    }

    @Test
    void normalModeNeverShowsASymbolForALineEnd() {
        String normal = LogDisplayText.normal("a\r\nb\rc\nd");
        assertEquals("a\nb\nc\nd", normal);
    }

    @Test
    void specialModeShowsTheRealTerminatorBeforeEachNewline() {
        assertEquals("a" + CR + LF + "\nb", LogDisplayText.special("a\r\nb").getText());
        assertEquals("a" + LF + "\nb", LogDisplayText.special("a\nb").getText());
        assertEquals("a" + CR + "\nb", LogDisplayText.special("a\rb").getText());
    }

    @Test
    void theLabelsAreLocatedAndNamedInWords() {
        LogDisplayText.Special special = LogDisplayText.special("a\r\nb\nc\rd");
        String text = special.getText();
        assertEquals("aCRLF\nbLF\ncCR\nd", text);
        List<LogDisplayText.LineEnd> ends = special.getLineEnds();
        assertEquals(4, ends.size());
        // CRLF is two labels with the same meaning; each range covers exactly its letters.
        assertEquals("CR", text.substring(ends.get(0).getStart(), ends.get(0).getEnd()));
        assertEquals("LF", text.substring(ends.get(1).getStart(), ends.get(1).getEnd()));
        assertEquals("Line end: carriage return + line feed (Windows)", ends.get(0).getMeaning());
        assertEquals(ends.get(0).getMeaning(), ends.get(1).getMeaning());
        assertEquals("LF", text.substring(ends.get(2).getStart(), ends.get(2).getEnd()));
        assertEquals("Line end: line feed (Unix)", ends.get(2).getMeaning());
        assertEquals("CR", text.substring(ends.get(3).getStart(), ends.get(3).getEnd()));
        assertEquals("Line end: carriage return (HL7 segment separator)", ends.get(3).getMeaning());
    }

    @Test
    void theMeaningsNameTheTerminatorNotTheLettersOfTheLabel() {
        for (LogDisplayText.LineEnd end : LogDisplayText.special("a\r\nb\nc\rd").getLineEnds()) {
            assertFalse(end.getMeaning().contains("U+"), end.getMeaning());
            assertTrue(end.getMeaning().startsWith("Line end: "), end.getMeaning());
        }
    }

    @Test
    void aLastLineWithoutATerminatorHasNoLabel() {
        assertEquals(1, LogDisplayText.special("a\nb").getLineEnds().size());
        assertTrue(LogDisplayText.special("no end").getLineEnds().isEmpty());
    }

    @Test
    void tabsAndSpacesAreLeftForTheViewerToDraw() {
        assertEquals("a\tb c", LogDisplayText.normal("a\tb c"));
        assertEquals("a\tb c", LogDisplayText.special("a\tb c").getText());
    }

    // ---- search highlight positions ---------------------------------------------

    /** The text each mapped pair covers, so a test reads as "the same words, found in the display text". */
    private static String covered(String display, int[] pairs, int index) {
        return display.substring(pairs[index * 2], pairs[index * 2 + 1]);
    }

    /** Positions of every occurrence of {@code word} in {@code raw}, as the engine would report them. */
    private static int[] positionsOf(String raw, String word) {
        List<Integer> found = new java.util.ArrayList<>();
        for (int i = raw.indexOf(word); i >= 0; i = raw.indexOf(word, i + 1)) {
            found.add(i);
            found.add(i + word.length());
        }
        return found.stream().mapToInt(Integer::intValue).toArray();
    }

    @Test
    void highlightsInAnLfPageKeepTheirPositions() {
        String raw = "alpha beta\ngamma beta\n";
        int[] raws = positionsOf(raw, "beta");
        int[] mapped = LogDisplayText.mapHighlights(raw, LogDisplayText.normal(raw), raws);
        assertEquals(4, mapped.length);
        assertEquals("beta", covered(LogDisplayText.normal(raw), mapped, 0));
        assertEquals("beta", covered(LogDisplayText.normal(raw), mapped, 1));
        assertEquals(raws[0], mapped[0]);
    }

    @Test
    void highlightsAfterACrlfMoveBackByOneForEachEarlierLine() {
        String raw = "one\r\ntwo hit\r\nthree hit\r\nfour hit";
        String display = LogDisplayText.normal(raw);
        int[] mapped = LogDisplayText.mapHighlights(raw, display, positionsOf(raw, "hit"));
        assertEquals(6, mapped.length);
        for (int i = 0; i < 3; i++) {
            assertEquals("hit", covered(display, mapped, i));
        }
        // "two hit": the raw column is 4 on raw line 1, which starts after "one\r\n" (5 chars) but
        // at 4 in the display text.
        assertEquals(4 + 4, mapped[0]);
    }

    @Test
    void highlightsInALoneCrPageLikeHl7Segments() {
        String raw = "MSH|^~\\&|A\rPID|1||hit|\rOBX|1|hit\r";
        String display = LogDisplayText.normal(raw);
        int[] mapped = LogDisplayText.mapHighlights(raw, display, positionsOf(raw, "hit"));
        assertEquals(4, mapped.length);
        assertEquals("hit", covered(display, mapped, 0));
        assertEquals("hit", covered(display, mapped, 1));
        assertEquals(2, display.substring(0, mapped[2]).chars().filter(c -> c == '\n').count());
    }

    @Test
    void highlightsInSpecialModeSkipTheLabelsAndStillCoverTheSameWords() {
        String raw = "a hit\r\nb hit\nc hit\rd hit";
        String display = LogDisplayText.special(raw).getText();
        int[] mapped = LogDisplayText.mapHighlights(raw, display, positionsOf(raw, "hit"));
        assertEquals(8, mapped.length);
        for (int i = 0; i < 4; i++) {
            assertEquals("hit", covered(display, mapped, i));
        }
    }

    @Test
    void aMatchAtTheVeryEndOfALineAndOfThePageMapsToo() {
        String raw = "x hit\ny hit";
        String display = LogDisplayText.normal(raw);
        int[] mapped = LogDisplayText.mapHighlights(raw, display, positionsOf(raw, "hit"));
        assertEquals(4, mapped.length);
        assertEquals("hit", covered(display, mapped, 1));
        assertEquals(display.length(), mapped[3]);
    }

    @Test
    void theSameRawPositionsGiveTheSameWordsInBothModes() {
        String raw = "INFO hit\r\n  at hit\r\n\r\nlast hit\r";
        int[] raws = positionsOf(raw, "hit");
        for (String display : new String[] {LogDisplayText.normal(raw), LogDisplayText.special(raw).getText()}) {
            int[] mapped = LogDisplayText.mapHighlights(raw, display, raws);
            assertEquals(raws.length, mapped.length);
            for (int i = 0; i < mapped.length / 2; i++) {
                assertEquals("hit", covered(display, mapped, i), display);
            }
        }
    }

    @Test
    void highlightsThatDoNotFitAreLeftOutAndNoneIsEmpty() {
        String raw = "ab\ncd";
        String display = LogDisplayText.normal(raw);
        assertEquals(0, LogDisplayText.mapHighlights(raw, display, null).length);
        assertEquals(0, LogDisplayText.mapHighlights(raw, display, new int[0]).length);
        // empty, backwards, negative and past the end
        assertEquals(0, LogDisplayText.mapHighlights(raw, display, new int[] {1, 1, 2, 1, -1, 1, 99, 100}).length);
        // a pair that sits on a line end only is empty once clipped to its line
        assertEquals(0, LogDisplayText.mapHighlights(raw, display, new int[] {2, 3}).length);
        assertEquals(2, LogDisplayText.mapHighlights(raw, display, new int[] {0, 2}).length);
    }

    @Test
    void aCharacterOutsideTheBasicMultilingualPlaneKeepsItsTwoChars() {
        String raw = "x " + cp(0x1F600) + " hit\r\ny";
        String display = LogDisplayText.normal(raw);
        int[] mapped = LogDisplayText.mapHighlights(raw, display, positionsOf(raw, "hit"));
        assertEquals("hit", covered(display, mapped, 0));
    }

    // ---- jumping to a match: the engine's index in the display text --------------

    @Test
    void aTargetIndexLandsOnTheSameCharacterInBothModes() {
        String raw = "one\r\ntwo hit\rthree hit\n";
        int target = raw.indexOf("hit");
        for (String display : new String[] {LogDisplayText.normal(raw), LogDisplayText.special(raw).getText()}) {
            int at = LogDisplayText.displayIndex(raw, display, target);
            assertEquals("hit", display.substring(at, at + 3), display);
        }
        int second = raw.lastIndexOf("hit");
        String special = LogDisplayText.special(raw).getText();
        int at = LogDisplayText.displayIndex(raw, special, second);
        assertEquals("hit", special.substring(at, at + 3));
    }

    @Test
    void theEndOfTheFileIsTheEndOfTheDisplayText() {
        String raw = "a\nb\n";
        String normal = LogDisplayText.normal(raw);
        assertEquals(normal.length(), LogDisplayText.displayIndex(raw, normal, 4));
        String special = LogDisplayText.special(raw).getText();
        assertEquals(special.length(), LogDisplayText.displayIndex(raw, special, 4));
        assertEquals(3, LogDisplayText.displayIndex("abc", "abc", 3));
    }

    @Test
    void anIndexOutsideThePageIsNotPlaced() {
        assertEquals(-1, LogDisplayText.displayIndex("abc", "abc", -1));
        assertEquals(-1, LogDisplayText.displayIndex("abc", "abc", 4));
        assertEquals(-1, LogDisplayText.displayIndex(null, "abc", 0));
    }

    // ---- copy: the page's own text behind a selection ---------------------------

    @Test
    void copyInNormalModeGivesBackTheFilesOwnLineEnds() {
        String raw = "ab\r\ncd\nef\rgh\r\n";
        String display = LogDisplayText.normal(raw);
        assertEquals("ab\ncd\nef\ngh", display);
        assertEquals("ab\r\ncd\nef\rgh", LogDisplayText.rawText(raw, display, 0, display.length()));
        // across one line end: the whole terminator, whatever it is
        assertEquals("b\r\nc", LogDisplayText.rawText(raw, display, 1, 4));
        assertEquals("f\rg", LogDisplayText.rawText(raw, display, 7, 10));
        // the selected line end alone
        assertEquals("\r\n", LogDisplayText.rawText(raw, display, 2, 3));
    }

    @Test
    void copyOfHl7SegmentsKeepsTheirCarriageReturns() {
        String raw = "MSH|^~\\&|A\rPID|1||x\rOBX|1\r";
        String display = LogDisplayText.normal(raw);
        assertEquals("MSH|^~\\&|A\rPID|1||x\rOBX|1", LogDisplayText.rawText(raw, display, 0, display.length()));
    }

    @Test
    void copyInSpecialModeLeavesTheLabelsOutAndKeepsWhatTheyStandFor() {
        String raw = "ab\r\ncd\n";
        String display = LogDisplayText.special(raw).getText();
        assertEquals("ab" + CR + LF + "\ncd" + LF, display);
        // everything shown, the last line end's label included
        assertEquals(raw, LogDisplayText.rawText(raw, display, 0, display.length()));
        assertEquals("ab", LogDisplayText.rawText(raw, display, 0, 2));
        // the CR label, all of it or one letter of it, is the carriage return
        assertEquals("ab\r", LogDisplayText.rawText(raw, display, 0, 4));
        assertEquals("ab\r", LogDisplayText.rawText(raw, display, 0, 3));
        // the LF label of a CRLF alone is its line feed
        assertEquals("\n", LogDisplayText.rawText(raw, display, 4, 6));
        // from inside the CR label to the next line
        assertEquals("\r\ncd", LogDisplayText.rawText(raw, display, 3, 9));
    }

    @Test
    void copyWithNothingSelectedOrOutOfRangeIsEmpty() {
        String raw = "ab\ncd";
        String display = LogDisplayText.normal(raw);
        assertEquals("", LogDisplayText.rawText(raw, display, 2, 2));
        assertEquals("", LogDisplayText.rawText(raw, display, 3, 1));
        assertEquals("", LogDisplayText.rawText(raw, display, -1, 2));
        assertEquals("", LogDisplayText.rawText(raw, display, 0, 99));
        assertEquals("", LogDisplayText.rawText(null, display, 0, 1));
    }

    @Test
    void copyKeepsTabsAndCharactersOutsideTheBasicPlaneAsTheyAre() {
        String raw = "a\t" + cp(0x1F600) + "b\r\nc";
        String display = LogDisplayText.special(raw).getText();
        assertEquals(raw, LogDisplayText.rawText(raw, display, 0, display.length()));
    }

    // ---- invisible characters -------------------------------------------------

    @Test
    void invisibleCharactersAreRecognised() {
        int[] invisible = {0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x00A0, 0x0080, 0x009F,
                0x2028, 0x2029, 0x200E, 0x200F, 0x061C, 0x2003, 0x3000, 0xE0001};
        for (int cp : invisible) {
            assertTrue(LogDisplayText.isInvisibleButPresent(cp), String.format("U+%04X", cp));
        }
    }

    @Test
    void visibleCharactersAreNotMarkedInvisible() {
        int[] visible = {'a', ' ', '\t', '-', 0x00E9, 0x2400, 0x2426, 0xFFFD, 0x1F600, 0x4E2D};
        for (int cp : visible) {
            assertFalse(LogDisplayText.isInvisibleButPresent(cp), String.format("U+%04X", cp));
        }
    }

    @Test
    void invisibleRangesMergeNeighboursAndCountSurrogatePairs() {
        // a, ZWSP, ZWJ, b, no-break space, grinning face (2 chars), BOM
        String text = "a" + cp(0x200B) + cp(0x200D) + "b" + cp(0x00A0) + cp(0x1F600) + cp(0xFEFF);
        List<int[]> ranges = LogDisplayText.invisibleRanges(text);
        assertEquals(3, ranges.size());
        assertEquals(1, ranges.get(0)[0]);
        assertEquals(3, ranges.get(0)[1]);
        assertEquals(4, ranges.get(1)[0]);
        assertEquals(5, ranges.get(1)[1]);
        assertEquals(7, ranges.get(2)[0]);
        assertEquals(8, ranges.get(2)[1]);
    }

    @Test
    void undisplayableRangesAskTheFontOnlyAboutNonAsciiVisibleCharacters() {
        // The font "cannot draw" the grinning face and the e acute, but not the CJK character.
        String text = "ab" + cp(0x1F600) + cp(0x00E9) + cp(0x4E2D) + cp(0x200B);
        List<int[]> ranges = LogDisplayText.undisplayableRanges(text, cp -> cp == 0x1F600 || cp == 0xE9 ? false : true);
        // Face (2 chars) and e acute are adjacent, so one run from 2 to 5. The ZWSP is invisible,
        // so it is not reported here even though this font would not draw it either.
        assertEquals(1, ranges.size());
        assertEquals(2, ranges.get(0)[0]);
        assertEquals(5, ranges.get(0)[1]);
    }

    @Test
    void undisplayableRangesNeverAskAboutAscii() {
        List<int[]> ranges = LogDisplayText.undisplayableRanges("plain\tascii\n", cp -> false);
        assertTrue(ranges.isEmpty());
    }

    // ---- tooltips -------------------------------------------------------------

    @Test
    void anOrdinaryCharacterIsJustNamed() {
        assertEquals("U+0041 LATIN CAPITAL LETTER A", LogDisplayText.tooltip('A'));
        assertNull(LogDisplayText.explain('A'));
    }

    @Test
    void anAstralCharacterShowsItsFullCodePoint() {
        assertEquals("U+1F600 GRINNING FACE", LogDisplayText.tooltip(0x1F600));
    }

    @Test
    void aControlPictureSaysWhichControlItStandsFor() {
        // U+240B is the picture for the vertical tab that frames an MLLP message
        String tooltip = LogDisplayText.tooltip(0x240B);
        assertTrue(tooltip.startsWith("U+240B SYMBOL FOR VERTICAL TABULATION"), tooltip);
        assertTrue(tooltip.contains("Control character LINE TABULATION shown as a symbol."), tooltip);
    }

    @Test
    void theDeleteSymbolIsExplainedToo() {
        assertTrue(LogDisplayText.tooltip(0x2421).contains("Control character DELETE shown as a symbol."));
    }

    @Test
    void theBidirectionalSubstituteIsExplained() {
        String tooltip = LogDisplayText.tooltip(0x2426);
        assertTrue(tooltip.startsWith("U+2426 "), tooltip);
        assertTrue(tooltip.contains("bidirectional override character"), tooltip);
        assertTrue(tooltip.contains("display in a different order than it is stored"), tooltip);
    }

    @Test
    void theReplacementCharacterIsExplained() {
        String tooltip = LogDisplayText.tooltip(0xFFFD);
        assertTrue(tooltip.startsWith("U+FFFD REPLACEMENT CHARACTER"), tooltip);
        assertTrue(tooltip.contains("not valid text in the file's character set"), tooltip);
    }

    @Test
    void anInvisibleCharacterSaysSo() {
        String tooltip = LogDisplayText.tooltip(0x200B);
        assertTrue(tooltip.startsWith("U+200B ZERO WIDTH SPACE"), tooltip);
        assertTrue(tooltip.contains("invisible"), tooltip);
    }

    @Test
    void anUnassignedCodePointHasAFallbackName() {
        assertEquals("U+0378 unassigned code point", LogDisplayText.describe(0x0378));
    }
}
