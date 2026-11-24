/*
 * IconImages.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;


/**
 * Reads the images for the icons of the services and converts them to the stored form: a small PNG
 * (issue #27). An image chosen by the user and an image fetched from a site take the same way, and the
 * original bytes are never stored.
 */
final class IconImages
{
    /** the size of the stored icons in pixels: sharp up to twice the size in the list. */
    static final int SIZE = 64;
    /** the largest image that is decoded (width and height), against images that need much memory. */
    static final int MAX_DIMENSION = 4096;
    /** the largest image file that is read. */
    static final int MAX_FILE_BYTES = 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };


    private IconImages() { }


    static boolean isPng(byte[] data)
    {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }


    /**
     * Reads an image file: PNG, JPEG, GIF, BMP (with JDK) or ICO.
     *
     * @throws IOException if the data is no readable image, or too large
     */
    static BufferedImage read(byte[] data) throws IOException
    {
        if (data.length > MAX_FILE_BYTES) {
            throw new IOException("The image is too large (" + data.length + " bytes)");
        }
        if (IcoReader.isIco(data)) {
            return IcoReader.read(data);
        }
        BufferedImage image = readStandard(data, MAX_DIMENSION);
        if (image == null) {
            throw new IOException("Unsupported or damaged image");
        }
        return image;
    }


    /**
     * Reads an image with the readers of JDK, checking its size before the pixels are decoded.
     *
     * @return the image, or null if no reader can read it
     * @throws IOException if it is damaged or larger than the given width and height
     */
    static BufferedImage readStandard(byte[] data, int maxDimension) throws IOException
    {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > maxDimension || h > maxDimension) {
                    throw new IOException("Unsupported image size " + w + " x " + h);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            // the readers of JDK throw runtime exceptions for some damaged files
            throw new IOException("Damaged image: " + e, e);
        }
    }


    /** @return the image scaled to {@link #SIZE} (keeping its proportions, centered on transparency) */
    static BufferedImage normalize(BufferedImage source)
    {
        BufferedImage image = toArgb(source);
        int w = image.getWidth();
        int h = image.getHeight();
        double scale = (double) SIZE / Math.max(w, h);
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        // halving step by step keeps the details of large images (a single bicubic step skips pixels)
        while (image.getWidth() / 2 >= tw && image.getHeight() / 2 >= th) {
            image = scaled(image, image.getWidth() / 2, image.getHeight() / 2);
        }
        BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            hints(g);
            g.drawImage(image, (SIZE - tw) / 2, (SIZE - th) / 2, tw, th, null);
        } finally {
            g.dispose();
        }
        return result;
    }


    private static BufferedImage toArgb(BufferedImage source)
    {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            return source;
        }
        return scaled(source, source.getWidth(), source.getHeight());
    }


    private static BufferedImage scaled(BufferedImage source, int w, int h)
    {
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        try {
            hints(g);
            g.drawImage(source, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return result;
    }


    private static void hints(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
    }


    /**
     * Converts an image file to the stored form.
     *
     * @return the image as a {@link #SIZE} x {@link #SIZE} PNG in Base64
     * @throws IOException if the data is no readable image
     */
    static String toStored(byte[] data) throws IOException
    {
        return encode(normalize(read(data)));
    }


    static String encode(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG cannot be written");
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }


    /** @return the stored icon, or null if it cannot be read (it is then not shown) */
    static BufferedImage decodeStored(String stored)
    {
        try {
            byte[] data = Base64.getDecoder().decode(stored);
            if (!isPng(data)) {
                return null;
            }
            return readStandard(data, IcoReader.MAX_SIZE);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
