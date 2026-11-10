/*
 * MessagesTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

import org.junit.jupiter.api.Test;


class MessagesTest
{
    @Test
    void automaticFollowsTheSystemLanguage()
    {
        assertEquals(Locale.JAPANESE, Messages.localeFor(Messages.AUTO, Locale.JAPAN));
        assertEquals(Locale.ENGLISH, Messages.localeFor(Messages.AUTO, Locale.GERMANY));
        assertEquals(Locale.ENGLISH, Messages.localeFor(Messages.AUTO, Locale.US));
        assertEquals(Locale.ENGLISH, Messages.localeFor("unknown", Locale.FRANCE));
    }

    @Test
    void explicitLanguageWinsOverTheSystem()
    {
        assertEquals(Locale.ENGLISH, Messages.localeFor(Messages.ENGLISH, Locale.JAPAN));
        assertEquals(Locale.JAPANESE, Messages.localeFor(Messages.JAPANESE, Locale.US));
    }

    @Test
    void englishIsUsedOnAJapaneseSystem()
    {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.JAPAN);
            assertEquals("Quit", new Messages(Locale.ENGLISH).get("menu.quit"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void japaneseTextsAreReadAsUtf8()
    {
        assertEquals("終了", new Messages(Locale.JAPANESE).get("menu.quit"));
        assertEquals("終了", new Messages(Locale.JAPAN).get("menu.quit"));
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws Exception
    {
        Properties en = load("messages.properties");
        Properties ja = load("messages_ja.properties");
        assertEquals(en.stringPropertyNames(), ja.stringPropertyNames());
        assertFalse(en.isEmpty());
    }

    @Test
    void patternsWithPlaceholdersAreValidAndTranslated() throws Exception
    {
        Properties en = load("messages.properties");
        Properties ja = load("messages_ja.properties");
        for (String key : en.stringPropertyNames()) {
            String enText = en.getProperty(key);
            String jaText = ja.getProperty(key);
            if (enText.contains("{0}")) {
                assertTrue(jaText.contains("{0}"), key);
                // a single quote would swallow the placeholder
                assertTrue(new MessageFormat(enText).format(new Object[] { "X" }).contains("X"), key);
                assertTrue(new MessageFormat(jaText).format(new Object[] { "X" }).contains("X"), key);
            }
            if (!key.startsWith("language.")) {
                assertNotEquals(enText, jaText, "not translated: " + key);
            }
        }
    }

    private static Properties load(String name) throws Exception
    {
        Properties p = new Properties();
        try (InputStream is = Messages.class.getResourceAsStream(name);
             Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }
}
