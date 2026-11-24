/*
 * AccountRowTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Dimension;
import java.util.Locale;

import javax.swing.JButton;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;


class AccountRowTest
{
    private static final AccountRow.Actions NONE = new AccountRow.Actions() {
        @Override
        public void copy(Account account, String code) { }

        @Override
        public void rename(Account account) { }

        @Override
        public void delete(Account account) { }

        @Override
        public boolean canMove(Account account, AccountRow.Move where)
        {
            return false;
        }

        @Override
        public void move(Account account, AccountRow.Move where) { }

        @Override
        public void chooseIcon(Account account) { }

        @Override
        public void fetchIcon(Account account) { }

        @Override
        public void removeIcon(Account account) { }
    };

    /** et-otp issue #1: the code must not change the size of the row when it changes. */
    @Test
    void sizeDoesNotChangeWithTheCode() throws Exception
    {
        AccountRow row = new AccountRow(new Account("Example", "JBSWY3DPEHPK3PXP"), new Messages(Locale.ENGLISH), NONE, true);
        row.update(0);
        Dimension first = row.getPreferredSize();

        for (long t = 30; t < 30 * 50; t += 30) {
            row.update(t);
            assertEquals(first, row.getPreferredSize(), "at " + t);
        }
    }

    /** et-otp-v2 issue #24: hidden codes show no digits, keep the size, and can still be copied. */
    @Test
    void hiddenCodesCanBeCopied() throws Exception
    {
        String[] copied = new String[1];
        AccountRow.Actions copy = new AccountRow.Actions() {
            @Override
            public void copy(Account account, String code) { copied[0] = code; }
            @Override
            public void rename(Account account) { }
            @Override
            public void delete(Account account) { }
            @Override
            public boolean canMove(Account account, AccountRow.Move where) { return false; }
            @Override
            public void move(Account account, AccountRow.Move where) { }
            @Override
            public void chooseIcon(Account account) { }
            @Override
            public void fetchIcon(Account account) { }
            @Override
            public void removeIcon(Account account) { }
        };
        Account account = new Account("Example", "JBSWY3DPEHPK3PXP");
        AccountRow row = new AccountRow(account, new Messages(Locale.ENGLISH), copy, true);
        row.update(0);
        String shownText = texts(row);

        row.setCodesHidden(true);
        row.update(0);
        assertFalse(row.codesShown());
        String hiddenText = texts(row);
        assertFalse(hiddenText.matches("(?s).*\\d.*"), hiddenText);
        assertTrue(hiddenText.contains(AccountRow.mask("123456")), hiddenText);
        Dimension hidden = row.getPreferredSize();
        button(row, "Copy").doClick();
        assertEquals(account.code(0), copied[0]);

        JButton show = button(row, "Show");
        assertTrue(show.isVisible());
        show.doClick();
        assertTrue(row.codesShown());
        assertEquals(shownText, texts(row));
        assertEquals(hidden, row.getPreferredSize(), "the row does not move when the codes are shown");

        row.setCodesHidden(false);
        assertTrue(row.codesShown());
        assertFalse(show.isVisible());
    }

    @Test
    void maskKeepsTheGroups()
    {
        assertEquals("••• •••", AccountRow.mask("123456"));
        assertEquals("•••• ••••", AccountRow.mask("12345678"));
    }

    private static String texts(AccountRow row)
    {
        StringBuilder sb = new StringBuilder();
        for (java.awt.Component c : row.getComponents()) {
            if (c instanceof JLabel) {
                sb.append(((JLabel) c).getText()).append('|');
            }
        }
        return sb.toString();
    }

    private static JButton button(AccountRow row, String text)
    {
        for (java.awt.Component c : row.getComponents()) {
            if (c instanceof JButton && text.equals(((JButton) c).getText())) {
                return (JButton) c;
            }
        }
        throw new AssertionError("no button " + text);
    }

    @Test
    void codesAreGroupedForReading()
    {
        assertEquals("123 456", AccountRow.group("123456"));
        assertEquals("1234 5678", AccountRow.group("12345678"));
    }
}
