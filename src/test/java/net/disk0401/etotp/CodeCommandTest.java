/*
 * CodeCommandTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.HeadlessException;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.Charset;
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
    private byte[] stdin = new byte[0];
    private Charset stdinCharset = StandardCharsets.UTF_8;
    private Clipboard clipboard = new Clipboard("test");

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
        Clipboard clip = clipboard;
        return new CodeCommand(config, new Messages(Locale.ENGLISH), print(out), print(err), source, clock,
                               new ByteArrayInputStream(stdin), stdinCharset, () -> {
                                   if (clip == null) {
                                       throw new HeadlessException();
                                   }
                                   return clip;
                               }).run(args);
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
        assertEquals(CodeCommand.USAGE, run("--list", "--code", "x"));
        assertEquals(CodeCommand.USAGE, run("--code", "x", "--code", "y"));
        assertEquals(CodeCommand.USAGE, run("--code", "x", "--json", "--json"));
        assertEquals(CodeCommand.USAGE, run("--code", "x", "--json", "--copy"));
        assertEquals(CodeCommand.USAGE, run("--code", "x", "--json", "--next"));
        assertEquals(CodeCommand.USAGE, run("--list", "--copy"));
        assertEquals(CodeCommand.USAGE, run("--list", "--next"));
        assertEquals(CodeCommand.USAGE, run("--version", "--json"));
        assertEquals(CodeCommand.USAGE, run("--json"));
        assertEquals(CodeCommand.USAGE, run("--config"));
        assertEquals(CodeCommand.USAGE, run("--list", "--config", " "));
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

    // --- issue #25 ---

    @Test
    void optionsMayComeInAnyOrder()
    {
        stdin = "pw\n".getBytes(StandardCharsets.UTF_8);

        assertEquals(CodeCommand.OK, run("--password-stdin", "--next", "--code", "GitHub (me)"));
        assertEquals(github.code(NOW / 1000 + 30) + System.lineSeparator(), out());
    }

    @Test
    void readsThePasswordFromTheStandardInput()
    {
        stdin = "pw\r\nignored\n".getBytes(StandardCharsets.UTF_8);

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--password-stdin"));
        assertEquals(github.code(NOW / 1000) + System.lineSeparator(), out());
        assertTrue(prompts.isEmpty(), "the console is not asked");
        assertEquals("", err());
    }

    @Test
    void aPasswordWithoutLineEndAndInTheEncodingOfTheInput() throws Exception
    {
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("パス".toCharArray(), TEST_ITERATIONS);
        Files.delete(config.toPath()); // a new file
        AccountStore.save(config, List.of(github), AccountStore.Protection.PASSWORD, key, null);
        key.destroy();
        stdinCharset = Charset.forName("MS932");
        stdin = "パス".getBytes(stdinCharset);

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--password-stdin"));
    }

    @Test
    void aWrongPasswordFromTheStandardInputIsNotAskedAgain()
    {
        stdin = "pw \n".getBytes(StandardCharsets.UTF_8);
        passwords.add("pw");

        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)", "--password-stdin"));
        assertTrue(prompts.isEmpty());
        assertTrue(err().contains("Bad password."), err());
        assertTrue(err().contains("ends with a space"), err());
        assertEquals("", out());
    }

    @Test
    void anEmptyStandardInputFails()
    {
        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)", "--password-stdin"));
        assertTrue(err().contains("No password was given on the standard input"), err());

        stdin = "\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(CodeCommand.FAILED, run("--list", "--password-stdin"));
        assertTrue(err().contains("Enter a password."), err());
        assertEquals("", out());
    }

    @Test
    void readLineStopsAtTheLineEndAndRemovesAByteOrderMark() throws Exception
    {
        Charset ms932 = Charset.forName("MS932");
        // the byte order mark gives the encoding, also when the system uses another one
        for (Charset cs : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] bom = "\uFEFF\u30D1\u30B9 word\r\nd".getBytes(cs);
            assertArrayEquals("\u30D1\u30B9 word".toCharArray(), CodeCommand.readLine(new ByteArrayInputStream(bom), ms932),
                              cs.name());
        }
        // a byte 0x0A inside a UTF-16 character is not a line end
        byte[] utf16 = "\uFEFF\u010Ax\n".getBytes(StandardCharsets.UTF_16LE);
        assertArrayEquals("\u010Ax".toCharArray(), CodeCommand.readLine(new ByteArrayInputStream(utf16), ms932));
        assertArrayEquals("\u30D1\u30B9".toCharArray(),
                          CodeCommand.readLine(new ByteArrayInputStream("\u30D1\u30B9\n".getBytes(ms932)), ms932));
        assertNull(CodeCommand.readLine(new ByteArrayInputStream(new byte[0]), StandardCharsets.UTF_8));
        assertNull(CodeCommand.readLine(new ByteArrayInputStream(new byte[5000]), StandardCharsets.UTF_8), "too long");
    }

    @Test
    void writesTheNextCode()
    {
        now = NOW + 18_000; // 2 seconds left: no waiting for the next code
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--next"));
        assertTrue(sleeps.isEmpty());
        assertEquals(github.code(now / 1000 + 30) + System.lineSeparator(), out());
    }

    @Test
    void writesTheCodeAsJson()
    {
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--json"));
        long t = NOW / 1000;
        assertEquals("{\"name\":\"GitHub (me)\",\"code\":\"" + github.code(t) + "\",\"secondsLeft\":20,\"period\":30,"
                     + "\"next\":\"" + github.code(t + 30) + "\"}" + System.lineSeparator(), out());
    }

    @Test
    void jsonAfterWaiting()
    {
        now = NOW + 18_000;
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--json"));
        assertTrue(out().contains("\"secondsLeft\":30,"), out());
        assertTrue(out().contains("\"code\":\"" + github.code((NOW + 20_100) / 1000) + "\""), out());
    }

    @Test
    void specialCharactersInJson() throws Exception
    {
        Account odd = new Account("a \"b\" \\ c\nd\u0001 日本語 😀", "JBSWY3DPEHPK3PXP");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS);
        Files.delete(config.toPath()); // a new file
        AccountStore.save(config, List.of(odd, new Account("", "MFRGGZDFMZTWQ2LK", Account.SHA1, 8, 60)),
                          AccountStore.Protection.PASSWORD, key, null);
        key.destroy();
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--list", "--json"));
        String expected = "[{\"name\":\"a \\\"b\\\" \\\\ c\\nd\\u0001 \\u65e5\\u672c\\u8a9e \\ud83d\\ude00\",\"digits\":6,"
                          + "\"period\":30},{\"name\":\"\",\"digits\":8,\"period\":60}]" + System.lineSeparator();
        assertEquals(expected, out());
        for (char c : out().toCharArray()) {
            assertTrue(c < 0x80, "ASCII only");
        }

        // the empty name of the JSON finds the service without a name
        out.reset();
        passwords.add("pw");
        assertEquals(CodeCommand.OK, run("--code", ""));
        assertEquals(new Account("", "MFRGGZDFMZTWQ2LK", Account.SHA1, 8, 60).code(NOW / 1000) + System.lineSeparator(),
                     out());
    }

    @Test
    void controlCharactersAreNotWrittenToTheTerminal() throws Exception
    {
        Files.delete(config.toPath()); // a new file
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS);
        AccountStore.save(config, List.of(new Account("a\nb\u001b[31mc\u009b", "JBSWY3DPEHPK3PXP")),
                          AccountStore.Protection.PASSWORD, key, null);
        key.destroy();
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--list"));
        assertEquals("a?b?[31mc?" + System.lineSeparator(), out());

        passwords.add("pw");
        assertEquals(CodeCommand.FAILED, run("--code", "x\u001by"));
        assertTrue(err().contains("\"x?y\"") && err().contains("a?b?[31mc?") && !err().contains("\u001b"), err());
    }

    @Test
    void jsonStringsAreEscaped()
    {
        assertEquals("\"\\\"\\\\\\n\\r\\t\\u0000\\u007f\\u00e9\"", CodeCommand.json("\"\\\n\r\t\u0000\u007f\u00e9"));
        assertEquals("\"\"", CodeCommand.json(""));
    }

    @Test
    void copiesTheCode() throws Exception
    {
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--code", "GitHub (me)", "--copy"));
        assertEquals("", out(), "nothing on the standard output");
        assertEquals(github.code(NOW / 1000), clipboard.getData(DataFlavor.stringFlavor));
        assertTrue(err().contains("Copied the code of GitHub (me)."), err());
    }

    @Test
    void copyFailsWithoutClipboard()
    {
        clipboard = null;
        passwords.add("pw");

        assertEquals(CodeCommand.FAILED, run("--code", "GitHub (me)", "--copy"));
        assertTrue(err().contains("there is no clipboard"), err());
    }

    @Test
    void usesTheGivenConfigurationFile() throws Exception
    {
        File other = new File(tmp, "other.properties");
        SecretStore.PasswordKey key = SecretStore.PasswordKey.create("pw".toCharArray(), TEST_ITERATIONS);
        AccountStore.save(other, List.of(new Account("Only", "GEZDGNBVGY3TQOJQ")), AccountStore.Protection.PASSWORD,
                          key, null);
        key.destroy();
        passwords.add("pw");

        assertEquals(CodeCommand.OK, run("--list", "--config", other.getPath()));
        assertEquals("Only" + System.lineSeparator(), out());

        assertEquals(CodeCommand.FAILED, run("--list", "--config", new File(tmp, "none").getPath()));
        assertTrue(err().contains("There is no configuration file"), err());
    }

    @Test
    void writesTheVersion()
    {
        assertEquals(CodeCommand.OK, run("--version"));
        assertTrue(out().startsWith("et-otp-v2 "), out());
        assertFalse(out().contains("null"), out());
        assertTrue(prompts.isEmpty());
    }
}
