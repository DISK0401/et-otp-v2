/*
 * SvgPath.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.Locale;


/**
 * Reads the path data of SVG ({@code d} attribute of {@code <path>}) into a Java 2D shape, to draw the bundled
 * icons (one path each) as vectors in any size (issue #27). JDK cannot read SVG, and the SVG libraries for Java
 * are either large or under licenses that do not fit GPL-2.0-only.
 */
final class SvgPath
{
    private final String d;
    private int pos;


    private SvgPath(String d)
    {
        this.d = d;
    }


    /**
     * @return the shape of the path data, filled with the nonzero rule (the SVG default)
     * @throws IllegalArgumentException if the path data is invalid
     */
    static Path2D.Double parse(String d)
    {
        return new SvgPath(d).read();
    }


    private Path2D.Double read()
    {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        double x = 0;
        double y = 0;
        double startX = 0;
        double startY = 0;
        // the control point of the previous curve, for S and T
        double ctrlX = 0;
        double ctrlY = 0;
        char previous = ' ';
        char command = ' ';
        skipSeparators();
        while (pos < d.length()) {
            char c = d.charAt(pos);
            if (Character.isLetter(c)) {
                command = c;
                pos++;
            } else if (command == ' ' || command == 'Z' || command == 'z') {
                throw new IllegalArgumentException("Number without a command at " + pos);
            } else if (command == 'M') {
                command = 'L'; // more pairs after a move are lines
            } else if (command == 'm') {
                command = 'l';
            }
            boolean relative = Character.isLowerCase(command);
            double ox = relative ? x : 0;
            double oy = relative ? y : 0;
            switch (Character.toUpperCase(command)) {
                case 'M':
                    x = ox + number();
                    y = oy + number();
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    break;
                case 'L':
                    x = ox + number();
                    y = oy + number();
                    lineTo(path, x, y);
                    break;
                case 'H':
                    x = ox + number();
                    lineTo(path, x, y);
                    break;
                case 'V':
                    y = oy + number();
                    lineTo(path, x, y);
                    break;
                case 'C': {
                    double x1 = ox + number();
                    double y1 = oy + number();
                    ctrlX = ox + number();
                    ctrlY = oy + number();
                    x = ox + number();
                    y = oy + number();
                    curveTo(path, x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'S': {
                    boolean after = "CcSs".indexOf(previous) >= 0;
                    double x1 = after ? 2 * x - ctrlX : x;
                    double y1 = after ? 2 * y - ctrlY : y;
                    ctrlX = ox + number();
                    ctrlY = oy + number();
                    x = ox + number();
                    y = oy + number();
                    curveTo(path, x1, y1, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'Q':
                    ctrlX = ox + number();
                    ctrlY = oy + number();
                    x = ox + number();
                    y = oy + number();
                    quadTo(path, ctrlX, ctrlY, x, y);
                    break;
                case 'T': {
                    boolean after = "QqTt".indexOf(previous) >= 0;
                    ctrlX = after ? 2 * x - ctrlX : x;
                    ctrlY = after ? 2 * y - ctrlY : y;
                    x = ox + number();
                    y = oy + number();
                    quadTo(path, ctrlX, ctrlY, x, y);
                    break;
                }
                case 'A': {
                    double rx = number();
                    double ry = number();
                    double angle = number();
                    boolean large = flag();
                    boolean sweep = flag();
                    double ex = ox + number();
                    double ey = oy + number();
                    arcTo(path, x, y, rx, ry, angle, large, sweep, ex, ey);
                    x = ex;
                    y = ey;
                    break;
                }
                case 'Z':
                    path.closePath();
                    x = startX;
                    y = startY;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported path command " + command + " at " + pos);
            }
            previous = command;
            skipSeparators();
        }
        return path;
    }


    /** A path may continue after Z without a move: it starts at the start of the closed part. */
    private static void ensureStarted(Path2D.Double path, double x, double y)
    {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        }
    }


    private static void lineTo(Path2D.Double path, double x, double y)
    {
        ensureStarted(path, 0, 0);
        path.lineTo(x, y);
    }


    private static void curveTo(Path2D.Double path, double x1, double y1, double x2, double y2, double x, double y)
    {
        ensureStarted(path, 0, 0);
        path.curveTo(x1, y1, x2, y2, x, y);
    }


    private static void quadTo(Path2D.Double path, double x1, double y1, double x, double y)
    {
        ensureStarted(path, 0, 0);
        path.quadTo(x1, y1, x, y);
    }


    /** An elliptical arc as defined in SVG 1.1, appendix F.6 (endpoint to center parameterization). */
    static void arcTo(Path2D.Double path, double x0, double y0, double rx, double ry, double angle,
                      boolean large, boolean sweep, double x, double y)
    {
        ensureStarted(path, x0, y0);
        if (x0 == x && y0 == y) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0) {
            path.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2;
        double dy = (y0 - y) / 2;
        double x1 = cos * dx + sin * dy;
        double y1 = -sin * dx + cos * dy;
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double coef = Math.sqrt(Math.max(0, num / den)) * (large == sweep ? -1 : 1);
        double cx1 = coef * rx * y1 / ry;
        double cy1 = -coef * ry * x1 / rx;
        double cx = cos * cx1 - sin * cy1 + (x0 + x) / 2;
        double cy = sin * cx1 + cos * cy1 + (y0 + y) / 2;
        double start = angle(1, 0, (x1 - cx1) / rx, (y1 - cy1) / ry);
        double extent = angle((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry);
        if (!sweep && extent > 0) {
            extent -= 2 * Math.PI;
        } else if (sweep && extent < 0) {
            extent += 2 * Math.PI;
        }
        // Arc2D measures angles counterclockwise with y up: in SVG coordinates (y down) the signs flip
        Arc2D.Double arc = new Arc2D.Double(-rx, -ry, 2 * rx, 2 * ry, -Math.toDegrees(start),
                                            -Math.toDegrees(extent), Arc2D.OPEN);
        AffineTransform t = new AffineTransform();
        t.translate(cx, cy);
        t.rotate(phi);
        Path2D.Double arcPath = new Path2D.Double(arc, t);
        // connect to the current point instead of starting a new subpath
        path.append(arcPath.getPathIterator(null), true);
        // the arc ends (up to rounding) at the end point; end exactly there for the next command
        Point2D end = path.getCurrentPoint();
        if (end == null || end.getX() != x || end.getY() != y) {
            path.lineTo(x, y);
        }
    }


    private static double angle(double ux, double uy, double vx, double vy)
    {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }


    private void skipSeparators()
    {
        while (pos < d.length() && (Character.isWhitespace(d.charAt(pos)) || d.charAt(pos) == ',')) {
            pos++;
        }
    }


    /** An arc flag is a single 0 or 1, which may be followed by the next number without a separator. */
    private boolean flag()
    {
        skipSeparators();
        if (pos >= d.length() || (d.charAt(pos) != '0' && d.charAt(pos) != '1')) {
            throw new IllegalArgumentException("Arc flag expected at " + pos);
        }
        return d.charAt(pos++) == '1';
    }


    /** Reads a number: "1.5.5" are the numbers 1.5 and .5, "1-2" the numbers 1 and -2. */
    private double number()
    {
        skipSeparators();
        int start = pos;
        if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) {
            pos++;
        }
        boolean digits = false;
        boolean dot = false;
        while (pos < d.length()) {
            char c = d.charAt(pos);
            if (c >= '0' && c <= '9') {
                digits = true;
            } else if (c == '.' && !dot) {
                dot = true;
            } else {
                break;
            }
            pos++;
        }
        if (digits && pos < d.length() && (d.charAt(pos) == 'e' || d.charAt(pos) == 'E')) {
            int mark = pos;
            pos++;
            if (pos < d.length() && (d.charAt(pos) == '+' || d.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            while (pos < d.length() && d.charAt(pos) >= '0' && d.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == expStart) {
                pos = mark; // not an exponent
            }
        }
        if (!digits) {
            throw new IllegalArgumentException("Number expected at " + start);
        }
        return Double.parseDouble(d.substring(start, pos).toLowerCase(Locale.ROOT));
    }
}
