/*
 * SessionWatcher.java - et-otp-v2: GPL Java TOTP soft token.
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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


/**
 * Tells when the Windows session is locked (Windows+L, the lock screen, switching the user or a remote
 * session that is disconnected), with the FFM API and no library: a message-only window registered with
 * WTSRegisterSessionNotification receives WM_WTSSESSION_CHANGE on its own thread (et-otp-v2 issue #22).
 * <p>
 * On macOS, the session of CoreGraphics ({@code CGSessionCopyCurrentDictionary}) is checked every second: the
 * screen is locked when it has {@code CGSSessionScreenIsLocked}, and another user has the screen when
 * {@code kCGSSessionOnConsoleKey} is false (fast user switching), like a disconnected session on Windows.
 */
final class SessionWatcher
{
    private static final int WM_WTSSESSION_CHANGE = 0x02B1;
    private static final int WTS_CONSOLE_DISCONNECT = 0x2;
    private static final int WTS_REMOTE_DISCONNECT = 0x4;
    private static final int WTS_SESSION_LOCK = 0x7;
    private static final int NOTIFY_FOR_THIS_SESSION = 0;
    /** HWND_MESSAGE: a window that only receives messages, never shown. */
    private static final long HWND_MESSAGE = -3;

    private static volatile Runnable onLock;
    private static volatile MethodHandle defWindowProc;
    private static volatile MethodHandle postMessage;
    private static volatile MemorySegment window;
    private static volatile boolean macWatching;


    private SessionWatcher() { }


    /** @return true if a locked session can be told here (Windows, macOS) */
    static boolean isSupported()
    {
        return Dpapi.isAvailable() || Mac.isMac();
    }


    /**
     * Starts watching (once; later calls only replace the listener).
     *
     * @param listener called on the watcher thread when the session is locked
     * @return true if the notifications are received
     */
    static synchronized boolean start(Runnable listener)
    {
        onLock = listener;
        if (window != null || macWatching) {
            return true;
        }
        if (!Dpapi.isAvailable()) {
            return macWatching = Mac.start();
        }
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        Thread t = new Thread(() -> run(ready, ok), "session-watcher");
        t.setDaemon(true);
        t.start();
        try {
            ready.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }


    /** For tests: sends the message of a locked session to the window, as Windows does. */
    static boolean simulateLock()
    {
        try {
            return window != null && (int) postMessage.invokeExact(window, WM_WTSSESSION_CHANGE,
                                                                  (long) WTS_SESSION_LOCK, 0L) != 0;
        } catch (Throwable t) {
            return false;
        }
    }


    private static void run(CountDownLatch ready, boolean[] ok)
    {
        // the arena lives as long as the thread: the window procedure must stay valid
        Arena arena = Arena.ofShared();
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            SymbolLookup wtsapi32 = SymbolLookup.libraryLookup("wtsapi32", arena);
            FunctionDescriptor wndProcType = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);
            defWindowProc = linker.downcallHandle(user32.findOrThrow("DefWindowProcW"), wndProcType);
            postMessage = linker.downcallHandle(user32.findOrThrow("PostMessageW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
            MethodHandle getModuleHandle = linker.downcallHandle(kernel32.findOrThrow("GetModuleHandleW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
            MethodHandle registerClass = linker.downcallHandle(user32.findOrThrow("RegisterClassExW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MethodHandle createWindow = linker.downcallHandle(user32.findOrThrow("CreateWindowExW"),
                FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                                      JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle register = linker.downcallHandle(wtsapi32.findOrThrow("WTSRegisterSessionNotification"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle getMessage = linker.downcallHandle(user32.findOrThrow("GetMessageW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
            MethodHandle dispatch = linker.downcallHandle(user32.findOrThrow("DispatchMessageW"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS));

            MethodHandle proc = MethodHandles.lookup().findStatic(SessionWatcher.class, "windowProc",
                MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
            MemorySegment procStub = linker.upcallStub(proc, wndProcType, arena);

            MemorySegment instance = (MemorySegment) getModuleHandle.invokeExact(MemorySegment.NULL);
            MemorySegment className = arena.allocateFrom("et-otp-v2.SessionWatcher", StandardCharsets.UTF_16LE);
            // WNDCLASSEXW on 64 bit Windows: 80 bytes
            MemorySegment wc = arena.allocate(80, 8);
            wc.set(JAVA_INT, 0, 80);
            wc.set(ADDRESS, 8, procStub);
            wc.set(ADDRESS, 24, instance);
            wc.set(ADDRESS, 64, className);
            if ((int) registerClass.invokeExact(wc) == 0) {
                return;
            }
            MemorySegment hwnd = (MemorySegment) createWindow.invokeExact(0, className, className, 0, 0, 0, 0, 0,
                MemorySegment.ofAddress(HWND_MESSAGE), MemorySegment.NULL, instance, MemorySegment.NULL);
            if (hwnd.address() == 0 || (int) register.invokeExact(hwnd, NOTIFY_FOR_THIS_SESSION) == 0) {
                return;
            }
            window = hwnd;
            ok[0] = true;
            ready.countDown();

            // MSG on 64 bit Windows: 48 bytes
            MemorySegment msg = arena.allocate(48, 8);
            while ((int) getMessage.invokeExact(msg, MemorySegment.NULL, 0, 0) > 0) {
                long ignored = (long) dispatch.invokeExact(msg);
            }
        } catch (Throwable t) {
            // no notifications: the other ways of locking still work
        } finally {
            ready.countDown();
        }
    }


    /** The window procedure, called by Windows on the watcher thread; must never throw. */
    private static long windowProc(MemorySegment hwnd, int message, long wParam, long lParam)
    {
        try {
            if (message == WM_WTSSESSION_CHANGE
                && (wParam == WTS_SESSION_LOCK || wParam == WTS_CONSOLE_DISCONNECT || wParam == WTS_REMOTE_DISCONNECT)) {
                Runnable r = onLock;
                if (r != null) {
                    r.run();
                }
                return 0;
            }
            return (long) defWindowProc.invokeExact(hwnd, message, wParam, lParam);
        } catch (Throwable t) {
            return 0;
        }
    }


    /** The macOS part: polls the session of CoreGraphics on its own thread. */
    static final class Mac
    {
        private static final long POLL_MILLIS = 1000;
        private static final int UTF8 = 0x08000100; // kCFStringEncodingUTF8

        private static MethodHandle copySession;
        private static MethodHandle getValue;
        private static MethodHandle typeOf;
        private static MethodHandle release;
        private static MethodHandle booleanValue;
        private static MethodHandle numberValue;
        private static long booleanType;
        private static long numberType;
        private static MemorySegment keyLocked;
        private static MemorySegment keyOnConsole;


        private Mac() { }


        static boolean isMac()
        {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("mac");
        }


        static boolean start()
        {
            if (!isMac()) {
                return false;
            }
            try {
                init();
                if (locked() == null) {
                    return false; // no session of the window server
                }
            } catch (Throwable t) {
                return false;
            }
            Thread t = new Thread(Mac::run, "session-watcher");
            t.setDaemon(true);
            t.start();
            return true;
        }


        private static void run()
        {
            boolean wasLocked = false;
            while (true) {
                Boolean locked = locked();
                if (locked != null) {
                    if (locked && !wasLocked) {
                        Runnable r = onLock;
                        if (r != null) {
                            r.run();
                        }
                    }
                    wasLocked = locked;
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }


        private static void init() throws Throwable
        {
            Linker linker = Linker.nativeLinker();
            Arena arena = Arena.global();
            SymbolLookup cg = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena);
            SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
            copySession = linker.downcallHandle(cg.findOrThrow("CGSessionCopyCurrentDictionary"), FunctionDescriptor.of(ADDRESS));
            getValue = linker.downcallHandle(cf.findOrThrow("CFDictionaryGetValue"), FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            typeOf = linker.downcallHandle(cf.findOrThrow("CFGetTypeID"), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            release = linker.downcallHandle(cf.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ADDRESS));
            booleanValue = linker.downcallHandle(cf.findOrThrow("CFBooleanGetValue"),
                                                 FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, ADDRESS));
            numberValue = linker.downcallHandle(cf.findOrThrow("CFNumberGetValue"),
                FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_BOOLEAN, ADDRESS, JAVA_LONG, ADDRESS));
            booleanType = (long) linker.downcallHandle(cf.findOrThrow("CFBooleanGetTypeID"), FunctionDescriptor.of(JAVA_LONG))
                                       .invokeExact();
            numberType = (long) linker.downcallHandle(cf.findOrThrow("CFNumberGetTypeID"), FunctionDescriptor.of(JAVA_LONG))
                                      .invokeExact();
            MethodHandle createString = linker.downcallHandle(cf.findOrThrow("CFStringCreateWithCString"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            // kept for the whole run of the program
            keyLocked = (MemorySegment) createString.invokeExact(MemorySegment.NULL,
                                                                 arena.allocateFrom("CGSSessionScreenIsLocked"), UTF8);
            keyOnConsole = (MemorySegment) createString.invokeExact(MemorySegment.NULL,
                                                                    arena.allocateFrom("kCGSSessionOnConsoleKey"), UTF8);
        }


        /** @return true if the screen is locked or another user has it, or null if unknown */
        private static Boolean locked()
        {
            try {
                MemorySegment session = (MemorySegment) copySession.invokeExact();
                if (session.equals(MemorySegment.NULL)) {
                    return null;
                }
                try {
                    Boolean screenLocked = truth((MemorySegment) getValue.invokeExact(session, keyLocked));
                    Boolean onConsole = truth((MemorySegment) getValue.invokeExact(session, keyOnConsole));
                    return Boolean.TRUE.equals(screenLocked) || Boolean.FALSE.equals(onConsole);
                } finally {
                    release.invokeExact(session);
                }
            } catch (Throwable t) {
                return null;
            }
        }


        /** @return the value of a CFBoolean or CFNumber, or null if it is missing or something else */
        private static Boolean truth(MemorySegment value) throws Throwable
        {
            if (value.equals(MemorySegment.NULL)) {
                return null;
            }
            long type = (long) typeOf.invokeExact(value);
            if (type == booleanType) {
                return (boolean) booleanValue.invokeExact(value);
            }
            if (type == numberType) {
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment out = arena.allocate(JAVA_LONG);
                    // kCFNumberSInt64Type
                    if ((boolean) numberValue.invokeExact(value, 4L, out)) {
                        return out.get(JAVA_LONG, 0) != 0;
                    }
                }
            }
            return null;
        }
    }
}
