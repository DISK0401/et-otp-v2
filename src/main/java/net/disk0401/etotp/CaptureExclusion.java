/*
 * CaptureExclusion.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Keeps the windows of this program out of screen sharing, recordings and screenshots (et-otp-v2 issue #24).
 * <p>
 * On Windows 10 version 2004 and later, {@code SetWindowDisplayAffinity} with {@code WDA_EXCLUDEFROMCAPTURE}
 * removes a window from every capture: the windows behind it are captured instead (not a black area). It is
 * called through the foreign function API, without internal APIs of the JDK.
 * <p>
 * On macOS 27 and later, {@code NSWindow.sharingType} is set to {@code NSWindowSharingNone} through the
 * Objective-C runtime (also with the foreign function API). Apple calls this value legacy; on macOS 15 screen
 * sharing through ScreenCaptureKit was reported to ignore it, so older versions are not supported. On macOS 27.2
 * it was checked to work with screenshots, {@code screencapture}, QuickTime and Zoom. Other systems are not supported.
 */
final class CaptureExclusion
{
    static final int WDA_NONE = 0;
    static final int WDA_EXCLUDEFROMCAPTURE = 0x11;
    static final long NS_WINDOW_SHARING_NONE = 0;
    static final long NS_WINDOW_SHARING_READ_ONLY = 1;
    /** the first version of macOS where it was checked to work (et-otp-v2 issue #24) */
    static final int MAC_MIN_MAJOR = 27;

    private static volatile boolean broken;


    private CaptureExclusion() { }


    /**
     * @return true if the windows can be excluded here (Windows, where the version is only known when trying, and
     *         macOS 27 or later)
     */
    static boolean isSupported()
    {
        return (ScreenCapture.windowsSupported() || isSupportedMac()) && !broken;
    }


    /** @return true on macOS 27 or later */
    static boolean isSupportedMac()
    {
        return isMac(System.getProperty("os.name", ""))
               && isSupportedMacVersion(System.getProperty("os.version", ""));
    }


    static boolean isMac(String osName)
    {
        return osName.toLowerCase(Locale.ROOT).startsWith("mac");
    }


    /** @return true if the version of macOS ("27.2" for example) is one where it works */
    static boolean isSupportedMacVersion(String osVersion)
    {
        int dot = osVersion.indexOf('.');
        String major = dot < 0 ? osVersion : osVersion.substring(0, dot);
        try {
            return Integer.parseInt(major.trim()) >= MAC_MIN_MAJOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }


    /**
     * Excludes the windows of this program from captures, or includes them again.
     *
     * @param exclude true to exclude
     * @return false if a visible window could not be changed (for example before Windows 10 version 2004); on macOS,
     *         where the change is made later on the AppKit thread, false if the previous change failed
     */
    static boolean apply(boolean exclude)
    {
        if (isSupportedMac()) {
            return Mac.apply(exclude);
        }
        if (!ScreenCapture.windowsSupported()) {
            return false;
        }
        // also the hidden windows: a dialog or a popup that is shown again keeps its affinity, and no event tells
        // when it is shown again. Some hidden windows of the system (input method) refuse it: only the visible count
        java.util.List<Long> all = ScreenCapture.ownWindows(false);
        boolean ok = true;
        try (Arena arena = Arena.ofConfined()) {
            int affinity = exclude ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE;
            MemorySegment current = arena.allocate(JAVA_INT);
            for (long h : all) {
                MemorySegment hwnd = MemorySegment.ofAddress(h);
                // only where it differs: this runs every second, and most windows have it already
                if ((int) getAffinity().invokeExact(hwnd, current) != 0 && current.get(JAVA_INT, 0) == affinity) {
                    continue;
                }
                if ((int) setAffinity().invokeExact(hwnd, affinity) == 0 && ScreenCapture.isVisible(h)) {
                    ok = false;
                }
            }
        } catch (Throwable t) {
            broken = true;
            return false;
        }
        return ok;
    }


    /**
     * For tests and checks on macOS: the sharing type of each window, as read back after the last change on the
     * AppKit thread.
     *
     * @return the values, or null if no change was made yet
     */
    static long[] macSharingTypes()
    {
        return Mac.lastTypes;
    }


    /** @return the display affinity of the window (Windows only), or -1 if unknown */
    static int affinity(long hwnd)
    {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(JAVA_INT);
            if ((int) getAffinity().invokeExact(MemorySegment.ofAddress(hwnd), out) == 0) {
                return -1;
            }
            return out.get(JAVA_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }


    private static MethodHandle setAffinity;
    private static MethodHandle getAffinity;


    /** made once: making a downcall handle takes milliseconds, and the windows are checked every second. */
    private static synchronized MethodHandle setAffinity()
    {
        if (setAffinity == null) {
            setAffinity = handle("SetWindowDisplayAffinity", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        }
        return setAffinity;
    }


    private static synchronized MethodHandle getAffinity()
    {
        if (getAffinity == null) {
            getAffinity = handle("GetWindowDisplayAffinity", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        }
        return getAffinity;
    }


    private static MethodHandle handle(String name, FunctionDescriptor descriptor)
    {
        SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
        return Linker.nativeLinker().downcallHandle(user32.findOrThrow(name), descriptor);
    }


    /**
     * The macOS part. AppKit must only be used on its own thread (the main thread), which is not the event dispatch
     * thread of Swing: the change is handed to it with {@code dispatch_async_f}, without waiting, as the AppKit
     * thread may itself be waiting for the event dispatch thread. Every window of the application is changed, as on
     * Windows, without looking for the one of each AWT window.
     */
    private static final class Mac
    {
        private static final Linker LINKER = Linker.nativeLinker();

        private static MethodHandle sendAddress;     // id   objc_msgSend(id, SEL)
        private static MethodHandle sendLong;        // long objc_msgSend(id, SEL)
        private static MethodHandle sendAddressLong; // id   objc_msgSend(id, SEL, long)
        private static MethodHandle sendVoidLong;    // void objc_msgSend(id, SEL, long)
        private static MethodHandle dispatchAsync;
        private static MemorySegment mainQueue;
        private static MemorySegment task;
        private static MemorySegment nsApplication;
        private static MemorySegment selSharedApplication;
        private static MemorySegment selWindows;
        private static MemorySegment selCount;
        private static MemorySegment selObjectAtIndex;
        private static MemorySegment selSetSharingType;
        private static MemorySegment selSharingType;

        /** a change is handed to the AppKit thread and has not run yet: no other is handed, so none pile up */
        private static final AtomicBoolean PENDING = new AtomicBoolean();
        private static volatile long wanted = NS_WINDOW_SHARING_READ_ONLY;
        private static volatile boolean lastOk = true;
        static volatile long[] lastTypes;


        private Mac() { }


        static boolean apply(boolean exclude)
        {
            wanted = exclude ? NS_WINDOW_SHARING_NONE : NS_WINDOW_SHARING_READ_ONLY;
            try {
                init();
                if (PENDING.compareAndSet(false, true)) {
                    dispatchAsync.invokeExact(mainQueue, MemorySegment.NULL, task);
                }
            } catch (Throwable t) {
                broken = true;
                return false;
            }
            return lastOk;
        }


        private static synchronized void init() throws Throwable
        {
            if (task != null) {
                return;
            }
            Arena arena = Arena.global();
            SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
            SymbolLookup system = LINKER.defaultLookup();
            MemorySegment msgSend = objc.findOrThrow("objc_msgSend");
            // one handle for each signature: on arm64, objc_msgSend must not be called as a variadic function
            sendAddress = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            sendLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            sendAddressLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            sendVoidLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));

            MethodHandle getClass = LINKER.downcallHandle(objc.findOrThrow("objc_getClass"),
                                                          FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle registerName = LINKER.downcallHandle(objc.findOrThrow("sel_registerName"),
                                                              FunctionDescriptor.of(ADDRESS, ADDRESS));
            nsApplication = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication"));
            if (nsApplication.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("no NSApplication");
            }
            selSharedApplication = (MemorySegment) registerName.invokeExact(arena.allocateFrom("sharedApplication"));
            selWindows = (MemorySegment) registerName.invokeExact(arena.allocateFrom("windows"));
            selCount = (MemorySegment) registerName.invokeExact(arena.allocateFrom("count"));
            selObjectAtIndex = (MemorySegment) registerName.invokeExact(arena.allocateFrom("objectAtIndex:"));
            selSetSharingType = (MemorySegment) registerName.invokeExact(arena.allocateFrom("setSharingType:"));
            selSharingType = (MemorySegment) registerName.invokeExact(arena.allocateFrom("sharingType"));

            // dispatch_get_main_queue() is a macro for the address of _dispatch_main_q (libSystem)
            mainQueue = system.findOrThrow("_dispatch_main_q");
            dispatchAsync = LINKER.downcallHandle(system.findOrThrow("dispatch_async_f"),
                                                  FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            MethodHandle run = MethodHandles.lookup().findStatic(Mac.class, "run",
                                                                 MethodType.methodType(void.class, MemorySegment.class));
            task = LINKER.upcallStub(run, FunctionDescriptor.ofVoid(ADDRESS), arena);
        }


        /** Runs on the AppKit thread; must not throw, as an exception in an upcall ends the program. */
        private static void run(MemorySegment context)
        {
            PENDING.set(false); // before reading wanted: a later change is then handed again
            long type = wanted;
            try {
                MemorySegment app = (MemorySegment) sendAddress.invokeExact(nsApplication, selSharedApplication);
                MemorySegment windows = (MemorySegment) sendAddress.invokeExact(app, selWindows);
                long count = (long) sendLong.invokeExact(windows, selCount);
                long[] types = new long[(int) count];
                boolean ok = true;
                for (int i = 0; i < count; i++) {
                    MemorySegment window = (MemorySegment) sendAddressLong.invokeExact(windows, selObjectAtIndex, (long) i);
                    sendVoidLong.invokeExact(window, selSetSharingType, type);
                    types[i] = (long) sendLong.invokeExact(window, selSharingType);
                    ok &= types[i] == type;
                }
                lastTypes = types;
                lastOk = ok;
            } catch (Throwable t) {
                lastOk = false;
            }
        }
    }
}
