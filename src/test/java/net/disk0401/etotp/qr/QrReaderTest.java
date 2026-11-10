/*
 * QrReaderTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.nayuki.qrcodegen.QrCode;


/**
 * Reads QR codes created with an independent generator (Nayuki's QR Code generator).
 */
class QrReaderTest
{
    private static final String OTPAUTH =
        "otpauth://totp/Example:alice@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Example";

    @Test
    void readsATypicalOtpauthCode()
    {
        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 4)));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 7, 10, 15, 25, 40})
    void readsAllVersionSizes(int version)
    {
        String text = OTPAUTH + "&pad=" + "x".repeat(Math.max(0, version * 12));
        QrCode qr = QrCode.encodeSegments(io.nayuki.qrcodegen.QrSegment.makeSegments(text), QrCode.Ecc.LOW,
                                          version, 40, -1, true);

        assertEquals(Collections.singletonList(text), QrReader.read(render(qr, 3)));
    }

    @Test
    void readsAllErrorCorrectionLevelsAndMasks()
    {
        for (QrCode.Ecc ecc : QrCode.Ecc.values()) {
            for (int mask = 0; mask < 8; mask++) {
                QrCode qr = QrCode.encodeSegments(io.nayuki.qrcodegen.QrSegment.makeSegments(OTPAUTH), ecc,
                                                  1, 40, mask, false);
                assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(render(qr, 4)), ecc + " mask " + mask);
            }
        }
    }

    @Test
    void readsNumericAlphanumericAndUtf8Data()
    {
        for (String text : new String[] {"0123456789012345", "HELLO WORLD $%*+-./:", "日本語の名前 ñ"}) {
            assertEquals(Collections.singletonList(text),
                         QrReader.read(render(QrCode.encodeText(text, QrCode.Ecc.MEDIUM), 4)), text);
        }
    }

    @Test
    void readsARotatedAndScaledCode()
    {
        BufferedImage code = render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 5);
        BufferedImage rotated = new BufferedImage(code.getWidth() * 2, code.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rotated.getWidth(), rotated.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform t = new AffineTransform();
        t.translate(rotated.getWidth() / 2.0, rotated.getHeight() / 2.0);
        t.rotate(Math.toRadians(23));
        t.scale(1.37, 1.37);
        t.translate(-code.getWidth() / 2.0, -code.getHeight() / 2.0);
        g.drawImage(code, t, null);
        g.dispose();

        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(rotated));
    }

    @Test
    void readsADarkModeCode()
    {
        BufferedImage img = render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 4);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, ~img.getRGB(x, y) | 0xff000000);
            }
        }

        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(img));
    }

    @Test
    void correctsDamagedModules()
    {
        QrCode qr = QrCode.encodeText(OTPAUTH, QrCode.Ecc.HIGH);
        BufferedImage img = render(qr, 4);
        // flip some modules away from the finder patterns
        Random random = new Random(1);
        int border = 4;
        for (int i = 0; i < 12; i++) {
            int mx = 9 + random.nextInt(qr.size - 18);
            int my = 9 + random.nextInt(qr.size - 18);
            boolean dark = qr.getModule(mx, my);
            fill(img, (mx + border) * 4, (my + border) * 4, 4, dark ? Color.WHITE : Color.BLACK);
        }

        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(img));
    }

    @Test
    void findsACodeInAScreenshotLikeImage()
    {
        BufferedImage screen = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.setColor(new Color(0xf3f3f3));
        g.fillRect(0, 0, 1920, 1080);
        g.setColor(Color.DARK_GRAY);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        for (int y = 30; y < 1080; y += 22) {
            g.drawString("Set up two-step verification. Scan the code with your authenticator app. 0123456789", 40, y);
        }
        g.setColor(Color.WHITE);
        g.fillRect(1200, 300, 400, 400);
        g.drawImage(render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 5), 1230, 330, null);
        g.dispose();

        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(screen));
    }

    @Test
    void readsTwoCodes()
    {
        String other = "otpauth://totp/Other?secret=GEZDGNBVGY3TQOJQ";
        BufferedImage a = render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 4);
        BufferedImage b = render(QrCode.encodeText(other, QrCode.Ecc.MEDIUM), 4);
        BufferedImage both = new BufferedImage(a.getWidth() + b.getWidth() + 40, Math.max(a.getHeight(), b.getHeight()),
                                               BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, both.getWidth(), both.getHeight());
        g.drawImage(a, 0, 0, null);
        g.drawImage(b, a.getWidth() + 40, 0, null);
        g.dispose();

        List<String> texts = QrReader.read(both);

        assertEquals(2, texts.size());
        assertTrue(texts.contains(OTPAUTH));
        assertTrue(texts.contains(other));
    }

    @Test
    void readsADarkCodeAndALightCodeInTheSameImage()
    {
        String url = "https://example.com/help";
        BufferedImage normal = render(QrCode.encodeText(url, QrCode.Ecc.MEDIUM), 4);
        BufferedImage inverted = render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 4);
        for (int y = 0; y < inverted.getHeight(); y++) {
            for (int x = 0; x < inverted.getWidth(); x++) {
                inverted.setRGB(x, y, ~inverted.getRGB(x, y) | 0xff000000);
            }
        }
        BufferedImage both = new BufferedImage(normal.getWidth() + inverted.getWidth() + 40,
                                               Math.max(normal.getHeight(), inverted.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = both.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, both.getWidth(), both.getHeight());
        g.drawImage(normal, 0, 0, null);
        g.drawImage(inverted, normal.getWidth() + 40, 0, null);
        g.dispose();

        List<String> texts = QrReader.read(both);

        assertTrue(texts.contains(url));
        assertTrue(texts.contains(OTPAUTH));
    }

    @Test
    void tooLargeImagesAreRejected()
    {
        // only the size is checked; the pixels are never allocated by the reader
        BufferedImage huge = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_BINARY) {
            @Override
            public int getWidth()
            {
                return 100_000;
            }

            @Override
            public int getHeight()
            {
                return 100_000;
            }
        };

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> QrReader.read(huge));
    }

    @Test
    void noCodeGivesAnEmptyList()
    {
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 300, 200);
        g.setColor(Color.BLACK);
        g.drawString("no code here", 20, 100);
        g.dispose();

        assertTrue(QrReader.read(img).isEmpty());
    }

    @Test
    void mirroredCodeIsRead()
    {
        BufferedImage code = render(QrCode.encodeText(OTPAUTH, QrCode.Ecc.MEDIUM), 4);
        BufferedImage mirrored = new BufferedImage(code.getWidth(), code.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < code.getHeight(); y++) {
            for (int x = 0; x < code.getWidth(); x++) {
                mirrored.setRGB(code.getWidth() - 1 - x, y, code.getRGB(x, y));
            }
        }

        assertEquals(Collections.singletonList(OTPAUTH), QrReader.read(mirrored));
    }


    /** Renders the code with a quiet zone of 4 modules. */
    static BufferedImage render(QrCode qr, int scale)
    {
        int border = 4;
        int size = (qr.size + border * 2) * scale;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        fill(img, 0, 0, size, Color.WHITE);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    fill(img, (x + border) * scale, (y + border) * scale, scale, Color.BLACK);
                }
            }
        }
        return img;
    }

    private static void fill(BufferedImage img, int x0, int y0, int size, Color c)
    {
        for (int y = y0; y < y0 + size; y++) {
            for (int x = x0; x < x0 + size; x++) {
                img.setRGB(x, y, c.getRGB());
            }
        }
    }
}
