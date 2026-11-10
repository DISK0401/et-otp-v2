/*
 * SecretStoreTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.Properties;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


/**
 * Tests for the encrypted configuration file, including et-otp 1.x compatibility.
 */
class SecretStoreTest
{
    private static final byte[] SECRET = "12345678901234567890".getBytes();
    /** Low iteration count to keep the tests fast. */
    private static final int TEST_ITERATIONS = 1000;

    @TempDir
    File tmp;

    private File file;

    @BeforeEach
    void setUp()
    {
        file = new File(tmp, ".et-otp.properties");
    }

    @Test
    void currentFormatRoundTrip() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);

        SecretStore.Result r = SecretStore.read(file, "pass".toCharArray());

        assertArrayEquals(SECRET, r.secret());
        assertFalse(r.isLegacy());
        Properties p = load(file);
        assertEquals("2", p.getProperty("key.1.version"));
        assertEquals("AES/GCM/NoPadding", p.getProperty("key.1.cipher"));
        assertEquals("PBKDF2WithHmacSHA256", p.getProperty("key.1.kdf"));
    }

    @Test
    void defaultIterationsMeetCurrentRecommendation() throws Exception
    {
        assertTrue(SecretStore.DEFAULT_ITERATIONS >= 600000);
    }

    @Test
    void currentFormatWrongPasswordIsRejected() throws Exception
    {
            SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
    

        assertThrows(BadPaddingException.class, () -> SecretStore.read(file, "wrong".toCharArray()));
    }

    @Test
    void tamperedParametersAreRejected() throws Exception
    {
            SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
            Properties p = load(file);
            p.setProperty("key.1.iterations", "1001");
            store(p, file);
    

        assertThrows(GeneralSecurityException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void saltAndIvAreRandomPerWrite() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties first = load(file);
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties second = load(file);

        assertFalse(first.getProperty("key.1.salt").equals(second.getProperty("key.1.salt")));
        assertFalse(first.getProperty("key.1.iv").equals(second.getProperty("key.1.iv")));
    }

    @Test
    void legacyFileIsReadable() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());

        SecretStore.Result r = SecretStore.read(file, "pass".toCharArray());

        assertArrayEquals(SECRET, r.secret());
        assertTrue(r.isLegacy());
    }

    @Test
    void legacyFileCanBeMigrated() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        SecretStore.Result legacy = SecretStore.read(file, "pass".toCharArray());

        SecretStore.write(file, legacy.secret(), "pass".toCharArray(), TEST_ITERATIONS);

        SecretStore.Result migrated = SecretStore.read(file, "pass".toCharArray());
        assertArrayEquals(SECRET, migrated.secret());
        assertFalse(migrated.isLegacy());
        assertEquals("2", load(file).getProperty("key.1.version"));
    }

    @Test
    void migrateKeepsBackupOfLegacyFile() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        byte[] original = java.nio.file.Files.readAllBytes(file.toPath());
        SecretStore.Result legacy = SecretStore.read(file, "pass".toCharArray());

        File backup = SecretStore.migrate(file, legacy, "pass".toCharArray());

        assertArrayEquals(original, java.nio.file.Files.readAllBytes(backup.toPath()));
        assertTrue(SecretStore.read(backup, "pass".toCharArray()).isLegacy());
        assertFalse(SecretStore.read(file, "pass".toCharArray()).isLegacy());
        assertArrayEquals(SECRET, SecretStore.read(file, "pass".toCharArray()).secret());
    }

    @Test
    void migrateNeverOverwritesExistingBackup() throws Exception
    {
        File backup = new File(file.getPath() + ".v1.bak");
        writeLegacy(backup, SECRET, "first".toCharArray());
        byte[] firstBackup = java.nio.file.Files.readAllBytes(backup.toPath());
        writeLegacy(file, SECRET, "pass".toCharArray());

        SecretStore.migrate(file, SecretStore.read(file, "pass".toCharArray()), "pass".toCharArray());

        assertArrayEquals(firstBackup, java.nio.file.Files.readAllBytes(backup.toPath()));
    }

    @Test
    void migrateKeepsOriginalWhenOtherBackupExists() throws Exception
    {
        File backup = new File(file.getPath() + ".v1.bak");
        writeLegacy(backup, SECRET, "first".toCharArray());
        writeLegacy(file, SECRET, "pass".toCharArray());
        byte[] original = java.nio.file.Files.readAllBytes(file.toPath());

        File used = SecretStore.migrate(file, SecretStore.read(file, "pass".toCharArray()), "pass".toCharArray());

        assertEquals(file.getName() + ".v1.bak.1", used.getName());
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(used.toPath()));
    }

    @Test
    void migrateReusesIdenticalBackup() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        java.nio.file.Files.copy(file.toPath(), new File(file.getPath() + ".v1.bak").toPath());

        File used = SecretStore.migrate(file, SecretStore.read(file, "pass".toCharArray()), "pass".toCharArray());

        assertEquals(file.getName() + ".v1.bak", used.getName());
        assertEquals(2, tmp.list().length);
    }

    @Test
    void migrateAbortsWhenFileChangedAfterRead() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        SecretStore.Result legacy = SecretStore.read(file, "pass".toCharArray());
        SecretStore.write(file, SECRET, "other".toCharArray(), TEST_ITERATIONS);
        byte[] saved = java.nio.file.Files.readAllBytes(file.toPath());

        assertThrows(IOException.class, () -> SecretStore.migrate(file, legacy, "pass".toCharArray()));
        assertArrayEquals(saved, java.nio.file.Files.readAllBytes(file.toPath()));
    }

    @Test
    void currentFormatWithoutVersionIsNotReadAsLegacy() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.remove("key.1.version");
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void excessiveIterationsAreRejectedBeforeKeyDerivation() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.iterations", Integer.toString(Integer.MAX_VALUE));
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void malformedSaltIsReportedAsConfigurationError() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.salt", "A");
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void iterationLimitIsInclusive() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.iterations", Integer.toString(SecretStore.MAX_ITERATIONS + 1));
        store(p, file);

        IOException e = assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
        assertTrue(e.getMessage().contains(Integer.toString(SecretStore.MAX_ITERATIONS + 1)));
    }

    @Test
    void wrongIvLengthIsReportedAsConfigurationError() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.iv", Base32.encode(new byte[16]));
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void truncatedCiphertextIsReportedAsConfigurationError() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.encoded", Base32.encode(new byte[16]));
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void legacyCiphertextMustBeWholeBlocks() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        Properties p = load(file);
        p.setProperty("key.1.encoded", Base32.encode(new byte[17]));
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void malformedUnicodeEscapeIsReportedAsConfigurationError() throws Exception
    {
        java.nio.file.Files.write(file.toPath(), "key.1.salt=\\uZZZZ\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void malformedLegacySaltIsReportedAsConfigurationError() throws Exception
    {
        writeLegacy(file, SECRET, "pass".toCharArray());
        Properties p = load(file);
        p.setProperty("key.1.salt", "A");
        store(p, file);

        assertThrows(IOException.class, () -> SecretStore.read(file, "pass".toCharArray()));
    }

    @Test
    void writeLeavesNoTemporaryFiles() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);

        assertEquals(1, tmp.list().length);
    }

    @Test
    void unsupportedVersionIsRejected() throws Exception
    {
        SecretStore.write(file, SECRET, "pass".toCharArray(), TEST_ITERATIONS);
        Properties p = load(file);
        p.setProperty("key.1.version", "99");
        store(p, file);

        try {
            SecretStore.read(file, "pass".toCharArray());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("99"));
        }
    }

    @Test
    void incompleteFileIsRejected() throws Exception
    {
        Properties p = new Properties();
        p.setProperty("key.1.name", "test");
        store(p, file);

        try {
            SecretStore.read(file, "pass".toCharArray());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    // --- helpers ---

    /** Writes a file exactly the way et-otp 1.x (ecki/et-otp) did. */
    private static void writeLegacy(File f, byte[] secret, char[] pass) throws Exception
    {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        SecretKeyFactory kf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] key = kf.generateSecret(new PBEKeySpec(pass, salt, 1000, 128)).getEncoded();
        Cipher c = Cipher.getInstance("AES");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] enc = c.doFinal(secret);

        Properties p = new Properties();
        p.put("key.1.name", "test");
        p.put("key.1.salt", Base32.encode(salt));
        p.put("key.1.encoded", Base32.encode(enc));
        store(p, f);
    }

    private static Properties load(File f) throws IOException
    {
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(f)) {
            p.load(is);
        }
        return p;
    }

    private static void store(Properties p, File f) throws IOException
    {
        try (OutputStream os = new FileOutputStream(f)) {
            p.store(os, "test");
        }
    }
}
