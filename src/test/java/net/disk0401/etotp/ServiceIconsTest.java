/*
 * ServiceIconsTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.swing.Icon;

import org.junit.jupiter.api.Test;


class ServiceIconsTest
{
    @Test
    void keysIgnoreCaseSpacesAndPunctuation()
    {
        assertEquals("battlenet", ServiceIcons.key("Battle.net"));
        assertEquals("amazonwebservices", ServiceIcons.key(" Amazon  Web Services "));
        assertEquals("yahoojapan", ServiceIcons.key("Yahoo! JAPAN"));
        // full width letters are the same as ASCII
        assertEquals("github", ServiceIcons.key("ＧｉｔＨｕｂ"));
        assertEquals("楽天", ServiceIcons.key("楽天"));
        assertEquals("", ServiceIcons.key(null));
    }

    @Test
    void findsByIssuerFirst()
    {
        ServiceIcons.Entry github = ServiceIcons.find("GitHub", "something else");
        assertNotNull(github);
        assertEquals("github.com", github.domain);
        assertTrue(github.hasIcon());
        assertSame(ServiceIcons.find("Twitter", ""), ServiceIcons.find("X", ""));
    }

    @Test
    void findsByNameWithoutIssuer()
    {
        // the name as made from a QR code: "Issuer (account)"
        assertEquals("gitlab.com", ServiceIcons.find("", "GitLab (alice@example.com)").domain);
        // the first words of a name entered by hand
        assertEquals("aws.amazon.com", ServiceIcons.find("", "Amazon Web Services production").domain);
        assertEquals("amazon.com", ServiceIcons.find("", "Amazon").domain);
        assertEquals("github.com", ServiceIcons.find(null, "GitHub work").domain);
        // an unknown issuer falls back to the name
        assertEquals("github.com", ServiceIcons.find("Unknown Corp", "GitHub").domain);
    }

    @Test
    void unknownServicesAreNotGuessed()
    {
        assertNull(ServiceIcons.find("", "Internal Portal"));
        assertNull(ServiceIcons.find("", "GitHubber"));
        assertNull(ServiceIcons.find("", ""));
        assertNull(ServiceIcons.find(null, null));
    }

    @Test
    void servicesRemovedFromSimpleIconsHaveOnlyTheirSite()
    {
        ServiceIcons.Entry microsoft = ServiceIcons.find("Microsoft", "");
        assertEquals("microsoft.com", microsoft.domain);
        assertFalse(microsoft.hasIcon());
        // self-hosted software has an icon, but no site
        ServiceIcons.Entry nextcloud = ServiceIcons.find("Nextcloud", "");
        assertTrue(nextcloud.hasIcon());
        assertEquals("", nextcloud.domain);
    }

    @Test
    void namesAreUniqueInTheTable() throws Exception
    {
        Set<String> keys = new HashSet<>();
        try (InputStream in = ServiceIcons.class.getResourceAsStream(ServiceIcons.RESOURCE)) {
            for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                for (String name : line.split("\t")[0].split("\\|")) {
                    assertTrue(keys.add(ServiceIcons.key(name)), "twice: " + name);
                }
                String domain = line.split("\t", -1)[1];
                assertTrue(domain.isEmpty() || domain.equals(IconFetcher.siteHost(domain)), domain);
            }
        }
    }

    @Test
    void lettersAndColors()
    {
        assertEquals("E", ServiceIcons.letter("example"));
        assertEquals("E", ServiceIcons.letter("  (example)"));
        assertEquals("社", ServiceIcons.letter("社内システム"));
        assertEquals("?", ServiceIcons.letter(""));
        assertEquals("?", ServiceIcons.letter("()"));
        assertEquals(ServiceIcons.letterColor("Example"), ServiceIcons.letterColor("example"));
        assertNotEquals(ServiceIcons.letterColor("Example"), ServiceIcons.letterColor("Other service"));
        assertTrue(ServiceIcons.isLight(Color.WHITE));
        assertTrue(ServiceIcons.isLight(new Color(0xFFFC00)));
        assertFalse(ServiceIcons.isLight(new Color(0x181717)));
    }

    @Test
    void iconsInOrder() throws Exception
    {
        Account a = new Account("GitHub (me)", "JBSWY3DPEHPK3PXP");
        assertInstanceOf(ServiceIcons.BrandIcon.class, ServiceIcons.icon(a, 32));
        Account other = new Account("Internal Portal", "JBSWY3DPEHPK3PXP");
        assertInstanceOf(ServiceIcons.LetterIcon.class, ServiceIcons.icon(other, 32));

        BufferedImage red = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = red.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 10, 10);
        g.dispose();
        a.setIcon(IconImages.encode(IconImages.normalize(red)));
        assertInstanceOf(ServiceIcons.StoredIcon.class, ServiceIcons.icon(a, 32));
        // a damaged stored icon is not shown
        a.setIcon("not an image");
        assertInstanceOf(ServiceIcons.BrandIcon.class, ServiceIcons.icon(a, 32));
    }

    @Test
    void iconsCanBePainted() throws Exception
    {
        for (Account a : new Account[] { new Account("GitHub", "JBSWY3DPEHPK3PXP"),
                                         new Account("", "JBSWY3DPEHPK3PXP"),
                                         new Account("社内システム", "JBSWY3DPEHPK3PXP") }) {
            Icon icon = ServiceIcons.icon(a, 32);
            assertEquals(32, icon.getIconWidth());
            // twice the size, as on a screen with 200 % scaling
            BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.scale(2, 2);
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            assertNotEquals(0, image.getRGB(32, 32) >>> 24, "painted");
        }
    }
}
