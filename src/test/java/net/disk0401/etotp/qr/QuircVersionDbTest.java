/*
 * QuircVersionDbTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp.qr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import io.nayuki.qrcodegen.QrCode;


/**
 * Checks the version table against the QR code standard (ISO/IEC 18004) as implemented by an
 * independent generator.
 */
class QuircVersionDbTest
{
    /** Alignment pattern positions, computed with the rule of the standard. */
    @Test
    void alignmentPatternPositions()
    {
        for (int ver = 1; ver <= 40; ver++) {
            int[] expected;
            if (ver == 1) {
                expected = new int[0];
            } else {
                int num = ver / 7 + 2;
                int step = ver == 32 ? 26 : (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
                expected = new int[num];
                expected[0] = 6;
                for (int i = num - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) {
                    expected[i] = pos;
                }
            }
            assertArrayEquals(expected, QuircVersionDb.ALIGNMENT[ver], "version " + ver);
        }
    }

    /** Code word counts and error correction blocks, compared with Nayuki's QR code generator. */
    @Test
    void errorCorrectionBlocks() throws Exception
    {
        Field eccPerBlock = QrCode.class.getDeclaredField("ECC_CODEWORDS_PER_BLOCK");
        Field numBlocks = QrCode.class.getDeclaredField("NUM_ERROR_CORRECTION_BLOCKS");
        Method rawModules = QrCode.class.getDeclaredMethod("getNumRawDataModules", int.class);
        eccPerBlock.setAccessible(true);
        numBlocks.setAccessible(true);
        rawModules.setAccessible(true);
        byte[][] ecc = (byte[][]) eccPerBlock.get(null);
        byte[][] blocks = (byte[][]) numBlocks.get(null);
        // quirc order of the levels: M, L, H, Q
        QrCode.Ecc[] order = {QrCode.Ecc.MEDIUM, QrCode.Ecc.LOW, QrCode.Ecc.HIGH, QrCode.Ecc.QUARTILE};

        for (int ver = 1; ver <= 40; ver++) {
            int total = (int) rawModules.invoke(null, ver) / 8;
            assertEquals(total, QuircVersionDb.DATA_BYTES[ver], "code words of version " + ver);
            for (int level = 0; level < 4; level++) {
                int[] sb = QuircVersionDb.ECC[ver][level];
                int lbCount = (total - sb[0] * sb[2]) / (sb[0] + 1);
                String what = "version " + ver + " " + order[level];
                assertEquals(ecc[order[level].ordinal()][ver], sb[0] - sb[1], "ecc code words per block of " + what);
                assertEquals(blocks[order[level].ordinal()][ver], sb[2] + lbCount, "blocks of " + what);
                assertEquals(total, sb[0] * sb[2] + (sb[0] + 1) * lbCount, "all code words of " + what);
            }
        }
    }
}
