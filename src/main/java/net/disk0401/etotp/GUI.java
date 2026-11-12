/*
 * GUI.java - et-otp: GPL Java TOTP soft token by Bernd Eckenfels.
 * Modified 2026 by DISK0401 for et-otp-v2: package renamed, encryption moved to SecretStore, homepage changed,
 * config location, English/Japanese texts, setup wizard, window position, build information,
 * several accounts, QR code registration, Windows user protection, desktop shortcut, command line codes.
 */
package net.disk0401.etotp;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.AWTEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
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
import javax.swing.Box;
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
import javax.swing.JCheckBox;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;

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

    /** the configuration file; it changes when the user chooses another location. */
    private File configFile;
    /** the configuration file at the start, before the setup wizard chose another location. */
    private final File startFile;
    /**
     * set when the setup wizard chose an existing file: its location is only remembered once it was opened,
     * so a file that cannot be opened is not used again at the next start.
     */
    private File pendingLocationFrom;
    private UiSettings uiSettings;
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
    private static FileLock instanceLock;
    /** why the instance lock could not be created; the account list is then opened read-only. */
    private static String lockProblem;
    /** the folder of the configuration file does not exist (for example a removed USB drive): nothing is locked. */
    private static boolean folderMissing;
    /** creates and updates desktop shortcuts one after the other, so an older update cannot win. */
    private static final java.util.concurrent.ExecutorService SHORTCUTS =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "et-otp-shortcut");
            t.setDaemon(true);
            return t;
        });

    // main window
    private JFrame frame;
    private final CardLayout cardLayout = new CardLayout();
    private JPanel cards;
    private String card = CARD_UNLOCK;
    private JLabel setupLabel;
    private JButton setupButton;
    private JPasswordField passwordField;
    /** on the unlock card: back to the setup, if the setup wizard chose the file. */
    private JButton otherFileButton;
    private JPanel listPanel;
    private final List<AccountRow> rows = new ArrayList<>();
    private final List<JButton> addButtons = new ArrayList<>();
    /** filters the rows by name (et-otp-v2 issue #20). */
    private JTextField searchField;
    /** shown instead of the rows when the filter hides all of them. */
    private JTextArea noMatchText;
    /** the index in {@link #rows} chosen with the arrow keys in the search field, or -1. */
    private int selectedRow = -1;
    private boolean typingStartsSearch;
    private JTextArea statusLabel;
    private Timer timer;
    /** locking after idle time and sleep (issue #22). */
    private final AutoLock autoLock = new AutoLock(System.currentTimeMillis());
    private final ClipboardGuard clipboard = new ClipboardGuard(Toolkit.getDefaultToolkit().getSystemClipboard());
    /** a lock waits for the open dialogs to close, so their changes are saved first. */
    private boolean lockPending;
    /** true from a lock until the list is opened again. */
    private boolean locked;
    private JMenuItem lockItem;

    private JDialog settingsDialog;
    private JDialog aboutDialog;
    /** the configuration file in the settings dialog, updated when the file was moved. */
    private JTextArea settingsPath;


    private GUI(File configFile, UiSettings uiSettings, Locale systemLocale)
    {
        this.configFile = configFile;
        this.startFile = configFile;
        this.uiSettings = uiSettings;
        this.systemLocale = systemLocale;
    }


    /**
     * Main method: opens the window, or with options (for example {@code --code <name>}) runs
     * {@link CodeCommand} without a window.
     * @param args the options of {@link CodeCommand}, or none
     */
    public static void main(String[] args)
    {
        if (CodeCommand.handles(args)) {
            System.exit(CodeCommand.main(args));
            return;
        }
        SwingUtilities.invokeLater(GUI::start);
    }


    /** @return the started window, or null if another instance runs (package-private for tests) */
    static GUI start()
    {
        useSystemLookAndFeel();
        File config = ConfigLocation.resolve();
        GUI gui = new GUI(config, UiSettings.load(UiSettings.locationFor(config)),
                          Locale.getDefault(Locale.Category.DISPLAY));
        gui.applyLanguage();
        File folder = config.getAbsoluteFile().getParentFile();
        folderMissing = folder == null || !folder.isDirectory();
        if (!folderMissing && !lockInstance(config)) {
            JOptionPane.showMessageDialog(null, gui.msg.format("error.running", config.getAbsolutePath()),
                                          PROGNAME, JOptionPane.INFORMATION_MESSAGE);
            System.exit(0);
            return null;
        }
        gui.buildMainFrame();
        gui.placeWindow(gui.uiSettings.windowLocation());
        gui.frame.setVisible(true);
        gui.timer = new Timer(1000, e -> gui.tick());
        gui.timer.start();
        // every key and mouse input in a window of the program counts as use
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> gui.autoLock.input(System.currentTimeMillis()),
            AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK
            | AWTEvent.MOUSE_WHEEL_EVENT_MASK);
        SessionWatcher.start(() -> SwingUtilities.invokeLater(() -> {
            if (gui.uiSettings.lockOnSystemLock()) {
                gui.requestLock();
            }
        }));
        gui.open();
        return gui;
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
            instanceLock = tryLock(config);
            return instanceLock != null;
        } catch (IOException e) {
            lockProblem = e.toString();
            return true;
        }
    }


    /** @return the lock of {@code <config>.lock}, or null if another instance holds it */
    private static FileLock tryLock(File config) throws IOException
    {
        FileChannel ch = FileChannel.open(lockFile(config).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = ch.tryLock();
            if (lock == null) {
                ch.close();
            }
            return lock;
        } catch (OverlappingFileLockException e) {
            ch.close();
            return null;
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }


    private static File lockFile(File config)
    {
        return new File(config.getAbsolutePath() + ".lock");
    }


    /**
     * Releases a lock. The lock file is kept: deleting it could let two other instances lock different files
     * of the same name (one already opened, one new).
     */
    private static void releaseLock(FileLock lock)
    {
        if (lock == null) {
            return;
        }
        try {
            lock.channel().close();
        } catch (IOException ignored) {
            // nothing more to release
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
            openFailed(e);
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
                    openFailed(e);
                }
                break;
            default:
                otherFileButton.setVisible(pendingLocationFrom != null);
                showCard(CARD_UNLOCK);
                break;
        }
    }


    /**
     * The file cannot be opened. If the setup wizard chose it, the setup can be run again to choose another
     * file; else the error is only shown (the file must not be replaced by a new one).
     */
    private void openFailed(IOException e)
    {
        showCard(CARD_SETUP);
        boolean chosen = pendingLocationFrom != null;
        setupButton.setVisible(chosen);
        setupLabel.setText(chosen ? msg.get("setup.text") : " ");
        setStatus(msg.format("error.load", e.getMessage()), true);
    }


    private void opened(AccountStore.Opened o)
    {
        if (pendingLocationFrom != null) {
            File from = pendingLocationFrom;
            pendingLocationFrom = null;
            otherFileButton.setVisible(false);
            locationChosen(from, false);
        }
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
        lockPending = false;
        locked = false;
        autoLock.reset(System.currentTimeMillis());
        updateLockItem();
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
        if (locked || lockPending) {
            // a change confirmed after the lock was requested may not come from the user: not saved,
            // the caller restores the previous state
            setStatus(msg.get("status.lockedNotSaved"), true);
            return false;
        }
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
        // without a lock (lockProblem or folderMissing) the file chosen in the wizard is locked before it is used,
        // see the switchTo call below: so another location can be chosen if this one cannot be written
        if (folderMissing) {
            JOptionPane.showMessageDialog(frame, msg.format("location.folderMissing", configFile.getAbsolutePath()),
                                          PROGNAME, JOptionPane.WARNING_MESSAGE);
        }
        SetupWizard wizard = new SetupWizard(frame, msg, pendingLocationFrom != null ? pendingLocationFrom : configFile);
        wizard.setLocationRelativeTo(frame);
        wizard.setVisible(true);
        SetupWizard.Result r = wizard.result();
        if (r == null) {
            if (CARD_UNLOCK.equals(card)) {
                return; // started from the unlock card ("choose another file"): keep unlocking the chosen file
            }
            showCard(CARD_SETUP);
            setStatus(msg.get("status.setupLater"), false);
            return;
        }
        // compared with the file of the start: an earlier, failed setup may already have switched the file
        File oldFile = startFile;
        // nothing is locked yet if the folder was missing at the start
        if ((instanceLock == null || !sameFile(r.configFile, configFile)) && !switchTo(r.configFile, r.useExisting)) {
            clear(r.password);
            showCard(CARD_SETUP);
            return;
        }
        if (r.useExisting) {
            // a configuration file that already exists: open it like at the start
            pendingLocationFrom = oldFile;
            open();
            return;
        }
        pendingLocationFrom = null;
        SecretStore.PasswordKey newKey = null;
        try {
            if (r.protection == AccountStore.Protection.PASSWORD) {
                newKey = SecretStore.PasswordKey.create(r.password, SecretStore.DEFAULT_ITERATIONS);
            }
        } catch (GeneralSecurityException e) {
            setStatus(msg.format("error.crypto", e), true);
            return;
        } finally {
            clear(r.password);
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
        locationChosen(oldFile, r.shortcut);
        if (r.shortcut) {
            createShortcut();
        }
    }


    private static void clear(char[] c)
    {
        if (c != null) {
            Arrays.fill(c, '\0');
        }
    }


    // --- location of the configuration file ---

    /**
     * Uses another configuration file (chosen in the setup wizard, nothing is opened yet): locks it and
     * releases the lock of the old one.
     *
     * @param existing true for an existing file: like at the start, it is opened read-only if it cannot be
     *        locked (for example in a read-only folder)
     * @return false if it cannot be used (an error dialog was shown)
     */
    private boolean switchTo(File newFile, boolean existing)
    {
        FileLock newLock;
        String problem = null;
        if (existing) {
            try {
                newLock = tryLock(newFile);
                if (newLock == null) {
                    JOptionPane.showMessageDialog(frame, msg.format("error.running", newFile.getAbsolutePath()),
                                                  PROGNAME, JOptionPane.WARNING_MESSAGE);
                    return false;
                }
            } catch (IOException | RuntimeException e) {
                newLock = null;
                problem = e.toString();
            }
        } else {
            newLock = lockOrShowError(newFile);
            if (newLock == null) {
                return false;
            }
        }
        releaseLock(instanceLock);
        instanceLock = newLock;
        lockProblem = problem;
        folderMissing = false;
        useFile(newFile);
        return true;
    }


    /** @return the lock of the file, or null if it cannot be locked (an error dialog was shown) */
    private FileLock lockOrShowError(File file)
    {
        Component parent = settingsDialog != null && settingsDialog.isShowing() ? settingsDialog : frame;
        try {
            FileLock lock = tryLock(file);
            if (lock == null) {
                JOptionPane.showMessageDialog(parent, msg.format("error.running", file.getAbsolutePath()),
                                              PROGNAME, JOptionPane.WARNING_MESSAGE);
            }
            return lock;
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(parent, msg.format("error.location", e.getMessage()),
                                          PROGNAME, JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }


    /** Changes the file in use; the user interface settings are saved next to it from now on. */
    private void useFile(File newFile)
    {
        configFile = newFile.getAbsoluteFile();
        uiSettings = uiSettings.copyTo(UiSettings.locationFor(configFile));
        try {
            uiSettings.save();
        } catch (IOException ignored) {
            // the language and the window position are conveniences, see saveWindowLocation
        }
        if (aboutDialog != null) {
            aboutDialog.dispose();
            aboutDialog = null;
        }
        if (settingsPath != null) {
            settingsPath.setText(configFile.getAbsolutePath());
            packWrapped(settingsDialog);
        }
    }


    /**
     * After the configuration file was moved or another one was chosen: remembers the location for the next
     * start and updates the desktop shortcut that opened the old file.
     *
     * @param newShortcut true if a new shortcut is created anyway
     */
    private void locationChosen(File oldFile, boolean newShortcut)
    {
        if (sameFile(oldFile, configFile)) {
            return;
        }
        Component parent = settingsDialog != null && settingsDialog.isShowing() ? settingsDialog : frame;
        try {
            ConfigLocation.remember(ConfigLocation.pointerFile(ConfigLocation.homeDir()), configFile);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(parent, msg.format("error.remember", e.getMessage()),
                                          PROGNAME, JOptionPane.WARNING_MESSAGE);
        }
        afterLocationChange(parent, oldFile, newShortcut);
    }


    /** Tells about a start command that names the old file and updates the desktop shortcut. */
    private void afterLocationChange(Component parent, File oldFile, boolean newShortcut)
    {
        if (System.getProperty(ConfigLocation.PROPERTY) != null) {
            // started with -Detotp.config (a launcher file, or the desktop shortcut that is updated below)
            JOptionPane.showMessageDialog(parent, msg.format("location.explicit", oldFile.getAbsolutePath()),
                                          PROGNAME, JOptionPane.INFORMATION_MESSAGE);
        }
        if (newShortcut || !DesktopShortcut.isSupported()) {
            return;
        }
        File newFile = configFile;
        SHORTCUTS.execute(new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception
            {
                return DesktopShortcut.update(oldFile, newFile);
            }

            @Override
            protected void done()
            {
                try {
                    String path = get();
                    if (path != null) {
                        setStatus(msg.format("status.shortcutUpdated", path), false);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    setStatus(msg.format("error.shortcut", e.getCause().getMessage()), true);
                }
            }
        });
    }


    /** Lets the user choose a new location and moves the opened configuration file there. */
    private void moveConfig()
    {
        if (!opened || readOnlyReason != null || lockPending) {
            return;
        }
        File chosen = chooseLocation(settingsDialog, msg, configFile);
        if (lockPending || locked) {
            setStatus(msg.get("status.lockedNotSaved"), true); // chosen after the lock was requested
            return;
        }
        if (chosen == null || sameFile(chosen, configFile)) {
            return;
        }
        if (chosen.exists()) {
            JOptionPane.showMessageDialog(settingsDialog, msg.format("location.exists", chosen.getAbsolutePath()),
                                          PROGNAME, JOptionPane.WARNING_MESSAGE);
            return;
        }
        File oldFile = configFile;
        try {
            if (!Arrays.equals(Files.readAllBytes(oldFile.toPath()), fileContent)) {
                throw new IOException("The configuration file was changed by another program; restart et-OTP-v2 to see the changes");
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(settingsDialog, msg.format("error.move", e.getMessage()),
                                          PROGNAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        FileLock newLock = lockOrShowError(chosen);
        if (newLock == null) {
            return;
        }
        try {
            AtomicFiles.writeNew(fileContent, chosen);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            releaseLock(newLock);
            JOptionPane.showMessageDialog(settingsDialog, msg.format("location.exists", chosen.getAbsolutePath()),
                                          PROGNAME, JOptionPane.WARNING_MESSAGE);
            return;
        } catch (IOException | RuntimeException e) {
            releaseLock(newLock);
            JOptionPane.showMessageDialog(settingsDialog, msg.format("error.move", e.getMessage()),
                                          PROGNAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (!Arrays.equals(Files.readAllBytes(chosen.toPath()), fileContent)) {
                throw new IOException("The copy differs from the configuration file: " + chosen);
            }
            // another program may have changed the file meanwhile: then keep it, and remove the copy
            if (!Arrays.equals(Files.readAllBytes(oldFile.toPath()), fileContent)) {
                throw new IOException("The configuration file was changed by another program; restart et-OTP-v2 to see the changes");
            }
        } catch (IOException | RuntimeException e) {
            deleteIfUnchanged(chosen, fileContent);
            releaseLock(newLock);
            JOptionPane.showMessageDialog(settingsDialog, msg.format("error.move", e.getMessage()),
                                          PROGNAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        // remembered before the original is deleted: if this fails, the next start still finds the original
        // (remember() also refuses to replace a configuration file that has the name of the pointer file)
        try {
            ConfigLocation.remember(ConfigLocation.pointerFile(ConfigLocation.homeDir()), chosen);
        } catch (IOException | RuntimeException e) {
            deleteIfUnchanged(chosen, fileContent);
            releaseLock(newLock);
            JOptionPane.showMessageDialog(settingsDialog, msg.format("error.move", msg.format("error.remember", e.getMessage())),
                                          PROGNAME, JOptionPane.ERROR_MESSAGE);
            return;
        }
        String status = msg.format("status.moved", chosen.getAbsolutePath());
        try {
            Files.delete(oldFile.toPath());
        } catch (IOException e) {
            status = msg.format("status.movedNotDeleted", chosen.getAbsolutePath(), oldFile.getAbsolutePath());
            // also as a dialog: the status line may soon show the result of the shortcut update instead
            JOptionPane.showMessageDialog(settingsDialog, status, PROGNAME, JOptionPane.WARNING_MESSAGE);
        }
        // released only now, so another instance cannot open the old file while it is deleted
        releaseLock(instanceLock);
        instanceLock = newLock;
        useFile(chosen);
        setStatus(status, false);
        afterLocationChange(settingsDialog, oldFile, false);
    }


    /** Deletes the copy made by a failed move, but not a file that another program changed meanwhile. */
    private static void deleteIfUnchanged(File file, byte[] content)
    {
        try {
            if (Arrays.equals(Files.readAllBytes(file.toPath()), content)) {
                Files.delete(file.toPath());
            }
        } catch (IOException ignored) {
            // then the copy stays; it holds the same encrypted data as the original
        }
    }


    /**
     * Shows a save dialog for the configuration file and checks the chosen location.
     *
     * @return the chosen file, or null if cancelled
     */
    static File chooseLocation(Component parent, Messages msg, File current)
    {
        JFileChooser chooser = new JFileChooser(current.getAbsoluteFile().getParentFile());
        chooser.setDialogTitle(msg.get("location.title"));
        chooser.setSelectedFile(current.getAbsoluteFile());
        // the default name starts with a dot: show such files too
        chooser.setFileHidingEnabled(false);
        while (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File chosen = ConfigLocation.normalize(chooser.getSelectedFile());
            String problem = ConfigLocation.problem(chosen, ConfigLocation.homeDir());
            if (problem == null) {
                return chosen;
            }
            JOptionPane.showMessageDialog(parent, msg.format(problem, chosen.getAbsolutePath()),
                                          msg.get("location.title"), JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }


    static boolean sameFile(File a, File b)
    {
        return ConfigLocation.sameFile(a, b);
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
        lockItem = menuItem("menu.lock", e -> requestLock());
        lockItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L,
                                                       Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        lockItem.setEnabled(canLock());
        m.add(lockItem);
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
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "search");
        frame.getRootPane().getActionMap().put("search", action(this::focusSearch));
        if (!typingStartsSearch) {
            // once: the window is built again when the language changes, the dispatcher checks the current one
            typingStartsSearch = true;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::typeIntoSearch);
        }

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
                finishShortcuts();
            }

            @Override
            public void windowIconified(WindowEvent e)
            {
                if (uiSettings.lockOnMinimize()) {
                    requestLock();
                }
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

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, 20, 20, 20);
        otherFileButton = new JButton(msg.get("unlock.otherFile"));
        otherFileButton.addActionListener(e -> showSetupWizard());
        otherFileButton.setVisible(pendingLocationFrom != null);
        panel.add(otherFileButton, c);
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
        JPanel north = new JPanel(new BorderLayout());
        north.add(buttons, BorderLayout.NORTH);
        north.add(buildSearchBar(), BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

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


    private JPanel buildSearchBar()
    {
        JPanel bar = new JPanel(new BorderLayout(6, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        JLabel label = new JLabel(msg.get("search.label"));
        searchField = new JTextField();
        label.setLabelFor(searchField);
        boolean mac = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK;
        searchField.setToolTipText(msg.format("search.tip", mac ? "\u2318F" : "Ctrl+F"));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e)
            {
                applyFilter(true);
            }

            @Override
            public void removeUpdate(DocumentEvent e)
            {
                applyFilter(true);
            }

            @Override
            public void changedUpdate(DocumentEvent e)
            {
                applyFilter(true);
            }
        });
        bind(searchField, KeyEvent.VK_UP, "selectUp", () -> moveSelection(-1));
        bind(searchField, KeyEvent.VK_DOWN, "selectDown", () -> moveSelection(1));
        bind(searchField, KeyEvent.VK_ENTER, "copySelected", this::copySelected);
        bind(searchField, KeyEvent.VK_ESCAPE, "clearSearch", () -> searchField.setText(""));
        bar.add(label, BorderLayout.WEST);
        bar.add(searchField, BorderLayout.CENTER);
        return bar;
    }


    private static void bind(JComponent c, int key, String name, Runnable r)
    {
        c.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        c.getActionMap().put(name, action(r));
    }


    private static javax.swing.Action action(Runnable r)
    {
        return new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e)
            {
                r.run();
            }
        };
    }


    private void focusSearch()
    {
        if (CARD_LIST.equals(card) && searchField != null) {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }
    }


    /**
     * Typing a character while the list has the focus (on a button, not in a text field) starts the search
     * with it. Space and keys with Ctrl, Alt or Cmd keep their meaning (buttons, mnemonics, shortcuts).
     * An input method cannot start on a button, so Japanese is typed after Ctrl+F.
     *
     * @return true if the character was put into the search field
     */
    private boolean typeIntoSearch(KeyEvent e)
    {
        if (e.getID() != KeyEvent.KEY_TYPED || frame == null || !frame.isActive() || !CARD_LIST.equals(card)
            || searchField == null || !searchField.isShowing()) {
            return false;
        }
        char c = e.getKeyChar();
        if (c == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(c) || Character.isWhitespace(c)) {
            return false;
        }
        int mods = e.getModifiersEx();
        int ctrlAlt = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
        boolean altGraph = (mods & ctrlAlt) == ctrlAlt || (mods & InputEvent.ALT_GRAPH_DOWN_MASK) != 0;
        if (!altGraph && (mods & (ctrlAlt | InputEvent.META_DOWN_MASK)) != 0) {
            return false;
        }
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner instanceof JTextComponent || MenuSelectionManager.defaultManager().getSelectedPath().length > 0) {
            return false;
        }
        searchField.requestFocusInWindow();
        searchField.setCaretPosition(searchField.getDocument().getLength());
        searchField.replaceSelection(String.valueOf(c));
        e.consume();
        return true;
    }


    /** Shows only the rows whose name contains the search text. */
    private void applyFilter(boolean clearSelection)
    {
        if (listPanel == null) {
            return;
        }
        String query = searchField == null ? "" : searchField.getText();
        int shown = 0;
        for (AccountRow row : rows) {
            boolean match = AccountList.matches(row.shownName(), query);
            row.setVisible(match);
            if (match) {
                shown++;
            }
        }
        if (clearSelection) {
            selectedRow = -1;
        }
        if (noMatchText != null) {
            noMatchText.setText(msg.format("search.none", query.trim()));
            noMatchText.setVisible(!rows.isEmpty() && shown == 0);
        }
        showSelection();
        listPanel.revalidate();
        listPanel.repaint();
    }


    private List<Integer> shownRows()
    {
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).isVisible()) {
                shown.add(i);
            }
        }
        return shown;
    }


    /** Chooses the next (1) or the previous (-1) shown row. */
    private void moveSelection(int step)
    {
        List<Integer> shown = shownRows();
        if (shown.isEmpty()) {
            return;
        }
        int pos = shown.indexOf(selectedRow);
        if (pos < 0) {
            pos = step > 0 ? 0 : shown.size() - 1;
        } else {
            pos = Math.max(0, Math.min(shown.size() - 1, pos + step));
        }
        selectedRow = shown.get(pos);
        showSelection();
    }


    private void showSelection()
    {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setSelected(i == selectedRow, listPanel.getBackground());
        }
        if (selectedRow >= 0 && selectedRow < rows.size()) {
            AccountRow row = rows.get(selectedRow);
            // after the layout, when the row has its place
            SwingUtilities.invokeLater(() -> row.scrollRectToVisible(new Rectangle(row.getSize())));
        }
    }


    /** Enter in the search field: copies the code of the chosen row, or of the only shown row. */
    private void copySelected()
    {
        List<Integer> shown = shownRows();
        int index = shown.contains(selectedRow) ? selectedRow : shown.size() == 1 ? shown.get(0) : -1;
        if (index >= 0) {
            AccountRow row = rows.get(index);
            copy(row.account(), row.code());
        } else if (shown.size() > 1) {
            setStatus(msg.get("search.choose"), false);
        }
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
        noMatchText = createStatusArea();
        noMatchText.setBorder(BorderFactory.createEmptyBorder(20, 15, 20, 15));
        noMatchText.setAlignmentX(Component.LEFT_ALIGNMENT);
        noMatchText.setVisible(false);
        listPanel.add(noMatchText);
        applyFilter(true);
    }


    private void tick()
    {
        long millis = System.currentTimeMillis();
        AutoLock.Reason reason = autoLock.tick(millis, uiSettings.lockIdleMinutes(), uiSettings.lockOnSystemLock());
        if (reason != null || lockPending) {
            requestLock();
        }
        updateLockItem(); // the protection may have changed in the settings
        long now = millis / 1000;
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
        clipboard.copy(code);
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
        applyFilter(false); // the new name may (not) match the search text
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


    @Override
    public boolean canMove(Account account, AccountRow.Move where)
    {
        // while filtered, the neighbors in the list are not the ones shown
        if (readOnlyReason != null || (searchField != null && AccountList.filters(searchField.getText()))) {
            return false;
        }
        int index = indexOf(account);
        return where == AccountRow.Move.DOWN ? index >= 0 && index < accounts.size() - 1 : index > 0;
    }


    @Override
    public void move(Account account, AccountRow.Move where)
    {
        if (!canMove(account, where)) {
            return;
        }
        int from = indexOf(account);
        int to = where == AccountRow.Move.UP ? from - 1 : where == AccountRow.Move.DOWN ? from + 1 : 0;
        AccountList.move(accounts, from, to);
        if (!persist()) {
            AccountList.move(accounts, to, from);
            return;
        }
        rebuildList();
        selectedRow = to;
        showSelection();
        // keep the keyboard on the moved row, so it can be moved again
        rows.get(to).editButton().requestFocusInWindow();
    }


    /** @return the index of this very account (two registrations of the same key are equal) */
    private int indexOf(Account account)
    {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i) == account) {
                return i;
            }
        }
        return -1;
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

    /**
     * Lets the user choose the screen or window with the QR code from thumbnails (like the screen
     * sharing of video conference programs) and registers the service it shows.
     */
    private void readScreen()
    {
        ScreenPicker.Choice choice = ScreenPicker.choose(frame, msg);
        if (choice == null) {
            return;
        }
        if (choice.texts.stream().anyMatch(QrSearch::isOtpauth)) {
            handleTexts(choice.texts);
            return;
        }
        readAgain(choice.target);
    }


    /**
     * Reads the chosen screen or window again, as its first capture showed no QR code. This
     * program's window is hidden, so it does not cover the QR code. A chosen window is brought to
     * the front (a browser may not draw while hidden) and read both by itself and from the screens,
     * for windows that cannot be captured by themselves.
     */
    private void readAgain(ScreenCapture.Target target)
    {
        setBusy(true);
        setStatus(msg.get("status.searching"), false);
        frame.setVisible(false);
        if (!target.isDisplay()) {
            ScreenCapture.bringToFront(target);
        }
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception
            {
                Thread.sleep(800); // until this window disappeared and the other window is drawn
                List<String> texts = new ArrayList<>(QrSearch.search(target));
                if (!target.isDisplay()) {
                    for (ScreenCapture.Target t : ScreenCapture.displays()) {
                        for (String s : QrSearch.search(t)) {
                            if (!texts.contains(s)) {
                                texts.add(s);
                            }
                        }
                    }
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
                handleTexts(result(this));
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
                    handleTexts(get());
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
    private void handleTexts(List<String> texts)
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
            JOptionPane.showMessageDialog(frame, message, msg.get("qr.title"),
                                          problem != null ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
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
        if (!opened) {
            setStatus(msg.get("status.lockedNotAdded"), true); // a QR code read while the list was locked
            return;
        }
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
        if (!opened) {
            setStatus(msg.get("status.lockedNotAdded"), true);
            return;
        }
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
        searchField.setText(""); // the new service is shown even if the list was filtered
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
        File file = configFile;
        setBusy(true);
        SHORTCUTS.execute(new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception
            {
                return DesktopShortcut.create(file);
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
        });
    }


    /** Waits a short time for queued shortcut updates, so the shortcut does not keep naming a moved file. */
    private static void finishShortcuts()
    {
        SHORTCUTS.shutdown();
        try {
            SHORTCUTS.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        finishShortcuts();
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


    // --- locking (issue #22) ---

    /** @return true if the list is open and protected by a password: only then a lock can be unlocked again */
    private boolean canLock()
    {
        return opened && protection == AccountStore.Protection.PASSWORD;
    }


    private void updateLockItem()
    {
        if (lockItem != null) {
            lockItem.setEnabled(canLock());
        }
    }


    /**
     * Locks now, or right after the open dialogs are closed: the code that opened them still runs and needs
     * the list. The codes and the dialogs are hidden at once, and nothing is saved until unlocked again.
     */
    private void requestLock()
    {
        if (!canLock()) {
            if (lockPending && opened) {
                showCard(CARD_LIST); // no longer protected by a password
            }
            lockPending = false;
            return;
        }
        if (modalDialogShown()) {
            if (!lockPending) {
                lockPending = true;
                clipboard.clear();
                showCard(CARD_UNLOCK);
            }
            // a dialog may show a key or a QR code: closed at once, as if cancelled (a change confirmed
            // now is not saved anyway); the lock follows when the dialogs are gone, see tick()
            hideModalDialogs();
            return;
        }
        lock();
    }


    private static void hideModalDialogs()
    {
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && ((Dialog) w).isModal() && w.isShowing()) {
                w.setVisible(false);
            }
        }
    }


    private static boolean modalDialogShown()
    {
        for (Window w : Window.getWindows()) {
            if (w instanceof Dialog && ((Dialog) w).isModal() && w.isShowing()) {
                return true;
            }
        }
        return false;
    }


    /** Removes the services, the derived key and the copied code; the password unlocks them again. */
    private void lock()
    {
        lockPending = false;
        locked = true;
        clipboard.clear();
        opened = false;
        accounts.clear(); // the keys of the services are strings: dropped, but cannot be overwritten
        if (key != null) {
            key.destroy();
            key = null;
        }
        fileContent = null;
        selectedRow = -1;
        if (searchField != null) {
            searchField.setText("");
        }
        rebuildList();
        updateLockItem();
        showCard(CARD_UNLOCK);
        setStatus(msg.get("status.locked"), false);
    }


    /** For tests: whether the services and the derived key were removed. */
    boolean lockedForTest()
    {
        return !opened && key == null && accounts.isEmpty();
    }


    private JPanel buildLockTab()
    {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 0);
        boolean password = protection == AccountStore.Protection.PASSWORD;

        int minutes = uiSettings.lockIdleMinutes();
        JCheckBox idle = new JCheckBox(msg.get("lock.idle"), minutes > 0);
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(minutes > 0 ? minutes : UiSettings.DEFAULT_LOCK_MINUTES,
                                                               1, UiSettings.MAX_LOCK_MINUTES, 1));
        JLabel unit = new JLabel(msg.get("lock.minutes"));
        unit.setLabelFor(spinner);
        spinner.getAccessibleContext().setAccessibleName(msg.get("lock.idle"));
        JPanel idleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        idleRow.add(idle);
        idleRow.add(Box.createHorizontalStrut(6));
        idleRow.add(spinner);
        idleRow.add(Box.createHorizontalStrut(6));
        idleRow.add(unit);
        JCheckBox system = new JCheckBox(msg.get(Dpapi.isAvailable() ? "lock.system" : "lock.sleep"),
                                         uiSettings.lockOnSystemLock());
        JCheckBox minimize = new JCheckBox(msg.get("lock.minimize"), uiSettings.lockOnMinimize());
        String shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() == InputEvent.META_DOWN_MASK
                          ? "\u2318L" : "Ctrl+L";

        panel.add(SetupWizard.note(msg.get(password ? "lock.text" : "lock.noPassword")), c);
        c.insets = new Insets(8, 0, 2, 0);
        panel.add(idleRow, c);
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(system, c);
        panel.add(minimize, c);
        c.insets = new Insets(8, 0, 2, 0);
        panel.add(SetupWizard.note(msg.format("lock.manual", shortcut)), c);

        Runnable save = () -> {
            spinner.setEnabled(password && idle.isSelected());
            uiSettings.setLockIdleMinutes(idle.isSelected() ? (Integer) spinner.getValue() : 0);
            uiSettings.setLockOnSystemLock(system.isSelected());
            uiSettings.setLockOnMinimize(minimize.isSelected());
            try {
                uiSettings.save();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(settingsDialog, msg.format("error.uiSettings", ex.getMessage()),
                                              settingsDialog.getTitle(), JOptionPane.WARNING_MESSAGE);
            }
        };
        for (JCheckBox b : new JCheckBox[] { idle, system, minimize }) {
            b.setEnabled(password);
            b.addActionListener(e -> save.run());
        }
        spinner.setEnabled(password && idle.isSelected());
        spinner.addChangeListener(e -> save.run());

        c.weighty = 1;
        panel.add(new JPanel(), c);
        return panel;
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
        tabs.addTab(msg.get("settings.tab.lock"), buildLockTab());
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
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        String[] names = new String[LANGUAGES.length];
        for (int i = 0; i < LANGUAGES.length; i++) {
            names[i] = msg.get("language." + LANGUAGES[i]);
        }
        JComboBox<String> language = new JComboBox<>(names);
        language.setSelectedIndex(Arrays.asList(LANGUAGES).indexOf(uiSettings.language()));
        language.addActionListener(e -> changeLanguage(LANGUAGES[language.getSelectedIndex()]));
        // the combo box keeps its full width in its own row
        JPanel languageRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        languageRow.add(new JLabel(msg.get("settings.language")));
        languageRow.add(Box.createHorizontalStrut(10));
        languageRow.add(language);
        panel.add(languageRow, c);

        c.insets = new Insets(20, 0, 0, 0);
        panel.add(new JLabel(msg.get("settings.file")), c);
        c.insets = new Insets(2, 0, 0, 0);
        settingsPath = pathText(configFile);
        panel.add(settingsPath, c);
        JLabel note = SetupWizard.note(msg.get("location.move.note"));
        note.setBorder(BorderFactory.createEmptyBorder());
        c.insets = new Insets(8, 0, 0, 0);
        panel.add(note, c);
        JButton move = new JButton(msg.get("location.move"));
        move.setEnabled(opened && readOnlyReason == null);
        move.addActionListener(e -> moveConfig());
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(8, 0, 0, 0);
        panel.add(move, c);
        c.weighty = 1;
        panel.add(new JPanel(), c);
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
