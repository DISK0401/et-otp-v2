/*
 * BuildInfo.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;


/**
 * Build information written by Maven into {@code build.properties} (and the jar manifest):
 * version, Git commit, branch and description, build time, and the OS and JDK used for the build.
 * Values that Maven could not fill in (for example a build outside a Git checkout) are null.
 */
final class BuildInfo
{
    private static final String RESOURCE = "build.properties";

    private final Properties props;


    BuildInfo(Properties props)
    {
        this.props = props;
    }


    /** Loads the information of the running program; missing information gives empty values. */
    static BuildInfo load()
    {
        return read(BuildInfo.class.getResourceAsStream(RESOURCE));
    }


    /** Reads build information from the stream (may be null) and closes it. */
    static BuildInfo read(InputStream is)
    {
        Properties p = new Properties();
        if (is != null) {
            // Maven writes the filtered file in the project encoding (UTF-8), e.g. a branch name in Japanese
            try (Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                p.clear();
            }
        }
        return new BuildInfo(p);
    }


    String version()
    {
        return value("version");
    }

    String commit()
    {
        return value("commit");
    }

    /** @return true if the working tree had uncommitted changes when the program was built */
    boolean dirty()
    {
        return "true".equals(value("dirty"));
    }

    String branch()
    {
        return value("branch");
    }

    /** @return the output of {@code git describe}, for example {@code v2.0.0-3-g1a2b3c4} */
    String describe()
    {
        return value("describe");
    }

    String time()
    {
        return value("time");
    }

    String os()
    {
        return value("os");
    }

    String jdk()
    {
        return value("jdk");
    }


    /** @return the value, or null if it is empty or a placeholder that Maven did not replace */
    private String value(String key)
    {
        String v = props.getProperty(key);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() || v.contains("${") ? null : v;
    }
}
