/*
 * AtomicFiles.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;


/**
 * Writes properties files so that an interrupted write never leaves a half-written file.
 */
final class AtomicFiles
{
    private AtomicFiles() { }


    /** Stores the properties in a temporary file in the same directory and then replaces the target. */
    static void store(Properties p, File file, String comment) throws IOException
    {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        p.store(bytes, comment);
        write(bytes.toByteArray(), file);
    }


    /**
     * Writes a new file like {@link #write(byte[], File)}, but fails if the target exists.
     *
     * @throws java.nio.file.FileAlreadyExistsException if the target exists, also if it was created meanwhile
     */
    static void writeNew(byte[] content, File file) throws IOException
    {
        // CREATE_NEW checks and creates in one step; a temporary file and a move could replace a file created
        // between the check and the move
        Path target = file.getAbsoluteFile().toPath();
        java.util.Set<StandardOpenOption> options = java.util.EnumSet.of(StandardOpenOption.CREATE_NEW,
                                                                          StandardOpenOption.WRITE);
        // only the owner may read it, like the temporary files of store() (on Windows the folder's rights apply)
        OutputStream os = java.nio.channels.Channels.newOutputStream(
            target.getFileSystem().supportedFileAttributeViews().contains("posix")
            ? Files.newByteChannel(target, options, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            : Files.newByteChannel(target, options));
        // from here on the file was created by this call: remove it if it cannot be written completely
        try {
            try (OutputStream out = os) {
                out.write(content);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }


    /** Writes the bytes to a temporary file in the same directory and then replaces the target. */
    static void write(byte[] content, File file) throws IOException
    {
        Path target = file.getAbsoluteFile().toPath();
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".et-otp", ".tmp");
        try {
            try (OutputStream os = new FileOutputStream(tmp.toFile())) {
                os.write(content);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
