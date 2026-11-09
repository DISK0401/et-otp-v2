/*
 * SecretStore.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import net.disk0401.etotp.Base32.DecodingException;


/**
 * Reads and writes the password-encrypted token secret in the configuration file.
 * <p>
 * Format version 2 (written by et-otp-v2): PBKDF2WithHmacSHA256 with a random 16 byte salt
 * derives a 256 bit key, the secret is encrypted with AES-256-GCM using a random 12 byte IV.
 * The format parameters are bound to the ciphertext as additional authenticated data, so any
 * modification of the file or a wrong password is detected.
 * <p>
 * Format version 1 (written by et-otp 1.x, no {@code key.1.version} property):
 * PBKDF2WithHmacSHA1 with 1000 iterations and a 128 bit key, AES in ECB mode with PKCS5 padding.
 * It is only read, so existing configuration files can be migrated.
 */
final class SecretStore
{
    /** PBKDF2-HMAC-SHA256 iterations for new files (OWASP 2023 recommendation). */
    static final int DEFAULT_ITERATIONS = 600000;

    static final int CURRENT_VERSION = 2;

    private static final String PREFIX = "key.1.";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final String LEGACY_KDF = "PBKDF2WithHmacSHA1";
    private static final String LEGACY_CIPHER = "AES/ECB/PKCS5Padding";
    private static final int LEGACY_ITERATIONS = 1000;
    private static final int LEGACY_KEY_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();


    private SecretStore() { }


    /** Decrypted secret and the format version it was read from. */
    static final class Result
    {
        private final byte[] secret;
        private final int version;

        Result(byte[] secret, int version)
        {
            this.secret = secret;
            this.version = version;
        }

        byte[] secret()
        {
            return secret;
        }

        int version()
        {
            return version;
        }

        /** @return true if the file uses the et-otp 1.x format and should be re-written */
        boolean isLegacy()
        {
            return version < CURRENT_VERSION;
        }
    }


    /**
     * Encrypts the secret with the password and (atomically) replaces the file.
     * The password array is not modified.
     */
    static void write(File file, byte[] secret, char[] pass) throws IOException, GeneralSecurityException
    {
        write(file, secret, pass, DEFAULT_ITERATIONS);
    }


    static void write(File file, byte[] secret, char[] pass, int iterations)
            throws IOException, GeneralSecurityException
    {
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);

        byte[] key = deriveKey(KDF, pass, salt, iterations, KEY_BITS);
        byte[] enc;
        try {
            Cipher c = Cipher.getInstance(CIPHER);
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(CURRENT_VERSION, KDF, iterations, CIPHER));
            enc = c.doFinal(secret);
        } finally {
            Arrays.fill(key, (byte) 0);
        }

        Properties p = new Properties();
        p.setProperty(PREFIX + "name", "default");
        p.setProperty(PREFIX + "version", Integer.toString(CURRENT_VERSION));
        p.setProperty(PREFIX + "kdf", KDF);
        p.setProperty(PREFIX + "iterations", Integer.toString(iterations));
        p.setProperty(PREFIX + "cipher", CIPHER);
        p.setProperty(PREFIX + "salt", Base32.encode(salt));
        p.setProperty(PREFIX + "iv", Base32.encode(iv));
        p.setProperty(PREFIX + "encoded", Base32.encode(enc));
        storeAtomically(p, file);
    }


    /**
     * Reads and decrypts the secret. The password array is not modified.
     *
     * @throws javax.crypto.BadPaddingException if the password is wrong (or the file was modified)
     * @throws IOException if the file is missing, incomplete or uses an unsupported version
     */
    static Result read(File file, char[] pass) throws IOException, GeneralSecurityException
    {
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(file)) {
            p.load(is);
        }

        String versionText = p.getProperty(PREFIX + "version");
        if (versionText == null) {
            return new Result(readLegacy(p, pass), 1);
        }

        int version;
        try {
            version = Integer.parseInt(versionText.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid configuration format version: " + versionText);
        }
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported configuration format version " + version
                                  + " (this program supports up to " + CURRENT_VERSION + ")");
        }

        String kdf = required(p, "kdf");
        String cipher = required(p, "cipher");
        int iterations;
        try {
            iterations = Integer.parseInt(required(p, "iterations").trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid iteration count in configuration");
        }
        if (!KDF.equals(kdf) || !CIPHER.equals(cipher) || iterations < 1) {
            throw new IOException("Unsupported configuration parameters: " + kdf + ", " + cipher + ", " + iterations);
        }
        byte[] salt = decode(p, "salt");
        byte[] iv = decode(p, "iv");
        byte[] enc = decode(p, "encoded");

        byte[] key = deriveKey(kdf, pass, salt, iterations, KEY_BITS);
        try {
            Cipher c = Cipher.getInstance(cipher);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad(version, kdf, iterations, cipher));
            return new Result(c.doFinal(enc), version);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }


    /**
     * Re-writes an et-otp 1.x file in the current format.
     * <p>
     * The legacy format cannot detect every wrong password (AES/ECB padding is accepted by chance
     * about once in 256 tries), so the original file is first copied to {@code <file>.v1.bak}.
     * An existing backup is never overwritten.
     *
     * @return the backup file
     */
    static File migrate(File file, Result legacy, char[] pass) throws IOException, GeneralSecurityException
    {
        File backup = new File(file.getPath() + ".v1.bak");
        if (!backup.exists()) {
            Files.copy(file.toPath(), backup.toPath());
        }
        write(file, legacy.secret(), pass);
        return backup;
    }


    private static byte[] readLegacy(Properties p, char[] pass) throws IOException, GeneralSecurityException
    {
        byte[] salt = decode(p, "salt");
        byte[] enc = decode(p, "encoded");
        byte[] key = deriveKey(LEGACY_KDF, pass, salt, LEGACY_ITERATIONS, LEGACY_KEY_BITS);
        try {
            Cipher c = Cipher.getInstance(LEGACY_CIPHER);
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c.doFinal(enc);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }


    private static byte[] deriveKey(String kdf, char[] pass, byte[] salt, int iterations, int bits)
            throws GeneralSecurityException
    {
        PBEKeySpec spec = new PBEKeySpec(pass, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance(kdf).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }


    private static byte[] aad(int version, String kdf, int iterations, String cipher)
    {
        return ("et-otp|" + version + "|" + kdf + "|" + iterations + "|" + cipher).getBytes(StandardCharsets.US_ASCII);
    }


    private static String required(Properties p, String name) throws IOException
    {
        String v = p.getProperty(PREFIX + name);
        if (v == null || v.trim().isEmpty()) {
            throw new IOException("Configuration file is incomplete (missing " + PREFIX + name + ")");
        }
        return v;
    }


    private static byte[] decode(Properties p, String name) throws IOException
    {
        try {
            return Base32.decode(required(p, name).trim());
        } catch (DecodingException e) {
            throw new IOException("Invalid " + PREFIX + name + " in configuration: " + e.getMessage());
        }
    }


    private static void storeAtomically(Properties p, File file) throws IOException
    {
        Path target = file.getAbsoluteFile().toPath();
        Path dir = target.getParent();
        Path tmp = Files.createTempFile(dir, ".et-otp", ".tmp");
        try {
            try (OutputStream os = new FileOutputStream(tmp.toFile())) {
                p.store(os, "et-otp-v2 soft token configuration");
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
