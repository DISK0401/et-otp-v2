/*
 * CaptureExclusion.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;


/**
 * Keeps the windows of this program out of screen sharing, recordings and screenshots (et-otp-v2 issue #24).
 * <p>
 * On Windows 10 version 2004 and later, {@code SetWindowDisplayAffinity} with {@code WDA_EXCLUDEFROMCAPTURE}
 * removes a window from every capture: the windows behind it are captured instead (not a black area). It is
 * called through the foreign function API, without internal APIs of the JDK. Other systems are not supported.
 */
final class CaptureExclusion
{
    static final int WDA_NONE = 0;
    static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    private static volatile boolean broken;


    private CaptureExclusion() { }


    /** @return true if the windows can be excluded here (Windows; the version is only known when trying) */
    static boolean isSupported()
    {
        return ScreenCapture.windowsSupported() && !broken;
    }


    /**
     * Excludes the windows of this program from captures, or includes them again.
     *
     * @param exclude true to exclude
     * @return false if a visible window could not be changed (for example before Windows 10 version 2004)
     */
    static boolean apply(boolean exclude)
    {
        if (!ScreenCapture.windowsSupported()) {
            return false;
        }
        // also the hidden windows: a dialog or a popup that is shown again keeps its affinity, and no event tells
        // when it is shown again. Some hidden windows of the system (input method) refuse it: only the visible count
        java.util.List<Long> visible = ScreenCapture.ownWindows(true);
        java.util.List<Long> all = ScreenCapture.ownWindows(false);
        boolean ok = true;
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle set = handle(arena, "SetWindowDisplayAffinity", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            int affinity = exclude ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
            for (long h : all) {
                if ((int) set.invokeExact(MemorySegment.ofAddress(h), affinity) == 0 && visible.contains(h)) {
                    ok = false;
                }
            }
        } catch (Throwable t) {
            broken = true;
            return false;
        }
        return ok;
    }


    /** @return the display affinity of the window, or -1 if unknown */
    static int affinity(long hwnd)
    {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle get = handle(arena, "GetWindowDisplayAffinity", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MemorySegment out = arena.allocate(JAVA_INT);
            if ((int) get.invokeExact(MemorySegment.ofAddress(hwnd), out) == 0) {
                return -1;
            }
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }


    private static MethodHandle handle(Arena arena, String name, FunctionDescriptor descriptor)
    {
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
        return Linker.nativeLinker().downcallHandle(user32.findOrThrow(name), descriptor);
    }
}
