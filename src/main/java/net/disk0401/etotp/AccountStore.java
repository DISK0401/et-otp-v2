/*
 * AccountStore.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;


/**
 * Reads and writes the list of accounts in the configuration file.
 * <p>
 * Format version 3 holds any number of accounts, protected either with a password
 * (PBKDF2-HMAC-SHA256 and AES-256-GCM, as format 2) or, on Windows, for the Windows user (DPAPI,
 * no password). Files of format 1 (et-otp 1.x) and 2 (et-otp-v2 2.0) hold one secret; they are
 * read with their password and re-written in format 3, keeping the original as a backup.
 * et-otp-v2 2.0 rejects format 3 files instead of misreading them.
 */
final class AccountStore
{
    static final int VERSION = 3;

    /** How the account list is protected. */
    enum Protection
    {
        /** encrypted with a key derived from a password entered at start. */
        PASSWORD("password"),
        /** encrypted for the Windows user with DPAPI; no password. */
        WINDOWS_USER("windows-user");

        final String id;

        Protection(String id)
        {
            this.id = id;
        }

        static Protection of(String id) throws IOException
        {
            for (Protection p : values()) {
                if (p.id.equals(id)) {
                    return p;
                }
            }
            throw new IOException("Unsupported protection in configuration: " + id);
        }
    }

    /** What is needed to open a configuration file. */
    enum Kind
    {
        /** no file yet. */
        MISSING,
        /** format 3 with a password. */
        PASSWORD,
        /** format 3 protected for the Windows user. */
        WINDOWS_USER,
        /** format 1 or 2 (one secret, with a password); converted to format 3 when opened. */
        LEGACY
    }

    private static final String PREFIX = "key.1.";
    private static final String PROTECTION = PREFIX + "protection";
    private static final String ENCODED = PREFIX + "encoded";
    /** Additional entropy for DPAPI, so other programs using DPAPI for the same user cannot simply decrypt. */
    private static final byte[] DPAPI_ENTROPY = "et-otp-v2/accounts/3".getBytes(StandardCharsets.UTF_8);

    /** An opened account list: the accounts and what is needed to save them again. */
    static final class Opened
    {
        final List<Account> accounts;
        final Protection protection;
        /** key for {@link Protection#PASSWORD}, else null; the receiver destroys it. */
        final SecretStore.PasswordKey key;
        /** backup of a converted format 1 or 2 file, else null. */
        final File backup;
        /** the file contents as opened, to detect a change by another program before saving. */
        final byte[] source;
        /**
         * why the list could not be written in format 3 (for example a read-only folder), else null.
         * The accounts can be shown, but not changed.
         */
        final String readOnlyReason;

        Opened(List<Account> accounts, Protection protection, SecretStore.PasswordKey key, File backup,
               byte[] source, String readOnlyReason)
        {
            this.accounts = accounts;
            this.protection = protection;
            this.key = key;
            this.backup = backup;
            this.source = source;
            this.readOnlyReason = readOnlyReason;
        }
    }


    private AccountStore() { }


    /**
     * Refuses to replace a configuration file with something else (the user interface settings or the location
     * pointer have fixed names, which a configuration file chosen with -Detotp.config may also have).
     *
     * @throws IOException if the file holds registered services
     */
    static void checkNotAStore(File file) throws IOException
    {
        if (!file.isFile()) {
            return;
        }
        Properties p = new Properties();
        try (java.io.InputStream is = Files.newInputStream(file.toPath())) {
            p.load(is);
        } catch (IllegalArgumentException e) {
            return; // not a properties file, so no configuration file either
        }
        if (p.getProperty(ENCODED) != null) {
            throw new IOException("This file holds registered services and is not replaced: " + file);
        }
    }


    static Kind inspect(File file) throws IOException
    {
        if (!file.exists()) {
            return Kind.MISSING;
        }
        Properties p = SecretStore.load(Files.readAllBytes(file.toPath()));
        if (p.getProperty(ENCODED) == null) {
            // every format stores its encrypted data here; without it this is some other file
            throw new IOException("Not an et-OTP configuration file (missing " + ENCODED + "): " + file);
        }
        int version = SecretStore.formatVersion(p);
        if (version == 1 || version == SecretStore.CURRENT_VERSION) {
            return Kind.LEGACY;
        }
        if (version != VERSION) {
            throw new IOException("Unsupported configuration format version " + version
                                  + " (this program supports up to " + VERSION + ")");
        }
        return Protection.of(p.getProperty(PROTECTION)) == Protection.WINDOWS_USER ? Kind.WINDOWS_USER : Kind.PASSWORD;
    }


    /** Opens a file protected for the Windows user. */
    static Opened openForWindowsUser(File file) throws IOException
    {
        byte[] source = Files.readAllBytes(file.toPath());
        Properties p = SecretStore.load(source);
        checkVersion(p, Protection.WINDOWS_USER);
        byte[] plain = Dpapi.unprotect(base32(p.getProperty(ENCODED)), DPAPI_ENTROPY);
        try {
            return new Opened(parse(plain), Protection.WINDOWS_USER, null, null, source, null);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }


    /**
     * Opens a password protected file, or converts a format 1 or 2 file to format 3 (with the same password).
     * The password array is not modified.
     *
     * @throws javax.crypto.BadPaddingException if the password is wrong
     */
    static Opened openWithPassword(File file, char[] pass) throws IOException, GeneralSecurityException
    {
        byte[] source = Files.readAllBytes(file.toPath());
        Properties p = SecretStore.load(source);
        int version = SecretStore.formatVersion(p);
        if (version != VERSION) {
            return convert(file, pass);
        }
        checkVersion(p, Protection.PASSWORD);
        SecretStore.PasswordKey key = SecretStore.PasswordKey.from(p, pass);
        byte[] plain = null;
        try {
            plain = SecretStore.decrypt(p, key, VERSION);
            Opened o = new Opened(parse(plain), Protection.PASSWORD, key, null, source, null);
            key = null; // handed over
            return o;
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
            if (key != null) {
                key.destroy();
            }
        }
    }


    /**
     * Reads a format 1 or 2 file and re-writes it in format 3, keeping a backup of the original.
     * If it cannot be re-written, the account is opened read-only, so its codes can still be shown.
     */
    private static Opened convert(File file, char[] pass) throws IOException, GeneralSecurityException
    {
        SecretStore.Result old = SecretStore.read(file, pass);
        byte[] secret = old.secret();
        List<Account> accounts = new ArrayList<>();
        try {
            try {
                accounts.add(new Account("", Base32.encode(secret)));
            } catch (Account.InvalidException e) {
                throw new IOException("The stored secret is not valid: " + e.getMessage());
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
        SecretStore.PasswordKey key = null;
        try {
            File backup = SecretStore.backup(file, old.source(), old.isLegacy() ? ".v1.bak" : ".v2.bak");
            key = SecretStore.PasswordKey.create(pass, SecretStore.DEFAULT_ITERATIONS);
            // save() checks that the file still holds what was read (also after the slow key derivation)
            byte[] written = save(file, accounts, Protection.PASSWORD, key, old.source());
            Opened o = new Opened(accounts, Protection.PASSWORD, key, backup, written, null);
            key = null; // handed over
            return o;
        } catch (IOException e) {
            return new Opened(accounts, Protection.PASSWORD, null, null, old.source(), e.getMessage());
        } finally {
            if (key != null) {
                key.destroy();
            }
        }
    }


    /**
     * Writes the accounts (atomically).
     *
     * @param key the password key for {@link Protection#PASSWORD}, ignored otherwise
     * @param expected the file contents as last read or written, or null for a new file: if the file
     *        holds something else, another program changed it, and nothing is written
     * @return the new file contents, the expected contents of the next save
     * @throws IOException also if the file was changed by another program
     */
    static byte[] save(File file, List<Account> accounts, Protection protection, SecretStore.PasswordKey key,
                       byte[] expected)
            throws IOException, GeneralSecurityException
    {
        byte[] plain = serialize(accounts);
        try {
            Properties p = new Properties();
            p.setProperty(PREFIX + "name", "accounts");
            p.setProperty(PROTECTION, protection.id);
            if (protection == Protection.PASSWORD) {
                SecretStore.encrypt(p, plain, key, VERSION);
            } else {
                p.setProperty(PREFIX + "version", Integer.toString(VERSION));
                p.setProperty(ENCODED, Base32.encode(Dpapi.protect(plain, DPAPI_ENTROPY)));
            }
            byte[] current = file.exists() ? Files.readAllBytes(file.toPath()) : null;
            if (!Arrays.equals(expected, current)) {
                throw new IOException("The configuration file was changed by another program; restart et-OTP-v2 to see the changes");
            }
            AtomicFiles.store(p, file, "et-otp-v2 accounts");
            return Files.readAllBytes(file.toPath());
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }


    private static void checkVersion(Properties p, Protection expected) throws IOException
    {
        int version = SecretStore.formatVersion(p);
        if (version != VERSION || Protection.of(p.getProperty(PROTECTION)) != expected) {
            throw new IOException("Configuration file changed while it was opened");
        }
    }


    private static byte[] base32(String value) throws IOException
    {
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Configuration file is incomplete (missing " + ENCODED + ")");
        }
        try {
            return Base32.decode(value.trim());
        } catch (Base32.DecodingException e) {
            throw new IOException("Invalid " + ENCODED + " in configuration: " + e.getMessage());
        }
    }


    /** The plain account list: a properties text in UTF-8. */
    static byte[] serialize(List<Account> accounts) throws IOException
    {
        Properties p = new Properties();
        p.setProperty("count", Integer.toString(accounts.size()));
        for (int i = 0; i < accounts.size(); i++) {
            Account a = accounts.get(i);
            String k = "account." + (i + 1) + ".";
            p.setProperty(k + "name", a.name());
            p.setProperty(k + "secret", a.secret());
            p.setProperty(k + "algorithm", a.algorithm());
            p.setProperty(k + "digits", Integer.toString(a.digits()));
            p.setProperty(k + "period", Integer.toString(a.period()));
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(buf, StandardCharsets.UTF_8)) {
            p.store(w, null);
        }
        return buf.toByteArray();
    }


    static List<Account> parse(byte[] plain) throws IOException
    {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(new ByteArrayInputStream(plain), StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IllegalArgumentException e) {
            throw new IOException("Account list is malformed: " + e.getMessage());
        }
        List<Account> list = new ArrayList<>();
        try {
            int count = Integer.parseInt(p.getProperty("count", "0").trim());
            for (int i = 1; i <= count; i++) {
                String k = "account." + i + ".";
                list.add(new Account(p.getProperty(k + "name"), p.getProperty(k + "secret"),
                                     p.getProperty(k + "algorithm", Account.SHA1),
                                     Integer.parseInt(p.getProperty(k + "digits", "6").trim()),
                                     Integer.parseInt(p.getProperty(k + "period", "30").trim())));
            }
        } catch (NumberFormatException | Account.InvalidException e) {
            throw new IOException("Account list is malformed: " + e.getMessage());
        }
        return list;
    }
}
