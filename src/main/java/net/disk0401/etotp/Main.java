/*
 * Main.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;


/**
 * The main class of the jar: starts the command line use or the window.
 * It refers to {@link GUI} only when the window is wanted, so the command line use does not load the classes of
 * the window and of Swing (issue #29).
 */
final class Main
{
    private Main() { }


    public static void main(String[] args)
    {
        if (CodeCommand.handles(args)) {
            System.exit(CodeCommand.main(args));
            return;
        }
        GUI.main(args);
    }
}
