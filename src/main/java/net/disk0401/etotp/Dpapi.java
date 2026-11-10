/*
 * Dpapi.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Locale;


/**
 * Windows Data Protection API (CryptProtectData / CryptUnprotectData), called with the FFM API.
 * Data protected this way can only be decrypted by the same Windows user (on the same computer,
 * unless the user has a roaming profile). No password is needed.
 */
final class Dpapi
{
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    /** typedef struct _CRYPTOAPI_BLOB { DWORD cbData; BYTE *pbData; } DATA_BLOB; */
    private static final StructLayout DATA_BLOB = MemoryLayout.structLayout(
        JAVA_INT.withName("cbData"),
        MemoryLayout.paddingLayout(4),
        ADDRESS.withName("pbData"));
    private static final VarHandle CB_DATA = DATA_BLOB.varHandle(MemoryLayout.PathElement.groupElement("cbData"));
    private static final VarHandle PB_DATA = DATA_BLOB.varHandle(MemoryLayout.PathElement.groupElement("pbData"));


    private Dpapi() { }


    /** @return true on Windows, where the API exists */
    static boolean isAvailable()
    {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }


    /** Encrypts for the current Windows user. */
    static byte[] protect(byte[] plain, byte[] entropy) throws IOException
    {
        return call(true, plain, entropy);
    }


    /** Decrypts data that the current Windows user protected. */
    static byte[] unprotect(byte[] encrypted, byte[] entropy) throws IOException
    {
        return call(false, encrypted, entropy);
    }


    private static byte[] call(boolean protect, byte[] input, byte[] entropy) throws IOException
    {
        if (!isAvailable()) {
            throw new IOException("Windows data protection is only available on Windows");
        }
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32", arena);
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);

            // BOOL CryptProtectData(DATA_BLOB *in, LPCWSTR desc, DATA_BLOB *entropy, PVOID reserved,
            //                       CRYPTPROTECT_PROMPTSTRUCT *prompt, DWORD flags, DATA_BLOB *out)
            // BOOL CryptUnprotectData(DATA_BLOB *in, LPWSTR *desc, DATA_BLOB *entropy, PVOID reserved,
            //                         CRYPTPROTECT_PROMPTSTRUCT *prompt, DWORD flags, DATA_BLOB *out)
            MethodHandle fn = linker.downcallHandle(
                crypt32.findOrThrow(protect ? "CryptProtectData" : "CryptUnprotectData"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
            // HLOCAL LocalFree(HLOCAL mem)
            MethodHandle localFree = linker.downcallHandle(kernel32.findOrThrow("LocalFree"),
                                                           FunctionDescriptor.of(ADDRESS, ADDRESS));

            MemorySegment in = blob(arena, input);
            MemorySegment ent = entropy == null ? MemorySegment.NULL : blob(arena, entropy);
            MemorySegment out = arena.allocate(DATA_BLOB);

            int ok = (int) fn.invokeExact(in, MemorySegment.NULL, ent, MemorySegment.NULL, MemorySegment.NULL,
                                          CRYPTPROTECT_UI_FORBIDDEN, out);
            // the input copy may hold plain text
            in.get(ADDRESS, 8).reinterpret(input.length).fill((byte) 0);
            if (ok == 0) {
                throw new IOException((protect ? "CryptProtectData" : "CryptUnprotectData")
                                      + " failed (the data may belong to another Windows user)");
            }
            int len = (int) CB_DATA.get(out, 0L);
            MemorySegment data = ((MemorySegment) PB_DATA.get(out, 0L)).reinterpret(len);
            try {
                return data.toArray(JAVA_BYTE);
            } finally {
                data.fill((byte) 0);
                MemorySegment ignored = (MemorySegment) localFree.invokeExact(data);
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Windows data protection failed: " + t, t);
        }
    }


    private static MemorySegment blob(Arena arena, byte[] bytes)
    {
        MemorySegment data = arena.allocate(Math.max(1, bytes.length));
        MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.length);
        MemorySegment blob = arena.allocate(DATA_BLOB);
        CB_DATA.set(blob, 0L, bytes.length);
        PB_DATA.set(blob, 0L, data);
        return blob;
    }
}
