/*
 * GUI.java - et-otp: GPL Java TOTP soft token by Bernd Eckenfels.
 * Modified 2026 by DISK0401 for et-otp-v2: package renamed, encryption moved to SecretStore, homepage changed,
 * config location, English/Japanese texts, setup wizard, window position, build information,
 * several accounts, QR code registration, Windows user protection, desktop shortcut.
 */
package net.disk0401.etotp;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import javax.crypto.BadPaddingException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.disk0401.etotp.qr.QrReader;


/**
 * et-OTP-v2 GUI main class: the list of registered services and their codes.
 */
public class GUI implements AccountRow.Actions
{
    private static final String HELPURL = "https://disk0401.github.io/et-otp-v2/";
    static final String PROGNAME = "et-OTP-v2";

    private static final String CARD_SETUP = "setup";
    private static final String CARD_UNLOCK = "unlock";
    private static final String CARD_LIST = "list";

    private static final String[] LANGUAGES = { Messages.AUTO, Messages.ENGLISH, Messages.JAPANESE };

    private final File configFile;
    private final UiSettings uiSettings;
    /** display locale of the operating system, used for the automatic language setting. */
    private final Locale systemLocale;
    private Messages msg;

    // the opened account list
    private final List<Account> accounts = new ArrayList<>();
    private AccountStore.Protection protection;
    private SecretStore.PasswordKey key;
    private boolean opened;
    /** the configuration file as last read or written, to detect changes by another program. */
    private byte[] fileContent;
    /** why the account list cannot be changed (see {@link AccountStore.Opened#readOnlyReason}), else null. */
    private String readOnlyReason;
    /** prevents a second instance with the same configuration file; held until the program ends. */
    @SuppressWarnings("unused")
    private static java.nio.channels.FileLock instanceLock;
    /** why the instance lock could not be created; the account list is then opened read-only. */
    private static String lockProblem;

    // main window
    private JFrame frame;
    private final CardLayout cardLayout = new CardLayout();
    private JPanel cards;
    private String card = CARD_UNLOCK;
    private JLabel setupLabel;
    private JButton setupButton;
    private JPasswordField passwordField;
    private JPanel listPanel;
    private final List<AccountRow> rows = new ArrayList<>();
    private final List<JButton> addButtons = new ArrayList<>();
    private JTextArea statusLabel;
    private Timer timer;

    private JDialog settingsDialog;
    private JDialog aboutDialog;


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
        if (!lockInstance(config)) {
            JOptionPane.showMessageDialog(null, gui.msg.format("error.running", config.getAbsolutePath()),
                                          PROGNAME, JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return;
        }
        gui.buildMainFrame();
        gui.placeWindow(gui.uiSettings.windowLocation());
        gui.frame.setVisible(true);
        gui.timer = new Timer(1000, e -> gui.tick());
        gui.timer.start();
        gui.open();
    }


    /**
     * Locks {@code <config>.lock}, so a second instance cannot overwrite the changes of the first with
     * its older account list.
     *
     * @return false if another instance holds the lock; true if locked, or if locking is not possible
     *         (for example a read-only folder): then {@link #lockProblem} is set and nothing can be changed,
     *         as the check before saving alone cannot exclude two instances saving at the same moment
     */
    private static boolean lockInstance(File config)
    {
        try {
            File lockFile = new File(config.getAbsolutePath() + ".lock");
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lockFile.toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            instanceLock = ch.tryLock();
            if (instanceLock == null) {
                ch.close();
                return false;
            }
            return true;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return false;
        } catch (IOException e) {
            lockProblem = e.toString();
            return true;
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


    // --- opening the account list ---

    private void open()
    {
        AccountStore.Kind kind;
        try {
            kind = AccountStore.inspect(configFile);
        } catch (IOException e) {
            showCard(CARD_UNLOCK);
            setStatus(msg.format("error.load", e.getMessage()), true);
            return;
        }
        switch (kind) {
            case MISSING:
                showCard(CARD_SETUP);
                SwingUtilities.invokeLater(this::showSetupWizard);
                break;
            case WINDOWS_USER:
                try {
                    opened(AccountStore.openForWindowsUser(configFile));
                } catch (IOException e) {
                    showCard(CARD_SETUP);
                    setupButton.setVisible(false);
                    setupLabel.setText(" ");
                    setStatus(msg.format("error.load", e.getMessage()), true);
                }
                break;
            default:
                showCard(CARD_UNLOCK);
                break;
        }
    }


    private void opened(AccountStore.Opened o)
    {
        accounts.clear();
        accounts.addAll(o.accounts);
        protection = o.protection;
        if (key != null && key != o.key) {
            key.destroy();
        }
        key = o.key;
        fileContent = o.source;
        readOnlyReason = o.readOnlyReason != null ? o.readOnlyReason : lockProblem;
        opened = true;
        rebuildList();
        showCard(CARD_LIST);
        setBusy(false);
        if (readOnlyReason != null) {
            setStatus(msg.format("status.readOnly", readOnlyReason), true);
        }
    }


    private void unlock()
    {
        char[] pass = passwordField.getPassword();
        frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            AccountStore.Opened o = AccountStore.openWithPassword(configFile, pass);
            passwordField.setText("");
            setStatus(null, false);
            // after clearing the status: opened() may show a warning there
            opened(o);
        }
        catch (BadPaddingException bp)
        {
            setStatus(msg.get("error.badPassword"), true);
        }
        catch (InvalidKeySpecException | IllegalArgumentException ik)
        {
            setStatus(msg.get("error.emptyPassword"), true);
        }
        catch (FileNotFoundException | NoSuchFileException fn)
        {
            setStatus(msg.format("error.load", configFile.getAbsolutePath()), true);
        }
        catch (IOException io)
        {
            setStatus(msg.format("error.io", io.getMessage()), true);
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ns)
        {
            setStatus(msg.format("error.crypto", ns), true);
        }
        catch (Exception ex)
        {
            setStatus(msg.format("error.other", ex), true);
        }
        finally
        {
            Arrays.fill(pass, '\0');
            frame.setCursor(Cursor.getDefaultCursor());
        }
    }


    /** Saves the account list. @return false if it failed (an error dialog was shown) */
    private boolean persist()
    {
        if (readOnlyReason != null) {
            JOptionPane.showMessageDialog(frame, msg.format("status.readOnly", readOnlyReason),
                                          msg.get("error.save.title"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
        try {
            fileContent = AccountStore.save(configFile, accounts, protection, key, fileContent);
            return true;
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            JOptionPane.showMessageDialog(frame, msg.format("error.save", ex.getClass().getName() + ": " + ex.getMessage()),
                                          msg.get("error.save.title"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }


    // --- first start wizard (et-otp issue #3) ---

    private void showSetupWizard()
    {
        if (lockProblem != null) {
            setStatus(msg.format("status.readOnly", lockProblem), true);
            return;
        }
        SetupWizard wizard = new SetupWizard(frame, msg, configFile);
        wizard.setLocationRelativeTo(frame);
        wizard.setVisible(true);
        SetupWizard.Result r = wizard.result();
        if (r == null) {
            showCard(CARD_SETUP);
            setStatus(msg.get("status.setupLater"), false);
            return;
        }
        SecretStore.PasswordKey newKey = null;
        try {
            if (r.protection == AccountStore.Protection.PASSWORD) {
                newKey = SecretStore.PasswordKey.create(r.password, SecretStore.DEFAULT_ITERATIONS);
            }
        } catch (GeneralSecurityException e) {
            setStatus(msg.format("error.crypto", e), true);
            return;
        } finally {
            if (r.password != null) {
                Arrays.fill(r.password, '\0');
            }
        }
        accounts.clear();
        protection = r.protection;
        if (key != null) {
            key.destroy();
        }
        key = newKey;
        fileContent = null; // a new file
        readOnlyReason = null;
        if (!persist()) {
            if (key != null) {
                key.destroy();
                key = null;
            }
            showCard(CARD_SETUP);
            return;
        }
        opened = true;
        rebuildList();
        showCard(CARD_LIST);
        if (r.shortcut) {
            createShortcut();
        }
    }


    // --- main window ---

    void buildMainFrame()
    {
        frame = new JFrame(PROGNAME);
        frame.setIconImages(AppIcon.images());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        JMenuBar mb = new JMenuBar();
        JMenu m = new JMenu(PROGNAME);
        m.setMnemonic(KeyEvent.VK_E);
        m.add(menuItem("menu.settings", e -> showSettings(opened ? 0 : 1)));
        if (DesktopShortcut.isSupported()) {
            m.add(menuItem("menu.shortcut", e -> createShortcut()));
        }
        m.addSeparator();
        m.add(menuItem("menu.quit", e -> quit()));
        mb.add(m);
        m = new JMenu(msg.get("menu.help"));
        m.setMnemonic(KeyEvent.VK_H);
        m.add(menuItem("menu.manual", e -> openManual()));
        m.add(menuItem("menu.about", e -> showAbout()));
        mb.add(m);
        frame.setJMenuBar(mb);

        cards = new JPanel(cardLayout);
        cards.add(buildSetupCard(), CARD_SETUP);
        cards.add(buildUnlockCard(), CARD_UNLOCK);
        cards.add(buildListCard(), CARD_LIST);

        statusLabel = createStatusArea();
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));

        JPanel content = new JPanel(new BorderLayout());
        content.add(cards, BorderLayout.CENTER);
        content.add(statusLabel, BorderLayout.SOUTH);
        frame.setContentPane(content);

        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent evt)
            {
                // we can only request focus when everything is shown
                frame.toFront();
                if (CARD_UNLOCK.equals(card)) {
                    passwordField.requestFocusInWindow();
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e)
            {
                saveWindowLocation();
            }
        });

        cardLayout.show(cards, card);
        frame.pack();
        frame.setMinimumSize(frame.getSize());
    }


    private JPanel buildSetupCard()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(10, 20, 10, 20);
        setupLabel = new JLabel(msg.get("setup.text"));
        panel.add(setupLabel, c);
        c.gridy = 1;
        setupButton = new JButton(msg.get("setup.button"));
        setupButton.addActionListener(e -> showSetupWizard());
        panel.add(setupButton, c);
        return panel;
    }


    private JPanel buildUnlockCard()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(20, 20, 10, 20);
        JLabel title = new JLabel(PROGNAME);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(title, c);

        c.gridy = 1;
        c.insets = new Insets(0, 20, 10, 20);
        panel.add(new JLabel(msg.get("unlock.text")), c);

        c.gridy = 2;
        c.gridwidth = 1;
        c.insets = new Insets(0, 20, 20, 5);
        panel.add(new JLabel(msg.get("unlock.password")), c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 20, 20);
        JPanel field = new JPanel(new BorderLayout(8, 0));
        passwordField = new JPasswordField(16);
        passwordField.addActionListener(e -> unlock());
        field.add(passwordField, BorderLayout.CENTER);
        JButton unlock = new JButton(msg.get("unlock.button"));
        unlock.addActionListener(e -> unlock());
        field.add(unlock, BorderLayout.EAST);
        panel.add(field, c);
        return panel;
    }


    private JPanel buildListCard()
    {
        JPanel panel = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        addButtons.clear();
        addButtons.add(button("add.screen", e -> readScreen()));
        addButtons.add(button("add.file", e -> readFile()));
        addButtons.add(button("add.manual", e -> addManually()));
        for (JButton b : addButtons) {
            buttons.add(b);
        }
        panel.add(buttons, BorderLayout.NORTH);

        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        JPanel holder = new WidthTrackingPanel();
        holder.add(listPanel, BorderLayout.NORTH);
        holder.setBackground(UIManager.getColor("List.background"));
        listPanel.setBackground(UIManager.getColor("List.background"));
        JScrollPane scroll = new JScrollPane(holder, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                             JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(listPanel.getBackground());
        scroll.setPreferredSize(new Dimension(Math.max(460, buttons.getPreferredSize().width), 300));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }


    private JMenuItem menuItem(String textKey, java.awt.event.ActionListener action)
    {
        JMenuItem mi = new JMenuItem(msg.get(textKey));
        mi.addActionListener(action);
        return mi;
    }


    private JButton button(String textKey, java.awt.event.ActionListener action)
    {
        JButton b = new JButton(msg.get(textKey));
        b.addActionListener(action);
        return b;
    }


    private void showCard(String name)
    {
        card = name;
        cardLayout.show(cards, name);
        if (CARD_UNLOCK.equals(name)) {
            SwingUtilities.invokeLater(() -> passwordField.requestFocusInWindow());
        }
    }


    private void rebuildList()
    {
        listPanel.removeAll();
        rows.clear();
        if (accounts.isEmpty()) {
            // a text area wraps Japanese correctly, unlike an HTML label
            JTextArea empty = createStatusArea();
            empty.setText(msg.get("list.empty"));
            empty.setBorder(BorderFactory.createEmptyBorder(20, 15, 20, 15));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            listPanel.add(empty);
        }
        for (Account a : accounts) {
            AccountRow row = new AccountRow(a, msg, this);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setBackground(listPanel.getBackground());
            rows.add(row);
            listPanel.add(row);
        }
        listPanel.revalidate();
        listPanel.repaint();
    }


    private void tick()
    {
        long now = System.currentTimeMillis() / 1000;
        for (AccountRow row : rows) {
            row.update(now);
        }
    }


    private void setBusy(boolean busy)
    {
        for (JButton b : addButtons) {
            b.setEnabled(!busy && readOnlyReason == null);
        }
        frame.setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }


    // --- status line ---

    /**
     * Creates the status line. A wrapping text area is used instead of an HTML label, because
     * Swing's HTML layout does not break Japanese text correctly.
     */
    private static JTextArea createStatusArea()
    {
        JTextArea area = new JTextArea(" ");
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(UIManager.getFont("Label.font"));
        area.setColumns(1);
        return area;
    }


    /** Shows a message in the status line (in red for errors); the full text is also its tool tip. */
    private void setStatus(String text, boolean error)
    {
        statusLabel.setForeground(error ? Color.RED : UIManager.getColor("Label.foreground"));
        boolean empty = text == null || text.trim().isEmpty();
        statusLabel.setText(empty ? " " : text);
        statusLabel.setToolTipText(empty ? null : text);
        // a longer message gets more lines; the window grows in height, never in width
        int width = frame.getContentPane().getWidth();
        if (width > 0) {
            Dimension size = statusLabel.getSize();
            statusLabel.setPreferredSize(null);
            statusLabel.setSize(width, Short.MAX_VALUE);
            int needed = statusLabel.getPreferredSize().height;
            statusLabel.setSize(size);
            statusLabel.setPreferredSize(new Dimension(1, needed));
            statusLabel.revalidate();
            Dimension frameSize = frame.getSize();
            Dimension pref = frame.getPreferredSize();
            if (pref.height > frameSize.height) {
                frame.setSize(frameSize.width, pref.height);
            }
        }
    }


    // --- AccountRow.Actions ---

    @Override
    public void copy(Account account, String code)
    {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);
        setStatus(msg.format("status.copied", displayName(account)), false);
    }


    @Override
    public void rename(Account account)
    {
        Object name = JOptionPane.showInputDialog(frame, msg.get("rename.prompt"), msg.get("row.rename"),
                                                  JOptionPane.PLAIN_MESSAGE, null, null, account.name());
        if (name == null) {
            return;
        }
        String old = account.name();
        account.setName(name.toString());
        if (!persist()) {
            account.setName(old);
        }
        for (AccountRow row : rows) {
            row.refreshName();
        }
    }


    @Override
    public void delete(Account account)
    {
        int answer = JOptionPane.showConfirmDialog(frame, msg.format("delete.confirm", displayName(account)),
                                                   msg.get("row.delete"), JOptionPane.OK_CANCEL_OPTION,
                                                   JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        int index = accounts.indexOf(account);
        accounts.remove(account);
        if (!persist()) {
            accounts.add(index, account);
            return;
        }
        rebuildList();
        setStatus(msg.format("status.deleted", displayName(account)), false);
    }


    private String displayName(Account a)
    {
        return a.name().isEmpty() ? msg.get("row.unnamed") : noHtml(a.name());
    }


    /**
     * Swing renders a text that starts with "&lt;html" as HTML. Names from QR codes and window titles
     * come from outside, so they get a leading space to be shown as plain text.
     */
    static String noHtml(String text)
    {
        return text.trim().toLowerCase(Locale.ROOT).startsWith("<html") ? " " + text : text;
    }


    /** @return "1: a", "2: b", ...: unique choices, also for equal names */
    private static String[] numbered(List<String> names)
    {
        String[] result = new String[names.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = (i + 1) + ": " + noHtml(names.get(i));
        }
        return result;
    }


    // --- registering services ---

    /** Reads QR codes on the screens; this window is hidden while the screens are captured. */
    private void readScreen()
    {
        setBusy(true);
        setStatus(msg.get("status.searching"), false);
        frame.setVisible(false);
        new SwingWorker<List<String>, int[]>() {
            @Override
            protected List<String> doInBackground() throws Exception
            {
                Thread.sleep(400); // until this window has disappeared
                List<String> texts = new ArrayList<>();
                for (ScreenCapture.Target t : ScreenCapture.displays()) {
                    texts.addAll(QrSearch.search(t));
                }
                publish(new int[] {0, 0});
                if (texts.stream().noneMatch(QrSearch::isOtpauth)) {
                    // windows behind other windows; this window is excluded, so it can be shown again
                    List<ScreenCapture.Target> windows = ScreenCapture.windows();
                    for (int i = 0; i < windows.size(); i++) {
                        publish(new int[] {i + 1, windows.size()});
                        for (String s : QrSearch.search(windows.get(i))) {
                            if (!texts.contains(s)) {
                                texts.add(s);
                            }
                        }
                        if (texts.stream().anyMatch(QrSearch::isOtpauth)) {
                            break;
                        }
                    }
                }
                return texts;
            }

            @Override
            protected void process(List<int[]> chunks)
            {
                frame.setVisible(true);
                int[] last = chunks.get(chunks.size() - 1);
                if (last[1] > 0) {
                    setStatus(msg.format("status.searchingWindows", last[0], last[1]), false);
                }
            }

            @Override
            protected void done()
            {
                frame.setVisible(true);
                setBusy(false);
                setStatus(null, false);
                handleTexts(result(this), true);
            }
        }.execute();
    }


    /** Brings the chosen window to the front and reads the screens and that window again. */
    private void readChosenWindow()
    {
        List<ScreenCapture.Target> windows = ScreenCapture.windows();
        if (windows.isEmpty()) {
            return;
        }
        String[] titles = numbered(windows.stream().map(t -> t.title).collect(java.util.stream.Collectors.toList()));
        Object chosen = JOptionPane.showInputDialog(frame, msg.get("qr.windowPrompt"), msg.get("qr.title"),
                                                    JOptionPane.QUESTION_MESSAGE, null, titles, titles[0]);
        if (chosen == null) {
            return;
        }
        ScreenCapture.Target target = windows.get(Arrays.asList(titles).indexOf(chosen));
        setBusy(true);
        setStatus(msg.get("status.searching"), false);
        frame.setVisible(false);
        ScreenCapture.bringToFront(target);
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception
            {
                Thread.sleep(800); // until the window is restored and drawn
                List<String> texts = new ArrayList<>(QrSearch.search(target));
                for (ScreenCapture.Target t : ScreenCapture.displays()) {
                    texts.addAll(QrSearch.search(t));
                }
                return texts;
            }

            @Override
            protected void done()
            {
                frame.setVisible(true);
                frame.toFront();
                setBusy(false);
                setStatus(null, false);
                handleTexts(result(this), false);
            }
        }.execute();
    }


    private void readFile()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter(msg.get("qr.fileFilter"), "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        setBusy(true);
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception
            {
                return QrReader.read(readImage(file));
            }

            @Override
            protected void done()
            {
                setBusy(false);
                try {
                    handleTexts(get(), false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    JOptionPane.showMessageDialog(frame, msg.format("qr.fileError", e.getCause().getMessage()),
                                                  msg.get("qr.title"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }


    /** Reads an image file, checking its size before the pixels are decoded. */
    private static BufferedImage readImage(File file) throws IOException
    {
        try (javax.imageio.stream.ImageInputStream in = ImageIO.createImageInputStream(file)) {
            java.util.Iterator<javax.imageio.ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException(file.getName());
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > QrReader.MAX_PIXELS) {
                    throw new IOException(file.getName() + " (" + reader.getWidth(0) + " x " + reader.getHeight(0) + ")");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }


    private static List<String> result(SwingWorker<List<String>, ?> worker)
    {
        try {
            return worker.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } catch (ExecutionException e) {
            return new ArrayList<>();
        }
    }


    /** Registers a service from the QR code texts found, asking the user where needed. */
    private void handleTexts(List<String> texts, boolean offerWindows)
    {
        List<Account> found = new ArrayList<>();
        String problem = null;
        for (String text : texts) {
            if (!QrSearch.isOtpauth(text)) {
                continue;
            }
            try {
                found.add(Account.fromUri(text));
            } catch (Account.InvalidException e) {
                problem = invalidText(e);
            }
        }
        if (found.isEmpty()) {
            String message = problem != null ? problem
                           : texts.isEmpty() ? msg.get("qr.notFound") : msg.get("qr.otherCodes");
            if (problem == null && offerWindows && ScreenCapture.windowsSupported()) {
                Object[] options = {msg.get("qr.chooseWindow"), UIManager.getString("OptionPane.okButtonText", msg.locale())};
                int answer = JOptionPane.showOptionDialog(frame, message, msg.get("qr.title"), JOptionPane.DEFAULT_OPTION,
                                                          JOptionPane.INFORMATION_MESSAGE, null, options, options[1]);
                if (answer == 0) {
                    readChosenWindow();
                }
            } else {
                JOptionPane.showMessageDialog(frame, message, msg.get("qr.title"),
                                              problem != null ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
            }
            return;
        }
        Account account = found.get(0);
        if (found.size() > 1) {
            String[] names = numbered(found.stream().map(this::displayName).collect(java.util.stream.Collectors.toList()));
            Object chosen = JOptionPane.showInputDialog(frame, msg.get("qr.choose"), msg.get("qr.title"),
                                                        JOptionPane.QUESTION_MESSAGE, null, names, names[0]);
            if (chosen == null) {
                return;
            }
            account = found.get(Arrays.asList(names).indexOf(chosen));
        }
        confirmAndAdd(account, account.name());
    }


    /** Asks for the name (and, for manual entry, the key) and registers the service. */
    private void addManually()
    {
        String name = "";
        String secret = "";
        while (true) {
            JTextField nameField = new JTextField(name, 24);
            JTextField secretField = new JTextField(secret, 24);
            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(4, 0, 4, 8);
            form.add(new JLabel(msg.get("add.name")), c);
            c.gridx = 1;
            form.add(nameField, c);
            c.gridx = 0;
            c.gridy = 1;
            form.add(new JLabel(msg.get("add.secret")), c);
            c.gridx = 1;
            form.add(secretField, c);
            SwingUtilities.invokeLater(nameField::requestFocusInWindow);
            if (JOptionPane.showConfirmDialog(frame, form, msg.get("add.title"), JOptionPane.OK_CANCEL_OPTION,
                                              JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            name = nameField.getText();
            secret = secretField.getText();
            try {
                add(new Account(name, secret));
                return;
            } catch (Account.InvalidException e) {
                JOptionPane.showMessageDialog(frame, invalidText(e), msg.get("add.title"), JOptionPane.WARNING_MESSAGE);
            }
        }
    }


    private void confirmAndAdd(Account account, String name)
    {
        JTextField nameField = new JTextField(name, 24);
        JPanel form = new JPanel(new BorderLayout(8, 0));
        form.add(new JLabel(msg.get("add.name")), BorderLayout.WEST);
        form.add(nameField, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(frame, form, msg.get("add.title"), JOptionPane.OK_CANCEL_OPTION,
                                          JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        account.setName(nameField.getText());
        add(account);
    }


    private void add(Account account)
    {
        for (Account a : accounts) {
            if (a.secret().equals(account.secret())) {
                int answer = JOptionPane.showConfirmDialog(frame, msg.format("add.duplicate", displayName(a)),
                                                           msg.get("add.title"), JOptionPane.YES_NO_OPTION,
                                                           JOptionPane.QUESTION_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
                break;
            }
        }
        accounts.add(account);
        if (!persist()) {
            accounts.remove(account);
            return;
        }
        rebuildList();
        setStatus(msg.format("status.added", displayName(account)), false);
    }


    private String invalidText(Account.InvalidException e)
    {
        return e.detail == null ? msg.get(e.getMessage()) : msg.format(e.getMessage(), e.detail);
    }


    // --- shortcut, manual, quit ---

    private void createShortcut()
    {
        setBusy(true);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception
            {
                return DesktopShortcut.create(configFile, uiSettings.file().getAbsoluteFile().getParentFile());
            }

            @Override
            protected void done()
            {
                setBusy(false);
                try {
                    setStatus(msg.format("status.shortcut", get()), false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    setStatus(msg.format("error.shortcut", e.getCause().getMessage()), true);
                }
            }
        }.execute();
    }


    private void openManual()
    {
        try {
            setStatus(msg.format("status.opening", HELPURL), false);
            Desktop.getDesktop().browse(new URI(HELPURL));
        } catch (Exception ignored) { /* dont care */ }
    }


    private void quit()
    {
        saveWindowLocation();
        System.exit(0);
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


    // --- settings dialog ---

    private void showSettings(int tab)
    {
        if (settingsDialog != null) {
            settingsDialog.dispose();
        }
        settingsDialog = buildSettingsDialog();
        JTabbedPane tabs = (JTabbedPane) settingsDialog.getContentPane().getComponent(0);
        tabs.setSelectedIndex(Math.min(tab, tabs.getTabCount() - 1));
        settingsDialog.setLocationRelativeTo(frame);
        settingsDialog.setVisible(true);
    }


    private JDialog buildSettingsDialog()
    {
        JDialog dia = new JDialog(frame, msg.format("settings.title", PROGNAME), true);
        JTabbedPane tabs = new JTabbedPane();
        JPanel protectionTab = buildProtectionTab(dia);
        tabs.addTab(msg.get("settings.tab.protection"), protectionTab);
        tabs.setEnabledAt(0, opened);
        tabs.addTab(msg.get("settings.tab.general"), buildGeneralTab());
        dia.getContentPane().add(tabs);
        packWrapped(dia);
        return dia;
    }


    private JPanel buildProtectionTab(JDialog dia)
    {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 0);

        JRadioButton windowsUser = new JRadioButton(msg.get("protection.windows"),
                                                    protection == AccountStore.Protection.WINDOWS_USER);
        JRadioButton password = new JRadioButton(msg.get("protection.password"),
                                                 protection != AccountStore.Protection.WINDOWS_USER);
        ButtonGroup group = new ButtonGroup();
        group.add(windowsUser);
        group.add(password);
        JPasswordField pass = new JPasswordField(20);
        JPasswordField confirm = new JPasswordField(20);
        JPanel fields = SetupWizard.passwordFields(msg, pass, confirm);
        fields.setBorder(BorderFactory.createEmptyBorder(0, 24, 0, 0));

        if (Dpapi.isAvailable()) {
            panel.add(windowsUser, c);
            panel.add(SetupWizard.note(msg.get("protection.windows.note")), c);
        }
        panel.add(password, c);
        panel.add(SetupWizard.note(msg.get("protection.password.note")), c);
        panel.add(fields, c);
        Runnable enable = () -> {
            pass.setEnabled(password.isSelected());
            confirm.setEnabled(password.isSelected());
        };
        windowsUser.addActionListener(e -> enable.run());
        password.addActionListener(e -> enable.run());
        enable.run();

        JButton apply = new JButton(msg.get("protection.apply"));
        apply.addActionListener(e -> {
            char[] p = pass.getPassword();
            char[] cf = confirm.getPassword();
            try {
                if (applyProtection(windowsUser.isSelected(), p, cf)) {
                    dia.setVisible(false);
                }
            } finally {
                Arrays.fill(p, '\0');
                Arrays.fill(cf, '\0');
                pass.setText("");
                confirm.setText("");
            }
        });
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(10, 0, 10, 0);
        panel.add(apply, c);

        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(10, 0, 0, 0);
        panel.add(new JLabel(msg.get("settings.file")), c);
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(pathText(configFile), c);
        c.weighty = 1;
        panel.add(new JPanel(), c);
        return panel;
    }


    /** @return true if the protection was changed (or nothing was to change) */
    private boolean applyProtection(boolean toWindowsUser, char[] pass, char[] confirm)
    {
        AccountStore.Protection oldProtection = protection;
        SecretStore.PasswordKey oldKey = key;
        if (toWindowsUser) {
            if (protection == AccountStore.Protection.WINDOWS_USER) {
                return true;
            }
            protection = AccountStore.Protection.WINDOWS_USER;
            key = null;
        } else {
            if (protection == AccountStore.Protection.PASSWORD && pass.length == 0 && confirm.length == 0) {
                return true; // keep the current password
            }
            String problem = SecretInput.checkPassword(pass, confirm);
            if (problem != null) {
                JOptionPane.showMessageDialog(settingsDialog, msg.get(problem), msg.get("settings.tab.protection"),
                                              JOptionPane.WARNING_MESSAGE);
                return false;
            }
            try {
                key = SecretStore.PasswordKey.create(pass, SecretStore.DEFAULT_ITERATIONS);
            } catch (GeneralSecurityException e) {
                setStatus(msg.format("error.crypto", e), true);
                return false;
            }
            protection = AccountStore.Protection.PASSWORD;
        }
        if (!persist()) {
            if (key != null && key != oldKey) {
                key.destroy();
            }
            protection = oldProtection;
            key = oldKey;
            return false;
        }
        if (oldKey != null && oldKey != key) {
            oldKey.destroy();
        }
        setStatus(msg.get("status.protection"), false);
        return true;
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
        rebuildList();
        showCard(card);
        frame.setLocation(location);
        frame.setVisible(true);
    }


    // --- about dialog (build information: et-otp issue #6) ---

    private void showAbout()
    {
        if (aboutDialog == null) {
            aboutDialog = buildAboutDialog();
        }
        aboutDialog.setVisible(true);
    }


    private JDialog buildAboutDialog()
    {
        JDialog d = new JDialog(frame, msg.format("about.title", PROGNAME), true);
        JTextPane t = new JTextPane();
        t.setContentType("text/html");
        t.setEditable(false);
        // use the font of the look and feel instead of the HTML default (a serif font, Mincho in Japanese)
        t.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        t.setFont(UIManager.getFont("Label.font"));
        t.setText(aboutHtml(BuildInfo.load()));
        t.setCaretPosition(0);
        d.add(new JScrollPane(t, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
              BorderLayout.CENTER);
        // the path is outside the HTML text, where it could not wrap
        JPanel config = new JPanel(new BorderLayout(0, 2));
        config.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        config.add(new JLabel(msg.get("about.config")), BorderLayout.NORTH);
        JTextArea path = pathText(configFile);
        config.add(path, BorderLayout.CENTER);
        d.add(config, BorderLayout.SOUTH);
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


    // --- helpers ---

    /** A panel that takes the width of the scroll pane, so the list wraps instead of scrolling sideways. */
    private static final class WidthTrackingPanel extends JPanel implements javax.swing.Scrollable
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
            return 16;
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


    /**
     * Packs a window that contains wrapped text (see {@link #pathText(File)}). The height of wrapped
     * text depends on the width, which is only known after a layout, so pack until the size is stable.
     */
    static void packWrapped(Window w)
    {
        for (int i = 0; i < 4; i++) {
            Dimension before = w.getSize();
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
}
