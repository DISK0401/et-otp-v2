/*
 * AutoLockTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;


/** et-otp-v2 issue #22: locking after idle time, sleep and a locked Windows session. */
class AutoLockTest
{
    private static final long T = 1_700_000_000_000L;

    @TempDir
    File tmp;

    @Test
    void locksAfterTheIdleMinutes()
    {
        AutoLock lock = new AutoLock(T);
        long now = T;
        for (int s = 1; s < 5 * 60; s++) {
            now = T + s * 1000L;
            assertNull(lock.tick(now, 5, true), "at " + s);
        }
        assertEquals(AutoLock.Reason.IDLE, lock.tick(T + 5 * 60_000L, 5, true));
    }

    @Test
    void inputStartsTheIdleTimeAgain()
    {
        AutoLock lock = new AutoLock(T);
        for (int s = 1; s <= 4 * 60; s++) {
            lock.tick(T + s * 1000L, 5, true);
        }
        lock.input(T + 4 * 60_000L);
        for (int s = 4 * 60 + 1; s < 9 * 60; s++) {
            assertNull(lock.tick(T + s * 1000L, 5, true), "at " + s);
        }
        assertEquals(AutoLock.Reason.IDLE, lock.tick(T + 9 * 60_000L, 5, true));
    }

    @Test
    void noIdleLockWithZeroMinutes()
    {
        AutoLock lock = new AutoLock(T);
        for (int s = 1; s < 3600; s++) {
            assertNull(lock.tick(T + s * 1000L, 0, true));
        }
    }

    @Test
    void locksAfterSleep()
    {
        AutoLock lock = new AutoLock(T);
        assertNull(lock.tick(T + 1000, 0, true));
        assertEquals(AutoLock.Reason.SLEEP, lock.tick(T + 1000 + 31_000, 0, true));
        // only once: the next tick is normal again
        assertNull(lock.tick(T + 1000 + 32_000, 0, true));
    }

    @Test
    void sleepIsIgnoredWhenSwitchedOff()
    {
        AutoLock lock = new AutoLock(T);
        assertNull(lock.tick(T + 3_600_000, 0, false));
    }

    @Test
    void resetStartsAgainAfterUnlocking()
    {
        AutoLock lock = new AutoLock(T);
        long later = T + 3_600_000;
        lock.reset(later);
        assertNull(lock.tick(later + 1000, 5, true));
    }

    @Test
    void theClipboardIsClearedOnlyWhileItHoldsTheCode() throws Exception
    {
        Clipboard clip = new Clipboard("test");
        ClipboardGuard guard = new ClipboardGuard(clip);

        guard.copy("123456");
        assertTrue(guard.clear());
        assertEquals("", clip.getData(DataFlavor.stringFlavor));

        guard.copy("654321");
        clip.setContents(new StringSelection("copied in another program"), null);
        assertFalse(guard.clear());
        assertEquals("copied in another program", clip.getData(DataFlavor.stringFlavor));

        assertFalse(guard.clear(), "nothing copied since");
    }

    @Test
    void lockSettings() throws Exception
    {
        File f = new File(tmp, UiSettings.FILE_NAME);
        UiSettings s = UiSettings.load(f);
        assertEquals(5, s.lockIdleMinutes());
        assertTrue(s.lockOnSystemLock());
        assertFalse(s.lockOnMinimize());

        s.setLockIdleMinutes(0);
        s.setLockOnSystemLock(false);
        s.setLockOnMinimize(true);
        s.save();
        UiSettings again = UiSettings.load(f);
        assertEquals(0, again.lockIdleMinutes());
        assertFalse(again.lockOnSystemLock());
        assertTrue(again.lockOnMinimize());

        again.setLockIdleMinutes(100_000);
        assertEquals(UiSettings.MAX_LOCK_MINUTES, again.lockIdleMinutes());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedWindowsSessionIsReported() throws Exception
    {
        CountDownLatch locked = new CountDownLatch(1);
        assertTrue(SessionWatcher.start(locked::countDown));
        assertTrue(SessionWatcher.simulateLock());
        assertTrue(locked.await(5, TimeUnit.SECONDS));
    }
}
