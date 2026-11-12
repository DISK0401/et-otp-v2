/*
 * AccountListTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;


class AccountListTest
{
    @Test
    void namesMatchIgnoringCaseAndWidth()
    {
        assertTrue(AccountList.matches("GitHub (me)", "hub"));
        assertTrue(AccountList.matches("GitHub (me)", "GITHUB"));
        assertTrue(AccountList.matches("AWS 本番", "ａｗｓ"));
        assertTrue(AccountList.matches("ｶﾞｲﾑ", "ガイム"));
        assertTrue(AccountList.matches("日本語のサービス", "サービス"));
        assertTrue(AccountList.matches("AWS", "  aws "));
        assertFalse(AccountList.matches("GitHub", "lab"));
    }

    @Test
    void anEmptyQueryMatchesEverything()
    {
        assertTrue(AccountList.matches("GitHub", ""));
        assertTrue(AccountList.matches("", "  "));
        assertTrue(AccountList.matches("GitHub", "　"));
        assertFalse(AccountList.filters(" 　 "));
        assertTrue(AccountList.filters("a"));
    }

    @Test
    void fullWidthSpacesAroundTheQueryAreIgnored()
    {
        assertTrue(AccountList.matches("AWS", "　ＡＷＳ　"));
    }

    @Test
    void moves()
    {
        List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));

        assertTrue(AccountList.move(list, 2, 1));
        assertEquals(List.of("a", "c", "b", "d"), list);
        assertTrue(AccountList.move(list, 1, 2));
        assertEquals(List.of("a", "b", "c", "d"), list);
        assertTrue(AccountList.move(list, 3, 0));
        assertEquals(List.of("d", "a", "b", "c"), list);
        assertTrue(AccountList.move(list, 0, 3));
        assertEquals(List.of("a", "b", "c", "d"), list);
    }

    @Test
    void movesOutsideTheListChangeNothing()
    {
        List<String> list = new ArrayList<>(List.of("a", "b"));

        assertFalse(AccountList.move(list, 0, -1));
        assertFalse(AccountList.move(list, 1, 2));
        assertFalse(AccountList.move(list, 1, 1));
        assertEquals(List.of("a", "b"), list);
    }
}
