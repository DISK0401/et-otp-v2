/*
 * UiSettingsTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Point;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class UiSettingsTest
{
    @TempDir
    File tmp;

    @Test
    void storedNextToTheConfigurationFile()
    {
        File config = new File(tmp, "token.properties");

        assertEquals(new File(tmp, UiSettings.FILE_NAME), UiSettings.locationFor(config));
    }

    @Test
    void neverTheConfigurationFileItself()
    {
        File config = new File(tmp, UiSettings.FILE_NAME);
        File upperCase = new File(tmp, UiSettings.FILE_NAME.toUpperCase());

        assertEquals(new File(tmp, UiSettings.ALTERNATIVE_FILE_NAME), UiSettings.locationFor(config));
        assertEquals(new File(tmp, UiSettings.ALTERNATIVE_FILE_NAME), UiSettings.locationFor(upperCase));
    }

    @Test
    void missingFileGivesDefaults()
    {
        UiSettings s = UiSettings.load(new File(tmp, UiSettings.FILE_NAME));

        assertEquals(Messages.AUTO, s.language());
        assertNull(s.windowLocation());
    }

    @Test
    void valuesSurviveSaveAndLoad() throws Exception
    {
        File f = new File(tmp, UiSettings.FILE_NAME);
        UiSettings s = UiSettings.load(f);
        s.setLanguage(Messages.JAPANESE);
        s.setWindowLocation(new Point(-1200, 340));
        s.save();

        UiSettings loaded = UiSettings.load(f);

        assertEquals(Messages.JAPANESE, loaded.language());
        assertEquals(new Point(-1200, 340), loaded.windowLocation());
        assertEquals(1, tmp.list().length);
    }

    @Test
    void copyKeepsTheValuesInTheNewFile() throws Exception
    {
        UiSettings s = UiSettings.load(new File(tmp, UiSettings.FILE_NAME));
        s.setLanguage(Messages.ENGLISH);
        File other = new File(tmp, "other.properties");

        UiSettings copy = s.copyTo(other);
        copy.save();

        assertEquals(other, copy.file());
        assertEquals(Messages.ENGLISH, UiSettings.load(other).language());
    }

    @Test
    void invalidValuesAreIgnored() throws Exception
    {
        File f = new File(tmp, UiSettings.FILE_NAME);
        Files.write(f.toPath(), "language=fr\nwindow.x=abc\nwindow.y=10\n".getBytes(StandardCharsets.ISO_8859_1));

        UiSettings s = UiSettings.load(f);

        assertEquals(Messages.AUTO, s.language());
        assertNull(s.windowLocation());
    }
}
