/*
 * GUI.java - et-otp: GPL Java TOTP soft token by Bernd Eckenfels.
 * Modified 2026 by DISK0401 for et-otp-v2: package renamed, encryption moved to SecretStore, homepage changed, config location.
 */
package net.disk0401.etotp;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;

import net.disk0401.etotp.Base32.DecodingException;


/**
 * et-OTP GUI main class.
 */
public class GUI implements ActionListener
{
    private static final String VERSION = GUI.class.getPackage().getImplementationVersion();
    private static final String HELPURL = "https://disk0401.github.io/et-otp-v2/";
    private static final String PROGNAME = "et-OTP v2";

    JPasswordField passwordField;
    private JTextField textField;
    private JLabel textLabel;
    private JFrame frame;
    private JDialog settingsDialog;
    private JDialog aboutDialog;
    private JTextField settingsCode;
    private JPasswordField settingsPass;
    private File configFile = ConfigLocation.resolve();
    private JLabel settingsFileLabel;
    private JLabel statusLabel;
    /** status text when upgrading an et-otp 1.x configuration file failed while unlocking, else empty. */
    private String migrationMessage = "";


    private GUI() { }


    static void buildMainFrame()
    {
        GridBagLayout layout = new GridBagLayout();
        GUI gui= new GUI();
        gui.frame = new JFrame(PROGNAME);
        gui.frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        gui.frame.setLayout(layout);

        JMenuBar mb = new JMenuBar();
        JMenu m = new JMenu(PROGNAME); m.setMnemonic(KeyEvent.VK_E);
        JMenuItem mi = new JMenuItem("Settings"); mi.addActionListener(gui);  m.add(mi);
        mi = new JMenuItem("Quit"); mi.addActionListener(gui);  m.add(mi);
        mb.add(m);
        m = new JMenu("Help"); m.setMnemonic(KeyEvent.VK_H);
        mi = new JMenuItem("Manual"); mi.addActionListener(gui);  m.add(mi);
        mi = new JMenuItem("About"); mi.addActionListener(gui);  m.add(mi);
        mb.add(m);
        gui.frame.setJMenuBar(mb);

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 1;
        c.gridheight = 1;
        c.gridwidth = 3;
        c.weighty = 1;
        c.weightx = 1;
        c.insets = new Insets(10, 10, 20, 10);
        c.anchor = GridBagConstraints.NORTH;
        JLabel label = new JLabel(PROGNAME + " Soft Token", SwingConstants.CENTER);
        label.setFont(new Font("Serif", Font.BOLD, 16));
        gui.frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 2;
        c.gridheight = 1;
        c.gridwidth = 2;
        c.weighty = 0;
        c.weightx = 1;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0,10,5,10);
        label = new JLabel("Unlock Password:");
        gui.frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 3;
        c.gridheight = 1;
        c.gridwidth = 2;
        c.weighty = 0;
        c.weightx = 1;
        c.insets = new Insets(0,10,10,10);
        JPasswordField pwField = new JPasswordField("");
        pwField.addActionListener(gui);
        pwField.setFocusable(true);
        gui.frame.add(pwField, c);
        gui.passwordField = pwField;

        c.fill = GridBagConstraints.BOTH;
        c.gridx = 3;
        c.gridy = 2;
        c.gridheight = 2;
        c.gridwidth = 1;
        c.weighty = 0;
        c.weightx = 0;
        c.ipadx = 0;
        c.insets = new Insets(0,10,10,10);
        c.anchor = GridBagConstraints.CENTER;
        JButton button = new JButton("Calc");
        button.addActionListener(gui);
        gui.frame.add(button, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 2;
        c.gridy = 4;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 1;
        c.ipadx = 70;
        c.insets = new Insets(0,10,10,10);
        c.anchor = GridBagConstraints.CENTER;
        JTextField text  = new JTextField("");
        text.setFont(new Font("Dialog", Font.BOLD, 16));
        // text.setFocusable(false);
        text.setEditable(false);
        gui.frame.add(text, c);
        gui.textField = text;

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 4;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 2;
        c.weighty = 0;
        c.insets = new Insets(0,20,10,0);
        c.anchor = GridBagConstraints.CENTER;
        label = new JLabel("Your Code:", SwingConstants.RIGHT);
        gui.frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 5;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 2;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(0,20,10,0);
        label = new JLabel("Next Code:", SwingConstants.RIGHT);
        gui.frame.add(label, c);

        c.fill = GridBagConstraints.BOTH;
        c.gridx = 3;
        c.gridy = 4;
        c.gridheight = 2;
        c.gridwidth = 1;
        c.weighty = 0;
        c.weightx = 0;
        c.ipadx = 0;
        c.insets = new Insets(0,10,10,10);
        c.anchor = GridBagConstraints.CENTER;
        JButton button2 = new JButton("Copy");
        button2.addActionListener(gui);
        gui.frame.add(button2, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 2;
        c.gridy = 5;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(0,10,10,0);
        label = new JLabel("", SwingConstants.LEFT);
        gui.frame.add(label, c);
        gui.textLabel = label;

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 6;
        c.gridheight = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(5,5,5,5);
        label = new JLabel("", SwingConstants.LEFT);
        gui.frame.add(label, c);
        gui.statusLabel = label;

        gui.frame.addComponentListener(gui.new ShowEvent());

        // now we realize the components
        gui.frame.pack();

        // now we can display it
        gui.frame.setVisible(true);
    }


    @Override
    public void actionPerformed(ActionEvent e)
    {

        statusLabel.setText(" ");
        statusLabel.setForeground(Color.BLACK);

        if ("Quit".equals(e.getActionCommand()))
        {
            System.exit(0);
        }
        else if ("Manual".equals(e.getActionCommand()))
        {
            try {
                statusLabel.setText("Opening " + HELPURL + " in browser.");
                Desktop.getDesktop().browse(new URI(HELPURL));
            } catch (Exception ignored) { /* dont care */ }
        }
        else if ("Save".equals(e.getActionCommand()))
        {
            if (settingsDialog != null)
            {
                settingsDialog.setVisible(false);
            }

            String code = settingsCode.getText();
            char[] pass = settingsPass.getPassword();

            try
            {
                writeSecret(code, pass);
                statusLabel.setText("Configuration saved.");
            }
            catch (Exception ex)
            {
                JOptionPane.showMessageDialog(frame, "Error while writing configuration\n" + ex.getClass().getName() + "\n" + ex.getMessage(), PROGNAME + ": Cannot save configuration", JOptionPane.ERROR_MESSAGE);
            }
            finally
            {
                Arrays.fill(pass, '\0');
            }
/*			catch (DecodingException e2) {
            } catch (IOException e3) {
            } catch (NoSuchAlgorithmException e4) {
            } catch (InvalidKeySpecException e6) {
            } catch (NoSuchPaddingException e7) {
            } catch (InvalidKeyException e8) {
            } catch (IllegalBlockSizeException e9) {
            } catch (BadPaddingException e10) {
*/
        }
        else if ("About".equals(e.getActionCommand()))
        {
            if (aboutDialog == null) {
                JDialog d = new JDialog(frame,"About " + PROGNAME, true);
                JTextPane t = new JTextPane();
                t.setContentType("text/html");
                t.setText("<h1>" + PROGNAME + " TOTP Generator</h1><p>This generator can be used to produce a TOTP (RFC 6238) time-based one-time password.</p>" +
                          "<p>This is a so called soft-token, it will be initialized with a specified BASE32 secret. This method is compatible with the 2-step verification methods of Amazon AWS, Dropbox, GitHub, Google, Microsoft and others.</p>" +
                          "<p>et-otp-v2 is maintained by <b>DISK0401</b>. It is based on et-otp by <b>Bernd Eckenfels</b> and includes some code from the reference implementation of HOTP (RFC 4226) as well as Google's BASE32 implementation from the Google Authenticator project.</p>" +
                          "<p>License: GPLv2.<br/>Version: " + VERSION + "<br/>Homepage: <b>" + HELPURL + "</b></p><p>Config file: <b>" + configFile.getAbsolutePath()+"</b></p>");
                t.validate();
                d.add(t);
                d.setSize(600, 400);
                aboutDialog = d;
            }
            aboutDialog.setVisible(true);
        }
        else if ("Settings".equals(e.getActionCommand()))
        {
            if (settingsDialog == null) {
                JDialog d = new JDialog(frame, PROGNAME + " settings", true);
                buildSettingsDialog(d);
                settingsDialog = d;
            }
            settingsCode.setText("");
            settingsPass.setText("");
            settingsFileLabel.setText("Config File " + (configFile.isFile()?"(overwrite)":"(missing)"));
            settingsDialog.setVisible(true);
        }
        else if ("Copy".equals(e.getActionCommand()))
        {
        	String s = textField.getText();
        	StringSelection selection = new StringSelection(s);
        	Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        	clipboard.setContents(selection, null);
        }
        else if ("Calc".equals(e.getActionCommand()) || (e.getSource() == passwordField))
        {
            try
            {
                char[] pass = passwordField.getPassword();
                migrationMessage = "";
                byte[] bytes;
                try {
                    bytes = readSecret(pass);
                } finally {
                    Arrays.fill(pass, '\0');
                }

                long seconds = System.currentTimeMillis() / 1000;
                long t0 = 0l;
                long step = 30l;
                long counter = (seconds - t0) / step;

                String s = RFC4226.generateOTP(bytes, counter, 6, false, -1);
                textField.setText(s);
                textField.requestFocus();
                textField.setCaretPosition(0);
                textField.moveCaretPosition(6);

                s = RFC4226.generateOTP(bytes, counter+1, 6, false, -1);
                textLabel.setText(s);
                Arrays.fill(bytes, (byte) 0);
                statusLabel.setText(migrationMessage);
            }
            catch (BadPaddingException bp)
            {
                errorText("Bad password.");
            }
            catch (InvalidKeySpecException | IllegalArgumentException ik)
            {
                errorText("Password empty or illegal.");
            }
            catch (FileNotFoundException fn)
            {
                errorText("Configuration File not found.");
            }
            catch (IOException io)
            {
                errorText("I/O Error while loading key: " + io.getMessage());
            }
            catch (NoSuchAlgorithmException ns)
            {
                errorText("Crypto Problem: " + ns);
            }
            catch (InvalidKeyException ik)
            {
                errorText("Crypto Key Problem: " + ik);
            }
            catch (Exception ex)
            {
                errorText("Problem: " + ex);
            }
        }
    }


    private void errorText(String text)
    {
        statusLabel.setForeground(Color.RED);
        statusLabel.setText(text);
    }


    private void writeSecret(String code, char[] pass) throws DecodingException, IOException, GeneralSecurityException
    {
        byte[] codeBytes = Base32.decode(code);
        try {
            SecretStore.write(configFile, codeBytes, pass);
        } finally {
            Arrays.fill(codeBytes, (byte) 0);
        }
    }


    /**
     * Reads the secret and transparently upgrades configuration files written by et-otp 1.x.
     */
    private byte[] readSecret(char[] pass) throws IOException, GeneralSecurityException
    {
        SecretStore.Result r = SecretStore.read(configFile, pass);
        if (r.isLegacy()) {
            // silent upgrade; best effort: a failed upgrade must not prevent showing the code
            try {
                SecretStore.migrate(configFile, r, pass);
            } catch (IOException | GeneralSecurityException e) {
                migrationMessage = "Could not upgrade configuration: " + e.getMessage();
            }
        }
        return r.secret();
    }


    private void buildSettingsDialog(JDialog dia)
    {
        dia.setLayout(new GridBagLayout());
        dia.setSize(640, 300);
        GridBagConstraints c = new GridBagConstraints();

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 1;
        c.weightx = 0;
        c.weighty = 0;
        c.insets = new Insets(10, 10, 10, 10);
        dia.add(new JLabel("Code (base32):", JLabel.RIGHT), c);

        c.gridx = 2;
        c.gridy = 1;
        c.weightx = 1;
        c.weighty = 0;
        settingsCode = new JTextField();
        dia.add(settingsCode, c);

        c.gridx = 1;
        c.gridy = 2;
        c.weightx = 0;
        dia.add(new JLabel("Password:", SwingConstants.RIGHT), c);

        c.gridx = 2;
        c.gridy = 2;
        c.weightx = 1;
        settingsPass = new JPasswordField();
        dia.add(settingsPass, c);

        c.gridx = 1;
        c.gridy = 3;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        JButton button = new JButton("Save");
        button.addActionListener(this);
        dia.add(button,c);

        String file = configFile.getAbsolutePath();
        c.gridx = 1;
        c.gridy = 4;
        c.weightx = 1;
        c.weighty = 0;
        c.gridwidth = 2;
        c.insets = new Insets(10,10,0,10);
        settingsFileLabel = new JLabel("Config File " + (configFile.isFile()?"(overwrite)":"(missing)"));
        dia.add(settingsFileLabel, c);

        c.gridx = 1;
        c.gridy = 5;
        c.weightx = 1;
        c.gridwidth = 2;
        c.insets = new Insets(0,10,10,10);
        JLabel label = new JLabel(file);
        dia.add(label, c);
    }


    /**
     * Main method - does not honor any arguments (yet).
     * @param args ignored
     */
    public static void main(String[] args)
    {
        buildMainFrame();
    }


    class ShowEvent extends ComponentAdapter
    {
        @Override
        public void componentShown(ComponentEvent evt)
        {
            JFrame f = (JFrame)evt.getComponent();
            // we can only request focus when everything is shown
            f.requestFocus();
            f.toFront();
            passwordField.requestFocusInWindow();
        }
    } // end class ShowEvent

} // end class GUI

