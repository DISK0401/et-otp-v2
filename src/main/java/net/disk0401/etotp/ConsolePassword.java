/*
 * ConsolePassword.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;


/**
 * Reads a password from the console without showing it, also when the standard output is redirected
 * (as in {@code for /f} of a batch file, where {@link System#console()} is null).
 */
final class ConsolePassword
{
    private static final int MAX_CHARS = 1024;
    private static final Charset UTF_16LE = StandardCharsets.UTF_16LE;

    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int FILE_SHARE_READ_WRITE = 0x3;
    private static final int OPEN_EXISTING = 3;
    private static final int ENABLE_PROCESSED_INPUT = 0x1;
    private static final int ENABLE_LINE_INPUT = 0x2;
    private static final int ENABLE_ECHO_INPUT = 0x4;


    private ConsolePassword() { }


    /**
     * Shows the prompt on the standard error output and reads one line.
     *
     * @return the password (the caller clears it), or null if there is no console to read from
     */
    static char[] read(String prompt, PrintStream err) throws IOException
    {
        // not System.console(): its prompt would go to the standard output, which holds only the code
        return Dpapi.isAvailable() ? readWindows(prompt, err) : readTty(prompt, err);
    }


    /** Reads from CONIN$, the input of the console window, with the echo switched off. */
    private static char[] readWindows(String prompt, PrintStream err) throws IOException
    {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle createFile = linker.downcallHandle(kernel32.findOrThrow("CreateFileW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            MethodHandle closeHandle = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MethodHandle getMode = linker.downcallHandle(kernel32.findOrThrow("GetConsoleMode"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle setMode = linker.downcallHandle(kernel32.findOrThrow("SetConsoleMode"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle readConsole = linker.downcallHandle(kernel32.findOrThrow("ReadConsoleW"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

            MemorySegment handle = (MemorySegment) createFile.invokeExact(arena.allocateFrom("CONIN$", UTF_16LE),
                GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ_WRITE, MemorySegment.NULL, OPEN_EXISTING, 0,
                MemorySegment.NULL);
            if (handle.address() == -1L || handle.address() == 0L) {
                return null; // no console (for example started with javaw)
            }
            try {
                MemorySegment modeOut = arena.allocate(JAVA_INT);
                if ((int) getMode.invokeExact(handle, modeOut) == 0) {
                    return null;
                }
                int mode = modeOut.get(JAVA_INT, 0);
                int quiet = (mode | ENABLE_LINE_INPUT | ENABLE_PROCESSED_INPUT) & ~ENABLE_ECHO_INPUT;
                // restore the echo also if the user stops with Ctrl+C
                Thread restore = new Thread(() -> restoreMode(mode));
                Runtime.getRuntime().addShutdownHook(restore);
                if ((int) setMode.invokeExact(handle, quiet) == 0) {
                    return null;
                }
                MemorySegment buffer = arena.allocate(JAVA_CHAR, MAX_CHARS);
                MemorySegment count = arena.allocate(JAVA_INT);
                char[] line = new char[MAX_CHARS];
                int length = 0;
                try {
                    err.print(prompt);
                    err.flush();
                    boolean end = false;
                    while (!end && length < MAX_CHARS) {
                        if ((int) readConsole.invokeExact(handle, buffer, MAX_CHARS, count, MemorySegment.NULL) == 0) {
                            throw new IOException("Cannot read from the console");
                        }
                        int n = count.get(JAVA_INT, 0);
                        if (n == 0) {
                            break;
                        }
                        for (int i = 0; i < n && !end; i++) {
                            char c = buffer.getAtIndex(JAVA_CHAR, i);
                            if (c == '\r' || c == '\n') {
                                end = true;
                            } else if (length < MAX_CHARS) {
                                line[length++] = c;
                            }
                        }
                    }
                    return Arrays.copyOf(line, length);
                } finally {
                    buffer.fill((byte) 0);
                    Arrays.fill(line, '\0');
                    int ignored = (int) setMode.invokeExact(handle, mode);
                    try {
                        Runtime.getRuntime().removeShutdownHook(restore);
                    } catch (IllegalStateException shuttingDown) {
                        // the hook restores the mode
                    }
                    err.println();
                }
            } finally {
                int ignored = (int) closeHandle.invokeExact(handle);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Cannot read the password from the console: " + t, t);
        }
    }


    /** Called on shutdown (Ctrl+C) while the echo is off: opens the console again to switch it on. */
    private static void restoreMode(int mode)
    {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle createFile = linker.downcallHandle(kernel32.findOrThrow("CreateFileW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
            MethodHandle setMode = linker.downcallHandle(kernel32.findOrThrow("SetConsoleMode"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
            MethodHandle closeHandle = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MemorySegment handle = (MemorySegment) createFile.invokeExact(arena.allocateFrom("CONIN$", UTF_16LE),
                GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ_WRITE, MemorySegment.NULL, OPEN_EXISTING, 0,
                MemorySegment.NULL);
            if (handle.address() != -1L && handle.address() != 0L) {
                int ignored = (int) setMode.invokeExact(handle, mode);
                ignored = (int) closeHandle.invokeExact(handle);
            }
        } catch (Throwable ignored) {
            // nothing more can be done while shutting down
        }
    }


    /** Reads from /dev/tty (Linux, macOS), with the echo switched off by stty. */
    private static char[] readTty(String prompt, PrintStream err) throws IOException
    {
        File tty = new File("/dev/tty");
        if (!tty.canRead()) {
            return null;
        }
        if (!stty(tty, "-echo")) {
            return null;
        }
        Thread restore = new Thread(() -> stty(tty, "echo"));
        Runtime.getRuntime().addShutdownHook(restore);
        char[] line = new char[MAX_CHARS];
        int length = 0;
        try (InputStream in = new FileInputStream(tty);
             Reader reader = new InputStreamReader(in, terminalCharset())) {
            err.print(prompt);
            err.flush();
            int c;
            while ((c = reader.read()) != -1 && c != '\n' && c != '\r') {
                if (length < MAX_CHARS) {
                    line[length++] = (char) c;
                }
            }
            return Arrays.copyOf(line, length);
        } finally {
            Arrays.fill(line, '\0');
            stty(tty, "echo");
            try {
                Runtime.getRuntime().removeShutdownHook(restore);
            } catch (IllegalStateException shuttingDown) {
                // the hook restores the echo
            }
            err.println();
        }
    }


    /** @return the encoding of the terminal; the default charset is UTF-8 also in other locales */
    private static Charset terminalCharset()
    {
        for (String property : new String[] { "stdin.encoding", "native.encoding" }) {
            String name = System.getProperty(property);
            try {
                if (name != null) {
                    return Charset.forName(name);
                }
            } catch (IllegalArgumentException e) {
                // try the next one
            }
        }
        return Charset.defaultCharset();
    }


    private static boolean stty(File tty, String setting)
    {
        try {
            Process p = new ProcessBuilder("stty", setting)
                .redirectInput(tty)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
