/*
 * ScreenCapture.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.awt.AWTException;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Lists and captures the screens and, on Windows, the application windows, to read QR codes from them.
 * Windows are captured with PrintWindow (FFM API), so a window hidden behind others can be read.
 * Captured images are only kept in memory.
 */
final class ScreenCapture
{
    /** A capture source: a whole display, or one application window. */
    static final class Target
    {
        /** display number from 1, or 0 for a window. */
        final int display;
        final String title;
        final GraphicsDevice device;
        /** HWND of a window, 0 for a display. */
        final long hwnd;

        private Target(int display, String title, GraphicsDevice device, long hwnd)
        {
            this.display = display;
            this.title = title;
            this.device = device;
            this.hwnd = hwnd;
        }

        boolean isDisplay()
        {
            return device != null;
        }
    }

    private static final int GW_OWNER = 4;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TOOLWINDOW = 0x80;
    private static final int DWMWA_CLOAKED = 14;
    /** captures windows drawn with hardware acceleration (browsers) too. */
    private static final int PW_RENDERFULLCONTENT = 2;
    private static final int SW_RESTORE = 9;
    private static final int DIB_RGB_COLORS = 0;
    private static final long MAX_PIXELS = 64_000_000L;

    /**
     * A window capture that never returns (PrintWindow cannot be interrupted) keeps its thread and
     * native resources. Each capture runs in its own thread, so a hanging one cannot delay others;
     * windows that timed out once are not captured again, and no more window captures are started
     * while {@link #MAX_HANGING} of them hang.
     */
    private static final int MAX_HANGING = 4;
    private static final java.util.concurrent.atomic.AtomicInteger HANGING = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.Set<Long> TIMED_OUT = java.util.concurrent.ConcurrentHashMap.newKeySet();


    private ScreenCapture() { }


    /** @return true if application windows can be listed and captured (Windows) */
    static boolean windowsSupported()
    {
        return Dpapi.isAvailable();
    }


    static List<Target> displays()
    {
        List<Target> list = new ArrayList<>();
        GraphicsDevice[] devices = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        for (int i = 0; i < devices.length; i++) {
            list.add(new Target(i + 1, null, devices[i], 0));
        }
        return list;
    }


    /** @return the visible application windows (as in the task bar), without this program's windows */
    static List<Target> windows()
    {
        List<Target> list = new ArrayList<>();
        if (!windowsSupported()) {
            return list;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 w = new Win32(arena);
            int ownPid = (int) w.getCurrentProcessId.invokeExact();
            List<Long> handles = new ArrayList<>();
            // BOOL CALLBACK EnumWindowsProc(HWND hwnd, LPARAM lParam)
            MethodHandle collect = MethodHandles.lookup().findStatic(ScreenCapture.class, "collect",
                MethodType.methodType(int.class, List.class, MemorySegment.class, long.class)).bindTo(handles);
            MemorySegment callback = Linker.nativeLinker().upcallStub(collect,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG), arena);
            int ignored = (int) w.enumWindows.invokeExact(callback, 0L);

            MemorySegment pid = arena.allocate(JAVA_INT);
            MemorySegment cloaked = arena.allocate(JAVA_INT);
            MemorySegment text = arena.allocate(JAVA_CHAR, 512);
            for (long h : handles) {
                MemorySegment hwnd = MemorySegment.ofAddress(h);
                if ((int) w.isWindowVisible.invokeExact(hwnd) == 0
                    || !((MemorySegment) w.getWindow.invokeExact(hwnd, GW_OWNER)).equals(MemorySegment.NULL)) {
                    continue;
                }
                int tid = (int) w.getWindowThreadProcessId.invokeExact(hwnd, pid);
                if (pid.get(JAVA_INT, 0) == ownPid) {
                    continue;
                }
                if (((int) w.getWindowLong.invokeExact(hwnd, GWL_EXSTYLE) & WS_EX_TOOLWINDOW) != 0) {
                    continue;
                }
                // windows on another virtual desktop, or hidden store apps
                if ((int) w.dwmGetWindowAttribute.invokeExact(hwnd, DWMWA_CLOAKED, cloaked, 4) == 0
                    && cloaked.get(JAVA_INT, 0) != 0) {
                    continue;
                }
                int len = (int) w.getWindowText.invokeExact(hwnd, text, 512);
                String title = new String(text.asSlice(0, (long) len * 2).toArray(JAVA_CHAR)).trim();
                if (title.isEmpty() || title.equals("Program Manager")) {
                    continue;
                }
                // capturing an application that does not respond would wait
                if ((int) w.isHungAppWindow.invokeExact(hwnd) != 0) {
                    continue;
                }
                list.add(new Target(0, title, null, h));
            }
        } catch (Throwable t) {
            // listing windows is an extra; the displays still work
        }
        return list;
    }


    @SuppressWarnings("unused") // called from native code
    private static int collect(List<Long> handles, MemorySegment hwnd, long param)
    {
        handles.add(hwnd.address());
        return 1;
    }


    /**
     * Captures with a time limit. PrintWindow cannot be interrupted, so a capture that takes too long is abandoned.
     *
     * @return the image, or null if it failed, timed out or the window is minimized
     */
    static BufferedImage capture(Target t, long millis)
    {
        if (t.isDisplay()) {
            // Robot captures do not wait for other applications
            try {
                return captureDisplay(t.device);
            } catch (AWTException | RuntimeException e) {
                return null;
            }
        }
        if (TIMED_OUT.contains(t.hwnd) || HANGING.get() >= MAX_HANGING) {
            return null;
        }
        java.util.concurrent.CompletableFuture<BufferedImage> f = new java.util.concurrent.CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                f.complete(captureWindow(t.hwnd));
            } catch (Throwable e) {
                // errors of the native calls (MethodHandle.invokeExact declares Throwable)
                f.completeExceptionally(e);
            } finally {
                if (f.isCancelled()) {
                    HANGING.decrementAndGet();
                }
            }
        }, "window-capture");
        worker.setDaemon(true);
        worker.start();
        try {
            return f.get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException e) {
            if (f.cancel(false)) {
                // still running: counted until the thread ends (decremented above)
                HANGING.incrementAndGet();
                TIMED_OUT.add(t.hwnd);
            }
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }


    static boolean isMinimized(Target t)
    {
        if (t.isDisplay()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            return (int) new Win32(arena).isIconic.invokeExact(MemorySegment.ofAddress(t.hwnd)) != 0;
        } catch (Throwable e) {
            return false;
        }
    }


    /** Restores a minimized window and brings it to the front (a browser may stop drawing while hidden). */
    static void bringToFront(Target t)
    {
        if (t.isDisplay()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Win32 w = new Win32(arena);
            MemorySegment hwnd = MemorySegment.ofAddress(t.hwnd);
            if ((int) w.isIconic.invokeExact(hwnd) != 0) {
                int ignored = (int) w.showWindow.invokeExact(hwnd, SW_RESTORE);
            }
            int ignored = (int) w.setForegroundWindow.invokeExact(hwnd);
        } catch (Throwable e) {
            // the user can bring the window to the front
        }
    }


    private static BufferedImage captureDisplay(GraphicsDevice dev) throws AWTException
    {
        MultiResolutionImage mri = new Robot(dev).createMultiResolutionScreenCapture(dev.getDefaultConfiguration().getBounds());
        // the variant with the most pixels is the physical resolution on scaled displays
        Image best = null;
        long bestPixels = -1;
        for (Image v : mri.getResolutionVariants()) {
            long px = (long) v.getWidth(null) * v.getHeight(null);
            if (px > bestPixels) {
                best = v;
                bestPixels = px;
            }
        }
        if (best instanceof BufferedImage) {
            return (BufferedImage) best;
        }
        BufferedImage copy = new BufferedImage(best.getWidth(null), best.getHeight(null), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(best, 0, 0, null);
        g.dispose();
        return copy;
    }


    private static BufferedImage captureWindow(long handle) throws Throwable
    {
        try (Arena arena = Arena.ofConfined()) {
            Win32 w = new Win32(arena);
            MemorySegment hwnd = MemorySegment.ofAddress(handle);
            if ((int) w.isIconic.invokeExact(hwnd) != 0) {
                return null;
            }
            MemorySegment rect = arena.allocate(JAVA_INT, 4);
            if ((int) w.getWindowRect.invokeExact(hwnd, rect) == 0) {
                return null;
            }
            int width = rect.getAtIndex(JAVA_INT, 2) - rect.getAtIndex(JAVA_INT, 0);
            int height = rect.getAtIndex(JAVA_INT, 3) - rect.getAtIndex(JAVA_INT, 1);
            if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                return null;
            }
            MemorySegment screen = (MemorySegment) w.getDC.invokeExact(MemorySegment.NULL);
            MemorySegment mem = MemorySegment.NULL;
            MemorySegment bmp = MemorySegment.NULL;
            try {
                mem = (MemorySegment) w.createCompatibleDC.invokeExact(screen);
                bmp = (MemorySegment) w.createCompatibleBitmap.invokeExact(screen, width, height);
                MemorySegment old = (MemorySegment) w.selectObject.invokeExact(mem, bmp);
                int printed = (int) w.printWindow.invokeExact(hwnd, mem, PW_RENDERFULLCONTENT);
                // the bitmap must not be selected into a DC for GetDIBits
                MemorySegment ignored = (MemorySegment) w.selectObject.invokeExact(mem, old);
                if (printed == 0) {
                    return null;
                }
                // BITMAPINFOHEADER (40 bytes); a negative height gives the rows from top to bottom
                MemorySegment bmi = arena.allocate(44);
                bmi.set(JAVA_INT, 0, 40);
                bmi.set(JAVA_INT, 4, width);
                bmi.set(JAVA_INT, 8, -height);
                bmi.set(JAVA_SHORT, 12, (short) 1);
                bmi.set(JAVA_SHORT, 14, (short) 32);
                bmi.set(JAVA_INT, 16, 0); // BI_RGB
                MemorySegment pixels = arena.allocate(JAVA_INT, (long) width * height);
                if ((int) w.getDIBits.invokeExact(mem, bmp, 0, height, pixels, bmi, DIB_RGB_COLORS) == 0) {
                    return null;
                }
                BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, width, height, pixels.toArray(JAVA_INT), 0, width);
                return img;
            } finally {
                if (!bmp.equals(MemorySegment.NULL)) {
                    int ignored = (int) w.deleteObject.invokeExact(bmp);
                }
                if (!mem.equals(MemorySegment.NULL)) {
                    int ignored = (int) w.deleteDC.invokeExact(mem);
                }
                int ignored = (int) w.releaseDC.invokeExact(MemorySegment.NULL, screen);
            }
        }
    }


    /** Downcall handles for the Win32 functions used here. */
    private static final class Win32
    {
        final MethodHandle enumWindows;
        final MethodHandle isWindowVisible;
        final MethodHandle getWindow;
        final MethodHandle getWindowThreadProcessId;
        final MethodHandle getWindowLong;
        final MethodHandle getWindowText;
        final MethodHandle isHungAppWindow;
        final MethodHandle isIconic;
        final MethodHandle showWindow;
        final MethodHandle setForegroundWindow;
        final MethodHandle getWindowRect;
        final MethodHandle printWindow;
        final MethodHandle getDC;
        final MethodHandle releaseDC;
        final MethodHandle createCompatibleDC;
        final MethodHandle createCompatibleBitmap;
        final MethodHandle selectObject;
        final MethodHandle getDIBits;
        final MethodHandle deleteObject;
        final MethodHandle deleteDC;
        final MethodHandle dwmGetWindowAttribute;
        final MethodHandle getCurrentProcessId;

        Win32(Arena arena)
        {
            Linker l = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", arena);
            SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", arena);
            SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

            enumWindows = l.downcallHandle(user32.findOrThrow("EnumWindows"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
            isWindowVisible = l.downcallHandle(user32.findOrThrow("IsWindowVisible"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getWindow = l.downcallHandle(user32.findOrThrow("GetWindow"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
            getWindowThreadProcessId = l.downcallHandle(user32.findOrThrow("GetWindowThreadProcessId"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            getWindowLong = l.downcallHandle(user32.findOrThrow("GetWindowLongW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            getWindowText = l.downcallHandle(user32.findOrThrow("GetWindowTextW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            isHungAppWindow = l.downcallHandle(user32.findOrThrow("IsHungAppWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            isIconic = l.downcallHandle(user32.findOrThrow("IsIconic"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            showWindow = l.downcallHandle(user32.findOrThrow("ShowWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            setForegroundWindow = l.downcallHandle(user32.findOrThrow("SetForegroundWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            getWindowRect = l.downcallHandle(user32.findOrThrow("GetWindowRect"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            printWindow = l.downcallHandle(user32.findOrThrow("PrintWindow"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            getDC = l.downcallHandle(user32.findOrThrow("GetDC"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
            releaseDC = l.downcallHandle(user32.findOrThrow("ReleaseDC"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            createCompatibleDC = l.downcallHandle(gdi32.findOrThrow("CreateCompatibleDC"),
                FunctionDescriptor.of(ADDRESS, ADDRESS));
            createCompatibleBitmap = l.downcallHandle(gdi32.findOrThrow("CreateCompatibleBitmap"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
            selectObject = l.downcallHandle(gdi32.findOrThrow("SelectObject"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            getDIBits = l.downcallHandle(gdi32.findOrThrow("GetDIBits"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
            deleteObject = l.downcallHandle(gdi32.findOrThrow("DeleteObject"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            deleteDC = l.downcallHandle(gdi32.findOrThrow("DeleteDC"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            dwmGetWindowAttribute = l.downcallHandle(dwmapi.findOrThrow("DwmGetWindowAttribute"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
            getCurrentProcessId = l.downcallHandle(kernel32.findOrThrow("GetCurrentProcessId"),
                FunctionDescriptor.of(JAVA_INT));
        }
    }
}
