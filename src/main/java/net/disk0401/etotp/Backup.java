/*
 * Backup.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;


/**
 * Writes and reads a backup of the registered services (et-otp-v2 issue #18).
 * <p>
 * A backup is encrypted with its own password, whatever protects the configuration file, so it can also be
 * restored on another computer or by another user (a file protected for the Windows user cannot).
 * <p>
 * Format version 1 is a properties file with the properties {@code backup.version}, {@code backup.kdf},
 * {@code backup.iterations}, {@code backup.cipher}, {@code backup.salt}, {@code backup.iv} and
 * {@code backup.encoded}: like the configuration file, PBKDF2-HMAC-SHA256 derives a 256 bit key from the
 * password, and the account list (the plain text of the configuration file) is encrypted with AES-256-GCM.
 * The authenticated data starts with another domain than the configuration file's, and the properties have
 * another prefix, so a backup cannot be opened as a configuration file or the other way round.
 */
final class Backup
{
    static final int VERSION = 1;
    /** suggested file name extension. */
    static final String EXTENSION = ".etotp-backup";

    private static final String PREFIX = "backup.";
    private static final String DOMAIN = "et-otp-backup";
    private static final String ENCODED = PREFIX + "encoded";
    private static final String CONFIG_ENCODED = "key.1.encoded";


    private Backup() { }


    /**
     * Encrypts the services with the password and writes them (atomically) to the file.
     * The password array is not modified.
     *
     * @throws IOException also if the file is a configuration file (it is not replaced)
     */
    static void write(File file, List<Account> accounts, char[] pass, int iterations)
            throws IOException, GeneralSecurityException
    {
        AccountStore.checkNotAStore(file);
        byte[] plain = AccountStore.serialize(accounts);
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create(pass, iterations);
        try {
            Properties p = new Properties();
            SecretStore.encrypt(p, PREFIX, DOMAIN, plain, key, VERSION);
            AtomicFiles.store(p, file, "et-otp-v2 backup of the registered services (encrypted)");
        } finally {
            key.destroy();
            Arrays.fill(plain, (byte) 0);
        }
    }


    /**
     * Reads and decrypts a backup. The password array is not modified.
     *
     * @throws javax.crypto.BadPaddingException if the password is wrong or the file was modified
     * @throws IOException if the file is no backup, incomplete or of an unsupported version
     */
    static List<Account> read(File file, char[] pass) throws IOException, GeneralSecurityException
    {
        Properties p = new Properties();
        try (java.io.InputStream is = Files.newInputStream(file.toPath())) {
            p.load(is);
        } catch (IllegalArgumentException e) {
            throw new IOException("Not an et-OTP-v2 backup: " + file);
        }
        if (p.getProperty(ENCODED) == null) {
            throw new IOException(p.getProperty(CONFIG_ENCODED) != null
                                  ? "This is a configuration file, not a backup: " + file
                                  : "Not an et-OTP-v2 backup: " + file);
        }
        String versionText = p.getProperty(PREFIX + "version", "").trim();
        int version;
        try {
            version = Integer.parseInt(versionText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid backup format version: " + versionText);
        }
        if (version != VERSION) {
            throw new IOException("Unsupported backup format version " + version
                                  + " (this program supports up to " + VERSION + ")");
        }
        SecretStore.PasswordKey key = SecretStore.PasswordKey.from(p, PREFIX, pass);
        byte[] plain = null;
        try {
            plain = SecretStore.decrypt(p, PREFIX, DOMAIN, key, VERSION);
            return AccountStore.parse(plain);
        } finally {
            key.destroy();
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }


    /**
     * @return the services of the backup whose key is not registered yet, each key once, in the order of the backup
     */
    static List<Account> newServices(List<Account> registered, List<Account> backup)
    {
        Set<String> keys = new HashSet<>();
        for (Account a : registered) {
            keys.add(a.secret());
        }
        List<Account> result = new ArrayList<>();
        for (Account a : backup) {
            if (keys.add(a.secret())) {
                result.add(a);
            }
        }
        return result;
    }


    /**
     * Identifies the services for the reminder to make a backup: it changes when a service is added or deleted
     * (or its key, digits or period differ), not when one is renamed or moved.
     */
    static String fingerprint(List<Account> accounts)
    {
        List<String> lines = new ArrayList<>();
        for (Account a : accounts) {
            lines.add(a.secret() + "/" + a.algorithm() + "/" + a.digits() + "/" + a.period());
        }
        lines.sort(null);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                md.update(line.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return Base32.encode(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java platform has SHA-256
        }
    }
}
