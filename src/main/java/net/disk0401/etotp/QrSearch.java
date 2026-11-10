/*
 * QrSearch.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

import net.disk0401.etotp.qr.QrReader;


/**
 * Searches the screens and, if nothing is found there, the application windows for QR codes.
 * Runs outside the event dispatch thread.
 */
final class QrSearch
{
    /** Time limit for capturing one display or window. */
    private static final long CAPTURE_MILLIS = 3000;


    private QrSearch() { }


    /** @return true if the text is the contents of a QR code for an authenticator app */
    static boolean isOtpauth(String text)
    {
        return text.trim().toLowerCase(Locale.ROOT).startsWith("otpauth://");
    }


    /**
     * Reads the QR codes on all displays; if none is for an authenticator app, also reads the windows.
     *
     * @param progress called with (done, total) while the windows are read
     * @return all QR code texts found, without duplicates
     */
    static List<String> searchScreen(BiConsumer<Integer, Integer> progress)
    {
        Set<String> texts = new LinkedHashSet<>();
        for (ScreenCapture.Target t : ScreenCapture.displays()) {
            read(t, texts);
        }
        if (texts.stream().anyMatch(QrSearch::isOtpauth)) {
            return new ArrayList<>(texts);
        }
        // windows behind other windows (PrintWindow, Windows only)
        List<ScreenCapture.Target> windows = ScreenCapture.windows();
        for (int i = 0; i < windows.size(); i++) {
            progress.accept(i + 1, windows.size());
            read(windows.get(i), texts);
        }
        return new ArrayList<>(texts);
    }


    /** Reads the QR codes of one display or window. */
    static List<String> search(ScreenCapture.Target target)
    {
        Set<String> texts = new LinkedHashSet<>();
        read(target, texts);
        return new ArrayList<>(texts);
    }


    private static void read(ScreenCapture.Target target, Set<String> texts)
    {
        BufferedImage img = ScreenCapture.capture(target, CAPTURE_MILLIS);
        if (img != null) {
            texts.addAll(QrReader.read(img));
        }
    }
}
