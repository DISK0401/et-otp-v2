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

    private static final String SCRIPT =
        "$ErrorActionPreference = 'Stop';"
        + "$desktop = [Environment]::GetFolderPath('Desktop');"
        + "$path = Join-Path $desktop ($env:ETOTP_NAME + '.lnk');"
        + "$s = (New-Object -ComObject WScript.Shell).CreateShortcut($path);"
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
     * @param iconDir a writable folder for the icon file
     * @return the path of the shortcut
     */
    static String create(File configFile, File iconDir) throws IOException
    {
        File jar = jar();
        if (!Dpapi.isAvailable() || jar == null) {
            throw new IOException("Shortcuts can only be created on Windows when running from the jar");
        }
        File javaw = new File(System.getProperty("java.home"), "bin" + File.separator + "javaw.exe");
        if (!javaw.isFile()) {
            throw new IOException("javaw.exe not found: " + javaw);
        }
        File icon = new File(iconDir, "et-otp-v2.ico");
        AppIcon.writeIco(icon.toPath());

        StringBuilder args = new StringBuilder();
        args.append(quote("-D" + ConfigLocation.PROPERTY + "=" + configFile.getAbsolutePath())).append(' ');
        args.append("-jar ").append(quote(jar.getAbsolutePath()));

        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                                               "-ExecutionPolicy", "Bypass", "-Command", SCRIPT);
        Map<String, String> env = pb.environment();
        env.put("ETOTP_NAME", NAME);
        env.put("ETOTP_TARGET", javaw.getAbsolutePath());
        env.put("ETOTP_ARGS", args.toString());
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


    private static String quote(String s)
    {
        return "\"" + s + "\"";
    }
}
