/*
 * AtomicFilesTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
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

    /** et-otp-v2 issue #35: saving through a symbolic link replaces the file it points to, not the link. */
    @Test
    void writeKeepsASymbolicLink() throws Exception
    {
        File shared = new File(tmp, "shared");
        assertTrue(shared.mkdir());
        Path target = new File(shared, "token.properties").toPath();
        Files.writeString(target, "old");
        Path link = tmp.toPath().resolve("link.properties");
        // relative, as links into a synchronized folder often are
        createLink(link, Path.of("shared", "token.properties"));

        AtomicFiles.write("new".getBytes(StandardCharsets.UTF_8), link.toFile());

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("new", Files.readString(target));
        assertEquals(1, shared.list().length);
        assertEquals(2, tmp.list().length);
    }

    @Test
    void writeFollowsAChainOfLinksToAMissingFile() throws Exception
    {
        Path target = tmp.toPath().resolve("missing.properties");
        Path second = tmp.toPath().resolve("second.properties");
        Path first = tmp.toPath().resolve("first.properties");
        createLink(second, target);
        createLink(first, second);

        AtomicFiles.write("new".getBytes(StandardCharsets.UTF_8), first.toFile());

        assertTrue(Files.isSymbolicLink(first));
        assertTrue(Files.isSymbolicLink(second));
        assertEquals("new", Files.readString(target));
    }

    @Test
    void writeFailsOnALoopOfLinks() throws Exception
    {
        Path a = tmp.toPath().resolve("a.properties");
        Path b = tmp.toPath().resolve("b.properties");
        createLink(a, b);
        createLink(b, a);

        assertThrows(FileSystemException.class, () -> AtomicFiles.write(new byte[] { 1 }, a.toFile()));

        assertTrue(Files.isSymbolicLink(a));
        assertTrue(Files.isSymbolicLink(b));
        assertFalse(Files.exists(a));
        assertEquals(2, tmp.list().length);
    }

    /** Creates a symbolic link, or skips the test where that is not allowed (Windows without developer mode). */
    private static void createLink(Path link, Path target)
    {
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            Assumptions.abort("symbolic links cannot be created: " + e);
        }
    }
}
