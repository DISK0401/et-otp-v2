/*
 * ClipboardGuard.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;


/**
 * Copies codes to the clipboard and removes them again, but only while the clipboard still holds the
 * copied code: something the user copied afterwards in another program is kept.
 * Used when locking (issue #22); also meant for removing codes after a time (issue #21).
 */
final class ClipboardGuard
{
    private final Clipboard clipboard;
    private String copied;


    ClipboardGuard(Clipboard clipboard)
    {
        this.clipboard = clipboard;
    }


    void copy(String code)
    {
        clipboard.setContents(new StringSelection(code), null);
        copied = code;
    }


    /** @return true if the copied code was still on the clipboard and was removed */
    boolean clear()
    {
        String code = copied;
        copied = null;
        if (code == null) {
            return false;
        }
        try {
            if (!code.equals(clipboard.getData(DataFlavor.stringFlavor))) {
                return false;
            }
            clipboard.setContents(new StringSelection(""), null);
            return true;
        } catch (UnsupportedFlavorException | IOException | IllegalStateException e) {
            // no text, or the clipboard is in use by another program: leave it
            return false;
        }
    }
}
