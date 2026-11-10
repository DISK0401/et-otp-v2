/*
 * ConfigLocationTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class ConfigLocationTest
{
    @TempDir
    File tmp;

    @Test
    void explicitPathWins() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");
        new File(cwd, ConfigLocation.FILE_NAME).createNewFile();

        File f = ConfigLocation.resolve("custom/token.properties", cwd, home);

        assertEquals(new File("custom/token.properties"), f);
    }

    @Test
    void existingFileInWorkingDirectoryIsKept() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");
        new File(cwd, ConfigLocation.FILE_NAME).createNewFile();

        File f = ConfigLocation.resolve(null, cwd, home);

        assertEquals(new File(cwd, ConfigLocation.FILE_NAME), f);
    }

    @Test
    void defaultsToHomeDirectory() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");

        File f = ConfigLocation.resolve(null, cwd, home);

        assertEquals(new File(home, ConfigLocation.FILE_NAME), f);
    }

    @Test
    void blankExplicitPathIsIgnored() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");

        File f = ConfigLocation.resolve("  ", cwd, home);

        assertEquals(new File(home, ConfigLocation.FILE_NAME), f);
    }

    @Test
    void rememberedLocationWinsOverTheWorkingDirectory() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");
        new File(cwd, ConfigLocation.FILE_NAME).createNewFile();
        File chosen = new File(newFolder("chosen"), "my token.properties");
        ConfigLocation.remember(ConfigLocation.pointerFile(home), chosen);

        assertEquals(chosen.getAbsoluteFile(), ConfigLocation.resolve(null, cwd, home));
        // an explicit path still wins
        assertEquals(new File("x.properties"), ConfigLocation.resolve("x.properties", cwd, home));
    }

    @Test
    void emptyRememberedLocationIsIgnored() throws Exception
    {
        File cwd = newFolder("cwd");
        File home = newFolder("home");
        java.nio.file.Files.writeString(ConfigLocation.pointerFile(home).toPath(), "config=  \n");

        assertNull(ConfigLocation.chosen(ConfigLocation.pointerFile(home)));
        assertEquals(new File(home, ConfigLocation.FILE_NAME), ConfigLocation.resolve(null, cwd, home));
    }

    @Test
    void chosenLocationsAreChecked() throws Exception
    {
        File home = newFolder("home");
        File dir = newFolder("dir");

        assertNull(ConfigLocation.problem(new File(dir, "token.properties"), home));
        assertNull(ConfigLocation.problem(new File(home, ConfigLocation.FILE_NAME), home));
        assertEquals("location.isDirectory", ConfigLocation.problem(dir, home));
        assertEquals("location.noFolder", ConfigLocation.problem(new File(dir, "missing/token.properties"), home));
        assertEquals("location.reserved", ConfigLocation.problem(ConfigLocation.pointerFile(home), home));
        assertEquals("location.reserved", ConfigLocation.problem(new File(dir, UiSettings.FILE_NAME), home));
        assertEquals("location.reserved", ConfigLocation.problem(new File(dir, "token.properties.lock"), home));
        assertEquals("location.reserved", ConfigLocation.problem(new File(dir, "token.properties.v1.BAK"), home));
    }

    private File newFolder(String name)
    {
        File f = new File(tmp, name);
        if (!f.mkdir()) {
            throw new IllegalStateException("cannot create " + f);
        }
        return f;
    }
}
