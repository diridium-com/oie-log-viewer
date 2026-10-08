// SPDX-License-Identifier: MPL-2.0
// Copyright (c) 2026 Diridium Technologies Inc.

package com.diridium.logviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class LogViewerWindowBoundsTest {

    private static final Rectangle SCREEN = new Rectangle(0, 0, 1920, 1080);

    @Test
    void theDefaultIs1280By800CentredOnTheAdministratorWindow() {
        Rectangle frame = new Rectangle(100, 50, 1000, 700);
        // Centred on the frame, then kept inside the screen: x would be -40 and y 0.
        assertEquals(new Rectangle(0, 0, 1280, 800), LogViewerWindowBounds.defaultBounds(SCREEN, frame));
        Rectangle big = new Rectangle(200, 100, 1600, 900);
        assertEquals(new Rectangle(360, 150, 1280, 800), LogViewerWindowBounds.defaultBounds(SCREEN, big));
    }

    @Test
    void withoutAUsableAdministratorWindowItIsCentredOnTheScreen() {
        Rectangle centred = new Rectangle(320, 140, 1280, 800);
        assertEquals(centred, LogViewerWindowBounds.defaultBounds(SCREEN, null));
        assertEquals(centred, LogViewerWindowBounds.defaultBounds(SCREEN, new Rectangle(0, 0, 0, 0)));
        assertEquals(centred, LogViewerWindowBounds.defaultBounds(SCREEN, new Rectangle(0, 0, 1000, 700)));
    }

    @Test
    void aSmallScreenShrinksTheDefaultToFit() {
        Rectangle small = new Rectangle(0, 0, 1024, 768);
        assertEquals(new Rectangle(0, 0, 1024, 768), LogViewerWindowBounds.defaultBounds(small, null));
    }

    @Test
    void aSecondScreenIsRespected() {
        Rectangle second = new Rectangle(1920, 0, 1600, 900);
        Rectangle frame = new Rectangle(2000, 40, 1500, 800);
        Rectangle bounds = LogViewerWindowBounds.defaultBounds(second, frame);
        assertEquals(new Rectangle(2110, 40, 1280, 800), bounds);
    }

    @Test
    void boundsRoundTripThroughThePreferenceText() {
        Rectangle bounds = new Rectangle(-1200, 30, 1100, 640);
        assertEquals("-1200,30,1100,640", LogViewerWindowBounds.format(bounds));
        assertEquals(bounds, LogViewerWindowBounds.parse(LogViewerWindowBounds.format(bounds)));
    }

    @Test
    void textThatIsNotBoundsIsNotParsed() {
        assertNull(LogViewerWindowBounds.parse(null));
        assertNull(LogViewerWindowBounds.parse(""));
        assertNull(LogViewerWindowBounds.parse("1,2,3"));
        assertNull(LogViewerWindowBounds.parse("1,2,3,x"));
        assertNull(LogViewerWindowBounds.parse("1,2,3,4,5"));
    }

    @Test
    void rememberedBoundsOnAScreenAreUsed() {
        Rectangle remembered = new Rectangle(100, 80, 1000, 700);
        assertEquals(remembered, LogViewerWindowBounds.usable(remembered, Collections.singletonList(SCREEN)));
    }

    @Test
    void rememberedBoundsOnAScreenThatIsGoneAreNotUsed() {
        Rectangle onSecond = new Rectangle(2500, 80, 1000, 700);
        assertNull(LogViewerWindowBounds.usable(onSecond, Collections.singletonList(SCREEN)));
        List<Rectangle> both = Arrays.asList(SCREEN, new Rectangle(1920, 0, 1920, 1080));
        assertEquals(onSecond, LogViewerWindowBounds.usable(onSecond, both));
    }

    @Test
    void rememberedBoundsNeedTheTitleBarWithinReach() {
        // Far off the bottom, above the top, or with only a sliver of the title bar showing.
        assertNull(LogViewerWindowBounds.usable(new Rectangle(100, 5000, 1000, 700), Collections.singletonList(SCREEN)));
        assertNull(LogViewerWindowBounds.usable(new Rectangle(100, -50, 1000, 700), Collections.singletonList(SCREEN)));
        assertNull(LogViewerWindowBounds.usable(new Rectangle(1900, 80, 1000, 700), Collections.singletonList(SCREEN)));
        assertEquals(new Rectangle(1800, 80, 1000, 700),
                LogViewerWindowBounds.usable(new Rectangle(1800, 80, 1000, 700), Collections.singletonList(SCREEN)));
    }

    @Test
    void rememberedBoundsBelowTheMinimumSizeAreNotUsed() {
        assertNull(LogViewerWindowBounds.usable(new Rectangle(100, 80, 600, 700), Collections.singletonList(SCREEN)));
        assertNull(LogViewerWindowBounds.usable(new Rectangle(100, 80, 1000, 300), Collections.singletonList(SCREEN)));
        assertNull(LogViewerWindowBounds.usable(null, Collections.singletonList(SCREEN)));
    }

    @Test
    void rememberedBoundsLargerThanTheScreenAreShrunkToFitIt() {
        Rectangle laptop = new Rectangle(0, 0, 1366, 728);
        // left on a 1920 x 1080 screen at 300,200 and 1600 x 900
        assertEquals(new Rectangle(0, 0, 1366, 728),
                LogViewerWindowBounds.fit(new Rectangle(300, 200, 1600, 900), laptop));
        // only too tall: the width and x stay
        assertEquals(new Rectangle(100, 0, 1000, 728),
                LogViewerWindowBounds.fit(new Rectangle(100, 50, 1000, 900), laptop));
    }

    @Test
    void boundsThatFitAreLeftAlone() {
        Rectangle fits = new Rectangle(1200, 300, 1000, 700);
        assertEquals(fits, LogViewerWindowBounds.fit(fits, SCREEN));
    }

    @Test
    void theScreenOpenedOnIsTheOneHoldingMostOfTheWindow() {
        Rectangle second = new Rectangle(1920, 0, 1280, 1024);
        List<Rectangle> both = Arrays.asList(SCREEN, second);
        assertEquals(second, LogViewerWindowBounds.screenOf(new Rectangle(1800, 100, 1000, 700), both));
        assertEquals(SCREEN, LogViewerWindowBounds.screenOf(new Rectangle(1000, 100, 1000, 700), both));
        // nowhere on a screen: the first
        assertEquals(SCREEN, LogViewerWindowBounds.screenOf(new Rectangle(9000, 9000, 1000, 700), both));
    }

    @Test
    void theMinimumSizeShrinksWithTheScreen() {
        assertEquals(new Dimension(900, 520), LogViewerWindowBounds.minimumSize(new Dimension(1920, 1080)));
        assertEquals(new Dimension(800, 520), LogViewerWindowBounds.minimumSize(new Dimension(800, 1080)));
    }
}
