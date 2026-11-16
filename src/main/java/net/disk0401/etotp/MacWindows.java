/*
 * MacWindows.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * Lists and captures the application windows on macOS with ScreenCaptureKit (macOS 14 and later), through the
 * Objective-C runtime and the foreign function API. A window hidden behind others is captured as well.
 * <p>
 * ScreenCaptureKit answers in completion handlers (Objective-C blocks). The blocks here are global blocks
 * without captured variables, so an answer cannot tell which request it belongs to: only one request of each
 * kind is outstanding at a time, and a request that is not answered in time blocks the next ones until its
 * answer comes. The answers come on threads of ScreenCaptureKit; AppKit must be running (a window of Swing is
 * open), as it is in this program. Needs the screen recording permission, as the captures of the screens do.
 */
final class MacWindows
{
    /** A window of another application. */
    record Info(long id, long pid, String title) { }

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CG_RECT = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE);
    private static final long MIN_SIZE = 40;
    private static final long MAX_PIXELS = 64_000_000L;
    /** NSApplicationActivateAllWindows */
    private static final long ACTIVATE_ALL_WINDOWS = 1;

    private static final Object CONTENT_LOCK = new Object();
    private static final Object IMAGE_LOCK = new Object();
    private static volatile CompletableFuture<MemorySegment> content;
    private static volatile CompletableFuture<BufferedImage> image;

    private static boolean initialized;
    private static boolean available;
    private static Native n;


    private MacWindows() { }


    /** @return true for the 64 bit ARM processors of Apple (else Intel, x86_64) */
    static boolean isArm(String osArch)
    {
        String a = osArch.toLowerCase(Locale.ROOT);
        return a.equals("aarch64") || a.equals("arm64");
    }


    /** @return true on macOS where ScreenCaptureKit can capture single windows (macOS 14 and later) */
    static synchronized boolean isAvailable()
    {
        if (!initialized) {
            initialized = true;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
                try {
                    n = new Native();
                    available = true;
                } catch (Throwable t) {
                    available = false;
                }
            }
        }
        return available;
    }


    /**
     * @return the visible windows of other applications, front to back; empty if they cannot be listed (for
     *         example without the screen recording permission)
     */
    static List<Info> list(long millis)
    {
        List<Info> list = new ArrayList<>();
        if (!isAvailable()) {
            return list;
        }
        long ownPid = ProcessHandle.current().pid();
        MemorySegment pool = n.poolPush();
        MemorySegment shareable = null;
        try (Arena arena = Arena.ofConfined()) {
            shareable = shareableContent(millis);
            if (shareable == null) {
                return list;
            }
            MemorySegment windows = n.id(shareable, "windows");
            long count = n.lng(windows, "count");
            for (long i = 0; i < count; i++) {
                MemorySegment w = n.idLong(windows, "objectAtIndex:", i);
                MemorySegment app = n.id(w, "owningApplication");
                if (app.equals(MemorySegment.NULL) || n.lng(w, "windowLayer") != 0) {
                    continue;
                }
                long pid = n.integer(app, "processID");
                MemorySegment frame = n.rect(arena, w, "frame");
                if (pid == ownPid || frame.get(JAVA_DOUBLE, 16) < MIN_SIZE || frame.get(JAVA_DOUBLE, 24) < MIN_SIZE) {
                    continue;
                }
                String title = n.string(n.id(w, "title"));
                if (title.isBlank()) {
                    title = n.string(n.id(app, "applicationName"));
                }
                if (title.isBlank()) {
                    continue;
                }
                list.add(new Info(Integer.toUnsignedLong(n.integer(w, "windowID")), pid, title.trim()));
            }
        } catch (Throwable t) {
            // listing windows is an extra; the displays still work
        } finally {
            n.release(shareable);
            n.poolPop(pool);
        }
        return list;
    }


    /** @return the window, or null if it cannot be captured (closed, minimized, no permission, no answer in time) */
    static BufferedImage capture(long windowId, long millis)
    {
        if (!isAvailable()) {
            return null;
        }
        long deadline = System.currentTimeMillis() + millis;
        MemorySegment pool = n.poolPush();
        MemorySegment shareable = null;
        MemorySegment filter = null;
        MemorySegment config = null;
        try (Arena arena = Arena.ofConfined()) {
            shareable = shareableContent(millis);
            if (shareable == null) {
                return null;
            }
            MemorySegment window = MemorySegment.NULL;
            MemorySegment windows = n.id(shareable, "windows");
            long count = n.lng(windows, "count");
            for (long i = 0; i < count && window.equals(MemorySegment.NULL); i++) {
                MemorySegment w = n.idLong(windows, "objectAtIndex:", i);
                if (Integer.toUnsignedLong(n.integer(w, "windowID")) == windowId) {
                    window = w;
                }
            }
            if (window.equals(MemorySegment.NULL)) {
                return null;
            }
            filter = n.idId(n.id(n.cls("SCContentFilter"), "alloc"), "initWithDesktopIndependentWindow:", window);
            MemorySegment frame = n.rect(arena, window, "frame");
            double scale = n.scale(filter);
            long width = Math.max(1, Math.round(frame.get(JAVA_DOUBLE, 16) * scale));
            long height = Math.max(1, Math.round(frame.get(JAVA_DOUBLE, 24) * scale));
            if (width * height > MAX_PIXELS) {
                return null;
            }
            config = n.id(n.cls("SCStreamConfiguration"), "new");
            n.voidLong(config, "setWidth:", width);
            n.voidLong(config, "setHeight:", height);
            n.voidBool(config, "setShowsCursor:", false);
            synchronized (IMAGE_LOCK) {
                if (image != null) {
                    return null; // an earlier capture was never answered
                }
                CompletableFuture<BufferedImage> f = new CompletableFuture<>();
                image = f;
                n.capture(filter, config);
                return f.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            n.release(config);
            n.release(filter);
            n.release(shareable);
            n.poolPop(pool);
        }
    }


    /** Brings the application of the window to the front (its window may need it to be drawn). */
    static void activate(long pid)
    {
        if (!isAvailable()) {
            return;
        }
        MemorySegment pool = n.poolPush();
        try {
            MemorySegment app = n.idInt(n.cls("NSRunningApplication"), "runningApplicationWithProcessIdentifier:", (int) pid);
            if (!app.equals(MemorySegment.NULL)) {
                n.boolLong(app, "activateWithOptions:", ACTIVATE_ALL_WINDOWS);
            }
        } catch (Throwable t) {
            // then the window is read as it is
        } finally {
            n.poolPop(pool);
        }
    }


    /** @return the shareable content (retained; release it), or null */
    private static MemorySegment shareableContent(long millis) throws Throwable
    {
        synchronized (CONTENT_LOCK) {
            if (content != null) {
                return null; // an earlier request was never answered
            }
            CompletableFuture<MemorySegment> f = new CompletableFuture<>();
            content = f;
            n.shareableContent();
            try {
                return f.get(millis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // a late answer (for example after the permission dialog) finds the request cancelled and releases
                // the content in complete(); if it came just now, the content is released here
                if (!f.cancel(false)) {
                    n.release(f.getNow(null));
                }
                return null;
            }
        }
    }


    // --- completion handlers, called on threads of ScreenCaptureKit; they must not throw ---

    private static void onContent(MemorySegment block, MemorySegment shareable, MemorySegment error)
    {
        CompletableFuture<MemorySegment> f = content;
        try {
            if (shareable.equals(MemorySegment.NULL)) {
                complete(f, null);
            } else {
                complete(f, n.retain(shareable));
            }
        } catch (Throwable t) {
            complete(f, null);
        }
    }


    private static void complete(CompletableFuture<MemorySegment> f, MemorySegment value)
    {
        content = null; // before completing: the waiting thread may start the next request at once
        if (f != null && !f.complete(value) && value != null) {
            n.release(value); // the request is over (timed out): nobody releases it
        }
    }


    private static void onImage(MemorySegment block, MemorySegment cgImage, MemorySegment error)
    {
        CompletableFuture<BufferedImage> f = image;
        BufferedImage result = null;
        try {
            if (!cgImage.equals(MemorySegment.NULL)) {
                result = n.toImage(cgImage); // the image is only valid during the call
            }
        } catch (Throwable t) {
            result = null;
        }
        image = null;
        if (f != null) {
            f.complete(result);
        }
    }


    /** The native functions. Creating it fails if ScreenCaptureKit or a needed function is missing. */
    private static final class Native
    {
        private final Arena arena = Arena.global();
        private final MethodHandle getClass;
        private final MethodHandle registerName;
        private final MethodHandle sendId;        // id   (id, SEL)
        private final MethodHandle sendLong;      // long (id, SEL)
        private final MethodHandle sendInt;       // int  (id, SEL)
        private final MethodHandle sendRect;      // CGRect (id, SEL)
        private final MethodHandle sendFloat;     // float (id, SEL)
        private final MethodHandle sendIdLong;    // id   (id, SEL, long)
        private final MethodHandle sendIdInt;     // id   (id, SEL, int)
        private final MethodHandle sendIdId;      // id   (id, SEL, id)
        private final MethodHandle sendVoidLong;  // void (id, SEL, long)
        private final MethodHandle sendVoidBool;  // void (id, SEL, BOOL)
        private final MethodHandle sendBoolLong;  // BOOL (id, SEL, long)
        private final MethodHandle sendContent;   // void (id, SEL, BOOL, BOOL, block)
        private final MethodHandle sendCapture;   // void (id, SEL, id, id, block)
        private final MethodHandle poolPush;
        private final MethodHandle poolPop;
        private final MethodHandle cfRetain;
        private final MethodHandle cfRelease;
        private final MethodHandle imageWidth;
        private final MethodHandle imageHeight;
        private final MethodHandle deviceRgb;
        private final MethodHandle bitmapContext;
        private final MethodHandle drawImage;
        private final MemorySegment contentBlock;
        private final MemorySegment imageBlock;


        Native() throws Throwable
        {
            SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena);
            SymbolLookup cf = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena);
            SymbolLookup cg = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena);
            // loading the frameworks registers their classes
            SymbolLookup.libraryLookup("/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit", arena);
            SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena);
            MemorySegment msgSend = objc.findOrThrow("objc_msgSend");
            // a CGRect (32 bytes) is returned in memory: on x86_64 by objc_msgSend_stret, whose hidden result
            // pointer comes first as in the C convention the descriptor gives; on arm64 objc_msgSend does it (x8)
            MemorySegment msgSendStruct = isArm(System.getProperty("os.arch", ""))
                ? msgSend : objc.findOrThrow("objc_msgSend_stret");
            getClass = LINKER.downcallHandle(objc.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            registerName = LINKER.downcallHandle(objc.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            // one handle for each signature: on arm64, objc_msgSend must not be called as a variadic function
            sendId = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            sendLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
            sendInt = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            sendRect = LINKER.downcallHandle(msgSendStruct, FunctionDescriptor.of(CG_RECT, ADDRESS, ADDRESS));
            sendFloat = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_FLOAT, ADDRESS, ADDRESS));
            sendIdLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
            sendIdInt = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
            sendIdId = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            sendVoidLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
            sendVoidBool = LINKER.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BOOLEAN));
            sendBoolLong = LINKER.downcallHandle(msgSend, FunctionDescriptor.of(JAVA_BOOLEAN, ADDRESS, ADDRESS, JAVA_LONG));
            sendContent = LINKER.downcallHandle(msgSend,
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BOOLEAN, JAVA_BOOLEAN, ADDRESS));
            sendCapture = LINKER.downcallHandle(msgSend, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            poolPush = LINKER.downcallHandle(objc.findOrThrow("objc_autoreleasePoolPush"), FunctionDescriptor.of(ADDRESS));
            poolPop = LINKER.downcallHandle(objc.findOrThrow("objc_autoreleasePoolPop"), FunctionDescriptor.ofVoid(ADDRESS));
            cfRetain = LINKER.downcallHandle(cf.findOrThrow("CFRetain"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            cfRelease = LINKER.downcallHandle(cf.findOrThrow("CFRelease"), FunctionDescriptor.ofVoid(ADDRESS));
            imageWidth = LINKER.downcallHandle(cg.findOrThrow("CGImageGetWidth"), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            imageHeight = LINKER.downcallHandle(cg.findOrThrow("CGImageGetHeight"), FunctionDescriptor.of(JAVA_LONG, ADDRESS));
            deviceRgb = LINKER.downcallHandle(cg.findOrThrow("CGColorSpaceCreateDeviceRGB"), FunctionDescriptor.of(ADDRESS));
            bitmapContext = LINKER.downcallHandle(cg.findOrThrow("CGBitmapContextCreate"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));
            drawImage = LINKER.downcallHandle(cg.findOrThrow("CGContextDrawImage"), FunctionDescriptor.ofVoid(ADDRESS, CG_RECT, ADDRESS));

            // SCScreenshotManager (macOS 14) is the newest class used here
            for (String name : new String[] {"SCShareableContent", "SCContentFilter", "SCStreamConfiguration",
                                             "SCScreenshotManager", "NSRunningApplication"}) {
                if (cls(name).equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("no " + name);
                }
            }
            MemorySegment globalBlock = LINKER.defaultLookup().findOrThrow("_NSConcreteGlobalBlock");
            contentBlock = block(globalBlock, "onContent");
            imageBlock = block(globalBlock, "onImage");
        }


        /**
         * A global block (no captured variables) that calls the method with (block, object, error):
         * struct { void *isa; int flags; int reserved; void *invoke; struct { long reserved; long size; } *descriptor; }
         */
        private MemorySegment block(MemorySegment isa, String method) throws Throwable
        {
            MethodHandle target = MethodHandles.lookup().findStatic(MacWindows.class, method,
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            MemorySegment invoke = LINKER.upcallStub(target, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), arena);
            MemorySegment descriptor = arena.allocate(16, 8);
            descriptor.set(JAVA_LONG, 0, 0);
            descriptor.set(JAVA_LONG, 8, 32);
            MemorySegment block = arena.allocate(32, 8);
            block.set(ADDRESS, 0, isa);
            block.set(JAVA_INT, 8, 1 << 28); // BLOCK_IS_GLOBAL: copying it returns the same block
            block.set(JAVA_INT, 12, 0);
            block.set(ADDRESS, 16, invoke);
            block.set(ADDRESS, 24, descriptor);
            return block;
        }


        MemorySegment cls(String name) throws Throwable
        {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) getClass.invokeExact(a.allocateFrom(name));
            }
        }


        private MemorySegment sel(String name) throws Throwable
        {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) registerName.invokeExact(a.allocateFrom(name));
            }
        }


        MemorySegment id(MemorySegment o, String s) throws Throwable
        {
            return (MemorySegment) sendId.invokeExact(o, sel(s));
        }


        long lng(MemorySegment o, String s) throws Throwable
        {
            return (long) sendLong.invokeExact(o, sel(s));
        }


        int integer(MemorySegment o, String s) throws Throwable
        {
            return (int) sendInt.invokeExact(o, sel(s));
        }


        MemorySegment rect(Arena a, MemorySegment o, String s) throws Throwable
        {
            return (MemorySegment) sendRect.invokeExact((SegmentAllocator) a, o, sel(s));
        }


        MemorySegment idLong(MemorySegment o, String s, long v) throws Throwable
        {
            return (MemorySegment) sendIdLong.invokeExact(o, sel(s), v);
        }


        MemorySegment idInt(MemorySegment o, String s, int v) throws Throwable
        {
            return (MemorySegment) sendIdInt.invokeExact(o, sel(s), v);
        }


        MemorySegment idId(MemorySegment o, String s, MemorySegment v) throws Throwable
        {
            return (MemorySegment) sendIdId.invokeExact(o, sel(s), v);
        }


        void voidLong(MemorySegment o, String s, long v) throws Throwable
        {
            sendVoidLong.invokeExact(o, sel(s), v);
        }


        void voidBool(MemorySegment o, String s, boolean v) throws Throwable
        {
            sendVoidBool.invokeExact(o, sel(s), v);
        }


        boolean boolLong(MemorySegment o, String s, long v) throws Throwable
        {
            return (boolean) sendBoolLong.invokeExact(o, sel(s), v);
        }


        /** @return the text of an NSString, or "" for nil */
        String string(MemorySegment nsString) throws Throwable
        {
            if (nsString.equals(MemorySegment.NULL)) {
                return "";
            }
            MemorySegment utf8 = id(nsString, "UTF8String");
            return utf8.equals(MemorySegment.NULL) ? "" : utf8.reinterpret(Integer.MAX_VALUE).getString(0);
        }


        /** @return the pixels for each point of the filtered content (2 on Retina displays) */
        double scale(MemorySegment filter)
        {
            try {
                MemorySegment info = idId(cls("SCShareableContent"), "infoForFilter:", filter);
                float s = (float) sendFloat.invokeExact(info, sel("pointPixelScale"));
                return s >= 1 && s <= 4 ? s : 2;
            } catch (Throwable t) {
                return 2;
            }
        }


        void shareableContent() throws Throwable
        {
            // excluding desktop windows, on-screen windows only (not minimized ones)
            sendContent.invokeExact(cls("SCShareableContent"),
                                    sel("getShareableContentExcludingDesktopWindows:onScreenWindowsOnly:completionHandler:"),
                                    true, true, contentBlock);
        }


        void capture(MemorySegment filter, MemorySegment config) throws Throwable
        {
            sendCapture.invokeExact(cls("SCScreenshotManager"), sel("captureImageWithFilter:configuration:completionHandler:"),
                                    filter, config, imageBlock);
        }


        BufferedImage toImage(MemorySegment cgImage) throws Throwable
        {
            long w = (long) imageWidth.invokeExact(cgImage);
            long h = (long) imageHeight.invokeExact(cgImage);
            if (w <= 0 || h <= 0 || w * h > MAX_PIXELS) {
                return null;
            }
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pixels = a.allocate(w * h * 4, 16);
                MemorySegment space = (MemorySegment) deviceRgb.invokeExact();
                // kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little: one int per pixel, 0xAARRGGBB
                MemorySegment context = (MemorySegment) bitmapContext.invokeExact(pixels, w, h, 8L, w * 4, space, 2 | (2 << 12));
                try {
                    if (context.equals(MemorySegment.NULL)) {
                        return null;
                    }
                    MemorySegment bounds = a.allocate(CG_RECT);
                    bounds.set(JAVA_DOUBLE, 16, (double) w);
                    bounds.set(JAVA_DOUBLE, 24, (double) h);
                    drawImage.invokeExact(context, bounds, cgImage);
                } finally {
                    release(context);
                    release(space);
                }
                BufferedImage img = new BufferedImage((int) w, (int) h, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, (int) w, (int) h, pixels.toArray(JAVA_INT), 0, (int) w);
                return img;
            }
        }


        MemorySegment retain(MemorySegment o) throws Throwable
        {
            return (MemorySegment) cfRetain.invokeExact(o);
        }


        /** Releases an object (CFRelease works for Objective-C objects too); nothing for null. */
        void release(MemorySegment o)
        {
            if (o == null || o.equals(MemorySegment.NULL)) {
                return;
            }
            try {
                cfRelease.invokeExact(o);
            } catch (Throwable t) {
                // nothing to do
            }
        }


        MemorySegment poolPush()
        {
            try {
                return (MemorySegment) poolPush.invokeExact();
            } catch (Throwable t) {
                return MemorySegment.NULL;
            }
        }


        void poolPop(MemorySegment pool)
        {
            if (pool.equals(MemorySegment.NULL)) {
                return;
            }
            try {
                poolPop.invokeExact(pool);
            } catch (Throwable t) {
                // nothing to do
            }
        }
    }
}
