/*
 * SvgPathTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import org.junit.jupiter.api.Test;


class SvgPathTest
{
    private static void assertBounds(double x, double y, double w, double h, Path2D path)
    {
        Rectangle2D b = path.getBounds2D();
        assertEquals(x, b.getX(), 1e-6, "x");
        assertEquals(y, b.getY(), 1e-6, "y");
        assertEquals(w, b.getWidth(), 1e-6, "width");
        assertEquals(h, b.getHeight(), 1e-6, "height");
    }

    @Test
    void absoluteAndRelativeLines()
    {
        assertBounds(2, 3, 10, 5, SvgPath.parse("M2 3H12V8H2Z"));
        assertBounds(2, 3, 10, 5, SvgPath.parse("m2 3h10v5h-10z"));
        // more pairs after a move are lines
        assertBounds(0, 0, 4, 4, SvgPath.parse("M0 0 4 0 4 4z"));
        assertBounds(1, 1, 3, 3, SvgPath.parse("m1 1 3 0 0 3z"));
    }

    @Test
    void numbersWithoutSeparators()
    {
        // "1.5.5" are two numbers, "-" starts a new one, exponents are part of the number
        assertBounds(0, 0, 1.5, 0.5, SvgPath.parse("M0 0L1.5.5L0 .5z"));
        assertBounds(-2, -1, 3, 2, SvgPath.parse("M1-1L-2 1z"));
        assertBounds(0, 0, 10, 1, SvgPath.parse("M0,0L1e1,1E0z"));
    }

    @Test
    void curves()
    {
        Path2D cubic = SvgPath.parse("M0 0C0 10 10 10 10 0S20-10 20 0");
        Rectangle2D b = cubic.getBounds2D();
        assertEquals(0, b.getX(), 1e-6);
        assertEquals(20, b.getMaxX(), 1e-6);
        Path2D quad = SvgPath.parse("M0 0Q5 10 10 0T20 0");
        assertTrue(quad.getBounds2D().getMaxY() > 0);
        assertTrue(quad.getBounds2D().getMinY() < 0, "T mirrors the control point");
    }

    @Test
    void arcs()
    {
        // a half circle of radius 5 from (0, 5) to (10, 5), above the line (sweep 1 is clockwise on screen)
        Path2D half = SvgPath.parse("M0 5A5 5 0 0 1 10 5");
        Rectangle2D b = half.getBounds2D();
        assertEquals(0, b.getMinX(), 1e-3);
        assertEquals(10, b.getMaxX(), 1e-3);
        assertEquals(0, b.getMinY(), 0.05);
        assertEquals(5, b.getMaxY(), 1e-3);
        // compact flags as written by SVG optimizers: "a5 5 0 015 5" is radius 5, flags 0 and 1, then 5 5
        Path2D compact = SvgPath.parse("M0 0a5 5 0 015 5");
        assertEquals(5, compact.getCurrentPoint().getX(), 1e-9);
        assertEquals(5, compact.getCurrentPoint().getY(), 1e-9);
        // too small radii are scaled up; zero radii are a line
        assertEquals(10, SvgPath.parse("M0 0A1 1 0 0 0 10 0").getCurrentPoint().getX(), 1e-9);
        assertBounds(0, 0, 10, 0, SvgPath.parse("M0 0A0 5 0 0 0 10 0"));
    }

    @Test
    void invalidPathData()
    {
        assertThrows(IllegalArgumentException.class, () -> SvgPath.parse("M0"));
        assertThrows(IllegalArgumentException.class, () -> SvgPath.parse("1 2"));
        assertThrows(IllegalArgumentException.class, () -> SvgPath.parse("M0 0X1 1"));
        assertThrows(IllegalArgumentException.class, () -> SvgPath.parse("M0 0A1 1 0 2 0 1 1"));
        assertTrue(SvgPath.parse("").getPathIterator(null).isDone());
    }

    /** Every bundled icon can be read and lies in its 24 x 24 box. */
    @Test
    void bundledIconsAreReadable()
    {
        int icons = 0;
        for (ServiceIcons.Entry e : ServiceIcons.table().values()) {
            if (!e.hasIcon()) {
                continue;
            }
            icons++;
            Rectangle2D b = e.shape().getBounds2D();
            assertTrue(b.getMinX() > -0.5 && b.getMinY() > -0.5 && b.getMaxX() < 24.5 && b.getMaxY() < 24.5,
                       e.names + " " + b);
            assertTrue(b.getWidth() > 5 && b.getHeight() > 2, e.names + " " + b);
        }
        assertTrue(icons > 100, "icons: " + icons);
    }
}
