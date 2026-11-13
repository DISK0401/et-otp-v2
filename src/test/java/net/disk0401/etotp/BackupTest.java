/*
 * BackupTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import javax.crypto.BadPaddingException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;


/** et-otp-v2 issue #18: encrypted backup of the registered services. */
class BackupTest
{
    private static final int TEST_ITERATIONS = 1000;
    private static final char[] PASS = "backup パスワード".toCharArray();

    @TempDir
    File tmp;

    private static List<Account> accounts() throws Exception
    {
        return Arrays.asList(new Account("GitHub (me)", "JBSWY3DPEHPK3PXP"),
                             new Account("日本語のサービス", "GEZDGNBVGY3TQOJQ", Account.SHA256, 8, 60),
                             new Account("", "MFRGGZDFMZTWQ2LK", Account.SHA512, 6, 30));
    }

    private static void assertSame(List<Account> expected, List<Account> actual)
    {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            Account e = expected.get(i);
            Account a = actual.get(i);
            assertEquals(e.name(), a.name());
            assertEquals(e.secret(), a.secret());
            assertEquals(e.algorithm(), a.algorithm());
            assertEquals(e.digits(), a.digits());
            assertEquals(e.period(), a.period());
        }
    }

    @Test
    void roundTrip() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);

        assertSame(accounts(), Backup.read(f, PASS));
        String text = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("JBSWY3DPEHPK3PXP"), "the keys are encrypted");
        assertFalse(text.contains("GitHub"), "the names are encrypted");
        Properties p = new Properties();
        p.load(new java.io.StringReader(text));
        assertEquals("1", p.getProperty("backup.version"));
    }

    @Test
    void emptyListRoundTrip() throws Exception
    {
        File f = new File(tmp, "empty" + Backup.EXTENSION);
        Backup.write(f, List.of(), PASS, TEST_ITERATIONS);

        assertTrue(Backup.read(f, PASS).isEmpty());
    }

    @Test
    void wrongPasswordIsDetected() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);

        assertThrows(BadPaddingException.class, () -> Backup.read(f, "wrong".toCharArray()));
    }

    @Test
    void modifiedFileIsDetected() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);
        Properties p = new Properties();
        try (var in = Files.newInputStream(f.toPath())) {
            p.load(in);
        }
        // a lower iteration count is authenticated: the backup cannot be weakened unnoticed
        p.setProperty("backup.iterations", "999");
        try (var out = Files.newOutputStream(f.toPath())) {
            p.store(out, null);
        }
        assertThrows(BadPaddingException.class, () -> Backup.read(f, PASS));

        Files.writeString(f.toPath(), "backup.version=1\nbackup.encoded=ABC!\n");
        assertThrows(IOException.class, () -> Backup.read(f, PASS));
    }

    @Test
    void unsupportedVersionIsRejected() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);
        String text = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
        Files.writeString(f.toPath(), text.replace("backup.version=1", "backup.version=2"), StandardCharsets.ISO_8859_1);

        IOException e = assertThrows(IOException.class, () -> Backup.read(f, PASS));
        assertTrue(e.getMessage().contains("version 2"), e.getMessage());
    }

    @Test
    void otherFilesAreNoBackups() throws Exception
    {
        File text = new File(tmp, "notes.txt");
        Files.writeString(text.toPath(), "some = text\n");
        assertThrows(IOException.class, () -> Backup.read(text, PASS));

        File config = new File(tmp, "c.properties");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create(PASS, TEST_ITERATIONS);
        AccountStore.save(config, accounts(), AccountStore.Protection.PASSWORD, key, null);
        IOException e = assertThrows(IOException.class, () -> Backup.read(config, PASS));
        assertTrue(e.getMessage().contains("configuration file"), e.getMessage());
    }

    /** The file is checked before its password is asked. */
    @Test
    void wrongFilesAreFoundWithoutThePassword() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);
        Backup.check(f);

        File config = new File(tmp, "c.properties");
        AccountStore.save(config, accounts(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create(PASS, TEST_ITERATIONS), null);
        assertThrows(IOException.class, () -> Backup.check(config));
        assertThrows(IOException.class, () -> Backup.check(new File(tmp, "missing" + Backup.EXTENSION)));
        String text = Files.readString(f.toPath(), StandardCharsets.ISO_8859_1);
        Files.writeString(f.toPath(), text.replace("backup.version=1", "backup.version=2"), StandardCharsets.ISO_8859_1);
        assertThrows(IOException.class, () -> Backup.check(f));
    }

    @Test
    void backupIsNoConfigurationFile() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);

        assertThrows(IOException.class, () -> AccountStore.inspect(f));
    }

    @Test
    void configurationFileIsNotReplaced() throws Exception
    {
        File config = new File(tmp, "c.properties");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create(PASS, TEST_ITERATIONS);
        AccountStore.save(config, accounts(), AccountStore.Protection.PASSWORD, key, null);
        byte[] before = Files.readAllBytes(config.toPath());

        assertThrows(IOException.class, () -> Backup.write(config, accounts(), PASS, TEST_ITERATIONS));
        assertArrayEquals(before, Files.readAllBytes(config.toPath()));
    }

    @Test
    void anExistingBackupIsReplaced() throws Exception
    {
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, accounts(), PASS, TEST_ITERATIONS);
        Backup.write(f, accounts().subList(0, 1), PASS, TEST_ITERATIONS);

        assertSame(accounts().subList(0, 1), Backup.read(f, PASS));
    }

    /** A backup made with protection for the Windows user can be restored without DPAPI (on any computer). */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsUserProtectionIsNotNeededToRestore() throws Exception
    {
        File config = new File(tmp, "c.properties");
        AccountStore.save(config, accounts(), AccountStore.Protection.WINDOWS_USER, null, null);
        AccountStore.Opened o = AccountStore.openForWindowsUser(config);
        File f = new File(tmp, "b" + Backup.EXTENSION);
        Backup.write(f, o.accounts, PASS, TEST_ITERATIONS);

        assertFalse(Files.readString(f.toPath(), StandardCharsets.ISO_8859_1).contains("windows-user"));
        assertSame(accounts(), Backup.read(f, PASS));
    }

    @Test
    void addingSkipsRegisteredKeys() throws Exception
    {
        List<Account> registered = List.of(new Account("Mine", "JBSWY3DPEHPK3PXP"));
        List<Account> backup = new ArrayList<>(accounts());
        backup.add(new Account("Twice", "GEZDGNBVGY3TQOJQ"));

        List<Account> added = Backup.newServices(registered, backup);

        assertEquals(2, added.size());
        assertEquals("日本語のサービス", added.get(0).name());
        assertEquals("", added.get(1).name());
        assertTrue(Backup.newServices(backup, backup).isEmpty());
    }

    @Test
    void fingerprintFollowsAddedAndDeletedServices() throws Exception
    {
        List<Account> list = new ArrayList<>(accounts());
        String mark = Backup.fingerprint(list);

        list.get(0).setName("renamed");
        AccountList.move(list, 2, 0);
        assertEquals(mark, Backup.fingerprint(list), "renaming and moving keep the mark");

        list.remove(0);
        assertNotEquals(mark, Backup.fingerprint(list));
        list.add(new Account("x", "MFRGGZDFMZTWQ2LK", Account.SHA512, 6, 30));
        assertEquals(mark, Backup.fingerprint(list));
        list.add(new Account("y", "MFRGGZDFMZTWQ2LK", Account.SHA512, 6, 30));
        assertNotEquals(mark, Backup.fingerprint(list), "a second registration of a key is a change");
    }

    @Test
    void backupMarkIsStoredEncrypted() throws Exception
    {
        File config = new File(tmp, "c.properties");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create(PASS, TEST_ITERATIONS);
        String mark = Backup.fingerprint(accounts());
        AccountStore.save(config, accounts(), mark, AccountStore.Protection.PASSWORD, key, null);

        assertFalse(Files.readString(config.toPath(), StandardCharsets.ISO_8859_1).contains(mark));
        AccountStore.Opened o = AccountStore.openWithPassword(config, PASS);
        assertEquals(mark, o.backupMark);
        assertSame(accounts(), o.accounts);

        AccountStore.save(config, o.accounts, o.protection, o.key, o.source);
        assertNull(AccountStore.openWithPassword(config, PASS).backupMark);
    }

    @Test
    void passwordKeyChecksThePassword() throws Exception
    {
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create(PASS, TEST_ITERATIONS);

        assertTrue(key.matches(PASS));
        assertFalse(key.matches("other".toCharArray()));
        assertEquals("backup パスワード", new String(PASS), "the password is not cleared");
    }
}
