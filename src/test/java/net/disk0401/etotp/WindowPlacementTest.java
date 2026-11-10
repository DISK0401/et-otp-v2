/*
 * WindowPlacementTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;


class WindowPlacementTest
{
    private static final List<Rectangle> ONE_SCREEN = Collections.singletonList(new Rectangle(0, 0, 1920, 1080));
    /** a second monitor left of the primary one */
    private static final List<Rectangle> TWO_SCREENS = Arrays.asList(new Rectangle(0, 0, 1920, 1080),
                                                                     new Rectangle(-1280, 0, 1280, 1024));

    @Test
    void windowOnTheScreenIsReachable()
    {
        assertTrue(WindowPlacement.isReachable(new Rectangle(100, 100, 400, 250), ONE_SCREEN));
    }

    @Test
    void windowOnADisconnectedMonitorIsNotReachable()
    {
        Rectangle window = new Rectangle(-900, 200, 400, 250);

        assertTrue(WindowPlacement.isReachable(window, TWO_SCREENS));
        assertFalse(WindowPlacement.isReachable(window, ONE_SCREEN));
    }

    @Test
    void windowBelowASmallerScreenIsNotReachable()
    {
        assertFalse(WindowPlacement.isReachable(new Rectangle(300, 1300, 400, 250), ONE_SCREEN));
    }

    @Test
    void titleBarAboveTheScreenIsNotReachable()
    {
        // only the lower part is visible; the title bar cannot be dragged
        assertFalse(WindowPlacement.isReachable(new Rectangle(100, -200, 400, 250), ONE_SCREEN));
    }

    @Test
    void partlyVisibleTitleBarIsEnough()
    {
        assertTrue(WindowPlacement.isReachable(new Rectangle(1800, 500, 400, 250), ONE_SCREEN));
        assertFalse(WindowPlacement.isReachable(new Rectangle(1890, 500, 400, 250), ONE_SCREEN));
    }

    @Test
    void noScreensMeansNotReachable()
    {
        assertFalse(WindowPlacement.isReachable(new Rectangle(0, 0, 400, 250), Collections.emptyList()));
    }
}
