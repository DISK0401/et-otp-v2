/*
 * ConfigLocationTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private File newFolder(String name)
    {
        File f = new File(tmp, name);
        if (!f.mkdir()) {
            throw new IllegalStateException("cannot create " + f);
        }
        return f;
    }
}
