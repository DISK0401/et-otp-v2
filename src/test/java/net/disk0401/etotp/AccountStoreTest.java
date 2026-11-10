/*
 * AccountStoreTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import javax.crypto.BadPaddingException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;


class AccountStoreTest
{
    private static final byte[] SECRET = "12345678901234567890".getBytes();
    private static final int TEST_ITERATIONS = 1000;

    @TempDir
    File tmp;

    private List<Account> accounts() throws Exception
    {
        return Arrays.asList(new Account("GitHub (me)", "JBSWY3DPEHPK3PXP"),
                             new Account("日本語のサービス", "GEZDGNBVGY3TQOJQ", Account.SHA256, 8, 60));
    }

    @Test
    void missingFile() throws Exception
    {
        assertEquals(AccountStore.Kind.MISSING, AccountStore.inspect(new File(tmp, "none.properties")));
    }

    @Test
    void passwordRoundTrip() throws Exception
    {
        File f = new File(tmp, "c.properties");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS);
        AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD, key, null);

        assertEquals(AccountStore.Kind.PASSWORD, AccountStore.inspect(f));
        AccountStore.Opened o = AccountStore.openWithPassword(f, "pw".toCharArray());

        assertEquals(2, o.accounts.size());
        assertEquals("日本語のサービス", o.accounts.get(1).name());
        assertEquals(Account.SHA256, o.accounts.get(1).algorithm());
        assertEquals(8, o.accounts.get(1).digits());
        assertEquals(60, o.accounts.get(1).period());
        assertEquals(AccountStore.Protection.PASSWORD, o.protection);
        assertNotNull(o.key);
    }

    @Test
    void wrongPasswordIsRejected() throws Exception
    {
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null);

        assertThrows(BadPaddingException.class, () -> AccountStore.openWithPassword(f, "wrong".toCharArray()));
    }

    @Test
    void savingAgainWithTheKeyOfTheOpenedFile() throws Exception
    {
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null);
        AccountStore.Opened o = AccountStore.openWithPassword(f, "pw".toCharArray());

        AccountStore.save(f, o.accounts.subList(0, 1), o.protection, o.key, o.source);

        assertEquals(1, AccountStore.openWithPassword(f, "pw".toCharArray()).accounts.size());
    }

    @Test
    void aChangeByAnotherProgramIsNotOverwritten() throws Exception
    {
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null);
        AccountStore.Opened a = AccountStore.openWithPassword(f, "pw".toCharArray());
        AccountStore.Opened b = AccountStore.openWithPassword(f, "pw".toCharArray());

        // instance A adds an account; instance B, opened before, must not save its old list over it
        List<Account> more = new java.util.ArrayList<>(a.accounts);
        more.add(new Account("third", "MFRGGZDFMZTWQ2LK"));
        AccountStore.save(f, more, a.protection, a.key, a.source);

        assertThrows(IOException.class, () -> AccountStore.save(f, b.accounts, b.protection, b.key, b.source));
        assertEquals(3, AccountStore.openWithPassword(f, "pw".toCharArray()).accounts.size());
    }

    @Test
    void newFileIsNotWrittenOverAnExistingOne() throws Exception
    {
        File f = new File(tmp, "c.properties");
        Files.write(f.toPath(), "something".getBytes());

        assertThrows(IOException.class, () -> AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD,
                                                                SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null));
        assertEquals("something", new String(Files.readAllBytes(f.toPath())));
    }

    @Test
    void version2FileThatCannotBeConvertedIsOpenedReadOnly() throws Exception
    {
        File f = new File(tmp, ".et-otp.properties");
        SecretStore.write(f, SECRET, "pw".toCharArray(), TEST_ITERATIONS);
        byte[] original = Files.readAllBytes(f.toPath());
        // a folder in place of the backup file makes the conversion fail
        new File(tmp, ".et-otp.properties.v2.bak").mkdir();

        AccountStore.Opened o = AccountStore.openWithPassword(f, "pw".toCharArray());

        assertNotNull(o.readOnlyReason);
        assertEquals(1, o.accounts.size());
        assertArrayEquals(SECRET, Base32.decode(o.accounts.get(0).secret()));
        assertArrayEquals(original, Files.readAllBytes(f.toPath()));
    }

    @Test
    void emptyListCanBeStored() throws Exception
    {
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, List.of(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null);

        assertTrue(AccountStore.openWithPassword(f, "pw".toCharArray()).accounts.isEmpty());
    }

    @Test
    void version2FileIsConvertedWithBackup() throws Exception
    {
        File f = new File(tmp, ".et-otp.properties");
        SecretStore.write(f, SECRET, "pw".toCharArray(), TEST_ITERATIONS);
        byte[] original = Files.readAllBytes(f.toPath());

        assertEquals(AccountStore.Kind.LEGACY, AccountStore.inspect(f));
        AccountStore.Opened o = AccountStore.openWithPassword(f, "pw".toCharArray());

        assertEquals(1, o.accounts.size());
        assertArrayEquals(SECRET, Base32.decode(o.accounts.get(0).secret()));
        assertEquals(".et-otp.properties.v2.bak", o.backup.getName());
        assertArrayEquals(original, Files.readAllBytes(o.backup.toPath()));
        assertEquals(AccountStore.Kind.PASSWORD, AccountStore.inspect(f));
        assertEquals(1, AccountStore.openWithPassword(f, "pw".toCharArray()).accounts.size());
    }

    @Test
    void version3IsRejectedByTheVersion2Reader() throws Exception
    {
        // et-otp-v2 2.0 must report an unsupported version instead of misreading the account list
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, accounts(), AccountStore.Protection.PASSWORD,
                          SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS), null);

        IOException e = assertThrows(IOException.class, () -> SecretStore.read(f, "pw".toCharArray()));
        assertTrue(e.getMessage().contains("version 3"));
    }

    @Test
    void wrongPasswordOnAVersion2FileKeepsTheFile() throws Exception
    {
        File f = new File(tmp, "c.properties");
        SecretStore.write(f, SECRET, "pw".toCharArray(), TEST_ITERATIONS);
        byte[] original = Files.readAllBytes(f.toPath());

        assertThrows(BadPaddingException.class, () -> AccountStore.openWithPassword(f, "wrong".toCharArray()));
        assertArrayEquals(original, Files.readAllBytes(f.toPath()));
        assertEquals(1, tmp.list().length);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsUserRoundTrip() throws Exception
    {
        File f = new File(tmp, "c.properties");
        AccountStore.save(f, accounts(), AccountStore.Protection.WINDOWS_USER, null, null);

        assertEquals(AccountStore.Kind.WINDOWS_USER, AccountStore.inspect(f));
        AccountStore.Opened o = AccountStore.openForWindowsUser(f);

        assertEquals(2, o.accounts.size());
        assertEquals("GitHub (me)", o.accounts.get(0).name());
        assertEquals(AccountStore.Protection.WINDOWS_USER, o.protection);
        // the file must not contain the secret in plain text
        String content = new String(Files.readAllBytes(f.toPath()));
        assertTrue(!content.contains("JBSWY3DPEHPK3PXP"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void dpapiRejectsModifiedData() throws Exception
    {
        byte[] enc = Dpapi.protect("secret".getBytes(), "e".getBytes());
        assertArrayEquals("secret".getBytes(), Dpapi.unprotect(enc, "e".getBytes()));

        assertThrows(IOException.class, () -> Dpapi.unprotect(enc, "other".getBytes()));
    }
}
