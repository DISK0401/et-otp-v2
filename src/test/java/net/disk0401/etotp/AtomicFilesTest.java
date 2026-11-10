/*
 * AtomicFilesTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class AtomicFilesTest
{
    @TempDir
    File tmp;

    @Test
    void writeNewCreatesTheFile() throws Exception
    {
        File f = new File(tmp, "new.properties");

        AtomicFiles.writeNew("abc".getBytes(StandardCharsets.UTF_8), f);

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(f.toPath()));
        assertEquals(1, tmp.list().length);
    }

    @Test
    void writeNewNeverReplacesAFile() throws Exception
    {
        File f = new File(tmp, "existing.properties");
        Files.writeString(f.toPath(), "keep");

        assertThrows(FileAlreadyExistsException.class,
                     () -> AtomicFiles.writeNew("new".getBytes(StandardCharsets.UTF_8), f));

        assertEquals("keep", Files.readString(f.toPath()));
        assertEquals(1, tmp.list().length);
    }
}
