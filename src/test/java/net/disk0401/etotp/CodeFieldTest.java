/*
 * CodeFieldTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401. Based on et-otp by Bernd Eckenfels.
 */
package net.disk0401.etotp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;

import javax.swing.JLabel;
import javax.swing.JTextField;

import org.junit.jupiter.api.Test;


/**
 * et-otp issue #1: the code fields must not change their size when a code is shown.
 */
class CodeFieldTest
{
    @Test
    void codeFieldKeepsItsSize()
    {
        JTextField field = GUI.createCodeField();
        Dimension empty = field.getPreferredSize();

        field.setText("123456");

        assertEquals(empty, field.getPreferredSize());
    }

    @Test
    void nextCodeLabelKeepsItsSize()
    {
        JLabel label = GUI.createNextCodeLabel();
        Dimension empty = label.getPreferredSize();

        label.setText("987654");

        assertEquals(empty, label.getPreferredSize());
    }
}
