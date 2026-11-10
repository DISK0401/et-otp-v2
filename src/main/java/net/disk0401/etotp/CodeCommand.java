/*
 * CodeCommand.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.security.GeneralSecurityException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.BadPaddingException;


/**
 * The command line use without a window, for batch files and scripts:
 * {@code --code <name>} writes the current code of a registered service to the standard output,
 * {@code --list} the names of the registered services. Prompts and errors go to the standard error output.
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


    private final File config;
    private final Messages msg;
    private final PrintStream out;
    private final PrintStream err;
    private final PasswordSource passwords;
    private final Clock clock;


    CodeCommand(File config, Messages msg, PrintStream out, PrintStream err, PasswordSource passwords, Clock clock)
    {
        this.config = config;
        this.msg = msg;
        this.out = out;
        this.err = err;
        this.passwords = passwords;
        this.clock = clock;
    }


    /** @return true if the arguments ask for the command line use instead of the window */
    static boolean handles(String[] args)
    {
        return args.length > 0 && args[0].startsWith("-");
    }


    /** Runs with the configuration file of the window, and returns the exit code. */
    static int main(String[] args)
    {
        File config = ConfigLocation.resolve();
        UiSettings ui = UiSettings.load(UiSettings.locationFor(config));
        Messages msg = new Messages(Messages.localeFor(ui.language(), Locale.getDefault(Locale.Category.DISPLAY)));
        PrintStream err = System.err;
        return new CodeCommand(config, msg, System.out, err, prompt -> ConsolePassword.read(prompt, err),
                               SYSTEM_CLOCK).run(args);
    }


    int run(String[] args)
    {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            out.println(msg.get("cli.usage"));
            return OK;
        }
        boolean list = args.length == 1 && "--list".equals(args[0]);
        boolean code = args.length == 2 && "--code".equals(args[0]);
        if (!list && !code) {
            err.println(msg.get("cli.usage"));
            return USAGE;
        }
        List<Account> accounts;
        try {
            accounts = open();
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
        if (list) {
            for (Account a : accounts) {
                out.println(shownName(a));
            }
            out.flush();
            return OK;
        }
        return writeCode(accounts, args[1]);
    }


    /** @return the accounts, or null after an error was shown */
    private List<Account> open() throws IOException, GeneralSecurityException
    {
        if (!config.isFile()) {
            err.println(msg.format("cli.noConfig", config.getAbsolutePath()));
            return null;
        }
        switch (AccountStore.inspect(config)) {
            case MISSING:
                err.println(msg.format("cli.noConfig", config.getAbsolutePath()));
                return null;
            case LEGACY:
                // converting writes the file: left to the window
                err.println(msg.format("cli.legacy", config.getAbsolutePath()));
                return null;
            case WINDOWS_USER:
                return AccountStore.openForWindowsUser(config).accounts;
            default:
                return openWithPassword();
        }
    }


    private List<Account> openWithPassword() throws IOException, GeneralSecurityException
    {
        for (int i = 0; i < PASSWORD_TRIES; i++) {
            char[] pass = passwords.read(msg.get("unlock.password") + " ");
            if (pass == null) {
                err.println(msg.get("cli.noConsole"));
                return null;
            }
            try {
                if (pass.length == 0) {
                    err.println(msg.get("error.password.empty"));
                    continue;
                }
                AccountStore.Opened o = AccountStore.openWithPassword(config, pass);
                if (o.key != null) {
                    o.key.destroy();
                }
                return o.accounts;
            } catch (BadPaddingException e) {
                err.println(msg.get("error.badPassword"));
            } catch (InvalidKeySpecException | IllegalArgumentException e) {
                err.println(msg.get("error.emptyPassword"));
            } finally {
                Arrays.fill(pass, '\0');
            }
        }
        return null;
    }


    private int writeCode(List<Account> accounts, String name)
    {
        List<Account> found = find(accounts, name);
        if (found.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Account a : accounts) {
                names.add(shownName(a));
            }
            err.println(msg.format("cli.notFound", name, String.join(", ", names)));
            return FAILED;
        }
        if (found.size() > 1) {
            err.println(msg.format("cli.ambiguous", name));
            return FAILED;
        }
        Account account = found.get(0);
        long now = clock.millis();
        int left = account.secondsLeft(Math.floorDiv(now, 1000L));
        if (left <= MIN_SECONDS_LEFT) {
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
        out.println(account.code(Math.floorDiv(now, 1000L)));
        out.flush();
        return OK;
    }


    /** @return the name as shown in the window, also for services without a name (as converted from 1.x) */
    private String shownName(Account a)
    {
        return a.name().isEmpty() ? msg.get("row.unnamed") : a.name();
    }


    /** @return the accounts with exactly this name, or else those with this name in another case */
    List<Account> find(List<Account> accounts, String name)
    {
        List<Account> exact = new ArrayList<>();
        List<Account> ignoringCase = new ArrayList<>();
        for (Account a : accounts) {
            String shown = shownName(a);
            if (shown.equals(name)) {
                exact.add(a);
            } else if (shown.equalsIgnoreCase(name)) {
                ignoringCase.add(a);
            }
        }
        return exact.isEmpty() ? ignoringCase : exact;
    }
}
