/*
 * AccountList.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 * Licensed under the GNU General Public License v2.0 only.
 */
package net.disk0401.etotp;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;


/**
 * Filtering and ordering of the services in the main window. The order is the order of the list in the
 * configuration file, so it needs no own setting and is the same for {@code --list}.
 */
final class AccountList
{
    private AccountList() { }


    /**
     * @return true if the name contains the query, ignoring case and the difference of full width and
     *         half width characters ("ＡＷＳ" finds "aws"); an empty query matches every name
     */
    static boolean matches(String name, String query)
    {
        // trimmed after folding: a full width space becomes a plain one
        String q = fold(query).trim();
        return q.isEmpty() || fold(name).contains(q);
    }


    /** @return true if the query hides services (it is not empty or only spaces) */
    static boolean filters(String query)
    {
        return !fold(query).trim().isEmpty();
    }


    private static String fold(String text)
    {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }


    /**
     * Moves the element at {@code from} to {@code to}, shifting the elements between.
     *
     * @return false if an index is outside the list or both are the same, then nothing is changed
     */
    static <T> boolean move(List<T> list, int from, int to)
    {
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) {
            return false;
        }
        list.add(to, list.remove(from));
        return true;
    }
}
