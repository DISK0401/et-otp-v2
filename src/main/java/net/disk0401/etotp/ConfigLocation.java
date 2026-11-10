/*
 * ConfigLocation.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;


/**
 * Decides where the configuration file is stored.
 * <ol>
 * <li>The path given with {@code -Detotp.config=...}.</li>
 * <li>{@code .et-otp.properties} in the working directory, if it exists (et-otp 1.x behaviour).</li>
 * <li>{@code .et-otp.properties} in the user's home directory.</li>
 * </ol>
 * The home directory default lets the packaged application (double-clicked, with an
 * unwritable or unknown working directory) save its settings.
 */
final class ConfigLocation
{
    static final String FILE_NAME = ".et-otp.properties";
    static final String PROPERTY = "etotp.config";


    private ConfigLocation() { }


    static File resolve()
    {
        return resolve(System.getProperty(PROPERTY), new File(System.getProperty("user.dir")),
                       new File(System.getProperty("user.home")));
    }


    static File resolve(String explicitPath, File workingDir, File homeDir)
    {
        if (explicitPath != null && !explicitPath.trim().isEmpty()) {
            return new File(explicitPath.trim());
        }
        File local = new File(workingDir, FILE_NAME);
        if (local.isFile()) {
            return local;
        }
        return new File(homeDir, FILE_NAME);
    }
}
