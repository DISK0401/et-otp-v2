/*
 * ScreenPicker.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.Border;

import net.disk0401.etotp.qr.QrReader;


/**
 * Lets the user choose the screen or window that shows the QR code, like the screen sharing
 * dialogs of video conference programs. Every candidate is shown as a thumbnail; candidates with a
 * QR code for an authenticator app are marked, and the first of them is selected. The captures
 * stay in memory and are only used for the thumbnails and to look for QR codes.
 */
final class ScreenPicker
{
    private static final int THUMB_W = 200;
    private static final int THUMB_H = 120;
    private static final long CAPTURE_MILLIS = 3000;
    private static final Color FOUND = new Color(0x2E7D32);

    /** The chosen screen or window, with the QR code texts found in its capture (empty if none). */
    static final class Choice
    {
        final ScreenCapture.Target target;
        final List<String> texts;

        Choice(ScreenCapture.Target target, List<String> texts)
        {
            this.target = target;
            this.texts = texts;
        }
    }

    private final Messages msg;
    private final JDialog dialog;
    private final JPanel displayGrid = new JPanel(new GridLayout(0, 3, 10, 10));
    private final JPanel windowGrid = new JPanel(new GridLayout(0, 3, 10, 10));
    private final JLabel progress = new JLabel(" ");
    private final JButton read;
    private final List<Tile> tiles = new ArrayList<>();
    private Tile selected;
    /** true once the user selected a tile; then the automatic selection no longer changes it. */
    private boolean userSelected;
    private SwingWorker<Void, Object[]> worker;
    /**
     * counts the loads; results of an older load (refresh) or after closing are ignored, as a
     * finished worker cannot be cancelled and its callbacks may still be waiting for the EDT.
     */
    private int generation;
    private Choice choice;


    private ScreenPicker(JFrame owner, Messages msg)
    {
        this.msg = msg;
        dialog = new JDialog(owner, msg.get("picker.title"), true);
        read = new JButton(msg.get("picker.read"));
    }


    /** Shows the dialog. @return the chosen screen or window, or null if cancelled */
    static Choice choose(JFrame owner, Messages msg)
    {
        ScreenPicker p = new ScreenPicker(owner, msg);
        p.show();
        return p.choice;
    }


    private void show()
    {
        // a text area wraps Japanese correctly, unlike an HTML label
        javax.swing.JTextArea guide = new javax.swing.JTextArea(msg.get("picker.text"));
        guide.setEditable(false);
        guide.setFocusable(false);
        guide.setLineWrap(true);
        guide.setWrapStyleWord(true);
        guide.setOpaque(false);
        guide.setFont(UIManager.getFont("Label.font"));
        guide.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        displayGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        windowGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(section(msg.get("picker.displays")));
        body.add(displayGrid);
        if (ScreenCapture.windowsSupported()) {
            body.add(javax.swing.Box.createVerticalStrut(16));
            body.add(section(msg.get("picker.windows")));
            body.add(windowGrid);
        }
        JPanel holder = new WidthTrackingPanel();
        holder.add(body, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                             JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        JButton refresh = new JButton(msg.get("picker.refresh"));
        refresh.addActionListener(e -> load());
        read.addActionListener(e -> accept());
        JButton cancel = new JButton(msg.get("picker.cancel"));
        cancel.addActionListener(e -> close());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        bottom.add(progress, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(refresh);
        buttons.add(read);
        buttons.add(cancel);
        bottom.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.add(guide, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(root);
        dialog.getRootPane().setDefaultButton(read);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e)
            {
                close();
            }
        });
        dialog.getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                                                    JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setSize(780, 620);
        dialog.setLocationRelativeTo(dialog.getOwner());
        load();
        dialog.setVisible(true); // modal: returns when closed
    }


    private static JLabel section(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }


    /** Lists the screens and windows and captures them one after the other in the background. */
    private void load()
    {
        if (worker != null) {
            worker.cancel(false);
        }
        int gen = ++generation;
        displayGrid.removeAll();
        windowGrid.removeAll();
        tiles.clear();
        selected = null;
        userSelected = false;
        read.setEnabled(false);

        List<Tile> order = new ArrayList<>();
        for (ScreenCapture.Target t : ScreenCapture.displays()) {
            Tile tile = new Tile(t, msg.format("picker.display", t.display));
            displayGrid.add(tile);
            tiles.add(tile);
        }
        List<Tile> windowTiles = new ArrayList<>();
        for (ScreenCapture.Target t : ScreenCapture.windows()) {
            Tile tile = new Tile(t, t.title);
            windowGrid.add(tile);
            tiles.add(tile);
            windowTiles.add(tile);
        }
        // windows first: a window that shows the QR code is the more specific choice
        order.addAll(windowTiles);
        for (Tile tile : tiles) {
            if (tile.target.isDisplay()) {
                order.add(tile);
            }
        }
        displayGrid.revalidate();
        windowGrid.revalidate();
        dialog.getContentPane().repaint();

        int total = order.size();
        worker = new SwingWorker<Void, Object[]>() {
            @Override
            protected Void doInBackground()
            {
                for (int i = 0; i < total && !isCancelled(); i++) {
                    Tile tile = order.get(i);
                    BufferedImage img = ScreenCapture.capture(tile.target, CAPTURE_MILLIS);
                    List<String> texts = Collections.emptyList();
                    BufferedImage thumb = null;
                    if (img != null) {
                        try {
                            texts = QrReader.read(img);
                        } catch (IllegalArgumentException e) {
                            texts = Collections.emptyList();
                        }
                        thumb = thumbnail(img);
                    }
                    publish(new Object[] {tile, thumb, texts, i + 1});
                }
                return null;
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void process(List<Object[]> chunks)
            {
                if (gen != generation) {
                    return;
                }
                for (Object[] c : chunks) {
                    Tile tile = (Tile) c[0];
                    tile.showResult((BufferedImage) c[1], (List<String>) c[2]);
                    if (!userSelected && tile.hasOtpauth() && (selected == null || !selected.hasOtpauth())) {
                        select(tile, false);
                    }
                    progress.setText(msg.format("picker.progress", c[3], total));
                }
            }

            @Override
            protected void done()
            {
                if (gen != generation) {
                    return;
                }
                progress.setText(msg.get(selected != null && selected.hasOtpauth() ? "picker.found.done" : "picker.done"));
            }
        };
        progress.setText(msg.format("picker.progress", 0, total));
        worker.execute();
    }


    private static BufferedImage thumbnail(BufferedImage img)
    {
        double scale = Math.min((double) THUMB_W / img.getWidth(), (double) THUMB_H / img.getHeight());
        int w = Math.max(1, (int) Math.round(img.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(img.getHeight() * scale));
        BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = thumb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return thumb;
    }


    private void select(Tile tile, boolean byUser)
    {
        if (selected != null) {
            selected.setSelected(false);
        }
        selected = tile;
        userSelected |= byUser;
        tile.setSelected(true);
        read.setEnabled(true);
        tile.scrollRectToVisible(new Rectangle(0, 0, tile.getWidth(), tile.getHeight()));
    }


    private void accept()
    {
        if (selected == null) {
            return;
        }
        choice = new Choice(selected.target, selected.texts);
        close();
    }


    private void close()
    {
        generation++;
        if (worker != null) {
            worker.cancel(false);
        }
        dialog.dispose();
    }


    /** One screen or window: thumbnail, name and whether a QR code was found. */
    private final class Tile extends JPanel
    {
        private static final long serialVersionUID = 1L;
        private final ScreenCapture.Target target;
        private final JLabel image = new JLabel(msg.get("picker.reading"), SwingConstants.CENTER);
        private final JLabel state = new JLabel(" ");
        private final Border normal;
        private final Border chosen;
        private List<String> texts = Collections.emptyList();

        Tile(ScreenCapture.Target target, String title)
        {
            super(new BorderLayout(0, 4));
            this.target = target;
            Color line = UIManager.getColor("Separator.foreground");
            normal = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(line == null ? Color.LIGHT_GRAY : line, 1),
                                                        BorderFactory.createEmptyBorder(7, 7, 7, 7));
            chosen = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(AppIcon.ACCENT, 3),
                                                        BorderFactory.createEmptyBorder(5, 5, 5, 5));
            setBorder(normal);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            image.setPreferredSize(new Dimension(THUMB_W, THUMB_H));
            image.setForeground(Color.GRAY);
            JLabel name = new JLabel(title);
            // window titles come from other programs: never interpret them as HTML
            name.putClientProperty("html.disable", Boolean.TRUE);
            name.setToolTipText(GUI.noHtml(title));
            JPanel text = new JPanel(new BorderLayout());
            text.setOpaque(false);
            text.add(name, BorderLayout.NORTH);
            state.setFont(state.getFont().deriveFont(Font.BOLD));
            state.setForeground(FOUND);
            text.add(state, BorderLayout.SOUTH);
            add(image, BorderLayout.CENTER);
            add(text, BorderLayout.SOUTH);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    select(Tile.this, true);
                    if (e.getClickCount() >= 2) {
                        accept();
                    }
                }
            });
        }

        void showResult(BufferedImage thumb, List<String> found)
        {
            texts = found;
            if (thumb != null) {
                image.setText(null);
                image.setIcon(new ImageIcon(thumb));
            } else {
                image.setText(msg.get(ScreenCapture.isMinimized(target) ? "picker.minimized" : "picker.noImage"));
            }
            state.setText(hasOtpauth() ? msg.get("picker.found") : " ");
        }

        boolean hasOtpauth()
        {
            return texts.stream().anyMatch(QrSearch::isOtpauth);
        }

        void setSelected(boolean on)
        {
            setBorder(on ? chosen : normal);
        }
    }


    /** A panel that takes the width of the scroll pane, so the grid wraps instead of scrolling sideways. */
    private static final class WidthTrackingPanel extends JPanel implements Scrollable
    {
        private static final long serialVersionUID = 1L;

        WidthTrackingPanel()
        {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
        {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
        {
            return visible.height;
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }
}
