/*
 * AccountRowTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;
import java.util.Locale;

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
    };

    /** et-otp issue #1: the code must not change the size of the row when it changes. */
    @Test
    void sizeDoesNotChangeWithTheCode() throws Exception
    {
        AccountRow row = new AccountRow(new Account("Example", "JBSWY3DPEHPK3PXP"), new Messages(Locale.ENGLISH), NONE);
        row.update(0);
        Dimension first = row.getPreferredSize();

        for (long t = 30; t < 30 * 50; t += 30) {
            row.update(t);
            assertEquals(first, row.getPreferredSize(), "at " + t);
        }
    }

    @Test
    void codesAreGroupedForReading()
    {
        assertEquals("123 456", AccountRow.group("123456"));
        assertEquals("1234 5678", AccountRow.group("12345678"));
    }
}
