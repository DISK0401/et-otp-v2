/*
 * BuildInfoTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;


class BuildInfoTest
{
    @Test
    void mavenFillsInTheBuildInformation()
    {
        BuildInfo info = BuildInfo.load();

        assertNotNull(info.version());
        assertNotNull(info.os());
        assertNotNull(info.jdk());
        assertNotNull(info.time());
    }

    @Test
    void unresolvedPlaceholdersAreUnknown()
    {
        Properties p = new Properties();
        p.setProperty("commit", "${git.commit.id.abbrev}");
        p.setProperty("branch", " ");
        p.setProperty("dirty", "${git.dirty}");
        p.setProperty("version", "2.1.0");

        BuildInfo info = new BuildInfo(p);

        assertNull(info.commit());
        assertNull(info.branch());
        assertNull(info.describe());
        assertFalse(info.dirty());
        assertEquals("2.1.0", info.version());
    }

    @Test
    void nonAsciiValuesAreReadAsUtf8()
    {
        byte[] file = "branch=feature/日本語\n".getBytes(StandardCharsets.UTF_8);

        assertEquals("feature/日本語", BuildInfo.read(new ByteArrayInputStream(file)).branch());
    }

    @Test
    void missingResourceGivesUnknownValues()
    {
        assertNull(BuildInfo.read(null).commit());
    }

    @Test
    void dirtyFlag()
    {
        Properties p = new Properties();
        p.setProperty("commit", "1a2b3c4");
        p.setProperty("dirty", "true");

        assertTrue(new BuildInfo(p).dirty());
    }
}
