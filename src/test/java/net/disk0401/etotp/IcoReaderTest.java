/*
 * IcoReaderTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;


/** The test icons are made here: no icons of real sites are kept in the repository. */
class IcoReaderTest
{
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;

    /** One image of an icon file. */
    private static final class Part
    {
        final int width;
        final int height;
        final int bits;
        final byte[] data;

        Part(int width, int height, int bits, byte[] data)
        {
            this.width = width;
            this.height = height;
            this.bits = bits;
            this.data = data;
        }
    }

    private static byte[] ico(Part... parts)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le16(out, 0);
        le16(out, 1);
        le16(out, parts.length);
        int offset = 6 + 16 * parts.length;
        for (Part p : parts) {
            out.write(p.width >= 256 ? 0 : p.width);
            out.write(p.height >= 256 ? 0 : p.height);
            out.write(0);
            out.write(0);
            le16(out, 1);
            le16(out, p.bits);
            le32(out, p.data.length);
            le32(out, offset);
            offset += p.data.length;
        }
        for (Part p : parts) {
            out.writeBytes(p.data);
        }
        return out.toByteArray();
    }

    private static void le16(ByteArrayOutputStream out, int v)
    {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void le32(ByteArrayOutputStream out, int v)
    {
        le16(out, v & 0xffff);
        le16(out, (v >>> 16) & 0xffff);
    }

    /** A bitmap as in icon files: header (height doubled), palette, colors bottom up, then the 1 bit mask. */
    private static byte[] dib(int w, int h, int bits, int[] palette, int[][] pixels, boolean[][] transparent,
                              boolean withMask)
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le32(out, 40);
        le32(out, w);
        le32(out, 2 * h);
        le16(out, 1);
        le16(out, bits);
        le32(out, 0);
        le32(out, 0);
        le32(out, 0);
        le32(out, 0);
        le32(out, palette == null ? 0 : palette.length);
        le32(out, 0);
        if (palette != null) {
            for (int c : palette) {
                out.write(c & 0xff);
                out.write((c >> 8) & 0xff);
                out.write((c >> 16) & 0xff);
                out.write(0);
            }
        }
        int stride = ((w * bits + 31) / 32) * 4;
        for (int y = h - 1; y >= 0; y--) {
            byte[] row = new byte[stride];
            for (int x = 0; x < w; x++) {
                int v = pixels[y][x];
                switch (bits) {
                    case 32:
                        row[4 * x] = (byte) v;
                        row[4 * x + 1] = (byte) (v >> 8);
                        row[4 * x + 2] = (byte) (v >> 16);
                        row[4 * x + 3] = (byte) (v >>> 24);
                        break;
                    case 24:
                        row[3 * x] = (byte) v;
                        row[3 * x + 1] = (byte) (v >> 8);
                        row[3 * x + 2] = (byte) (v >> 16);
                        break;
                    default:
                        int bit = x * bits;
                        row[bit / 8] |= (byte) (v << (8 - bits - bit % 8));
                        break;
                }
            }
            out.writeBytes(row);
        }
        if (withMask) {
            int maskStride = ((w + 31) / 32) * 4;
            for (int y = h - 1; y >= 0; y--) {
                byte[] row = new byte[maskStride];
                for (int x = 0; x < w; x++) {
                    if (transparent[y][x]) {
                        row[x / 8] |= (byte) (0x80 >> (x % 8));
                    }
                }
                out.writeBytes(row);
            }
        }
        return out.toByteArray();
    }

    private static int[][] fill(int w, int h, int value)
    {
        int[][] p = new int[h][w];
        for (int[] row : p) {
            Arrays.fill(row, value);
        }
        return p;
    }

    private static boolean[][] corner(int w, int h)
    {
        boolean[][] t = new boolean[h][w];
        t[0][0] = true; // the top left pixel is transparent
        return t;
    }

    private static byte[] png(int w, int h, int argb) throws IOException
    {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void recognizesIconFiles() throws IOException
    {
        assertTrue(IcoReader.isIco(ico(new Part(16, 16, 32, png(16, 16, RED)))));
        assertFalse(IcoReader.isIco(png(16, 16, RED)));
        assertFalse(IcoReader.isIco(new byte[] { 0, 0, 1, 0, 0, 0 }), "no images");
        assertFalse(IcoReader.isIco(new byte[3]));
    }

    @Test
    void readsPngImages() throws IOException
    {
        BufferedImage image = IcoReader.read(ico(new Part(256, 256, 32, png(256, 256, RED))));
        assertEquals(256, image.getWidth());
        assertEquals(RED, image.getRGB(10, 10));
    }

    @Test
    void choosesTheLargestImage() throws IOException
    {
        byte[] small = dib(16, 16, 24, null, fill(16, 16, BLUE), corner(16, 16), true);
        BufferedImage image = IcoReader.read(ico(new Part(16, 16, 24, small), new Part(48, 48, 32, png(48, 48, RED))));
        assertEquals(48, image.getWidth());
        assertEquals(RED, image.getRGB(0, 0));
    }

    @Test
    void readsBitmapsWithAlpha() throws IOException
    {
        int[][] pixels = fill(16, 16, RED);
        pixels[3][5] = 0x80123456;
        BufferedImage image = IcoReader.read(ico(new Part(16, 16, 32, dib(16, 16, 32, null, pixels, corner(16, 16), true))));
        assertEquals(RED, image.getRGB(0, 0), "the alpha channel wins over the mask");
        assertEquals(0x80123456, image.getRGB(5, 3));
    }

    @Test
    void usesTheMaskIfTheAlphaIsEmpty() throws IOException
    {
        int[][] pixels = fill(16, 16, 0x00ff0000);
        BufferedImage image = IcoReader.read(ico(new Part(16, 16, 32, dib(16, 16, 32, null, pixels, corner(16, 16), true))));
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(RED, image.getRGB(1, 0));
        // without a mask such an image is opaque
        image = IcoReader.read(ico(new Part(16, 16, 32, dib(16, 16, 32, null, pixels, null, false))));
        assertEquals(RED, image.getRGB(0, 0));
    }

    @Test
    void readsTrueColorAndPaletteBitmaps() throws IOException
    {
        BufferedImage image = IcoReader.read(ico(new Part(16, 16, 24, dib(16, 16, 24, null, fill(16, 16, BLUE), corner(16, 16), true))));
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(BLUE, image.getRGB(15, 15));

        int[] palette = { 0x000000, 0xff0000, 0x0000ff };
        int[][] indexes = fill(16, 16, 2);
        indexes[15][15] = 1;
        for (int bits : new int[] { 4, 8 }) {
            image = IcoReader.read(ico(new Part(16, 16, bits, dib(16, 16, bits, palette, indexes, corner(16, 16), true))));
            assertEquals(0, image.getRGB(0, 0) >>> 24, bits + " bits");
            assertEquals(BLUE, image.getRGB(1, 0), bits + " bits");
            assertEquals(RED, image.getRGB(15, 15), bits + " bits");
        }

        int[][] bitsImage = fill(20, 3, 0);
        bitsImage[1][17] = 1;
        image = IcoReader.read(ico(new Part(20, 3, 1, dib(20, 3, 1, new int[] { 0x000000, 0xffffff }, bitsImage,
                                                              new boolean[3][20], true))));
        assertEquals(0xffffffff, image.getRGB(17, 1));
        assertEquals(0xff000000, image.getRGB(16, 1));
    }

    @Test
    void rejectsDamagedFiles() throws IOException
    {
        byte[] good = ico(new Part(16, 16, 24, dib(16, 16, 24, null, fill(16, 16, BLUE), corner(16, 16), true)));
        // truncated directory and truncated image
        assertThrows(IOException.class, () -> IcoReader.read(Arrays.copyOf(good, 10)));
        assertThrows(IOException.class, () -> IcoReader.read(Arrays.copyOf(good, good.length - 200)));
        // image outside the file
        byte[] outside = good.clone();
        outside[18] = (byte) 0xff;
        outside[19] = (byte) 0x7f;
        assertThrows(IOException.class, () -> IcoReader.read(outside));
        // a huge bitmap
        byte[] huge = good.clone();
        huge[22 + 4] = 0;
        huge[22 + 5] = 0x10;
        assertThrows(IOException.class, () -> IcoReader.read(huge));
        // a palette index beyond the palette
        int[][] indexes = fill(4, 4, 7);
        assertThrows(IOException.class,
                     () -> IcoReader.read(ico(new Part(4, 4, 8, dib(4, 4, 8, new int[] { 0, 1 }, indexes, corner(4, 4), true)))));
        assertThrows(IOException.class, () -> IcoReader.read(png(4, 4, RED)));
    }

    /** Random damage never gives other exceptions than IOException. */
    @Test
    void randomDamageIsAnIOException() throws IOException
    {
        byte[] good = ico(new Part(16, 16, 8, dib(16, 16, 8, new int[] { 0, 0xff0000 }, fill(16, 16, 1), corner(16, 16), true)),
                          new Part(32, 32, 32, png(32, 32, RED)));
        Random random = new Random(27);
        for (int i = 0; i < 2000; i++) {
            byte[] damaged = good.clone();
            for (int j = 0; j < 1 + random.nextInt(4); j++) {
                damaged[random.nextInt(Math.min(damaged.length, 120))] = (byte) random.nextInt(256);
            }
            try {
                IcoReader.read(damaged);
            } catch (IOException e) {
                // expected for most
            }
        }
    }
}
