/*
 * SetupWizard.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;


/**
 * First start wizard (et-otp issue #3): explains the program, lets the user choose the protection
 * (on Windows: Windows user or password) and the password, and offers a desktop shortcut. The location of
 * the configuration file can be changed, also to an existing configuration file, which is then opened.
 * The caller creates the configuration file from the result.
 */
final class SetupWizard extends JDialog
{
    private static final long serialVersionUID = 1L;
    static final int TEXT_WIDTH = 420;

    private static final String STEP_INTRO = "intro";
    private static final String STEP_PROTECTION = "protection";
    private static final String STEP_PASSWORD = "password";

    /** The choices of a completed wizard. */
    static final class Result
    {
        final AccountStore.Protection protection;
        /** the new password for {@link AccountStore.Protection#PASSWORD}; the caller clears it. */
        final char[] password;
        final boolean shortcut;
        /** the configuration file to create, or to open if {@link #useExisting} */
        final File configFile;
        /** true if the user chose an existing configuration file: open it instead of creating one */
        final boolean useExisting;

        Result(AccountStore.Protection protection, char[] password, boolean shortcut, File configFile,
               boolean useExisting)
        {
            this.protection = protection;
            this.password = password;
            this.shortcut = shortcut;
            this.configFile = configFile;
            this.useExisting = useExisting;
        }
    }

    private final Messages msg;
    private final CardLayout cards = new CardLayout();
    private final JPanel stepPanel = new JPanel(cards);
    private final List<String> steps = new ArrayList<>();
    private final JRadioButton windowsUser;
    private final JRadioButton password;
    private final JCheckBox shortcut;
    private final JPasswordField pass = new JPasswordField(20);
    private final JPasswordField confirm = new JPasswordField(20);
    private final JLabel error = new JLabel(" ");
    private final JButton back;
    private final JButton next;
    private int step;
    private Result result;
    private File configFile;
    private javax.swing.JTextArea pathText;


    SetupWizard(JFrame owner, Messages msg, File configFile)
    {
        super(owner, msg.format("wizard.title", GUI.PROGNAME), true);
        this.msg = msg;
        this.configFile = configFile.getAbsoluteFile();
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        windowsUser = new JRadioButton(msg.get("protection.windows"), true);
        password = new JRadioButton(msg.get("protection.password"), false);
        ButtonGroup group = new ButtonGroup();
        group.add(windowsUser);
        group.add(password);
        shortcut = new JCheckBox(msg.get("wizard.shortcut"), DesktopShortcut.isSupported());

        addStep(STEP_INTRO, introStep());
        // without Windows data protection there is nothing to choose: always a password
        if (Dpapi.isAvailable()) {
            addStep(STEP_PROTECTION, protectionStep());
        } else {
            password.setSelected(true);
        }
        addStep(STEP_PASSWORD, passwordStep());

        error.setForeground(Color.RED);
        error.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));

        back = new JButton(msg.get("wizard.back"));
        back.addActionListener(e -> showStep(Math.max(0, step - 1)));
        next = new JButton(msg.get("wizard.next"));
        next.addActionListener(e -> next());
        JButton cancel = new JButton(msg.get("wizard.cancel"));
        cancel.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(back);
        buttons.add(next);
        buttons.add(cancel);

        JPanel south = new JPanel(new BorderLayout());
        south.add(error, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(stepPanel, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(next);
        showStep(0);
        GUI.packWrapped(this);
    }


    /** @return the choices, or null if the wizard was cancelled */
    Result result()
    {
        return result;
    }


    @Override
    public void dispose()
    {
        pass.setText("");
        confirm.setText("");
        super.dispose();
    }


    private void addStep(String name, JPanel panel)
    {
        steps.add(name);
        stepPanel.add(panel, name);
    }


    /** @return true if the current step is the last one; the password step is skipped for the Windows user */
    private boolean isLast()
    {
        String name = steps.get(step);
        return STEP_PASSWORD.equals(name) || (STEP_PROTECTION.equals(name) && windowsUser.isSelected());
    }


    private void showStep(int newStep)
    {
        step = newStep;
        cards.show(stepPanel, steps.get(step));
        error.setText(" ");
        back.setEnabled(step > 0);
        next.setText(msg.get(isLast() ? "wizard.finish" : "wizard.next"));
        if (STEP_PASSWORD.equals(steps.get(step))) {
            pass.requestFocusInWindow();
        }
    }


    private void next()
    {
        if (!isLast()) {
            showStep(step + 1);
            return;
        }
        boolean wantShortcut = shortcut.isSelected() && DesktopShortcut.isSupported();
        if (windowsUser.isSelected() && Dpapi.isAvailable()) {
            result = new Result(AccountStore.Protection.WINDOWS_USER, null, wantShortcut, configFile, false);
            dispose();
            return;
        }
        char[] p = pass.getPassword();
        char[] c = confirm.getPassword();
        try {
            String problem = SecretInput.checkPassword(p, c);
            if (problem != null) {
                error.setText(msg.get(problem));
                Arrays.fill(p, '\0');
                return;
            }
            result = new Result(AccountStore.Protection.PASSWORD, p, wantShortcut, configFile, false);
            dispose();
        } finally {
            Arrays.fill(c, '\0');
        }
    }


    private JPanel introStep()
    {
        JPanel panel = textPanel();
        panel.add(text("<h2>" + GUI.html(msg.format("wizard.intro.heading", GUI.PROGNAME)) + "</h2>"
                       + para(msg.format("wizard.intro.text1", GUI.PROGNAME))
                       + para(msg.format("wizard.intro.text2", GUI.PROGNAME))
                       + para(msg.get("wizard.intro.text3"))), constraints(0));
        pathText = GUI.pathText(configFile);
        panel.add(pathText, constraints(1));
        JButton change = new JButton(msg.get("location.change"));
        change.addActionListener(e -> changeLocation());
        GridBagConstraints c = constraints(2);
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        panel.add(change, c);
        return fillRest(panel);
    }


    /**
     * Lets the user choose the location. A new file is created there at the end of the wizard; an existing
     * configuration file is opened right away after a confirmation.
     */
    private void changeLocation()
    {
        File chosen = GUI.chooseLocation(this, msg, configFile);
        if (chosen == null) {
            return;
        }
        if (chosen.exists()) {
            try {
                AccountStore.inspect(chosen);
            } catch (IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(this, msg.format("location.notConfig", chosen.getAbsolutePath()),
                                              msg.get("location.title"), JOptionPane.WARNING_MESSAGE);
                return;
            }
            int answer = JOptionPane.showConfirmDialog(this, msg.format("location.useExisting", chosen.getAbsolutePath()),
                                                       msg.get("location.title"), JOptionPane.OK_CANCEL_OPTION,
                                                       JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.OK_OPTION) {
                result = new Result(null, null, false, chosen, true);
                dispose();
            }
            return;
        }
        configFile = chosen;
        pathText.setText(chosen.getAbsolutePath());
        GUI.packWrapped(this);
    }


    private JPanel protectionStep()
    {
        JPanel panel = textPanel();
        panel.add(text(para(msg.get("wizard.protection.text"))), constraints(0));
        panel.add(windowsUser, constraints(1));
        panel.add(note(msg.get("protection.windows.note")), constraints(2));
        panel.add(password, constraints(3));
        panel.add(note(msg.get("protection.password.note")), constraints(4));
        if (DesktopShortcut.isSupported()) {
            GridBagConstraints c = constraints(5);
            c.insets = new Insets(15, 0, 5, 0);
            panel.add(shortcut, c);
        }
        windowsUser.addActionListener(e -> showStep(step));
        password.addActionListener(e -> showStep(step));
        return fillRest(panel);
    }


    private JPanel passwordStep()
    {
        JPanel panel = textPanel();
        panel.add(text(para(msg.get("wizard.password.text"))), constraints(0));
        panel.add(passwordFields(msg, pass, confirm), constraints(1));
        return fillRest(panel);
    }


    /** The "new password" and "confirm password" fields, also used in the settings. */
    static JPanel passwordFields(Messages msg, JPasswordField pass, JPasswordField confirm)
    {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 5, 10);
        c.anchor = GridBagConstraints.EAST;
        fields.add(new JLabel(msg.get("protection.newPassword"), SwingConstants.RIGHT), c);
        c.gridy = 1;
        fields.add(new JLabel(msg.get("protection.confirm"), SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        fields.add(pass, c);
        c.gridy = 1;
        fields.add(confirm, c);
        return fields;
    }


    /** A short explanation under a choice, indented like the choice's text. */
    static JLabel note(String text)
    {
        JLabel label = new JLabel("<html><body style=\"width: " + (TEXT_WIDTH - 24) + "px\">" + GUI.html(text)
                                  + "</body></html>");
        label.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        label.setForeground(Color.DARK_GRAY);
        return label;
    }


    private static JPanel textPanel()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
        return panel;
    }


    /** Keeps the contents at the top of the step by giving the remaining height to an empty row. */
    private static JPanel fillRest(JPanel panel)
    {
        GridBagConstraints c = constraints(99);
        c.weighty = 1;
        panel.add(new JPanel(), c);
        return panel;
    }


    private static String para(String text)
    {
        return "<p style=\"margin-top: 6px\">" + GUI.html(text) + "</p>";
    }


    private static GridBagConstraints constraints(int row)
    {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 0, 5, 0);
        return c;
    }


    private static JLabel text(String html)
    {
        return new JLabel("<html><body style=\"width: " + TEXT_WIDTH + "px\">" + html + "</body></html>");
    }
}
