/*
 * IcoReader.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * Reads Windows icon files (.ico), the usual format of favicon.ico, which JDK cannot read (issue #27).
 * An icon file holds a directory of images, each a PNG or a bitmap without file header (a DIB whose height
 * includes a 1 bit transparency mask). The largest image that can be read is returned.
 */
final class IcoReader
{
    /** the largest image in an icon file (256 is stored as 0). */
    static final int MAX_SIZE = 256;

    private final byte[] data;


    private IcoReader(byte[] data)
    {
        this.data = data;
    }


    /** @return true if the data starts like an icon file */
    static boolean isIco(byte[] data)
    {
        return data.length >= 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0
               && (data[4] != 0 || data[5] != 0);
    }


    /**
     * @return the largest image of the icon file that can be read
     * @throws IOException if the data is no icon file or none of its images can be read
     */
    static BufferedImage read(byte[] data) throws IOException
    {
        return new IcoReader(data).read();
    }


    private static final class Entry
    {
        final int width;
        final int height;
        final int bits;
        final int size;
        final int offset;

        Entry(int width, int height, int bits, int size, int offset)
        {
            this.width = width;
            this.height = height;
            this.bits = bits;
            this.size = size;
            this.offset = offset;
        }
    }


    private BufferedImage read() throws IOException
    {
        if (!isIco(data)) {
            throw new IOException("Not an icon file");
        }
        int count = u16(4);
        if (6 + 16L * count > data.length) {
            throw new IOException("Icon file is truncated");
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int p = 6 + 16 * i;
            int w = data[p] & 0xff;
            int h = data[p + 1] & 0xff;
            int bits = u16(p + 6);
            long size = u32(p + 8);
            long offset = u32(p + 12);
            if (size > 0 && offset + size <= data.length && offset >= 6 + 16L * count) {
                entries.add(new Entry(w == 0 ? 256 : w, h == 0 ? 256 : h, bits, (int) size, (int) offset));
            }
        }
        // the largest first; for the same size the one with more colors
        entries.sort(Comparator.<Entry>comparingInt(e -> e.width * e.height).thenComparingInt(e -> e.bits).reversed());
        IOException problem = null;
        for (Entry e : entries) {
            try {
                return image(e);
            } catch (IOException | RuntimeException ex) {
                problem = ex instanceof IOException ? (IOException) ex : new IOException(ex.toString(), ex);
            }
        }
        throw problem != null ? problem : new IOException("Icon file has no images");
    }


    private BufferedImage image(Entry e) throws IOException
    {
        byte[] part = new byte[e.size];
        System.arraycopy(data, e.offset, part, 0, e.size);
        if (IconImages.isPng(part)) {
            BufferedImage image = IconImages.readStandard(part, MAX_SIZE);
            if (image == null) {
                throw new IOException("Unreadable PNG in icon file");
            }
            return image;
        }
        return new Dib(part).read();
    }


    /** A bitmap without file header, as stored in icon files. */
    static final class Dib
    {
        private final byte[] d;

        Dib(byte[] d)
        {
            this.d = d;
        }

        BufferedImage read() throws IOException
        {
            if (d.length < 40) {
                throw new IOException("Bitmap header is truncated");
            }
            long headerSize = u32(d, 0);
            int width = s32(d, 4);
            int height2 = s32(d, 8);
            int bits = u16(d, 14);
            long compression = u32(d, 16);
            long colorsUsed = u32(d, 32);
            if (headerSize < 40 || headerSize > d.length) {
                throw new IOException("Invalid bitmap header");
            }
            // the height includes the mask; a negative height (top down) does not occur in icons
            int height = height2 / 2;
            if (width <= 0 || height <= 0 || width > MAX_SIZE || height > MAX_SIZE) {
                throw new IOException("Unsupported bitmap size " + width + " x " + height2);
            }
            if (compression != 0 && !(compression == 3 && bits == 32)) {
                throw new IOException("Unsupported bitmap compression " + compression);
            }
            if (bits != 1 && bits != 4 && bits != 8 && bits != 24 && bits != 32) {
                throw new IOException("Unsupported bitmap depth " + bits);
            }
            long pos = headerSize;
            if (compression == 3 && headerSize == 40) {
                pos += 12; // the three color masks after the header; the usual BGRA order is assumed
            }
            int[] palette = null;
            if (bits <= 8) {
                long n = colorsUsed == 0 ? 1L << bits : colorsUsed;
                if (n > 256 || pos + 4 * n > d.length) {
                    throw new IOException("Invalid bitmap palette");
                }
                palette = new int[(int) n];
                for (int i = 0; i < n; i++) {
                    int p = (int) (pos + 4 * i);
                    palette[i] = ((d[p + 2] & 0xff) << 16) | ((d[p + 1] & 0xff) << 8) | (d[p] & 0xff);
                }
                pos += 4 * n;
            }
            int stride = ((width * bits + 31) / 32) * 4;
            int maskStride = ((width + 31) / 32) * 4;
            long colorEnd = pos + (long) stride * height;
            if (colorEnd > d.length) {
                throw new IOException("Bitmap is truncated");
            }
            // some 32 bit icons have no mask; then the alpha channel alone tells the transparency
            boolean hasMask = colorEnd + (long) maskStride * height <= d.length;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            boolean anyAlpha = false;
            for (int row = 0; row < height; row++) {
                int y = height - 1 - row; // bottom up
                int rowStart = (int) (pos + (long) stride * row);
                for (int x = 0; x < width; x++) {
                    int argb;
                    switch (bits) {
                        case 32: {
                            int p = rowStart + 4 * x;
                            int a = d[p + 3] & 0xff;
                            anyAlpha |= a != 0;
                            argb = (a << 24) | ((d[p + 2] & 0xff) << 16) | ((d[p + 1] & 0xff) << 8) | (d[p] & 0xff);
                            break;
                        }
                        case 24: {
                            int p = rowStart + 3 * x;
                            argb = 0xff000000 | ((d[p + 2] & 0xff) << 16) | ((d[p + 1] & 0xff) << 8) | (d[p] & 0xff);
                            break;
                        }
                        default: {
                            int bitIndex = x * bits;
                            int b = d[rowStart + bitIndex / 8] & 0xff;
                            int shift = 8 - bits - (bitIndex % 8);
                            int index = (b >> shift) & ((1 << bits) - 1);
                            if (index >= palette.length) {
                                throw new IOException("Invalid color index in bitmap");
                            }
                            argb = 0xff000000 | palette[index];
                            break;
                        }
                    }
                    image.setRGB(x, y, argb);
                }
            }
            if (bits == 32 && anyAlpha) {
                return image; // the alpha channel is used, the mask is redundant
            }
            if (hasMask) {
                for (int row = 0; row < height; row++) {
                    int y = height - 1 - row;
                    int rowStart = (int) (colorEnd + (long) maskStride * row);
                    for (int x = 0; x < width; x++) {
                        boolean transparent = ((d[rowStart + x / 8] >> (7 - x % 8)) & 1) != 0;
                        int rgb = image.getRGB(x, y) & 0xffffff;
                        image.setRGB(x, y, transparent ? rgb : 0xff000000 | rgb);
                    }
                }
            } else if (bits == 32) {
                // neither alpha nor mask: opaque
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        image.setRGB(x, y, image.getRGB(x, y) | 0xff000000);
                    }
                }
            }
            return image;
        }
    }


    private int u16(int p)
    {
        return u16(data, p);
    }


    private long u32(int p)
    {
        return u32(data, p);
    }


    static int u16(byte[] d, int p)
    {
        return (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8);
    }


    static long u32(byte[] d, int p)
    {
        return s32(d, p) & 0xffffffffL;
    }


    static int s32(byte[] d, int p)
    {
        return (d[p] & 0xff) | ((d[p + 1] & 0xff) << 8) | ((d[p + 2] & 0xff) << 16) | ((d[p + 3] & 0xff) << 24);
    }
}
