/*
 * DesktopShortcut.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Creates a shortcut on the Windows desktop that starts this program with the same Java runtime.
 * The shortcut is written by PowerShell (WScript.Shell); the paths are passed in environment
 * variables, so they need no quoting in the script.
 */
final class DesktopShortcut
{
    static final String NAME = "et-OTP-v2";
    private static final String ICON_FILE_NAME = "et-otp-v2.ico";

    private static final String SCRIPT =
        "$ErrorActionPreference = 'Stop';"
        + "$desktop = [Environment]::GetFolderPath('Desktop');"
        + "$path = Join-Path $desktop ($env:ETOTP_NAME + '.lnk');"
        + "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($path);"
        // when updating: only a shortcut that exists and opens the old configuration file
        // (compared as full paths, ignoring case like Windows)
        + "if ($env:ETOTP_OLD_CONFIG) {"
        + " if (-not (Test-Path -LiteralPath $path)) { exit 0 };"
        // no double quote in the script: Windows removes them from the command line (\x22 is the quote)
        + " $m = [regex]::Match($s.Arguments, '-Detotp[.]config=([^\\x22]*)');"
        + " if (-not $m.Success) { exit 0 };"
        + " if (-not [string]::Equals([IO.Path]::GetFullPath($m.Groups[1].Value),"
        + " [IO.Path]::GetFullPath($env:ETOTP_OLD_CONFIG), [StringComparison]::OrdinalIgnoreCase)) { exit 0 } };"
        + "$s.TargetPath = $env:ETOTP_TARGET;"
        + "$s.Arguments = $env:ETOTP_ARGS;"
        + "$s.WorkingDirectory = $env:ETOTP_DIR;"
        + "$s.IconLocation = $env:ETOTP_ICON;"
        + "$s.Description = $env:ETOTP_NAME;"
        + "$s.Save();"
        + "Write-Output $path";


    private DesktopShortcut() { }


    /** @return true on Windows when running from a jar, so a shortcut can start it */
    static boolean isSupported()
    {
        return Dpapi.isAvailable() && jar() != null;
    }


    /** @return the jar this program runs from, or null (for example when started from class files) */
    static File jar()
    {
        try {
            File f = new File(DesktopShortcut.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() && f.getName().endsWith(".jar") ? f : null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }


    /**
     * Creates (or replaces) the shortcut.
     *
     * @param configFile the configuration file in use; the shortcut always opens this file, even if it was
     *        found in the working directory (the shortcut starts in the program's folder)
     * @return the path of the shortcut
     */
    static String create(File configFile) throws IOException
    {
        return write(configFile, null);
    }


    /**
     * Updates the shortcut after the configuration file was moved: only if it exists and opens the old file.
     *
     * @return the path of the updated shortcut, or null if there was none to update
     */
    static String update(File oldConfigFile, File newConfigFile) throws IOException
    {
        if (!isSupported()) {
            return null;
        }
        String path = write(newConfigFile, oldConfigFile.getAbsolutePath());
        return path.isEmpty() ? null : path;
    }


    /** @param oldConfig if not null: only update a shortcut that opens this file */
    private static String write(File configFile, String oldConfig) throws IOException
    {
        File jar = jar();
        if (!Dpapi.isAvailable() || jar == null) {
            throw new IOException("Shortcuts can only be created on Windows when running from the jar");
        }
        File javaw = new File(System.getProperty("java.home"), "bin" + File.separator + "javaw.exe");
        if (!javaw.isFile()) {
            throw new IOException("javaw.exe not found: " + javaw);
        }
        File icon = iconFile();
        java.nio.file.Files.createDirectories(icon.toPath().getParent());
        AppIcon.writeIco(icon.toPath());

        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                                               "-ExecutionPolicy", "Bypass", "-Command", SCRIPT);
        Map<String, String> env = pb.environment();
        env.put("ETOTP_NAME", NAME);
        env.put("ETOTP_TARGET", javaw.getAbsolutePath());
        env.put("ETOTP_ARGS", arguments(configFile, jar));
        if (oldConfig != null) {
            env.put("ETOTP_OLD_CONFIG", oldConfig);
        } else {
            env.remove("ETOTP_OLD_CONFIG");
        }
        env.put("ETOTP_DIR", jar.getAbsoluteFile().getParent());
        env.put("ETOTP_ICON", icon.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String output;
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("PowerShell did not finish");
            }
            output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (p.exitValue() != 0) {
            throw new IOException(output.isEmpty() ? "PowerShell failed (" + p.exitValue() + ")" : output);
        }
        return output;
    }


    /**
     * @return the icon file of the shortcut, in the program's folder in the local application data of the user,
     *         which is writable wherever the configuration file is
     */
    static File iconFile()
    {
        String local = System.getenv("LOCALAPPDATA");
        File base = local != null && !local.isEmpty() ? new File(local) : ConfigLocation.homeDir();
        return new File(new File(base, NAME), ICON_FILE_NAME);
    }


    private static String arguments(File configFile, File jar)
    {
        return configArgument(configFile) + " -jar " + quote(jar.getAbsolutePath());
    }


    private static String configArgument(File configFile)
    {
        return quote("-D" + ConfigLocation.PROPERTY + "=" + configFile.getAbsolutePath());
    }


    private static String quote(String s)
    {
        return "\"" + s + "\"";
    }
}
