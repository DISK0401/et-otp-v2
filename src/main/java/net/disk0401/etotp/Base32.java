/*
 * Base32.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.util.Locale;

/**
 * Base32 of RFC 4648 (letters A to Z, digits 2 to 7), as used by the keys of 2-step verification.
 * Written for et-otp-v2; it keeps the behavior of the class of the same name in et-otp:
 * <ul>
 * <li>{@link #encode} writes upper case letters without padding ({@code =}).</li>
 * <li>{@link #decode} ignores case and the separators {@code -}, space and {@code .}, reads {@code 8} as
 * {@code B} and {@code 1} as {@code I}, and ignores the bits after the last whole byte. Padding is not
 * accepted.</li>
 * </ul>
 */
final class Base32
{
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS = 5;


    private Base32() { }


    /** @return the bytes of the text; empty for an empty text (or one with separators only) */
    static byte[] decode(String text) throws DecodingException
    {
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '-' && c != ' ' && c != '.') {
                kept.append(c);
            }
        }
        // as in et-otp: also other letters whose upper case is A to Z
        String upper = kept.toString().toUpperCase(Locale.ENGLISH);
        int[] values = new int[upper.length()];
        int count = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            int v = value(c);
            if (v < 0) {
                throw new DecodingException("Illegal character: " + c);
            }
            values[count++] = v;
        }
        byte[] out = new byte[count * BITS / 8];
        int bits = 0;
        int pending = 0;
        int n = 0;
        for (int i = 0; i < count; i++) {
            pending = (pending << BITS | values[i]) & 0xFFF; // at most 12 bits are kept
            bits += BITS;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (pending >> bits);
            }
        }
        return out;
    }


    /** @return the value 0 to 31 of the character, or -1 */
    private static int value(char c)
    {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '2' && c <= '7') {
            return 26 + c - '2';
        }
        if (c == '8') {
            return 'B' - 'A';
        }
        if (c == '1') {
            return 'I' - 'A';
        }
        return -1;
    }


    /** @return the text of the bytes, without padding */
    static String encode(byte[] data)
    {
        StringBuilder out = new StringBuilder((data.length * 8 + BITS - 1) / BITS);
        int bits = 0;
        int pending = 0;
        for (byte b : data) {
            pending = (pending << 8 | (b & 0xFF)) & 0xFFF;
            bits += 8;
            while (bits >= BITS) {
                bits -= BITS;
                out.append(ALPHABET.charAt((pending >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((pending << (BITS - bits)) & 0x1F)); // filled with zero bits
        }
        return out.toString();
    }


    static final class DecodingException extends Exception
    {
        private static final long serialVersionUID = 1L;


        DecodingException(String message)
        {
            super(message);
        }
    }
}
