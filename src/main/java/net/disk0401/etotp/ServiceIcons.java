/*
 * ServiceIcons.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.UIManager;


/**
 * The icons of the services in the list (issue #27), in this order:
 * <ol>
 * <li>the image the user chose or fetched from the site of the service (stored with the service),</li>
 * <li>the bundled icon of the issuer (Simple Icons, CC0),</li>
 * <li>the first letter of the name on a color derived from the name.</li>
 * </ol>
 * The bundled table also gives the site of the service, to fetch its icon: an otpauth URI only holds the
 * issuer and the account name.
 */
final class ServiceIcons
{
    static final String RESOURCE = "services.txt";

    /** A service in the bundled table. */
    static final class Entry
    {
        final List<String> names;
        /** host name of the site, or empty (self-hosted software). */
        final String domain;
        /** brand color, or null without an icon. */
        final Color color;
        private final String pathData;
        private Shape shape;

        Entry(List<String> names, String domain, Color color, String pathData)
        {
            this.names = names;
            this.domain = domain;
            this.color = color;
            this.pathData = pathData;
        }

        boolean hasIcon()
        {
            return color != null && !pathData.isEmpty();
        }

        /** @return the icon in a 24 x 24 box */
        synchronized Shape shape()
        {
            if (shape == null) {
                shape = SvgPath.parse(pathData);
            }
            return shape;
        }
    }

    private static volatile Map<String, Entry> table;


    private ServiceIcons() { }


    /** @return the bundled table by {@link #key}; loaded at the first use, never for --code */
    static Map<String, Entry> table()
    {
        Map<String, Entry> t = table;
        if (t == null) {
            synchronized (ServiceIcons.class) {
                t = table;
                if (t == null) {
                    try (InputStream in = ServiceIcons.class.getResourceAsStream(RESOURCE)) {
                        t = in == null ? Collections.emptyMap() : parse(in);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    table = t;
                }
            }
        }
        return t;
    }


    static Map<String, Entry> parse(InputStream in) throws IOException
    {
        Map<String, Entry> result = new HashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        for (String line; (line = r.readLine()) != null;) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f.length != 4) {
                throw new IOException("Invalid line in " + RESOURCE + ": " + line);
            }
            List<String> names = List.of(f[0].split("\\|"));
            Color color = f[2].isEmpty() ? null : new Color(Integer.parseInt(f[2], 16));
            Entry e = new Entry(names, f[1], color, f[3]);
            for (String n : names) {
                result.put(key(n), e);
            }
        }
        return result;
    }


    /** @return the name for comparing: without case, spaces and punctuation ("Battle.net" is "battlenet") */
    static String key(String name)
    {
        if (name == null) {
            return "";
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        n.codePoints().filter(Character::isLetterOrDigit).forEach(sb::appendCodePoint);
        return sb.toString();
    }


    /**
     * Finds the service in the bundled table: by the issuer of the QR code, else by the name. A name such as
     * "Amazon Web Services (me)" or "GitHub work" also matches by its first words.
     *
     * @return the entry, or null
     */
    static Entry find(String issuer, String name)
    {
        Map<String, Entry> t = table();
        if (issuer != null && !issuer.trim().isEmpty()) {
            Entry e = t.get(key(issuer));
            if (e != null) {
                return e;
            }
        }
        if (name == null) {
            return null;
        }
        String service = serviceName(name);
        List<String> words = new ArrayList<>(List.of(service.trim().split("\\s+")));
        while (!words.isEmpty()) {
            Entry e = t.get(key(String.join(" ", words)));
            if (e != null) {
                return e;
            }
            words.remove(words.size() - 1);
        }
        return null;
    }


    /** @return the name without the account in parentheses, as {@link Account#nameFrom} adds it */
    static String serviceName(String name)
    {
        String n = name.trim();
        int paren = n.indexOf(" (");
        return paren > 0 && n.endsWith(")") ? n.substring(0, paren) : n;
    }


    /** @return the site of the service in the bundled table, or empty */
    static String domain(Account a)
    {
        Entry e = find(a.issuer(), a.name());
        return e == null ? "" : e.domain;
    }


    /**
     * @param size the size in the user interface (it is drawn sharply also on high resolution screens)
     * @return the icon of the service
     */
    static Icon icon(Account a, int size)
    {
        if (a.icon() != null) {
            BufferedImage image = IconImages.decodeStored(a.icon());
            if (image != null) {
                return new StoredIcon(image, size);
            }
        }
        Entry e = find(a.issuer(), a.name());
        if (e != null && e.hasIcon()) {
            return new BrandIcon(e, size);
        }
        String text = a.issuer().isEmpty() ? serviceName(a.name()) : a.issuer();
        return new LetterIcon(text, size);
    }


    /** Colors for the letter icons: all readable with white letters. */
    private static final Color[] LETTER_COLORS = {
        new Color(0x1565C0), new Color(0x2E7D32), new Color(0xC62828), new Color(0x6A1B9A),
        new Color(0xEF6C00), new Color(0x00838F), new Color(0xAD1457), new Color(0x4E342E),
        new Color(0x283593), new Color(0x558B2F), new Color(0x37474F), new Color(0x00695C),
    };


    /** @return the color of the letter icon of a name; always the same for the same name */
    static Color letterColor(String text)
    {
        return LETTER_COLORS[Math.floorMod(key(text).hashCode(), LETTER_COLORS.length)];
    }


    /** @return the first letter or digit of the name in upper case, or "?" */
    static String letter(String text)
    {
        String k = text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC);
        return k.codePoints().filter(Character::isLetterOrDigit).findFirst()
                .stream().mapToObj(cp -> new String(Character.toChars(cp)).toUpperCase(Locale.ROOT))
                .findFirst().orElse("?");
    }


    /** @return true if dark text reads better than white text on the color */
    static boolean isLight(Color c)
    {
        double l = 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
        return l > 0.4;
    }


    private static double linear(int channel)
    {
        double v = channel / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }


    private abstract static class BaseIcon implements Icon
    {
        final int size;

        BaseIcon(int size)
        {
            this.size = size;
        }

        @Override
        public int getIconWidth()
        {
            return size;
        }

        @Override
        public int getIconHeight()
        {
            return size;
        }

        @Override
        public final void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.translate(x, y);
                paint(g2);
            } finally {
                g2.dispose();
            }
        }

        abstract void paint(Graphics2D g);

        RoundRectangle2D tile()
        {
            double arc = size * 0.25;
            return new RoundRectangle2D.Double(0, 0, size, size, arc, arc);
        }

        /** A thin edge, so a white or very light tile stands out from the window. */
        void edge(Graphics2D g, Color fill)
        {
            if (fill.getRed() + fill.getGreen() + fill.getBlue() > 3 * 225) {
                g.setColor(new Color(0, 0, 0, 60));
                g.setStroke(new BasicStroke(1f));
                RoundRectangle2D t = tile();
                g.draw(new RoundRectangle2D.Double(0.5, 0.5, t.getWidth() - 1, t.getHeight() - 1,
                                                   t.getArcWidth(), t.getArcHeight()));
            }
        }
    }


    /** The stored image: drawn from its full resolution, so it is sharp on high resolution screens. */
    static final class StoredIcon extends BaseIcon
    {
        private final BufferedImage image;

        StoredIcon(BufferedImage image, int size)
        {
            super(size);
            this.image = image;
        }

        @Override
        void paint(Graphics2D g)
        {
            g.drawImage(image, 0, 0, size, size, null);
        }
    }


    /** A bundled icon: the logo in white (or black) on a tile in the color of the brand. */
    static final class BrandIcon extends BaseIcon
    {
        private final Entry entry;

        BrandIcon(Entry entry, int size)
        {
            super(size);
            this.entry = entry;
        }

        @Override
        void paint(Graphics2D g)
        {
            g.setColor(entry.color);
            g.fill(tile());
            edge(g, entry.color);
            double inner = size * 0.62;
            AffineTransform t = new AffineTransform();
            t.translate((size - inner) / 2, (size - inner) / 2);
            t.scale(inner / 24, inner / 24);
            g.setColor(isLight(entry.color) ? Color.BLACK : Color.WHITE);
            g.fill(t.createTransformedShape(entry.shape()));
        }
    }


    /** The first letter of the name on a color derived from the name. */
    static final class LetterIcon extends BaseIcon
    {
        private final String letter;
        private final Color color;

        LetterIcon(String text, int size)
        {
            super(size);
            this.letter = letter(text);
            this.color = letterColor(text);
        }

        @Override
        void paint(Graphics2D g)
        {
            g.setColor(color);
            g.fill(tile());
            Font base = UIManager.getFont("Label.font");
            Font font = (base != null ? base : new Font(Font.SANS_SERIF, Font.PLAIN, 12))
                .deriveFont(Font.BOLD, size * 0.55f);
            FontRenderContext frc = g.getFontRenderContext();
            GlyphVector gv = font.createGlyphVector(frc, letter);
            Rectangle2D b = gv.getVisualBounds();
            if (b.isEmpty()) {
                return;
            }
            float x = (float) ((size - b.getWidth()) / 2 - b.getX());
            float y = (float) ((size - b.getHeight()) / 2 - b.getY());
            g.setColor(Color.WHITE);
            g.fill(gv.getOutline(x, y));
        }
    }
}
