/*
 * ConfigLocationTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.Assert.assertEquals;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;


public class ConfigLocationTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void explicitPathWins() throws Exception
    {
        File cwd = tmp.newFolder("cwd");
        File home = tmp.newFolder("home");
        new File(cwd, ConfigLocation.FILE_NAME).createNewFile();

        File f = ConfigLocation.resolve("custom/token.properties", cwd, home);

        assertEquals(new File("custom/token.properties"), f);
    }

    @Test
    public void existingFileInWorkingDirectoryIsKept() throws Exception
    {
        File cwd = tmp.newFolder("cwd");
        File home = tmp.newFolder("home");
        new File(cwd, ConfigLocation.FILE_NAME).createNewFile();

        File f = ConfigLocation.resolve(null, cwd, home);

        assertEquals(new File(cwd, ConfigLocation.FILE_NAME), f);
    }

    @Test
    public void defaultsToHomeDirectory() throws Exception
    {
        File cwd = tmp.newFolder("cwd");
        File home = tmp.newFolder("home");

        File f = ConfigLocation.resolve(null, cwd, home);

        assertEquals(new File(home, ConfigLocation.FILE_NAME), f);
    }

    @Test
    public void blankExplicitPathIsIgnored() throws Exception
    {
        File cwd = tmp.newFolder("cwd");
        File home = tmp.newFolder("home");

        File f = ConfigLocation.resolve("  ", cwd, home);

        assertEquals(new File(home, ConfigLocation.FILE_NAME), f);
    }
}
