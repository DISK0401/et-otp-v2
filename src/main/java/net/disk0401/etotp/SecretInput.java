/*
 * SecretInput.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.util.Arrays;

import net.disk0401.etotp.Base32.DecodingException;


/**
 * Checks the secret and password entered in the setup wizard and the settings dialog.
 * The methods return the message key of the problem, or null if the input is valid.
 */
final class SecretInput
{
    private SecretInput() { }


    static String checkSecret(String code)
    {
        if (code == null || code.trim().isEmpty()) {
            return "error.secret.empty";
        }
        try {
            byte[] bytes = Base32.decode(code.trim());
            boolean empty = bytes.length == 0;
            Arrays.fill(bytes, (byte) 0);
            return empty ? "error.secret.empty" : null;
        } catch (DecodingException | IllegalArgumentException e) {
            return "error.secret.invalid";
        }
    }


    static String checkPassword(char[] pass, char[] confirm)
    {
        if (pass.length == 0) {
            return "error.password.empty";
        }
        if (!Arrays.equals(pass, confirm)) {
            return "error.password.mismatch";
        }
        return null;
    }
}
