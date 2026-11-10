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
import javax.swing.JProgressBar;
import javax.swing.UIManager;


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
    }

    private final Account account;
    private final Messages msg;
    private final JLabel nameLabel = new JLabel();
    private final JLabel codeLabel = new JLabel();
    private final JLabel nextLabel = new JLabel();
    private final JProgressBar timeBar;
    private long shownCounter = Long.MIN_VALUE;
    private String code = "";


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
                actions.copy(account, code);
            }
        });
        nextLabel.setForeground(Color.GRAY);
        timeBar = new JProgressBar(0, account.period());
        timeBar.setForeground(AppIcon.ACCENT);
        timeBar.setBorderPainted(false);
        timeBar.setPreferredSize(new java.awt.Dimension(10, 4));

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
        edit.addActionListener(e -> menu.show(edit, 0, edit.getHeight()));

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

        refreshName();
        update(System.currentTimeMillis() / 1000);
    }


    Account account()
    {
        return account;
    }


    void refreshName()
    {
        nameLabel.setText(account.name().isEmpty() ? msg.get("row.unnamed") : account.name());
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


    /** @return the code in two groups for reading, for example "123 456" */
    static String group(String code)
    {
        int half = code.length() / 2;
        return code.substring(0, half) + " " + code.substring(half);
    }
}
