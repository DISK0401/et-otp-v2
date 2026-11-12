/*
 * AccountRow.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;


/**
 * One registered service in the list: name, current code, next code and the remaining time.
 */
final class AccountRow extends JPanel
{
    private static final long serialVersionUID = 1L;

    /** Actions of the row, implemented by the main window. */
    interface Actions
    {
        void copy(Account account, String code);

        void rename(Account account);

        void delete(Account account);

        /** @return false if the account cannot be moved there now (at the end of the list, or filtered) */
        boolean canMove(Account account, Move where);

        void move(Account account, Move where);
    }

    /** Where the edit menu moves a service. */
    enum Move
    {
        UP, DOWN, TOP
    }

    private final Account account;
    private final Messages msg;
    private final JLabel nameLabel = new JLabel();
    private final JLabel codeLabel = new JLabel();
    private final JLabel nextLabel = new JLabel();
    private final TimeBar timeBar;
    private long shownCounter = Long.MIN_VALUE;
    private String code = "";
    private final JButton editButton;


    AccountRow(Account account, Messages msg, Actions actions)
    {
        super(new GridBagLayout());
        this.account = account;
        this.msg = msg;
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        Font base = UIManager.getFont("Label.font");
        nameLabel.setFont(base.deriveFont(Font.BOLD));
        // the name may come from a QR code: never interpret it as HTML
        nameLabel.putClientProperty("html.disable", Boolean.TRUE);
        codeLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.round(base.getSize2D() * 2f)));
        codeLabel.setForeground(AppIcon.ACCENT);
        codeLabel.setToolTipText(msg.get("row.copyTip"));
        codeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        codeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e)
            {
                // a right click opens the menu of the row, and must not replace the clipboard
                if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || e.isPopupTrigger()) {
                    return;
                }
                actions.copy(account, code);
            }
        });
        nextLabel.setForeground(Color.GRAY);
        timeBar = new TimeBar(account.period());

        JButton copy = new JButton(msg.get("row.copy"));
        copy.addActionListener(e -> actions.copy(account, code));
        JButton edit = new JButton(msg.get("row.edit"));
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem(msg.get("row.rename"));
        rename.addActionListener(e -> actions.rename(account));
        JMenuItem delete = new JMenuItem(msg.get("row.delete"));
        delete.addActionListener(e -> actions.delete(account));
        menu.add(rename);
        menu.add(delete);
        menu.addSeparator();
        JMenuItem[] moves = new JMenuItem[Move.values().length];
        for (Move where : Move.values()) {
            JMenuItem item = new JMenuItem(msg.get("row.move." + where.name().toLowerCase(java.util.Locale.ROOT)));
            item.addActionListener(e -> actions.move(account, where));
            moves[where.ordinal()] = item;
            menu.add(item);
        }
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e)
            {
                for (Move where : Move.values()) {
                    moves[where.ordinal()].setEnabled(actions.canMove(account, where));
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) { }
        });
        edit.addActionListener(e -> menu.show(edit, 0, edit.getHeight()));
        editButton = edit;
        // the same menu with a right click anywhere on the row
        setComponentPopupMenu(menu);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(nameLabel, c);

        c.gridy = 1;
        c.gridwidth = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        add(codeLabel, c);

        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.SOUTHWEST;
        c.insets = new Insets(0, 12, 6, 0);
        add(nextLabel, c);

        c.gridx = 2;
        c.gridy = 0;
        c.gridheight = 2;
        c.weightx = 0;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0, 8, 0, 0);
        add(copy, c);
        c.gridx = 3;
        add(edit, c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 4;
        c.gridheight = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(6, 0, 0, 0);
        add(timeBar, c);

        for (java.awt.Component child : getComponents()) {
            if (child instanceof JComponent) {
                ((JComponent) child).setInheritsPopupMenu(true);
            }
        }
        refreshName();
        update(System.currentTimeMillis() / 1000);
    }


    Account account()
    {
        return account;
    }


    /** @return the code shown now */
    String code()
    {
        return code;
    }


    /** @return the name as shown, also for a service without a name */
    String shownName()
    {
        return account.name().isEmpty() ? msg.get("row.unnamed") : account.name();
    }


    /** Marks the row chosen with the arrow keys in the search field. */
    void setSelected(boolean selected, Color normalBackground)
    {
        setBackground(selected ? mix(normalBackground, UIManager.getColor("List.selectionBackground")) : normalBackground);
    }


    /** @return a light tint of the selection color: the texts keep their colors, so they must stay readable */
    private static Color mix(Color base, Color tint)
    {
        if (base == null || tint == null) {
            return base != null ? base : Color.LIGHT_GRAY;
        }
        float t = 0.25f;
        return new Color(Math.round(base.getRed() * (1 - t) + tint.getRed() * t),
                         Math.round(base.getGreen() * (1 - t) + tint.getGreen() * t),
                         Math.round(base.getBlue() * (1 - t) + tint.getBlue() * t));
    }


    /** The button that opens the edit menu, to keep the keyboard focus on the row after a move. */
    JButton editButton()
    {
        return editButton;
    }


    void refreshName()
    {
        nameLabel.setText(shownName());
    }


    /** Shows the codes for the given time (seconds since 1970). */
    void update(long now)
    {
        long counter = Math.floorDiv(now, account.period());
        if (counter != shownCounter) {
            shownCounter = counter;
            code = account.code(now);
            codeLabel.setText(group(code));
            nextLabel.setText(msg.format("row.next", group(account.totp(counter + 1))));
        }
        timeBar.setValue(account.secondsLeft(now));
    }


    /**
     * The remaining time as a flat bar. JProgressBar is not used: the Windows look ignores its
     * colors, and the basic look draws it in segments.
     */
    static final class TimeBar extends JComponent
    {
        private static final long serialVersionUID = 1L;
        private static final Color TRACK = new Color(0xE0E0E0);
        private final int max;
        private int value;

        TimeBar(int max)
        {
            this.max = max;
            setPreferredSize(new java.awt.Dimension(10, 5));
            // paints its whole area, so the parent need not be repainted
            setOpaque(true);
        }

        void setValue(int value)
        {
            if (value != this.value) {
                this.value = value;
                repaint();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g)
        {
            g.setColor(TRACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(AppIcon.ACCENT);
            g.fillRect(0, 0, (int) ((long) getWidth() * value / max), getHeight());
        }
    }


    /** @return the code in two groups for reading, for example "123 456" */
    static String group(String code)
    {
        int half = code.length() / 2;
        return code.substring(0, half) + " " + code.substring(half);
    }
}
