/*
 * ConfigLocation.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;


/**
 * Decides where the configuration file is stored.
 * <ol>
 * <li>The path given with {@code -Detotp.config=...}.</li>
 * <li>The path chosen in the setup wizard or the settings, remembered in
 *     {@code .et-otp-location.properties} in the user's home directory.</li>
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
    /** remembers the chosen location, in the home directory. */
    static final String POINTER_FILE_NAME = ".et-otp-location.properties";

    private static final String POINTER_KEY = "config";


    private ConfigLocation() { }


    static File resolve()
    {
        return resolve(System.getProperty(PROPERTY), new File(System.getProperty("user.dir")), homeDir());
    }


    static File resolve(String explicitPath, File workingDir, File homeDir)
    {
        if (explicitPath != null && !explicitPath.trim().isEmpty()) {
            return new File(explicitPath.trim());
        }
        File chosen = chosen(pointerFile(homeDir));
        if (chosen != null) {
            return chosen;
        }
        File local = new File(workingDir, FILE_NAME);
        if (local.isFile()) {
            return local;
        }
        return defaultFile(homeDir);
    }


    static File homeDir()
    {
        return new File(System.getProperty("user.home"));
    }


    static File defaultFile(File homeDir)
    {
        return new File(homeDir, FILE_NAME);
    }


    static File pointerFile(File homeDir)
    {
        return new File(homeDir, POINTER_FILE_NAME);
    }


    /** @return the remembered location, or null if none (or an unreadable one) is remembered */
    static File chosen(File pointerFile)
    {
        if (!pointerFile.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream is = Files.newInputStream(pointerFile.toPath())) {
            p.load(is);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        // not trimmed: a file name may end with a space
        String path = p.getProperty(POINTER_KEY, "");
        return path.trim().isEmpty() ? null : new File(path);
    }


    /** Remembers the location for the next start (when it is started without {@code -Detotp.config}). */
    static void remember(File pointerFile, File configFile) throws IOException
    {
        AccountStore.checkNotAStore(pointerFile);
        Properties p = new Properties();
        p.setProperty(POINTER_KEY, configFile.getAbsolutePath());
        AtomicFiles.store(p, pointerFile, "et-otp-v2: location of the configuration file");
    }


    /**
     * Checks a location chosen by the user, before anything is written there.
     *
     * @return null if the file can be used, else the message key of the problem
     */
    static String problem(File chosen, File homeDir)
    {
        File f = chosen.getAbsoluteFile();
        if (f.isDirectory()) {
            return "location.isDirectory";
        }
        File dir = f.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return "location.noFolder";
        }
        String name = f.getName();
        // files that the program itself writes next to the configuration file or in the home directory
        if (sameFile(f, pointerFile(homeDir)) || f.getPath().equalsIgnoreCase(pointerFile(homeDir).getAbsolutePath())
            || sameFile(f, DesktopShortcut.iconFile()) || f.getPath().equalsIgnoreCase(DesktopShortcut.iconFile().getAbsolutePath())
            || name.equalsIgnoreCase(UiSettings.FILE_NAME) || name.equalsIgnoreCase(UiSettings.ALTERNATIVE_FILE_NAME)
            || name.toLowerCase(java.util.Locale.ROOT).endsWith(".lock")
            || name.toLowerCase(java.util.Locale.ROOT).endsWith(".bak")) {
            return "location.reserved";
        }
        return null;
    }


    /** @return true if both name the same file (also through ".", ".." or links) */
    static boolean sameFile(File a, File b)
    {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }


    /** @return the file without ".", ".." and links, as far as they can be resolved */
    static File normalize(File f)
    {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            return f.getAbsoluteFile();
        }
    }
}
