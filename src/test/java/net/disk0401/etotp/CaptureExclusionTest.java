/*
 * CaptureExclusionTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


class CaptureExclusionTest
{
    @Test
    void macIsKnownByTheNameOfTheSystem()
    {
        assertTrue(CaptureExclusion.isMac("Mac OS X"));
        assertTrue(CaptureExclusion.isMac("macOS"));
        assertFalse(CaptureExclusion.isMac("Windows 11"));
        assertFalse(CaptureExclusion.isMac("Linux"));
        assertFalse(CaptureExclusion.isMac(""));
    }


    @Test
    void onlyMacOs27AndLaterAreSupported()
    {
        assertTrue(CaptureExclusion.isSupportedMacVersion("27.2"));
        assertTrue(CaptureExclusion.isSupportedMacVersion("27"));
        assertTrue(CaptureExclusion.isSupportedMacVersion("28.0.1"));

        // macOS 15 and later: screen sharing through ScreenCaptureKit was reported to ignore the setting
        assertFalse(CaptureExclusion.isSupportedMacVersion("26.6.2"));
        assertFalse(CaptureExclusion.isSupportedMacVersion("15.4"));
        assertFalse(CaptureExclusion.isSupportedMacVersion("14.7"));
        // an old JDK on a new macOS may report 10.16
        assertFalse(CaptureExclusion.isSupportedMacVersion("10.16"));
    }


    @Test
    void anUnknownVersionIsNotSupported()
    {
        assertFalse(CaptureExclusion.isSupportedMacVersion(""));
        assertFalse(CaptureExclusion.isSupportedMacVersion("unknown"));
        assertFalse(CaptureExclusion.isSupportedMacVersion(".1"));
    }
}
