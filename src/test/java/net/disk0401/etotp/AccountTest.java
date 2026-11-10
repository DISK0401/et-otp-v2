/*
 * AccountTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;


class AccountTest
{
    private static final String SHA1_KEY = b32("12345678901234567890");
    private static final String SHA256_KEY = b32("12345678901234567890123456789012");
    private static final String SHA512_KEY = b32("1234567890123456789012345678901234567890123456789012345678901234");

    /** Test vectors of RFC 6238, appendix B (8 digits, 30 seconds). */
    @Test
    void rfc6238TestVectors() throws Exception
    {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        String[] sha1 = {"94287082", "07081804", "14050471", "89005924", "69279037", "65353130"};
        String[] sha256 = {"46119246", "68084774", "67062674", "91819424", "90698825", "77737706"};
        String[] sha512 = {"90693936", "25091201", "99943326", "93441116", "38618901", "47863826"};
        Account a1 = new Account("a", SHA1_KEY, Account.SHA1, 8, 30);
        Account a256 = new Account("a", SHA256_KEY, Account.SHA256, 8, 30);
        Account a512 = new Account("a", SHA512_KEY, Account.SHA512, 8, 30);
        for (int i = 0; i < times.length; i++) {
            assertEquals(sha1[i], a1.code(times[i]), "SHA1 " + times[i]);
            assertEquals(sha256[i], a256.code(times[i]), "SHA256 " + times[i]);
            assertEquals(sha512[i], a512.code(times[i]), "SHA512 " + times[i]);
        }
    }

    @Test
    void sixDigitsMatchTheOriginalImplementation() throws Exception
    {
        Account a = new Account("a", SHA1_KEY);
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertEquals(RFC4226.generateOTP(secret, 1111111109L / 30, 6, false, -1), a.code(1111111109L));
    }

    @Test
    void codesUseAsciiDigitsInEveryLocale() throws Exception
    {
        java.util.Locale saved = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertEquals("94287082", new Account("a", SHA1_KEY, Account.SHA1, 8, 30).code(59L));
        } finally {
            java.util.Locale.setDefault(saved);
        }
    }

    @Test
    void secondsLeft() throws Exception
    {
        Account a = new Account("a", SHA1_KEY);

        assertEquals(30, a.secondsLeft(60));
        assertEquals(1, a.secondsLeft(89));
    }

    @Test
    void parsesAnOtpauthUri() throws Exception
    {
        Account a = Account.fromUri("otpauth://totp/Example:alice@example.com?secret=jbsw%20y3dp-ehpk3pxp&issuer=Example");

        assertEquals("Example (alice@example.com)", a.name());
        assertEquals("JBSWY3DPEHPK3PXP", a.secret());
        assertEquals(Account.SHA1, a.algorithm());
        assertEquals(6, a.digits());
        assertEquals(30, a.period());
    }

    @Test
    void parsesParameters() throws Exception
    {
        Account a = Account.fromUri("otpauth://totp/ACME%20Co:john?secret=" + SHA256_KEY
                                    + "&algorithm=SHA256&digits=8&period=60");

        assertEquals("ACME Co (john)", a.name());
        assertEquals(Account.SHA256, a.algorithm());
        assertEquals(8, a.digits());
        assertEquals(60, a.period());
    }

    @Test
    void namesFromLabelAndIssuer()
    {
        assertEquals("GitHub (me)", Account.nameFrom("/me", "GitHub"));
        assertEquals("GitHub (me)", Account.nameFrom("/GitHub:me", ""));
        assertEquals("GitHub", Account.nameFrom("/GitHub", "GitHub"));
        assertEquals("me", Account.nameFrom("/me", ""));
        assertEquals("", Account.nameFrom("", ""));
    }

    @Test
    void rejectsUnsupportedContents()
    {
        assertEquals("error.otpauth.notOtpauth",
                     assertThrows(Account.InvalidException.class, () -> Account.fromUri("https://example.com")).getMessage());
        assertEquals("error.otpauth.notTotp",
                     assertThrows(Account.InvalidException.class,
                                  () -> Account.fromUri("otpauth://hotp/x?secret=JBSWY3DP&counter=1")).getMessage());
        assertEquals("error.otpauth.noSecret",
                     assertThrows(Account.InvalidException.class, () -> Account.fromUri("otpauth://totp/x")).getMessage());
        assertEquals("error.otpauth.unsupported",
                     assertThrows(Account.InvalidException.class,
                                  () -> Account.fromUri("otpauth://totp/x?secret=JBSWY3DP&algorithm=MD5")).getMessage());
        assertEquals("error.otpauth.unsupported",
                     assertThrows(Account.InvalidException.class,
                                  () -> Account.fromUri("otpauth://totp/x?secret=JBSWY3DP&digits=4")).getMessage());
        assertEquals("error.secret.invalid",
                     assertThrows(Account.InvalidException.class, () -> new Account("x", "JBSWY3DP1!")).getMessage());
    }

    private static String b32(String ascii)
    {
        return Base32.encode(ascii.getBytes(StandardCharsets.US_ASCII));
    }
}
