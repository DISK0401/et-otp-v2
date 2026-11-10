/*
 * Messages.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;


/**
 * User interface texts in English (default) or Japanese.
 */
final class Messages
{
    /** Language setting: follow the operating system. */
    static final String AUTO = "auto";
    static final String ENGLISH = "en";
    static final String JAPANESE = "ja";

    private static final String BUNDLE = "net.disk0401.etotp.messages";

    private final Locale locale;
    private final ResourceBundle bundle;


    Messages(Locale locale)
    {
        this.locale = locale;
        // without the no-fallback control, English on a Japanese system would load the Japanese bundle
        this.bundle = ResourceBundle.getBundle(BUNDLE, locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }


    /**
     * Decides the user interface language.
     *
     * @param setting {@link #AUTO}, {@link #ENGLISH} or {@link #JAPANESE}; other values mean {@link #AUTO}
     * @param system the display locale of the operating system
     * @return Japanese if selected, or if automatic and the system language is Japanese; else English
     */
    static Locale localeFor(String setting, Locale system)
    {
        if (ENGLISH.equals(setting)) {
            return Locale.ENGLISH;
        }
        if (JAPANESE.equals(setting)) {
            return Locale.JAPANESE;
        }
        return JAPANESE.equals(system.getLanguage()) ? Locale.JAPANESE : Locale.ENGLISH;
    }


    Locale locale()
    {
        return locale;
    }


    /** Returns the text as it is (no placeholders are replaced). */
    String get(String key)
    {
        return bundle.getString(key);
    }


    /** Returns the text with the {0}, {1}, ... placeholders replaced (MessageFormat syntax). */
    String format(String key, Object... args)
    {
        return new MessageFormat(get(key), locale).format(args);
    }
}
