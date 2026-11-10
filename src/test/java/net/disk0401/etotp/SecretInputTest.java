/*
 * SecretInputTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;


class SecretInputTest
{
    @Test
    void validSecretsAreAccepted()
    {
        assertNull(SecretInput.checkSecret("JBSWY3DPEHPK3PXP"));
        assertNull(SecretInput.checkSecret("jbsw y3dp-ehpk 3pxp"));
        assertNull(SecretInput.checkSecret("  JBSWY3DPEHPK3PXP  "));
    }

    @Test
    void emptySecretIsRejected()
    {
        assertEquals("error.secret.empty", SecretInput.checkSecret(null));
        assertEquals("error.secret.empty", SecretInput.checkSecret(""));
        assertEquals("error.secret.empty", SecretInput.checkSecret("   "));
        assertEquals("error.secret.empty", SecretInput.checkSecret("A"));
    }

    @Test
    void invalidCharactersAreRejected()
    {
        assertEquals("error.secret.invalid", SecretInput.checkSecret("JBSWY3DP0"));
        assertEquals("error.secret.invalid", SecretInput.checkSecret("JBSWY3DP="));
    }

    @Test
    void passwordMustBeEnteredTwice()
    {
        assertNull(SecretInput.checkPassword("pw".toCharArray(), "pw".toCharArray()));
        assertEquals("error.password.empty", SecretInput.checkPassword(new char[0], new char[0]));
        assertEquals("error.password.mismatch", SecretInput.checkPassword("pw".toCharArray(), "pW".toCharArray()));
    }
}
