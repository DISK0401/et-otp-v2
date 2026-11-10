/*
 * QuircDecoderTest.java - et-otp-v2: GPL Java TOTP soft token.
 * Copyright (C) 2026 DISK0401.
 */
package net.disk0401.etotp.qr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.nayuki.qrcodegen.QrCode;
import io.nayuki.qrcodegen.QrSegment;


/**
 * Decodes grids taken directly from an independent generator, without an image in between.
 */
class QuircDecoderTest
{
    /**
     * A clean grid must decode without any correction. This checks the de-interleaving of the code
     * words, also for versions with small and large blocks (where quirc reads a wrong code word).
     */
    @Test
    void cleanGridsNeedNoCorrectionInAnyVersionAndLevel() throws Exception
    {
        for (int version = 1; version <= 40; version++) {
            for (QrCode.Ecc ecc : QrCode.Ecc.values()) {
                // short enough for version 1-H; larger versions are padded, which also exercises all blocks
                String text = "OTP" + version;
                QrCode qr = QrCode.encodeSegments(QrSegment.makeSegments(text), ecc, version, version, -1, false);
                QuircDecoder.Data data = QuircDecoder.decode(grid(qr));

                assertEquals(text, new String(data.payload, 0, data.payloadLen, StandardCharsets.UTF_8),
                             "version " + version + " " + ecc);
                assertEquals(0, data.correctedBlocks, "version " + version + " " + ecc);
            }
        }
    }

    @Test
    void correctsErrorsUpToTheLimitOfEachBlock() throws Exception
    {
        // version 5-Q has two blocks of 33 and two of 34 code words with 18 error correction code words each
        String text = "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP";
        QrCode qr = QrCode.encodeSegments(QrSegment.makeSegments(text), QrCode.Ecc.QUARTILE, 5, 5, 3, false);
        QuircCode grid = grid(qr);
        // flip the cells of the first rows of data on the right side (they hold the last code words)
        int flipped = 0;
        for (int x = grid.size - 1; x >= grid.size - 4 && flipped < 8; x -= 2) {
            for (int y = grid.size - 1; y >= grid.size - 4; y--) {
                grid.set(x, y, !grid.cell(x, y));
                flipped++;
            }
        }
        QuircDecoder.Data data = QuircDecoder.decode(grid);

        assertEquals(text, new String(data.payload, 0, data.payloadLen, StandardCharsets.UTF_8));
    }

    private static QuircCode grid(QrCode qr)
    {
        QuircCode code = new QuircCode(qr.size);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                code.set(x, y, qr.getModule(x, y));
            }
        }
        return code;
    }
}
