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
        Path target = file.getAbsoluteFile().toPath();
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".et-otp", ".tmp");
        try {
            try (OutputStream os = new FileOutputStream(tmp.toFile())) {
                p.store(os, comment);
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
