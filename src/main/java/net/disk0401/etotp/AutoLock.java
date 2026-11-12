/*
 * AutoLock.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;


/**
 * Decides when the unlocked services are locked again (et-otp-v2 issue #22): after the configured
 * minutes without input in the window, and after the computer slept. Sleep is seen as a gap between
 * two ticks of the one second timer, so no notification of the operating system is needed.
 */
final class AutoLock
{
    /** a gap between two ticks of more than this is taken as sleep (or a paused program). */
    static final long SLEEP_GAP_MILLIS = 30_000;

    enum Reason
    {
        IDLE, SLEEP
    }

    private long lastInput;
    private long lastTick;


    AutoLock(long now)
    {
        lastInput = now;
        lastTick = now;
    }


    /** Called for each key or mouse input in a window of the program. */
    void input(long now)
    {
        lastInput = now;
    }


    /**
     * Called by the one second timer.
     *
     * @param idleMinutes the minutes without input, 0 for never
     * @param onSleep true to lock after sleep
     * @return why to lock now, or null
     */
    Reason tick(long now, int idleMinutes, boolean onSleep)
    {
        long gap = now - lastTick;
        lastTick = now;
        if (onSleep && gap > SLEEP_GAP_MILLIS) {
            return Reason.SLEEP;
        }
        if (idleMinutes > 0 && now - lastInput >= idleMinutes * 60_000L) {
            return Reason.IDLE;
        }
        return null;
    }


    /** Starts the idle time again, for example after unlocking. */
    void reset(long now)
    {
        lastInput = now;
        lastTick = now;
    }
}
