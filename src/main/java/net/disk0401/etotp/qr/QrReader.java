/*
 * QrReader.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp.qr;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Reads the texts of the QR codes in an image, with the Java port of quirc.
 */
public final class QrReader
{
    /** Largest image read (64 megapixels), so a huge image cannot exhaust the memory. */
    public static final long MAX_PIXELS = 64_000_000L;

    private static final int ECI_SHIFT_JIS = 20;
    private static final int ECI_UTF_8 = 26;


    private QrReader() { }


    /**
     * @return the texts of all QR codes found, without duplicates, in the order found; empty if none
     * @throws IllegalArgumentException if the image has more than {@link #MAX_PIXELS} pixels
     */
    public static List<String> read(BufferedImage image)
    {
        int w = image.getWidth();
        int h = image.getHeight();
        if ((long) w * h > MAX_PIXELS) {
            throw new IllegalArgumentException("image too large: " + w + " x " + h);
        }
        byte[] gray = toGray(image);

        Set<String> texts = new LinkedHashSet<>();
        readInto(w, h, gray, texts);
        // also light codes on dark background (dark mode pages), even if dark codes were found
        for (int i = 0; i < gray.length; i++) {
            gray[i] = (byte) (255 - (gray[i] & 0xff));
        }
        readInto(w, h, gray, texts);
        return new ArrayList<>(texts);
    }


    private static void readInto(int w, int h, byte[] gray, Set<String> texts)
    {
        Quirc quirc = new Quirc(w, h, gray);
        for (int i = 0; i < quirc.count(); i++) {
            QuircCode code = quirc.extract(i);
            QuircDecoder.Data data;
            try {
                data = QuircDecoder.decode(code);
            } catch (QuircDecoder.DecodeException e) {
                try {
                    // mirrored code
                    data = QuircDecoder.decode(code.flipped());
                } catch (QuircDecoder.DecodeException e2) {
                    continue;
                }
            }
            texts.add(text(data));
        }
    }


    /** Converts to luminance (ITU-R BT.601), 0 = black. */
    static byte[] toGray(BufferedImage image)
    {
        int w = image.getWidth();
        int h = image.getHeight();
        byte[] gray = new byte[w * h];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = row[x];
                int a = rgb >>> 24;
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                // transparent pixels count as white background
                lum = (lum * a + 255 * (255 - a)) / 255;
                gray[y * w + x] = (byte) lum;
            }
        }
        return gray;
    }


    private static String text(QuircDecoder.Data data)
    {
        ByteBuffer bytes = ByteBuffer.wrap(data.payload, 0, data.payloadLen);
        if (data.eci == ECI_UTF_8) {
            return StandardCharsets.UTF_8.decode(bytes).toString();
        }
        if ((data.eci == ECI_SHIFT_JIS || data.dataType == QuircDecoder.DATA_TYPE_KANJI)
            && Charset.isSupported("Shift_JIS")) {
            // Shift_JIS is in the jdk.charsets module, which a minimal runtime may not contain
            return Charset.forName("Shift_JIS").decode(bytes).toString();
        }
        // without ECI, most generators write UTF-8; fall back to ISO-8859-1 (the QR default)
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes.duplicate()).toString();
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1.decode(bytes).toString();
        }
    }
}
