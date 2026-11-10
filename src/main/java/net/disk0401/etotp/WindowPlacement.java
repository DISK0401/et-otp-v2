/*
 * WindowPlacement.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.List;


/**
 * Decides whether a remembered window position can be used with the current screens.
 */
final class WindowPlacement
{
    /** Height of the strip at the top of the window that must be visible, so the window can be dragged. */
    static final int TITLE_HEIGHT = 30;
    /** Minimal visible part of that strip. */
    static final int MIN_VISIBLE_WIDTH = 80;
    static final int MIN_VISIBLE_HEIGHT = 10;


    private WindowPlacement() { }


    /**
     * Checks that enough of the title bar of a window at the given bounds lies on one of the screens.
     * A position saved with a monitor that is no longer attached, or with a larger screen, fails this check.
     */
    static boolean isReachable(Rectangle window, List<Rectangle> screens)
    {
        Rectangle title = new Rectangle(window.x, window.y, window.width, Math.min(TITLE_HEIGHT, window.height));
        int minWidth = Math.min(MIN_VISIBLE_WIDTH, title.width);
        int minHeight = Math.min(MIN_VISIBLE_HEIGHT, title.height);
        for (Rectangle screen : screens) {
            Rectangle visible = title.intersection(screen);
            if (visible.width >= minWidth && visible.height >= minHeight) {
                return true;
            }
        }
        return false;
    }


    /**
     * @return the usable area of all screens in virtual desktop coordinates, without task bars and
     *         docks (empty when headless)
     */
    static List<Rectangle> screens()
    {
        List<Rectangle> result = new ArrayList<>();
        if (GraphicsEnvironment.isHeadless()) {
            return result;
        }
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            Rectangle b = gc.getBounds();
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            result.add(new Rectangle(b.x + in.left, b.y + in.top,
                                     b.width - in.left - in.right, b.height - in.top - in.bottom));
        }
        return result;
    }
}
