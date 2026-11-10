/*
 * GuiTextTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;

import javax.swing.plaf.basic.BasicHTML;

import org.junit.jupiter.api.Test;


class GuiTextTest
{
    /** Names from QR codes must not be rendered as HTML (for example to load images from a server). */
    @Test
    void namesAreNeverHtml()
    {
        String evil = "<html><img src='https://example.com/x.png'></html>";

        assertFalse(BasicHTML.isHTMLString(GUI.noHtml(evil)));
        assertFalse(BasicHTML.isHTMLString(GUI.noHtml("<HTML>x")));
        assertEquals("GitHub (me)", GUI.noHtml("GitHub (me)"));
    }

    @Test
    void htmlEscaping()
    {
        assertEquals("&lt;b&gt; &amp; &quot;", GUI.html("<b> & \""));
        assertEquals("x", GUI.html("x".toLowerCase(Locale.ROOT)));
    }
}
