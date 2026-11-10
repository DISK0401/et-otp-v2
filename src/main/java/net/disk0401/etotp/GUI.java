/*
 * GUI.java - et-otp: GPL Java TOTP soft token by Bernd Eckenfels.
 * Modified 2026 by DISK0401 for et-otp-v2: package renamed, encryption moved to SecretStore, homepage changed,
 * config location, English/Japanese texts, setup wizard, window position, build information.
 */
package net.disk0401.etotp;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Locale;

import javax.crypto.BadPaddingException;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

import net.disk0401.etotp.Base32.DecodingException;


/**
 * et-OTP-v2 GUI main class.
 */
public class GUI implements ActionListener
{
    private static final String HELPURL = "https://disk0401.github.io/et-otp-v2/";
    static final String PROGNAME = "et-OTP-v2";
    /** Sample code that defines the width of the code fields, so they do not grow when filled. */
    static final String CODE_SAMPLE = "000000";

    private static final String CMD_SETTINGS = "settings";
    private static final String CMD_QUIT = "quit";
    private static final String CMD_MANUAL = "manual";
    private static final String CMD_ABOUT = "about";
    private static final String CMD_SAVE = "save";
    private static final String CMD_COPY = "copy";
    private static final String CMD_CALC = "calc";

    private static final String[] LANGUAGES = { Messages.AUTO, Messages.ENGLISH, Messages.JAPANESE };

    private final File configFile;
    private final UiSettings uiSettings;
    /** display locale of the operating system, used for the automatic language setting. */
    private final Locale systemLocale;
    private Messages msg;

    JPasswordField passwordField;
    private JTextField textField;
    private JLabel textLabel;
    private JFrame frame;
    private JDialog settingsDialog;
    private JTabbedPane settingsTabs;
    private JDialog aboutDialog;
    private JTextField settingsCode;
    private JPasswordField settingsPass;
    private JPasswordField settingsConfirm;
    private JLabel settingsFileLabel;
    private JTextArea statusLabel;
    /** width of the status line in pixels, fixed after the main window was laid out. */
    private int statusWidth = 300;
    /** status text when upgrading an et-otp 1.x configuration file failed while unlocking, else empty. */
    private String migrationMessage = "";


    private GUI(File configFile, UiSettings uiSettings, Locale systemLocale)
    {
        this.configFile = configFile;
        this.uiSettings = uiSettings;
        this.systemLocale = systemLocale;
    }


    /**
     * Main method - does not honor any arguments (yet).
     * @param args ignored
     */
    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(GUI::start);
    }


    private static void start()
    {
        useSystemLookAndFeel();
        File config = ConfigLocation.resolve();
        GUI gui = new GUI(config, UiSettings.load(UiSettings.locationFor(config)),
                          Locale.getDefault(Locale.Category.DISPLAY));
        gui.applyLanguage();
        gui.buildMainFrame();
        gui.placeWindow(gui.uiSettings.windowLocation());
        gui.frame.setVisible(true);
        if (!config.exists()) {
            SwingUtilities.invokeLater(gui::showSetupWizard);
        }
    }


    /**
     * Uses the look of the operating system. Its font (for example Yu Gothic UI on Windows) shows
     * Japanese much better than the default cross-platform look, which falls back to MS Gothic.
     */
    private static void useSystemLookAndFeel()
    {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception | LinkageError ignored) {
            // keep the default look
        }
    }


    /** Loads the texts for the configured language; Swing's own dialogs (OK, Cancel) follow it. */
    private void applyLanguage()
    {
        Locale locale = Messages.localeFor(uiSettings.language(), systemLocale);
        msg = new Messages(locale);
        JComponent.setDefaultLocale(locale);
    }


    void buildMainFrame()
    {
        GridBagLayout layout = new GridBagLayout();
        frame = new JFrame(PROGNAME);
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(layout);

        JMenuBar mb = new JMenuBar();
        JMenu m = new JMenu(PROGNAME); m.setMnemonic(KeyEvent.VK_E);
        m.add(menuItem("menu.settings", CMD_SETTINGS));
        m.add(menuItem("menu.quit", CMD_QUIT));
        mb.add(m);
        m = new JMenu(msg.get("menu.help")); m.setMnemonic(KeyEvent.VK_H);
        m.add(menuItem("menu.manual", CMD_MANUAL));
        m.add(menuItem("menu.about", CMD_ABOUT));
        mb.add(m);
        frame.setJMenuBar(mb);

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
        JLabel label = new JLabel(msg.format("main.title", PROGNAME), SwingConstants.CENTER);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 16f));
        frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 2;
        c.gridheight = 1;
        c.gridwidth = 2;
        c.weighty = 0;
        c.weightx = 1;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0,10,5,10);
        label = new JLabel(msg.get("main.password"));
        frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 3;
        c.gridheight = 1;
        c.gridwidth = 2;
        c.weighty = 0;
        c.weightx = 1;
        c.insets = new Insets(0,10,10,10);
        JPasswordField pwField = new JPasswordField("");
        pwField.setActionCommand(CMD_CALC);
        pwField.addActionListener(this);
        pwField.setFocusable(true);
        frame.add(pwField, c);
        passwordField = pwField;

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
        frame.add(button("main.calc", CMD_CALC), c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 2;
        c.gridy = 4;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 1;
        c.ipadx = 0;
        c.insets = new Insets(0,10,10,10);
        c.anchor = GridBagConstraints.CENTER;
        textField = createCodeField();
        frame.add(textField, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 4;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 2;
        c.weighty = 0;
        c.insets = new Insets(0,20,10,0);
        c.anchor = GridBagConstraints.CENTER;
        label = new JLabel(msg.get("main.code"), SwingConstants.RIGHT);
        frame.add(label, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 5;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 2;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(0,20,10,0);
        label = new JLabel(msg.get("main.next"), SwingConstants.RIGHT);
        frame.add(label, c);

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
        frame.add(button("main.copy", CMD_COPY), c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 2;
        c.gridy = 5;
        c.gridheight = 1;
        c.gridwidth = 1;
        c.weightx = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(0,10,10,0);
        textLabel = createNextCodeLabel();
        frame.add(textLabel, c);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 6;
        c.gridheight = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.weighty = 0;
        c.anchor = GridBagConstraints.SOUTH;
        c.insets = new Insets(5,5,5,5);
        statusLabel = createStatusArea();
        frame.add(statusLabel, c);

        frame.addComponentListener(new ShowEvent());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e)
            {
                saveWindowLocation();
            }
        });

        // now we realize the components
        frame.pack();
        // The status line wraps within the width of the other components and has room for two lines.
        // A long message must not widen it: GridBagLayout would then shrink every component.
        statusWidth = Math.max(100, frame.getContentPane().getWidth() - 10);
        statusLabel.setText("X\nX");
        reserveStatusHeight();
        statusLabel.setText("");
        frame.pack();
    }


    /**
     * Creates the status line. A wrapping text area is used instead of an HTML label, because
     * Swing's HTML layout does not break Japanese text correctly.
     */
    private static JTextArea createStatusArea()
    {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(UIManager.getFont("Label.font"));
        return area;
    }


    /** Gives the status line the height of its current text at the fixed width; it never shrinks. */
    private boolean reserveStatusHeight()
    {
        int reserved = statusLabel.isPreferredSizeSet() ? statusLabel.getPreferredSize().height : 0;
        Dimension old = statusLabel.isPreferredSizeSet() ? statusLabel.getPreferredSize() : null;
        // measure the wrapped text at the final width, then restore the laid out size
        Dimension size = statusLabel.getSize();
        statusLabel.setPreferredSize(null);
        statusLabel.setSize(statusWidth, Short.MAX_VALUE);
        int needed = statusLabel.getPreferredSize().height;
        statusLabel.setSize(size);
        if (needed <= reserved) {
            statusLabel.setPreferredSize(old);
            statusLabel.revalidate();
            return false;
        }
        Dimension d = new Dimension(statusWidth, needed);
        statusLabel.setPreferredSize(d);
        statusLabel.setMinimumSize(d);
        return true;
    }


    /** Shows a message in the status line (in red for errors); the full text is also its tool tip. */
    private void setStatus(String text, boolean error)
    {
        statusLabel.setForeground(error ? Color.RED : UIManager.getColor("Label.foreground"));
        boolean empty = text == null || text.trim().isEmpty();
        statusLabel.setText(empty ? "" : text);
        statusLabel.setToolTipText(empty ? null : text);
        // grow the window if the message needs more lines than reserved
        if (!empty && reserveStatusHeight()) {
            frame.pack();
        }
    }


    /**
     * Creates the field for the current code. Its width is fixed by the column count and its
     * height by the font, so it keeps its size when the code is filled in (et-otp issue #1).
     */
    static JTextField createCodeField()
    {
        JTextField text = new JTextField("", CODE_SAMPLE.length() + 1);
        text.setFont(text.getFont().deriveFont(Font.BOLD, 16f));
        text.setEditable(false);
        text.setMinimumSize(text.getPreferredSize());
        return text;
    }


    /** Creates the label for the next code, sized for a code so it does not change size either. */
    static JLabel createNextCodeLabel()
    {
        JLabel label = new JLabel(CODE_SAMPLE, SwingConstants.LEFT);
        label.setPreferredSize(label.getPreferredSize());
        label.setText(" ");
        return label;
    }


    private JMenuItem menuItem(String textKey, String command)
    {
        JMenuItem mi = new JMenuItem(msg.get(textKey));
        mi.setActionCommand(command);
        mi.addActionListener(this);
        return mi;
    }


    private JButton button(String textKey, String command)
    {
        JButton b = new JButton(msg.get(textKey));
        b.setActionCommand(command);
        b.addActionListener(this);
        return b;
    }


    /** Moves the window to the saved position if it is still on a screen, else centers it (issue #4). */
    private void placeWindow(Point saved)
    {
        if (saved != null
            && WindowPlacement.isReachable(new Rectangle(saved, frame.getSize()), WindowPlacement.screens())) {
            frame.setLocation(saved);
        } else {
            frame.setLocationRelativeTo(null);
        }
    }


    private void saveWindowLocation()
    {
        if (frame == null || !frame.isShowing()) {
            return;
        }
        uiSettings.setWindowLocation(frame.getLocation());
        try {
            uiSettings.save();
        } catch (IOException ignored) {
            // the position is a convenience; failing to store it must not prevent quitting
        }
    }


    @Override
    public void actionPerformed(ActionEvent e)
    {
        setStatus(null, false);

        String command = e.getActionCommand();
        if (CMD_QUIT.equals(command))
        {
            saveWindowLocation();
            System.exit(0);
        }
        else if (CMD_MANUAL.equals(command))
        {
            try {
                setStatus(msg.format("status.opening", HELPURL), false);
                Desktop.getDesktop().browse(new URI(HELPURL));
            } catch (Exception ignored) { /* dont care */ }
        }
        else if (CMD_SAVE.equals(command))
        {
            saveFromSettings();
        }
        else if (CMD_ABOUT.equals(command))
        {
            if (aboutDialog == null) {
                aboutDialog = buildAboutDialog();
            }
            aboutDialog.setVisible(true);
        }
        else if (CMD_SETTINGS.equals(command))
        {
            showSettings(0);
        }
        else if (CMD_COPY.equals(command))
        {
            String s = textField.getText();
            StringSelection selection = new StringSelection(s);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, null);
        }
        else if (CMD_CALC.equals(command))
        {
            calculate();
        }
    }


    private void calculate()
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
            setStatus(migrationMessage, false);
        }
        catch (BadPaddingException bp)
        {
            errorText(msg.get("error.badPassword"));
        }
        catch (InvalidKeySpecException | IllegalArgumentException ik)
        {
            errorText(msg.get("error.emptyPassword"));
        }
        catch (FileNotFoundException | NoSuchFileException fn)
        {
            errorText(msg.get("error.notFound"));
        }
        catch (IOException io)
        {
            errorText(msg.format("error.io", io.getMessage()));
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ns)
        {
            errorText(msg.format("error.crypto", ns));
        }
        catch (Exception ex)
        {
            errorText(msg.format("error.other", ex));
        }
    }


    private void errorText(String text)
    {
        setStatus(text, true);
    }


    private void writeSecret(String code, char[] pass) throws DecodingException, IOException, GeneralSecurityException
    {
        byte[] codeBytes = Base32.decode(code.trim());
        try {
            SecretStore.write(configFile, codeBytes, pass);
        } finally {
            Arrays.fill(codeBytes, (byte) 0);
        }
    }


    /**
     * Saves the secret entered in the setup wizard or the settings dialog.
     *
     * @return true if it was saved; on failure an error dialog was shown
     */
    boolean saveSecret(Component parent, String code, char[] pass)
    {
        try {
            writeSecret(code, pass);
            setStatus(msg.get("status.saved"), false);
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent == null ? frame : parent,
                                          msg.format("error.save", ex.getClass().getName() + ": " + ex.getMessage()),
                                          msg.get("error.save.title"), JOptionPane.ERROR_MESSAGE);
            return false;
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
                migrationMessage = msg.format("status.upgradeFailed", e.getMessage());
            }
        }
        return r.secret();
    }


    // --- first start wizard (et-otp issue #3) ---

    private void showSetupWizard()
    {
        SetupWizard wizard = new SetupWizard(frame, msg, configFile, this::saveSecret);
        wizard.setLocationRelativeTo(frame);
        wizard.setVisible(true);
        if (!wizard.isCompleted()) {
            setStatus(msg.get("status.setupLater"), false);
        }
        passwordField.requestFocusInWindow();
    }


    // --- settings dialog ---

    private void showSettings(int tab)
    {
        if (settingsDialog == null) {
            settingsDialog = buildSettingsDialog();
        }
        clearSettingsFields();
        settingsFileLabel.setText(msg.get(configFile.isFile() ? "settings.file.exists" : "settings.file.missing"));
        settingsTabs.setSelectedIndex(tab);
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }


    private void saveFromSettings()
    {
        String code = settingsCode.getText();
        char[] pass = settingsPass.getPassword();
        char[] confirm = settingsConfirm.getPassword();
        try {
            String problem = SecretInput.checkSecret(code);
            if (problem == null) {
                problem = SecretInput.checkPassword(pass, confirm);
            }
            if (problem != null) {
                JOptionPane.showMessageDialog(settingsDialog, msg.get(problem), settingsDialog.getTitle(),
                                              JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (saveSecret(settingsTabs, code, pass)) {
                // hiding clears the fields (see buildSettingsDialog)
                settingsDialog.setVisible(false);
            }
        } finally {
            Arrays.fill(pass, '\0');
            Arrays.fill(confirm, '\0');
        }
    }


    private JDialog buildSettingsDialog()
    {
        JDialog dia = new JDialog(frame, msg.format("settings.title", PROGNAME), true);
        settingsTabs = new JTabbedPane();
        settingsTabs.addTab(msg.get("settings.tab.secret"), buildSecretTab());
        settingsTabs.addTab(msg.get("settings.tab.general"), buildGeneralTab());
        dia.add(settingsTabs);
        packWrapped(dia);
        // do not keep the secret and the passwords in the hidden dialog
        dia.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentHidden(ComponentEvent e)
            {
                clearSettingsFields();
            }
        });
        return dia;
    }


    private void clearSettingsFields()
    {
        settingsCode.setText("");
        settingsPass.setText("");
        settingsConfirm.setText("");
    }


    private JPanel buildSecretTab()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();

        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.gridy = 1;
        c.weightx = 0;
        c.weighty = 0;
        c.insets = new Insets(10, 10, 5, 10);
        panel.add(new JLabel(msg.get("settings.code"), SwingConstants.RIGHT), c);

        c.gridx = 2;
        c.weightx = 1;
        settingsCode = new JTextField(36);
        panel.add(settingsCode, c);

        c.gridx = 1;
        c.gridy = 2;
        c.weightx = 0;
        panel.add(new JLabel(msg.get("settings.password"), SwingConstants.RIGHT), c);

        c.gridx = 2;
        c.weightx = 1;
        settingsPass = new JPasswordField();
        panel.add(settingsPass, c);

        c.gridx = 1;
        c.gridy = 3;
        c.weightx = 0;
        panel.add(new JLabel(msg.get("settings.confirm"), SwingConstants.RIGHT), c);

        c.gridx = 2;
        c.weightx = 1;
        settingsConfirm = new JPasswordField();
        settingsConfirm.setActionCommand(CMD_SAVE);
        settingsConfirm.addActionListener(this);
        panel.add(settingsConfirm, c);

        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = 1;
        c.insets = new Insets(10, 10, 10, 10);
        panel.add(button("settings.save", CMD_SAVE), c);

        c.gridy = 5;
        c.weighty = 0;
        c.insets = new Insets(10, 10, 0, 10);
        // the text is updated when the dialog is shown; set here so the layout reserves its height
        settingsFileLabel = new JLabel(msg.get("settings.file.missing"));
        panel.add(settingsFileLabel, c);

        c.gridy = 6;
        c.insets = new Insets(0, 10, 10, 10);
        panel.add(pathText(configFile), c);
        return panel;
    }


    private JPanel buildGeneralTab()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(10, 10, 10, 10);
        c.gridx = 1;
        c.gridy = 1;
        panel.add(new JLabel(msg.get("settings.language")), c);

        String[] names = new String[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) {
            names[i] = msg.get("language." + LANGUAGES[i]);
        }
        JComboBox<String> language = new JComboBox<>(names);
        language.setSelectedIndex(Arrays.asList(LANGUAGES).indexOf(uiSettings.language()));
        language.addActionListener(e -> changeLanguage(LANGUAGES[language.getSelectedIndex()]));
        c.gridx = 2;
        c.weightx = 1;
        c.weighty = 1;
        panel.add(language, c);
        return panel;
    }


    /** Stores the language and rebuilds all windows in it, then shows the settings again. */
    private void changeLanguage(String language)
    {
        if (language.equals(uiSettings.language())) {
            return;
        }
        uiSettings.setLanguage(language);
        try {
            uiSettings.save();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(settingsDialog, msg.format("error.uiSettings", ex.getMessage()),
                                          settingsDialog.getTitle(), JOptionPane.WARNING_MESSAGE);
        }
        settingsDialog.setVisible(false);
        SwingUtilities.invokeLater(() -> {
            rebuild();
            showSettings(1);
        });
    }


    private void rebuild()
    {
        Point location = frame.getLocation();
        if (settingsDialog != null) {
            clearSettingsFields();
            settingsDialog.dispose();
            settingsDialog = null;
        }
        if (aboutDialog != null) {
            aboutDialog.dispose();
            aboutDialog = null;
        }
        frame.dispose();
        applyLanguage();
        buildMainFrame();
        frame.setLocation(location);
        frame.setVisible(true);
    }


    // --- about dialog (build information: et-otp issue #6) ---

    private JDialog buildAboutDialog()
    {
        JDialog d = new JDialog(frame, msg.format("about.title", PROGNAME), true);
        JTextPane t = new JTextPane();
        t.setContentType("text/html");
        t.setEditable(false);
        // use the font of the look and feel instead of the HTML default (a serif font, Mincho in Japanese)
        t.putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        t.setFont(UIManager.getFont("Label.font"));
        t.setText(aboutHtml(BuildInfo.load()));
        t.setCaretPosition(0);
        d.add(new JScrollPane(t, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
              java.awt.BorderLayout.CENTER);
        // the path is outside the HTML text, where it could not wrap
        JPanel config = new JPanel(new java.awt.BorderLayout(0, 2));
        config.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        config.add(new JLabel(msg.get("about.config")), java.awt.BorderLayout.NORTH);
        JTextArea path = pathText(configFile);
        config.add(path, java.awt.BorderLayout.CENTER);
        d.add(config, java.awt.BorderLayout.SOUTH);
        d.setSize(600, 520);
        // the wrapped path knows its height only after a layout at the final width, which needs a peer
        d.addNotify();
        d.validate();
        path.invalidate();
        d.validate();
        d.setLocationRelativeTo(frame);
        return d;
    }


    String aboutHtml(BuildInfo build)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>").append(html(msg.format("about.heading", PROGNAME))).append("</h1>");
        sb.append("<p>").append(html(msg.get("about.text1"))).append("</p>");
        sb.append("<p>").append(html(msg.get("about.text2"))).append("</p>");
        sb.append("<p>").append(html(msg.get("about.text3"))).append("</p>");
        sb.append("<p>").append(html(msg.get("about.license"))).append("<br>");
        sb.append(html(msg.get("about.homepage"))).append(" <b>").append(html(HELPURL)).append("</b></p>");

        String unknown = msg.get("about.unknown");
        String commit = build.commit() == null ? unknown
                      : build.dirty() ? msg.format("about.build.dirty", build.commit()) : build.commit();
        sb.append("<table>");
        row(sb, "about.version", build.version() == null ? unknown : build.version());
        row(sb, "about.build.commit", commit);
        row(sb, "about.build.branch", or(build.branch(), unknown));
        row(sb, "about.build.describe", or(build.describe(), unknown));
        row(sb, "about.build.time", or(build.time(), unknown));
        row(sb, "about.build.os", or(build.os(), unknown));
        row(sb, "about.build.jdk", or(build.jdk(), unknown));
        sb.append("</table>");
        return sb.toString();
    }


    private void row(StringBuilder sb, String labelKey, String value)
    {
        sb.append("<tr><td>").append(html(msg.get(labelKey))).append("</td><td>").append(html(value))
          .append("</td></tr>");
    }


    private static String or(String value, String fallback)
    {
        return value == null ? fallback : value;
    }


    /**
     * Packs a window that contains wrapped text (see {@link #pathText(File)}). The height of wrapped
     * text depends on the width, which is only known after a layout, so pack until the size is stable.
     */
    static void packWrapped(java.awt.Window w)
    {
        for (int i = 0; i < 4; i++) {
            java.awt.Dimension before = w.getSize();
            w.pack();
            if (w.getSize().equals(before)) {
                return;
            }
        }
    }


    /** Shows a file path that wraps instead of being cut off, and that can be selected and copied. */
    static JTextArea pathText(File file)
    {
        JTextArea area = new JTextArea(file.getAbsolutePath());
        area.setEditable(false);
        area.setLineWrap(true);
        // a small preferred width lets the layout decide the width, then the text wraps to it
        area.setColumns(1);
        area.setOpaque(false);
        area.setBorder(null);
        area.setFont(new JLabel().getFont().deriveFont(Font.BOLD));
        return area;
    }


    static String html(String s)
    {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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
