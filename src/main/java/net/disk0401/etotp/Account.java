/*
 * Account.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import net.disk0401.etotp.Base32.DecodingException;


/**
 * One registered service: a name and the TOTP parameters (RFC 6238).
 */
final class Account
{
    static final String SHA1 = "SHA1";
    static final String SHA256 = "SHA256";
    static final String SHA512 = "SHA512";
    static final int DEFAULT_DIGITS = 6;
    static final int DEFAULT_PERIOD = 30;

    /** Problem with the input; {@link #getMessage()} is the message key, {@link #detail} an optional value. */
    static final class InvalidException extends Exception
    {
        private static final long serialVersionUID = 1L;
        final String detail;

        InvalidException(String messageKey)
        {
            this(messageKey, null);
        }

        InvalidException(String messageKey, String detail)
        {
            super(messageKey);
            this.detail = detail;
        }
    }

    private String name;
    /** Base32 without separators, upper case. */
    private final String secret;
    private final String algorithm;
    private final int digits;
    private final int period;


    Account(String name, String secret) throws InvalidException
    {
        this(name, secret, SHA1, DEFAULT_DIGITS, DEFAULT_PERIOD);
    }


    Account(String name, String secret, String algorithm, int digits, int period) throws InvalidException
    {
        String normalized = secret == null ? "" : secret.replaceAll("[\\s\\-=]", "").toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new InvalidException("error.secret.empty");
        }
        byte[] key;
        try {
            key = Base32.decode(normalized);
        } catch (DecodingException | IllegalArgumentException e) {
            throw new InvalidException("error.secret.invalid");
        }
        boolean empty = key.length == 0;
        Arrays.fill(key, (byte) 0);
        if (empty) {
            throw new InvalidException("error.secret.empty");
        }
        if (!SHA1.equals(algorithm) && !SHA256.equals(algorithm) && !SHA512.equals(algorithm)) {
            throw new InvalidException("error.otpauth.unsupported", algorithm);
        }
        if (digits < 6 || digits > 8) {
            throw new InvalidException("error.otpauth.unsupported", digits + " digits");
        }
        if (period < 1 || period > 3600) {
            throw new InvalidException("error.otpauth.unsupported", period + " s");
        }
        this.secret = normalized;
        this.algorithm = algorithm;
        this.digits = digits;
        this.period = period;
        setName(name);
    }


    String name()
    {
        return name;
    }

    /** Sets the display name; null or blank gives an empty name (the user interface shows a placeholder). */
    void setName(String name)
    {
        this.name = name == null ? "" : name.trim();
    }

    String secret()
    {
        return secret;
    }

    String algorithm()
    {
        return algorithm;
    }

    int digits()
    {
        return digits;
    }

    int period()
    {
        return period;
    }


    /** @return the code for the given time (seconds since 1970) */
    String code(long epochSeconds)
    {
        return totp(Math.floorDiv(epochSeconds, period));
    }


    /** @return the seconds until the code of the given time changes (1 to period) */
    int secondsLeft(long epochSeconds)
    {
        return (int) (period - Math.floorMod(epochSeconds, period));
    }


    /** HOTP (RFC 4226) with the configured HMAC, as used by TOTP (RFC 6238). */
    String totp(long counter)
    {
        byte[] key;
        try {
            key = Base32.decode(secret);
        } catch (DecodingException e) {
            throw new IllegalStateException(e);
        }
        try {
            Mac mac = Mac.getInstance("Hmac" + algorithm);
            mac.init(new SecretKeySpec(key, "RAW"));
            byte[] hash = mac.doFinal(new byte[] {
                (byte) (counter >>> 56), (byte) (counter >>> 48), (byte) (counter >>> 40), (byte) (counter >>> 32),
                (byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter});
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                         | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, digits);
            // ASCII digits in every locale (String.format with the default locale may use other digits)
            return String.format(Locale.ROOT, "%0" + digits + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }


    /**
     * Reads the contents of a QR code for an authenticator app, for example
     * {@code otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example}.
     *
     * @throws InvalidException with a message key for the user
     */
    static Account fromUri(String text) throws InvalidException
    {
        URI uri;
        try {
            uri = new URI(text.trim());
        } catch (URISyntaxException e) {
            throw new InvalidException("error.otpauth.notOtpauth");
        }
        if (!"otpauth".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidException("error.otpauth.notOtpauth");
        }
        if (!"totp".equalsIgnoreCase(uri.getHost())) {
            throw new InvalidException("error.otpauth.notTotp");
        }
        Map<String, String> q = new HashMap<>();
        if (uri.getRawQuery() != null) {
            for (String kv : uri.getRawQuery().split("&")) {
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i);
                String v = i < 0 ? "" : kv.substring(i + 1);
                q.put(URLDecoder.decode(k, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
                      URLDecoder.decode(v, StandardCharsets.UTF_8).trim());
            }
        }
        String algorithm = q.getOrDefault("algorithm", SHA1).toUpperCase(Locale.ROOT).replace("-", "");
        int digits;
        int period;
        try {
            digits = Integer.parseInt(q.getOrDefault("digits", Integer.toString(DEFAULT_DIGITS)));
            period = Integer.parseInt(q.getOrDefault("period", Integer.toString(DEFAULT_PERIOD)));
        } catch (NumberFormatException e) {
            throw new InvalidException("error.otpauth.unsupported", q.get("digits") + " / " + q.get("period"));
        }
        String secret = q.getOrDefault("secret", "");
        if (secret.isEmpty()) {
            throw new InvalidException("error.otpauth.noSecret");
        }
        return new Account(nameFrom(uri.getPath(), q.getOrDefault("issuer", "")), secret, algorithm, digits, period);
    }


    /** Combines the label ("Issuer:account" or "account") and the issuer parameter into a display name. */
    static String nameFrom(String path, String issuer)
    {
        String label = path == null ? "" : path.replaceFirst("^/", "").trim();
        String account = label;
        String labelIssuer = "";
        int colon = label.indexOf(':');
        if (colon >= 0) {
            labelIssuer = label.substring(0, colon).trim();
            account = label.substring(colon + 1).trim();
        }
        String service = issuer.isEmpty() ? labelIssuer : issuer;
        if (service.isEmpty()) {
            return account;
        }
        if (account.isEmpty() || account.equals(service)) {
            return service;
        }
        return service + " (" + account + ")";
    }
}
