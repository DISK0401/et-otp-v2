/*
 * CodeCommand.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.awt.AWTError;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import javax.crypto.BadPaddingException;


/**
 * The command line use without a window, for batch files and scripts:
 * {@code --code <name>} writes the current code of a registered service to the standard output,
 * {@code --list} the names of the registered services, {@code --version} the version.
 * Options: {@code --copy}, {@code --next}, {@code --json}, {@code --password-stdin}, {@code --config <file>}.
 * Prompts and errors go to the standard error output.
 * The configuration file is only read, so this also works while the window is open.
 */
final class CodeCommand
{
    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    /** a code that changes within this many seconds is not written; the next one is waited for. */
    static final int MIN_SECONDS_LEFT = 3;

    private static final int PASSWORD_TRIES = 3;
    /** a longer first line of the standard input is not taken as a password. */
    private static final int MAX_PASSWORD_BYTES = 4096;

    /** Asks for the password; null if there is no console to ask. */
    interface PasswordSource
    {
        char[] read(String prompt) throws IOException;
    }

    interface Clock
    {
        long millis();

        void sleep(long millis) throws InterruptedException;
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long millis()
        {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException
        {
            Thread.sleep(millis);
        }
    };


    /** The parsed arguments. */
    static final class Options
    {
        enum Command { CODE, LIST, VERSION, HELP }

        Command command;
        String name;
        boolean copy;
        boolean next;
        boolean json;
        boolean passwordStdin;
        File config;


        /** @return the options, or null if the arguments are wrong */
        static Options parse(String[] args)
        {
            Options o = new Options();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (seen.contains(a)) {
                    return null; // given twice
                }
                seen.add(a);
                switch (a) {
                    case "--code":
                        if (o.command != null || i + 1 >= args.length) {
                            return null;
                        }
                        o.command = Command.CODE;
                        o.name = args[++i];
                        break;
                    case "--list":
                        o.command = o.command == null ? Command.LIST : null;
                        break;
                    case "--version":
                        o.command = o.command == null ? Command.VERSION : null;
                        break;
                    case "--help":
                    case "-h":
                        o.command = o.command == null ? Command.HELP : null;
                        break;
                    case "--copy":
                        o.copy = true;
                        break;
                    case "--next":
                        o.next = true;
                        break;
                    case "--json":
                        o.json = true;
                        break;
                    case "--password-stdin":
                        o.passwordStdin = true;
                        break;
                    case "--config":
                        if (i + 1 >= args.length || args[i + 1].trim().isEmpty()) {
                            return null;
                        }
                        o.config = new File(args[++i]); // spaces may belong to the name
                        break;
                    default:
                        return null;
                }
                if (o.command == null && (a.equals("--list") || a.equals("--version") || a.equals("--help")
                                          || a.equals("-h"))) {
                    return null; // a second command
                }
            }
            return o.valid() ? o : null;
        }


        private boolean valid()
        {
            if (command == null) {
                return false;
            }
            switch (command) {
                case CODE:
                    // the JSON has the code and the next code; nothing is written when copying
                    return !(json && (copy || next));
                case LIST:
                    return !copy && !next;
                default:
                    // --version and --help stand alone
                    return !copy && !next && !json && !passwordStdin && config == null;
            }
        }
    }


    private final File config;
    private final Messages msg;
    private final PrintStream out;
    private final PrintStream err;
    private final PasswordSource passwords;
    private final Clock clock;
    private final InputStream in;
    private final Charset inCharset;
    private final Supplier<Clipboard> clipboard;


    /**
     * @param config the configuration file used without {@code --config}
     * @param in the standard input, read with {@code --password-stdin}
     * @param clipboard gives the clipboard for {@code --copy}; may throw {@link HeadlessException}
     */
    CodeCommand(File config, Messages msg, PrintStream out, PrintStream err, PasswordSource passwords, Clock clock,
                InputStream in, Charset inCharset, Supplier<Clipboard> clipboard)
    {
        this.config = config;
        this.msg = msg;
        this.out = out;
        this.err = err;
        this.passwords = passwords;
        this.clock = clock;
        this.in = in;
        this.inCharset = inCharset;
        this.clipboard = clipboard;
    }


    /** @return true if the arguments ask for the command line use instead of the window */
    static boolean handles(String[] args)
    {
        return args.length > 0 && args[0].startsWith("-");
    }


    /** Runs with the configuration file of the window (or of {@code --config}), and returns the exit code. */
    static int main(String[] args)
    {
        Options o = Options.parse(args);
        File config = o != null && o.config != null ? o.config : ConfigLocation.resolve();
        UiSettings ui = UiSettings.load(UiSettings.locationFor(config));
        Messages msg = new Messages(Messages.localeFor(ui.language(), Locale.getDefault(Locale.Category.DISPLAY)));
        PrintStream err = System.err;
        return new CodeCommand(config, msg, System.out, err, prompt -> ConsolePassword.read(prompt, err),
                               SYSTEM_CLOCK, System.in, stdinCharset(),
                               () -> Toolkit.getDefaultToolkit().getSystemClipboard()).run(args);
    }


    /** @return the encoding of the standard input: of the console, or else of the system */
    static Charset stdinCharset()
    {
        String name = System.getProperty("stdin.encoding", System.getProperty("native.encoding"));
        try {
            return name == null ? Charset.defaultCharset() : Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return Charset.defaultCharset();
        }
    }


    int run(String[] args)
    {
        Options o = Options.parse(args);
        if (o == null) {
            err.println(msg.get("cli.usage"));
            return USAGE;
        }
        if (o.command == Options.Command.HELP) {
            out.println(msg.get("cli.usage"));
            return OK;
        }
        if (o.command == Options.Command.VERSION) {
            String v = BuildInfo.load().version();
            out.println("et-otp-v2 " + (v == null ? "unknown" : v));
            out.flush();
            return OK;
        }
        File file = o.config != null ? o.config : config;
        List<Account> accounts;
        try {
            accounts = open(file, o.passwordStdin);
        } catch (IOException e) {
            err.println(msg.format("error.io", e.getMessage()));
            return FAILED;
        } catch (GeneralSecurityException e) {
            err.println(msg.format("error.crypto", e));
            return FAILED;
        }
        if (accounts == null) {
            return FAILED;
        }
        if (o.command == Options.Command.LIST) {
            writeList(accounts, o.json);
            return OK;
        }
        return writeCode(accounts, o);
    }


    /** @return the accounts, or null after an error was shown */
    private List<Account> open(File file, boolean passwordStdin) throws IOException, GeneralSecurityException
    {
        if (!file.isFile()) {
            err.println(msg.format("cli.noConfig", file.getAbsolutePath()));
            return null;
        }
        switch (AccountStore.inspect(file)) {
            case MISSING:
                err.println(msg.format("cli.noConfig", file.getAbsolutePath()));
                return null;
            case LEGACY:
                // converting writes the file: left to the window
                err.println(msg.format("cli.legacy", file.getAbsolutePath()));
                return null;
            case WINDOWS_USER:
                return AccountStore.openForWindowsUser(file).accounts;
            default:
                if (passwordStdin) {
                    // read once: nobody to ask again
                    return openWithPassword(file, prompt -> readLine(in, inCharset), 1, "cli.noStdin", true);
                }
                return openWithPassword(file, passwords, PASSWORD_TRIES, "cli.noConsole", false);
        }
    }


    private List<Account> openWithPassword(File file, PasswordSource source, int tries, String noPassword,
                                           boolean fromStdin)
        throws IOException, GeneralSecurityException
    {
        for (int i = 0; i < tries; i++) {
            char[] pass = source.read(msg.get("unlock.password") + " ");
            if (pass == null) {
                err.println(msg.get(noPassword));
                return null;
            }
            try {
                if (pass.length == 0) {
                    err.println(msg.get("error.password.empty"));
                    continue;
                }
                AccountStore.Opened o = AccountStore.openWithPassword(file, pass);
                if (o.key != null) {
                    o.key.destroy();
                }
                return o.accounts;
            } catch (BadPaddingException e) {
                err.println(msg.get("error.badPassword"));
                if (fromStdin && Character.isWhitespace(pass[pass.length - 1])) {
                    // "echo pw | ..." in cmd also passes the space before the "|"
                    err.println(msg.get("cli.trailingSpace"));
                }
            } catch (InvalidKeySpecException | IllegalArgumentException e) {
                err.println(msg.get("error.emptyPassword"));
            } finally {
                Arrays.fill(pass, '\0');
            }
        }
        return null;
    }


    /**
     * Reads the first line, without the line end, and overwrites what was read in between.
     * A byte order mark (as written by Notepad or PowerShell) gives the encoding: UTF-8 or UTF-16.
     * @param charset the encoding without a byte order mark
     * @return the line, or null if the input is empty or the line is too long
     */
    static char[] readLine(InputStream in, Charset charset) throws IOException
    {
        byte[] bytes = new byte[MAX_PASSWORD_BYTES];
        int n = 0;
        int start = 0;
        Charset cs = charset;
        try {
            int b;
            while ((b = in.read()) != -1) {
                if (n == bytes.length) {
                    return null;
                }
                bytes[n++] = (byte) b;
                if (n == 2 && start == 0 && bom(bytes, 0xFF, 0xFE)) {
                    cs = StandardCharsets.UTF_16LE;
                    start = 2;
                } else if (n == 2 && start == 0 && bom(bytes, 0xFE, 0xFF)) {
                    cs = StandardCharsets.UTF_16BE;
                    start = 2;
                } else if (n == 3 && start == 0 && bom(bytes, 0xEF, 0xBB) && (bytes[2] & 0xFF) == 0xBF) {
                    cs = StandardCharsets.UTF_8;
                    start = 3;
                } else if (cs == StandardCharsets.UTF_16LE || cs == StandardCharsets.UTF_16BE) {
                    boolean le = cs == StandardCharsets.UTF_16LE;
                    if ((n - start) % 2 == 0 && bytes[n - (le ? 2 : 1)] == '\n' && bytes[n - (le ? 1 : 2)] == 0) {
                        break;
                    }
                } else if (b == '\n') {
                    break;
                }
            }
            if (n == 0) {
                return null;
            }
            CharBuffer chars = cs.newDecoder()
                                 .onMalformedInput(CodingErrorAction.REPLACE)
                                 .onUnmappableCharacter(CodingErrorAction.REPLACE)
                                 .decode(ByteBuffer.wrap(bytes, start, n - start));
            try {
                int end = chars.limit();
                if (end > chars.position() && chars.get(end - 1) == '\n') {
                    end--;
                }
                if (end > chars.position() && chars.get(end - 1) == '\r') {
                    end--;
                }
                char[] line = new char[end - chars.position()];
                chars.get(line);
                return line;
            } finally {
                if (chars.hasArray()) {
                    Arrays.fill(chars.array(), '\0');
                }
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }


    private static boolean bom(byte[] bytes, int first, int second)
    {
        return (bytes[0] & 0xFF) == first && (bytes[1] & 0xFF) == second;
    }


    private void writeList(List<Account> accounts, boolean json)
    {
        if (json) {
            StringBuilder b = new StringBuilder("[");
            for (Account a : accounts) {
                if (b.length() > 1) {
                    b.append(',');
                }
                b.append("{\"name\":").append(json(a.name()))
                 .append(",\"digits\":").append(a.digits())
                 .append(",\"period\":").append(a.period()).append('}');
            }
            out.println(b.append(']'));
        } else {
            for (Account a : accounts) {
                out.println(printable(shownName(a)));
            }
        }
        out.flush();
    }


    private int writeCode(List<Account> accounts, Options o)
    {
        List<Account> found = find(accounts, o.name);
        if (found.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Account a : accounts) {
                names.add(printable(shownName(a)));
            }
            err.println(msg.format("cli.notFound", printable(o.name), String.join(", ", names)));
            return FAILED;
        }
        if (found.size() > 1) {
            err.println(msg.format("cli.ambiguous", printable(o.name)));
            return FAILED;
        }
        Account account = found.get(0);
        long now = clock.millis();
        int left = account.secondsLeft(Math.floorDiv(now, 1000L));
        if (!o.next && left <= MIN_SECONDS_LEFT) {
            // the next code is written at once, it is valid for the whole next period
            err.println(msg.format("cli.waiting", left));
            long periodMillis = account.period() * 1000L;
            try {
                // a little after the change, so the clock is surely in the next period
                clock.sleep(periodMillis - Math.floorMod(now, periodMillis) + 100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FAILED;
            }
            now = clock.millis();
        }
        long seconds = Math.floorDiv(now, 1000L);
        String next = account.code(seconds + account.period());
        String code = o.next ? next : account.code(seconds);
        if (o.json) {
            out.println("{\"name\":" + json(account.name()) + ",\"code\":" + json(code)
                        + ",\"secondsLeft\":" + account.secondsLeft(seconds) + ",\"period\":" + account.period()
                        + ",\"next\":" + json(next) + "}");
        } else if (o.copy) {
            try {
                clipboard.get().setContents(new StringSelection(code), null);
            } catch (HeadlessException | IllegalStateException | AWTError e) {
                // AWTError: no display could be opened (DISPLAY not valid)
                err.println(msg.get("cli.noClipboard"));
                return FAILED;
            }
            err.println(msg.format("status.copied", printable(shownName(account))));
        } else {
            out.println(code);
        }
        out.flush();
        return OK;
    }


    /**
     * @return the text as a JSON string; everything outside printable ASCII is escaped, so the output
     *         is the same in every encoding of the standard output
     */
    static String json(String s)
    {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }


    /**
     * @return the text with control characters (line breaks, escape sequences of the terminal) replaced by
     *         {@code ?}: a name from a QR code may contain them, and one name is one line of {@code --list}
     */
    static String printable(String s)
    {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isISOControl(c) ? '?' : c);
        }
        return b.toString();
    }


    /** @return the name as shown in the window, also for services without a name (as converted from 1.x) */
    private String shownName(Account a)
    {
        return a.name().isEmpty() ? msg.get("row.unnamed") : a.name();
    }


    /**
     * @return the accounts with exactly this name (as shown, or as written by {@code --json}), or else those with
     *         this name in another case
     */
    List<Account> find(List<Account> accounts, String name)
    {
        List<Account> exact = new ArrayList<>();
        List<Account> ignoringCase = new ArrayList<>();
        for (Account a : accounts) {
            String shown = shownName(a);
            if (shown.equals(name) || a.name().equals(name)) { // "" as written by --json for no name
                exact.add(a);
            } else if (shown.equalsIgnoreCase(name)) {
                ignoringCase.add(a);
            }
        }
        return exact.isEmpty() ? ignoringCase : exact;
    }
}
