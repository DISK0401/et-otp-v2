/*
 * SecretStore.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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

    /**
     * Upper bound for the (not yet authenticated) iteration count read from a file, so a corrupted
     * or tampered file cannot block the application in key derivation.
     */
    static final int MAX_ITERATIONS = 10000000;

    static final int CURRENT_VERSION = 2;

    private static final String PREFIX = "key.1.";
    /** First part of the authenticated data of the configuration file. */
    private static final String CONFIG_DOMAIN = "et-otp";
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Properties that only exist in format version 2 and later. */
    private static final String[] V2_ONLY = { "kdf", "iterations", "cipher", "iv" };

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
        /** the raw file content the secret was read from, used to detect concurrent changes. */
        private final byte[] source;

        Result(byte[] secret, int version, byte[] source)
        {
            this.secret = secret;
            this.version = version;
            this.source = source;
        }

        byte[] secret()
        {
            return secret;
        }

        int version()
        {
            return version;
        }

        /** @return the file contents the secret was read from */
        byte[] source()
        {
            return source;
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
        storeAtomically(encrypt(secret, pass, iterations), file);
    }


    private static Properties encrypt(byte[] secret, char[] pass, int iterations) throws GeneralSecurityException
    {
        PasswordKey key = PasswordKey.create(pass, iterations);
        try {
            Properties p = new Properties();
            p.setProperty(PREFIX + "name", "default");
            encrypt(p, secret, key, CURRENT_VERSION);
            return p;
        } finally {
            key.destroy();
        }
    }


    /**
     * A key derived from a password, with its salt and iteration count. The account list keeps it
     * while the program runs, so saving a change does not repeat the slow key derivation.
     */
    static final class PasswordKey
    {
        private final byte[] key;
        private final byte[] salt;
        private final int iterations;

        private PasswordKey(byte[] key, byte[] salt, int iterations)
        {
            this.key = key;
            this.salt = salt;
            this.iterations = iterations;
        }

        /** Derives a key with a new random salt. The password array is not modified. */
        static PasswordKey create(char[] pass, int iterations) throws GeneralSecurityException
        {
            byte[] salt = new byte[SALT_BYTES];
            RANDOM.nextBytes(salt);
            return new PasswordKey(deriveKey(KDF, pass, salt, iterations, KEY_BITS), salt, iterations);
        }

        /** Derives the key with the salt and iteration count stored in the properties. */
        static PasswordKey from(Properties p, char[] pass) throws IOException, GeneralSecurityException
        {
            return from(p, PREFIX, pass);
        }

        /** Derives the key with the salt and iteration count stored in the properties under the prefix. */
        static PasswordKey from(Properties p, String prefix, char[] pass) throws IOException, GeneralSecurityException
        {
            String kdf = required(p, prefix, "kdf");
            String cipher = required(p, prefix, "cipher");
            int iterations;
            try {
                iterations = Integer.parseInt(required(p, prefix, "iterations").trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid iteration count in " + prefix + "iterations");
            }
            if (!KDF.equals(kdf) || !CIPHER.equals(cipher) || iterations < 1 || iterations > MAX_ITERATIONS) {
                throw new IOException("Unsupported encryption parameters: " + kdf + ", " + cipher + ", " + iterations);
            }
            byte[] salt = decode(p, prefix, "salt");
            check(salt.length == SALT_BYTES, prefix, "salt", "must be " + SALT_BYTES + " bytes");
            return new PasswordKey(deriveKey(kdf, pass, salt, iterations, KEY_BITS), salt, iterations);
        }

        /**
         * Checks a password against this key (derives the key again with the same salt).
         * The password array is not modified.
         */
        boolean matches(char[] pass) throws GeneralSecurityException
        {
            byte[] other = deriveKey(KDF, pass, salt, iterations, KEY_BITS);
            try {
                return java.security.MessageDigest.isEqual(key, other);
            } finally {
                Arrays.fill(other, (byte) 0);
            }
        }

        /** Overwrites the key in memory; the object cannot be used afterwards. */
        void destroy()
        {
            Arrays.fill(key, (byte) 0);
        }
    }


    /**
     * Encrypts the data with AES-256-GCM and a new random IV and writes the format properties
     * (version, kdf, iterations, cipher, salt, iv, encoded) into p. The version is authenticated.
     */
    static void encrypt(Properties p, byte[] plain, PasswordKey key, int version) throws GeneralSecurityException
    {
        encrypt(p, PREFIX, CONFIG_DOMAIN, plain, key, version);
    }


    /**
     * Encrypts like {@link #encrypt(Properties, byte[], PasswordKey, int)}, with the properties under another prefix
     * and another domain in the authenticated data, so data of one kind cannot be read as the other.
     */
    static void encrypt(Properties p, String prefix, String domain, byte[] plain, PasswordKey key, int version)
            throws GeneralSecurityException
    {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad(domain, version, KDF, key.iterations, CIPHER));
        byte[] enc = c.doFinal(plain);

        p.setProperty(prefix + "version", Integer.toString(version));
        p.setProperty(prefix + "kdf", KDF);
        p.setProperty(prefix + "iterations", Integer.toString(key.iterations));
        p.setProperty(prefix + "cipher", CIPHER);
        p.setProperty(prefix + "salt", Base32.encode(key.salt));
        p.setProperty(prefix + "iv", Base32.encode(iv));
        p.setProperty(prefix + "encoded", Base32.encode(enc));
    }


    /**
     * Decrypts data written by {@link #encrypt(Properties, byte[], PasswordKey, int)}.
     *
     * @throws javax.crypto.BadPaddingException if the key is wrong or the file was modified
     */
    static byte[] decrypt(Properties p, PasswordKey key, int version) throws IOException, GeneralSecurityException
    {
        return decrypt(p, PREFIX, CONFIG_DOMAIN, key, version);
    }


    /** Decrypts data written by {@link #encrypt(Properties, String, String, byte[], PasswordKey, int)}. */
    static byte[] decrypt(Properties p, String prefix, String domain, PasswordKey key, int version)
            throws IOException, GeneralSecurityException
    {
        byte[] iv = decode(p, prefix, "iv");
        byte[] enc = decode(p, prefix, "encoded");
        check(iv.length == IV_BYTES, prefix, "iv", "must be " + IV_BYTES + " bytes");
        check(enc.length > TAG_BITS / 8, prefix, "encoded", "too short");
        Cipher c = Cipher.getInstance(CIPHER);
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        c.updateAAD(aad(domain, version, KDF, key.iterations, CIPHER));
        return c.doFinal(enc);
    }


    /** Parses the configuration file contents. */
    static Properties load(byte[] source) throws IOException
    {
        Properties p = new Properties();
        try (InputStream is = new ByteArrayInputStream(source)) {
            p.load(is);
        } catch (IllegalArgumentException e) {
            throw new IOException("Configuration file is malformed: " + e.getMessage());
        }
        return p;
    }


    /**
     * @return the format version of the properties: 1 for et-otp 1.x (no version property), else the number
     * @throws IOException for an invalid version, or a version 2 file whose version was removed
     */
    static int formatVersion(Properties p) throws IOException
    {
        String versionText = p.getProperty(PREFIX + "version");
        if (versionText == null) {
            // a v2 file with a removed version must not be decrypted with the unauthenticated legacy format
            for (String name : V2_ONLY) {
                if (p.getProperty(PREFIX + name) != null) {
                    throw new IOException("Configuration file is malformed (missing " + PREFIX + "version)");
                }
            }
            return 1;
        }
        try {
            return Integer.parseInt(versionText.trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid configuration format version: " + versionText);
        }
    }


    /**
     * Reads and decrypts the secret. The password array is not modified.
     *
     * @throws javax.crypto.BadPaddingException if the password is wrong (or the file was modified)
     * @throws IOException if the file is missing, incomplete or uses an unsupported version
     */
    static Result read(File file, char[] pass) throws IOException, GeneralSecurityException
    {
        byte[] source = Files.readAllBytes(file.toPath());
        Properties p = load(source);
        int version = formatVersion(p);
        if (version == 1) {
            return new Result(readLegacy(p, pass), 1, source);
        }
        if (version != CURRENT_VERSION) {
            throw new IOException("Unsupported configuration format version " + version
                                  + " (this program supports up to " + CURRENT_VERSION + ")");
        }
        PasswordKey key = PasswordKey.from(p, pass);
        try {
            return new Result(decrypt(p, key, version), version, source);
        } finally {
            key.destroy();
        }
    }


    /**
     * Re-writes an et-otp 1.x file in the current format.
     * <p>
     * The legacy format cannot detect every wrong password (AES/ECB padding is accepted by chance
     * about once in 256 tries), so the original file is first copied to {@code <file>.v1.bak}.
     * An existing backup is never overwritten: if it holds different content, the next free name
     * {@code <file>.v1.bak.1}, {@code .2}, ... is used.
     *
     * @return the backup file
     * @throws IOException if the file was changed since it was read
     */
    static File migrate(File file, Result legacy, char[] pass) throws IOException, GeneralSecurityException
    {
        File backup = backup(file, legacy.source, ".v1.bak");
        Properties upgraded = encrypt(legacy.secret(), pass, DEFAULT_ITERATIONS);
        // checked after the slow key derivation: narrows (but cannot close) the window for
        // overwriting a configuration that another instance saved meanwhile
        if (!Arrays.equals(legacy.source, Files.readAllBytes(file.toPath()))) {
            throw new IOException("Configuration file was changed while it was read; not upgraded");
        }
        storeAtomically(upgraded, file);
        return backup;
    }


    /**
     * Copies the content to {@code <file><suffix>}, or to the next free name with .1, .2, ... if that
     * exists with different content. An existing backup with the same content is reused.
     */
    static File backup(File file, byte[] content, String suffix) throws IOException
    {
        String base = file.getPath() + suffix;
        for (int i = 0; i < 1000; i++) {
            File backup = new File(i == 0 ? base : base + "." + i);
            if (backup.exists()) {
                if (Arrays.equals(content, Files.readAllBytes(backup.toPath()))) {
                    return backup;
                }
                continue;
            }
            try {
                Files.write(backup.toPath(), content, StandardOpenOption.CREATE_NEW);
                return backup;
            } catch (FileAlreadyExistsException e) {
                // created concurrently, try the next name
            }
        }
        throw new IOException("Cannot create a backup of " + file + ": too many backups exist");
    }


    private static byte[] readLegacy(Properties p, char[] pass) throws IOException, GeneralSecurityException
    {
        byte[] salt = decode(p, "salt");
        byte[] enc = decode(p, "encoded");
        check(salt.length > 0, "salt", "empty");
        check(enc.length > 0 && enc.length % 16 == 0, "encoded", "not a multiple of the AES block size");
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


    private static byte[] aad(String domain, int version, String kdf, int iterations, String cipher)
    {
        return (domain + "|" + version + "|" + kdf + "|" + iterations + "|" + cipher).getBytes(StandardCharsets.US_ASCII);
    }


    private static String required(Properties p, String prefix, String name) throws IOException
    {
        String v = p.getProperty(prefix + name);
        if (v == null || v.trim().isEmpty()) {
            throw new IOException("File is incomplete (missing " + prefix + name + ")");
        }
        return v;
    }


    private static byte[] decode(Properties p, String name) throws IOException
    {
        return decode(p, PREFIX, name);
    }


    private static byte[] decode(Properties p, String prefix, String name) throws IOException
    {
        try {
            return Base32.decode(required(p, prefix, name).trim());
        } catch (DecodingException e) {
            throw new IOException("Invalid " + prefix + name + ": " + e.getMessage());
        }
    }


    private static void check(boolean valid, String name, String problem) throws IOException
    {
        check(valid, PREFIX, name, problem);
    }


    /** Reports a malformed value as a file error before it reaches the crypto API. */
    private static void check(boolean valid, String prefix, String name, String problem) throws IOException
    {
        if (!valid) {
            throw new IOException("Invalid " + prefix + name + ": " + problem);
        }
    }


    private static void storeAtomically(Properties p, File file) throws IOException
    {
        AtomicFiles.store(p, file, "et-otp-v2 soft token configuration");
    }
}
