/*
 * SetupWizard.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;


/**
 * Guides through storing the secret when et-otp-v2 starts without a configuration file (et-otp issue #3):
 * 1. what the program does and what is needed, 2. the Base32 secret, 3. the password (entered twice).
 */
final class SetupWizard extends JDialog
{
    private static final long serialVersionUID = 1L;
    /** Width of the explanation texts in pixels. */
    private static final int TEXT_WIDTH = 420;
    private static final int STEPS = 3;

    /** Saves the secret; shows its own error message and returns false on failure. */
    interface Saver
    {
        boolean save(Component parent, String code, char[] pass);
    }

    private final Messages msg;
    private final Saver saver;
    private final CardLayout cards = new CardLayout();
    private final JPanel steps = new JPanel(cards);
    private final JTextField code = new JTextField(30);
    private final JPasswordField pass = new JPasswordField(20);
    private final JPasswordField confirm = new JPasswordField(20);
    private final JLabel error = new JLabel(" ");
    private final JButton back;
    private final JButton next;
    private int step;
    private boolean completed;


    SetupWizard(JFrame owner, Messages msg, File configFile, Saver saver)
    {
        super(owner, msg.format("wizard.title", GUI.PROGNAME), true);
        this.msg = msg;
        this.saver = saver;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        steps.add(introStep(configFile), "0");
        steps.add(inputStep("wizard.secret.text", "wizard.secret.label", code), "1");
        steps.add(passwordStep(), "2");

        error.setForeground(Color.RED);
        error.setBorder(BorderFactory.createEmptyBorder(0, 15, 0, 15));

        back = new JButton(msg.get("wizard.back"));
        back.addActionListener(e -> showStep(step - 1));
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
        add(steps, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(next);
        showStep(0);
        GUI.packWrapped(this);
    }


    /** @return true if the secret was saved */
    boolean isCompleted()
    {
        return completed;
    }


    @Override
    public void dispose()
    {
        pass.setText("");
        confirm.setText("");
        code.setText("");
        super.dispose();
    }


    private void showStep(int newStep)
    {
        step = newStep;
        cards.show(steps, Integer.toString(step));
        error.setText(" ");
        back.setEnabled(step > 0);
        next.setText(msg.get(step == STEPS - 1 ? "wizard.finish" : "wizard.next"));
        if (step == 1) {
            code.requestFocusInWindow();
        } else if (step == 2) {
            pass.requestFocusInWindow();
        }
    }


    private void next()
    {
        if (step == 1) {
            String problem = SecretInput.checkSecret(code.getText());
            if (problem != null) {
                error.setText(msg.get(problem));
                return;
            }
        }
        if (step < STEPS - 1) {
            showStep(step + 1);
            return;
        }
        char[] p = pass.getPassword();
        char[] c = confirm.getPassword();
        try {
            String problem = SecretInput.checkPassword(p, c);
            if (problem != null) {
                error.setText(msg.get(problem));
                return;
            }
            if (saver.save(this, code.getText(), p)) {
                completed = true;
                dispose();
            }
        } finally {
            Arrays.fill(p, '\0');
            Arrays.fill(c, '\0');
        }
    }


    private JPanel introStep(File configFile)
    {
        JPanel panel = textPanel();
        panel.add(text("<h2>" + GUI.html(msg.format("wizard.intro.heading", GUI.PROGNAME)) + "</h2>"
                       + para(msg.format("wizard.intro.text1", GUI.PROGNAME))
                       + para(msg.get("wizard.intro.text2"))
                       + para(msg.get("wizard.intro.text3"))), constraints(0));
        panel.add(GUI.pathText(configFile), constraints(1));
        return fillRest(panel);
    }


    private JPanel inputStep(String textKey, String labelKey, JTextField field)
    {
        JPanel panel = textPanel();
        panel.add(text(para(msg.get(textKey))), constraints(0));
        GridBagConstraints c = constraints(1);
        panel.add(new JLabel(msg.get(labelKey)), c);
        c.gridy = 2;
        panel.add(field, c);
        return fillRest(panel);
    }


    private JPanel passwordStep()
    {
        JPanel panel = textPanel();
        panel.add(text(para(msg.get("wizard.password.text"))), constraints(0));
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 5, 10);
        c.anchor = GridBagConstraints.EAST;
        fields.add(new JLabel(msg.get("settings.password"), SwingConstants.RIGHT), c);
        c.gridy = 1;
        fields.add(new JLabel(msg.get("settings.confirm"), SwingConstants.RIGHT), c);
        c.gridx = 1;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        fields.add(pass, c);
        c.gridy = 1;
        fields.add(confirm, c);
        panel.add(fields, constraints(1));
        return fillRest(panel);
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
