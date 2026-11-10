/*
 * CodeCommandTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;


class CodeCommandTest
{
    private static final int TEST_ITERATIONS = 1000;
    /** a time 10 seconds into a 30 second period. */
    private static final long NOW = 1_700_000_020_000L;

    @TempDir
    File tmp;

    private File config;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Deque<String> passwords = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private long now = NOW;
    private Account github;

    @BeforeEach
    void createConfig() throws Exception
    {
        config = new File(tmp, "accounts.properties");
        github = new Account("GitHub (me)", "JBSWY3DPEHPK3PXP");
        List<Account> accounts = Arrays.asList(github,
                                               new Account("日本語のサービス", "GEZDGNBVGY3TQOJQ", Account.SHA256, 8, 60),
                                               new Account("Twice", "GEZDGNBVGY3TQOJQ"),
                                               new Account("Twice", "JBSWY3DPEHPK3PXP"),
                                               new Account("", "MFRGGZDFMZTWQ2LK"));
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS);
        AccountStore.save(config, accounts, AccountStore.Protection.PASSWORD, key, null);
        key.destroy();
    }

    private int run(String... args)
    {
        CodeCommand.Clock clock = new CodeCommand.Clock() {
            @Override
            public long millis()
            {
                return now;
            }

            @Override
            public void sleep(long millis)
            {
                sleeps.add(millis);
                now += millis;
            }
        };
        CodeCommand.PasswordSource source = prompt -> {
            prompts.add(prompt);
            String p = passwords.poll();
            return p == null ? null : p.toCharArray();
        };
        return new CodeCommand(config, new Messages(Locale.ENGLISH), print(out), print(err), source, clock).run(args);
    }

    private static PrintStream print(ByteArrayOutputStream bytes)
    {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private String out()
    {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err()
    {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void writesOnlyTheCode() throws Exception
    {
        byte[] before = Files.readAllBytes(config.toPath());
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)"));
        assertEquals(github.code(NOW / 1000) + System.lineSeparator(), out());
        assertEquals("", err());
        assertEquals(List.of("Password: "), prompts);
        assertTrue(sleeps.isEmpty());
        assertArrayEquals(before, Files.readAllBytes(config.toPath()), "the file is only read");
    }

    @Test
    void namesInAnotherCaseAreFound()
    {
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "github (ME)"));
        assertEquals(github.code(NOW / 1000) + System.lineSeparator(), out());
    }

    @Test
    void servicesWithoutNameUseTheShownName() throws Exception
    {
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "(No Name)"));
        assertEquals(new Account("", "MFRGGZDFMZTWQ2LK").code(NOW / 1000) + System.lineSeparator(), out());
    }

    @Test
    void listsTheNames()
    {
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--list"));
        String nl = System.lineSeparator();
        assertEquals("GitHub (me)" + nl + "日本語のサービス" + nl + "Twice" + nl + "Twice" + nl + "(no name)" + nl, out());
    }

    @Test
    void unknownAndAmbiguousNamesFail()
    {
        passwords.add("pw");
        assertEquals(CodeCommand.FAILED, run("--code", "GitLab"));
        assertTrue(err().contains("No service is named \"GitLab\""), err());
        assertTrue(err().contains("GitHub (me), 日本語のサービス"), err());

        passwords.add("pw");
        assertEquals(CodeCommand.FAILED, run("--code", "twice"));
        assertTrue(err().contains("Several services are named \"twice\""), err());
        assertEquals("", out());
    }

    @Test
    void waitsForTheNextCodeIfItChangesWithin3Seconds()
    {
        now = NOW + 17_500; // 2.5 seconds left
        long next = NOW + 20_000;
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)"));
        assertEquals(List.of(2_600L), sleeps);
        assertEquals(github.code(next / 1000) + System.lineSeparator(), out());
        assertTrue(err().contains("The code changes in 3 seconds"), err());
    }

    @Test
    void doesNotWaitWithMoreThan3SecondsLeft()
    {
        now = NOW + 16_000; // 4 seconds left
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)"));
        assertTrue(sleeps.isEmpty());
        assertEquals(github.code(now / 1000) + System.lineSeparator(), out());
    }

    @Test
    void wrongPasswordsAreAskedAgain()
    {
        passwords.addAll(List.of("bad", "", "pw"));

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)"));
        assertEquals(3, prompts.size());
        assertTrue(err().contains("Bad password."), err());
        assertTrue(err().contains("Enter a password."), err());
    }

    @Test
    void stopsAfterThreeWrongPasswords()
    {
        passwords.addAll(List.of("bad", "bad", "bad", "pw"));

        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)"));
        assertEquals(3, prompts.size());
        assertEquals("", out());
    }

    @Test
    void failsWithoutConsole()
    {
        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)"));
        assertTrue(err().contains("there is no console"), err());
    }

    @Test
    void failsWithoutConfigurationFile()
    {
        config = new File(tmp, "missing.properties");

        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)"));
        assertTrue(err().contains("There is no configuration file"), err());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void wrongOptionsShowTheUsage()
    {
        assertEquals(CodeCommand.USAGE, run("--code"));
        assertEquals(CodeCommand.USAGE, run("--codes", "x"));
        assertEquals(CodeCommand.USAGE, run("--list", "x"));
        assertTrue(err().startsWith("Usage:"), err());
        assertTrue(prompts.isEmpty());

        assertEquals(CodeCommand.OK, run("--help"));
        assertTrue(out().startsWith("Usage:"), out());
    }

    @Test
    void onlyOptionsSelectTheCommandLine()
    {
        assertTrue(CodeCommand.handles(new String[] { "--code", "x" }));
        assertTrue(!CodeCommand.handles(new String[0]));
        assertTrue(!CodeCommand.handles(new String[] { "file.properties" }));
    }
}
