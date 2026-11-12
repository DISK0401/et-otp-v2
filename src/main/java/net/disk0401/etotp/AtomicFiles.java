/*
 * AtomicFiles.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
        FileChannel channel = target.getFileSystem().supportedFileAttributeViews().contains("posix")
            ? FileChannel.open(target, options, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                  java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            : FileChannel.open(target, options);
        // from here on the file was created by this call: remove it if it cannot be written completely
        try {
            try (FileChannel c = channel) {
                writeAndSync(c, content);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        syncDirectory(target.getParent());
    }


    /** Writes the bytes to a temporary file in the same directory and then replaces the target. */
    static void write(byte[] content, File file) throws IOException
    {
        Path target = file.getAbsoluteFile().toPath();
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".et-otp", ".tmp");
        try {
            try (FileChannel c = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                // on the disk before the move: after a power loss, the file is either old or new, never empty
                writeAndSync(c, content);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        syncDirectory(dir);
    }


    private static void writeAndSync(FileChannel c, byte[] content) throws IOException
    {
        ByteBuffer b = ByteBuffer.wrap(content);
        while (b.hasRemaining()) {
            c.write(b);
        }
        c.force(true);
    }


    /** Makes the new name in the directory durable; not possible (and not needed) on Windows. */
    private static void syncDirectory(Path dir)
    {
        if (dir == null || File.separatorChar == '\\') {
            return;
        }
        try (FileChannel c = FileChannel.open(dir, StandardOpenOption.READ)) {
            c.force(true);
        } catch (IOException | RuntimeException e) {
            // some file systems cannot open a directory: the file itself is written
        }
    }
}
