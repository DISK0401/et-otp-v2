/*
 * UiSettings.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Properties;


/**
 * User interface settings (language, window position), stored in {@code .et-otp-ui.properties}
 * next to the configuration file. They are kept apart from the encrypted secret, so saving the
 * window position on exit never re-writes the secret.
 */
final class UiSettings
{
    static final String FILE_NAME = ".et-otp-ui.properties";
    /** Used if the configuration file itself is named {@link #FILE_NAME} (with -Detotp.config). */
    static final String ALTERNATIVE_FILE_NAME = ".et-otp-ui-settings.properties";

    private static final String LANGUAGE = "language";
    private static final String WINDOW_X = "window.x";
    private static final String WINDOW_Y = "window.y";

    private final File file;
    private final Properties props;


    private UiSettings(File file, Properties props)
    {
        this.file = file;
        this.props = props;
    }


    /** @return the settings file in the directory of the configuration file, never the configuration file itself */
    static File locationFor(File configFile)
    {
        File config = configFile.getAbsoluteFile();
        // ignoring case also covers case-insensitive file systems (Windows, macOS)
        String name = FILE_NAME.equalsIgnoreCase(config.getName()) ? ALTERNATIVE_FILE_NAME : FILE_NAME;
        return new File(config.getParentFile(), name);
    }


    /** Loads the settings; a missing or unreadable file gives the defaults. */
    static UiSettings load(File file)
    {
        Properties p = new Properties();
        if (file.isFile()) {
            try (InputStream is = Files.newInputStream(file.toPath())) {
                p.load(is);
            } catch (IOException | IllegalArgumentException e) {
                p.clear();
            }
        }
        return new UiSettings(file, p);
    }


    /** @return the same settings, to be saved in another file (after the configuration file moved) */
    UiSettings copyTo(File other)
    {
        Properties p = new Properties();
        p.putAll(props);
        return new UiSettings(other, p);
    }


    File file()
    {
        return file;
    }


    /** @return {@link Messages#AUTO}, {@link Messages#ENGLISH} or {@link Messages#JAPANESE} */
    String language()
    {
        String v = props.getProperty(LANGUAGE, Messages.AUTO).trim();
        return Messages.ENGLISH.equals(v) || Messages.JAPANESE.equals(v) ? v : Messages.AUTO;
    }


    void setLanguage(String language)
    {
        props.setProperty(LANGUAGE, language);
    }


    /** @return the saved top left corner of the main window, or null if none (or invalid) is saved */
    Point windowLocation()
    {
        try {
            String x = props.getProperty(WINDOW_X);
            String y = props.getProperty(WINDOW_Y);
            if (x == null || y == null) {
                return null;
            }
            return new Point(Integer.parseInt(x.trim()), Integer.parseInt(y.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }


    void setWindowLocation(Point p)
    {
        props.setProperty(WINDOW_X, Integer.toString(p.x));
        props.setProperty(WINDOW_Y, Integer.toString(p.y));
    }


    void save() throws IOException
    {
        AccountStore.checkNotAStore(file);
        AtomicFiles.store(props, file, "et-otp-v2 user interface settings");
    }
}
