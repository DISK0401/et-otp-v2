/*
 * AppIcon.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;


/**
 * The application icon, drawn in code: a rounded square with a countdown ring, as a one-time
 * password changes every 30 seconds.
 */
final class AppIcon
{
    static final Color ACCENT = new Color(0x00695C);
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};


    private AppIcon() { }


    static BufferedImage draw(int size)
    {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        g.setColor(ACCENT);
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.35, s * 0.35));

        double inset = s * 0.2;
        double d = s - 2 * inset;
        g.setColor(new Color(255, 255, 255, 90));
        g.setStroke(new BasicStroke((float) Math.max(1.5, s * 0.1), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(inset, inset, d, d, 0, 360, Arc2D.OPEN));
        g.setColor(Color.WHITE);
        g.draw(new Arc2D.Double(inset, inset, d, d, 90, -250, Arc2D.OPEN));

        double dot = Math.max(2, s * 0.14);
        g.fill(new java.awt.geom.Ellipse2D.Double((s - dot) / 2, (s - dot) / 2, dot, dot));
        g.dispose();
        return img;
    }


    /** @return the icon in several sizes for window title bars and task bars */
    static List<Image> images()
    {
        List<Image> list = new ArrayList<>();
        for (int size : SIZES) {
            list.add(draw(size));
        }
        return list;
    }


    /** Writes a Windows .ico file with PNG images (supported since Windows Vista). */
    static void writeIco(Path path) throws IOException
    {
        int[] sizes = {16, 24, 32, 48, 256};
        List<byte[]> pngs = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", buf);
            pngs.add(buf.toByteArray());
        }
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = header.capacity();
        for (int i = 0; i < sizes.length; i++) {
            int dim = sizes[i] >= 256 ? 0 : sizes[i];
            header.put((byte) dim).put((byte) dim).put((byte) 0).put((byte) 0);
            header.putShort((short) 1).putShort((short) 32);
            header.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (OutputStream os = Files.newOutputStream(path)) {
            os.write(header.array());
            for (byte[] png : pngs) {
                os.write(png);
            }
        }
    }
}
