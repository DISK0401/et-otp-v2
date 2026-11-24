/*
 * IconImagesTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;


class IconImagesTest
{
    private static BufferedImage image(int w, int h, Color color)
    {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static byte[] file(BufferedImage image, String format) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    @Test
    void imagesAreStoredAsSmallPngs() throws IOException
    {
        for (String format : new String[] { "png", "jpg", "gif", "bmp" }) {
            String stored = IconImages.toStored(file(image(300, 300, Color.BLUE), format));
            BufferedImage back = IconImages.decodeStored(stored);
            assertNotNull(back, format);
            assertEquals(IconImages.SIZE, back.getWidth(), format);
            assertEquals(IconImages.SIZE, back.getHeight(), format);
            assertTrue(IconImages.isPng(Base64.getDecoder().decode(stored)), format);
        }
    }

    @Test
    void proportionsAreKept()
    {
        BufferedImage wide = IconImages.normalize(image(200, 100, Color.RED));
        assertEquals(0, wide.getRGB(32, 2) >>> 24, "transparent above");
        assertEquals(0xffff0000, wide.getRGB(32, 32));
        BufferedImage small = IconImages.normalize(image(16, 16, Color.RED));
        assertEquals(0xffff0000, small.getRGB(0, 0), "small icons are enlarged");
    }

    @Test
    void storedIconsStaySmall() throws IOException
    {
        // a noisy image is the worst case for PNG
        BufferedImage noise = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        java.util.Random random = new java.util.Random(1);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                noise.setRGB(x, y, random.nextInt());
            }
        }
        assertTrue(IconImages.encode(IconImages.normalize(noise)).length() < 24 * 1024);
        // a usual icon is much smaller
        assertTrue(IconImages.toStored(file(image(180, 180, Color.ORANGE), "png")).length() < 2 * 1024);
    }

    @Test
    void unreadableImagesAreRejected() throws IOException
    {
        assertThrows(IOException.class, () -> IconImages.read("<html></html>".getBytes()));
        assertThrows(IOException.class, () -> IconImages.read(new byte[0]));
        byte[] png = file(image(10, 10, Color.RED), "png");
        assertThrows(IOException.class, () -> IconImages.read(Arrays.copyOf(png, png.length / 2)));
        assertThrows(IOException.class, () -> IconImages.read(new byte[IconImages.MAX_FILE_BYTES + 1]));
    }

    /** The size is checked before the pixels are decoded. */
    @Test
    void hugeImagesAreRejected() throws IOException
    {
        byte[] png = file(image(4, 4, Color.RED), "png");
        // the width in the IHDR chunk: 100000 pixels, with a valid checksum
        png[16] = 0;
        png[17] = 0x01;
        png[18] = (byte) 0x86;
        png[19] = (byte) 0xa0;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(png, 12, 17);
        long v = crc.getValue();
        for (int i = 0; i < 4; i++) {
            png[29 + i] = (byte) (v >>> (24 - 8 * i));
        }
        IOException e = assertThrows(IOException.class, () -> IconImages.read(png));
        assertTrue(e.getMessage().contains("100000"), e.getMessage());
    }


    @Test
    void damagedStoredIconsAreNotShown() throws IOException
    {
        assertNull(IconImages.decodeStored("not base64!"));
        assertNull(IconImages.decodeStored(Base64.getEncoder().encodeToString("text".getBytes())));
        // only PNG is stored
        assertNull(IconImages.decodeStored(Base64.getEncoder().encodeToString(file(image(4, 4, Color.RED), "bmp"))));
    }

    /** The icon is kept with the service, also in backups, and not decoded when the list is read. */
    @Test
    void iconsAreStoredWithTheServices() throws Exception
    {
        Account a = Account.fromUri("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub");
        assertEquals("GitHub", a.issuer());
        a.setIcon(IconImages.encode(IconImages.normalize(image(8, 8, Color.GREEN))));
        a.setSite("github.com");
        Account b = new Account("Plain", "JBSWY3DPEHPK3PXQ");
        List<Account> read = AccountStore.parse(AccountStore.serialize(List.of(a, b)));
        assertEquals("GitHub", read.get(0).issuer());
        assertEquals(a.icon(), read.get(0).icon());
        assertEquals("github.com", read.get(0).site());
        assertEquals("", read.get(1).issuer());
        assertNull(read.get(1).icon());
        assertEquals("", read.get(1).site());
        // the backup reminder does not change with the icon
        assertEquals(Backup.fingerprint(List.of(a, b)), Backup.fingerprint(read));
    }
}
