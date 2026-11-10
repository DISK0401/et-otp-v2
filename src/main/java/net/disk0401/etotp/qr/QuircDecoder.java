/*
 * QuircDecoder.java - et-otp-v2: QR code decoding (error correction and data).
 *
 * Java port of decode.c from quirc (https://github.com/dlbeer/quirc),
 * QR-code recognition library.
 * Copyright (C) 2010-2012 Daniel Beer <dlbeer@gmail.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for
 * any purpose with or without fee is hereby granted, provided that the
 * above copyright notice and this permission notice appear in all
 * copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
 * WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
 * AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL
 * DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR
 * PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 * PERFORMANCE OF THIS SOFTWARE.
 *
 * Ported to Java 2026 by DISK0401 for et-otp-v2. Bytes are held in int arrays (0 to 255).
 */
package net.disk0401.etotp.qr;

import java.util.Arrays;


/**
 * Reads the format, corrects errors (Reed-Solomon) and decodes the data of a sampled QR grid.
 */
final class QuircDecoder
{
    static final int MAX_PAYLOAD = 8896;

    static final int DATA_TYPE_NUMERIC = 1;
    static final int DATA_TYPE_ALPHA = 2;
    static final int DATA_TYPE_BYTE = 4;
    static final int DATA_TYPE_KANJI = 8;

    /** Decoding failed; the message names the reason as in quirc_strerror(). */
    static final class DecodeException extends Exception
    {
        private static final long serialVersionUID = 1L;

        DecodeException(String message)
        {
            super(message);
        }
    }

    /** The decoded contents of a QR code (struct quirc_data). */
    static final class Data
    {
        int version;
        int eccLevel;
        int mask;
        /** highest-valued data type found in the code. */
        int dataType;
        /** Kanji is encoded as Shift-JIS, all other types as the bytes in the code. */
        final byte[] payload = new byte[MAX_PAYLOAD];
        int payloadLen;
        /** ECI assignment number, 0 if none. */
        int eci;
        /** number of error correction blocks that contained errors (for tests). */
        int correctedBlocks;
    }

    private static final int MAX_POLY = 64;

    /** Galois field: p is the order minus one. */
    private static final class GaloisField
    {
        final int p;
        final int[] log;
        final int[] exp;

        GaloisField(int p, int[] log, int[] exp)
        {
            this.p = p;
            this.log = log;
            this.exp = exp;
        }
    }

    private static final int[] GF16_EXP = {
        0x01, 0x02, 0x04, 0x08, 0x03, 0x06, 0x0c, 0x0b,
        0x05, 0x0a, 0x07, 0x0e, 0x0f, 0x0d, 0x09, 0x01
    };

    private static final int[] GF16_LOG = {
        0x00, 0x0f, 0x01, 0x04, 0x02, 0x08, 0x05, 0x0a,
        0x03, 0x0e, 0x09, 0x07, 0x06, 0x0d, 0x0b, 0x0c
    };

    private static final int[] GF256_EXP = {
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80,
        0x1d, 0x3a, 0x74, 0xe8, 0xcd, 0x87, 0x13, 0x26,
        0x4c, 0x98, 0x2d, 0x5a, 0xb4, 0x75, 0xea, 0xc9,
        0x8f, 0x03, 0x06, 0x0c, 0x18, 0x30, 0x60, 0xc0,
        0x9d, 0x27, 0x4e, 0x9c, 0x25, 0x4a, 0x94, 0x35,
        0x6a, 0xd4, 0xb5, 0x77, 0xee, 0xc1, 0x9f, 0x23,
        0x46, 0x8c, 0x05, 0x0a, 0x14, 0x28, 0x50, 0xa0,
        0x5d, 0xba, 0x69, 0xd2, 0xb9, 0x6f, 0xde, 0xa1,
        0x5f, 0xbe, 0x61, 0xc2, 0x99, 0x2f, 0x5e, 0xbc,
        0x65, 0xca, 0x89, 0x0f, 0x1e, 0x3c, 0x78, 0xf0,
        0xfd, 0xe7, 0xd3, 0xbb, 0x6b, 0xd6, 0xb1, 0x7f,
        0xfe, 0xe1, 0xdf, 0xa3, 0x5b, 0xb6, 0x71, 0xe2,
        0xd9, 0xaf, 0x43, 0x86, 0x11, 0x22, 0x44, 0x88,
        0x0d, 0x1a, 0x34, 0x68, 0xd0, 0xbd, 0x67, 0xce,
        0x81, 0x1f, 0x3e, 0x7c, 0xf8, 0xed, 0xc7, 0x93,
        0x3b, 0x76, 0xec, 0xc5, 0x97, 0x33, 0x66, 0xcc,
        0x85, 0x17, 0x2e, 0x5c, 0xb8, 0x6d, 0xda, 0xa9,
        0x4f, 0x9e, 0x21, 0x42, 0x84, 0x15, 0x2a, 0x54,
        0xa8, 0x4d, 0x9a, 0x29, 0x52, 0xa4, 0x55, 0xaa,
        0x49, 0x92, 0x39, 0x72, 0xe4, 0xd5, 0xb7, 0x73,
        0xe6, 0xd1, 0xbf, 0x63, 0xc6, 0x91, 0x3f, 0x7e,
        0xfc, 0xe5, 0xd7, 0xb3, 0x7b, 0xf6, 0xf1, 0xff,
        0xe3, 0xdb, 0xab, 0x4b, 0x96, 0x31, 0x62, 0xc4,
        0x95, 0x37, 0x6e, 0xdc, 0xa5, 0x57, 0xae, 0x41,
        0x82, 0x19, 0x32, 0x64, 0xc8, 0x8d, 0x07, 0x0e,
        0x1c, 0x38, 0x70, 0xe0, 0xdd, 0xa7, 0x53, 0xa6,
        0x51, 0xa2, 0x59, 0xb2, 0x79, 0xf2, 0xf9, 0xef,
        0xc3, 0x9b, 0x2b, 0x56, 0xac, 0x45, 0x8a, 0x09,
        0x12, 0x24, 0x48, 0x90, 0x3d, 0x7a, 0xf4, 0xf5,
        0xf7, 0xf3, 0xfb, 0xeb, 0xcb, 0x8b, 0x0b, 0x16,
        0x2c, 0x58, 0xb0, 0x7d, 0xfa, 0xe9, 0xcf, 0x83,
        0x1b, 0x36, 0x6c, 0xd8, 0xad, 0x47, 0x8e, 0x01
    };

    private static final int[] GF256_LOG = {
        0x00, 0xff, 0x01, 0x19, 0x02, 0x32, 0x1a, 0xc6,
        0x03, 0xdf, 0x33, 0xee, 0x1b, 0x68, 0xc7, 0x4b,
        0x04, 0x64, 0xe0, 0x0e, 0x34, 0x8d, 0xef, 0x81,
        0x1c, 0xc1, 0x69, 0xf8, 0xc8, 0x08, 0x4c, 0x71,
        0x05, 0x8a, 0x65, 0x2f, 0xe1, 0x24, 0x0f, 0x21,
        0x35, 0x93, 0x8e, 0xda, 0xf0, 0x12, 0x82, 0x45,
        0x1d, 0xb5, 0xc2, 0x7d, 0x6a, 0x27, 0xf9, 0xb9,
        0xc9, 0x9a, 0x09, 0x78, 0x4d, 0xe4, 0x72, 0xa6,
        0x06, 0xbf, 0x8b, 0x62, 0x66, 0xdd, 0x30, 0xfd,
        0xe2, 0x98, 0x25, 0xb3, 0x10, 0x91, 0x22, 0x88,
        0x36, 0xd0, 0x94, 0xce, 0x8f, 0x96, 0xdb, 0xbd,
        0xf1, 0xd2, 0x13, 0x5c, 0x83, 0x38, 0x46, 0x40,
        0x1e, 0x42, 0xb6, 0xa3, 0xc3, 0x48, 0x7e, 0x6e,
        0x6b, 0x3a, 0x28, 0x54, 0xfa, 0x85, 0xba, 0x3d,
        0xca, 0x5e, 0x9b, 0x9f, 0x0a, 0x15, 0x79, 0x2b,
        0x4e, 0xd4, 0xe5, 0xac, 0x73, 0xf3, 0xa7, 0x57,
        0x07, 0x70, 0xc0, 0xf7, 0x8c, 0x80, 0x63, 0x0d,
        0x67, 0x4a, 0xde, 0xed, 0x31, 0xc5, 0xfe, 0x18,
        0xe3, 0xa5, 0x99, 0x77, 0x26, 0xb8, 0xb4, 0x7c,
        0x11, 0x44, 0x92, 0xd9, 0x23, 0x20, 0x89, 0x2e,
        0x37, 0x3f, 0xd1, 0x5b, 0x95, 0xbc, 0xcf, 0xcd,
        0x90, 0x87, 0x97, 0xb2, 0xdc, 0xfc, 0xbe, 0x61,
        0xf2, 0x56, 0xd3, 0xab, 0x14, 0x2a, 0x5d, 0x9e,
        0x84, 0x3c, 0x39, 0x53, 0x47, 0x6d, 0x41, 0xa2,
        0x1f, 0x2d, 0x43, 0xd8, 0xb7, 0x7b, 0xa4, 0x76,
        0xc4, 0x17, 0x49, 0xec, 0x7f, 0x0c, 0x6f, 0xf6,
        0x6c, 0xa1, 0x3b, 0x52, 0x29, 0x9d, 0x55, 0xaa,
        0xfb, 0x60, 0x86, 0xb1, 0xbb, 0xcc, 0x3e, 0x5a,
        0xcb, 0x59, 0x5f, 0xb0, 0x9c, 0xa9, 0xa0, 0x51,
        0x0b, 0xf5, 0x16, 0xeb, 0x7a, 0x75, 0x2c, 0xd7,
        0x4f, 0xae, 0xd5, 0xe9, 0xe6, 0xe7, 0xad, 0xe8,
        0x74, 0xd6, 0xf4, 0xea, 0xa8, 0x50, 0x58, 0xaf
    };

    private static final GaloisField GF16 = new GaloisField(15, GF16_LOG, GF16_EXP);
    private static final GaloisField GF256 = new GaloisField(255, GF256_LOG, GF256_EXP);


    private QuircDecoder() { }


    // --- polynomial operations ---

    private static void polyAdd(int[] dst, int[] src, int c, int shift, GaloisField gf)
    {
        if (c == 0) {
            return;
        }
        int logC = gf.log[c];
        for (int i = 0; i < MAX_POLY; i++) {
            int p = i + shift;
            int v = src[i];
            if (p < 0 || p >= MAX_POLY || v == 0) {
                continue;
            }
            dst[p] ^= gf.exp[(gf.log[v] + logC) % gf.p];
        }
    }


    private static int polyEval(int[] s, int x, GaloisField gf)
    {
        if (x == 0) {
            return s[0];
        }
        int sum = 0;
        int logX = gf.log[x];
        for (int i = 0; i < MAX_POLY; i++) {
            int c = s[i];
            if (c == 0) {
                continue;
            }
            sum ^= gf.exp[(gf.log[c] + logX * i) % gf.p];
        }
        return sum;
    }


    /** Berlekamp-Massey algorithm for finding error locator polynomials. */
    private static void berlekampMassey(int[] s, int n, GaloisField gf, int[] sigma)
    {
        int[] c = new int[MAX_POLY];
        int[] b = new int[MAX_POLY];
        int l = 0;
        int m = 1;
        int bb = 1;
        b[0] = 1;
        c[0] = 1;

        for (int k = 0; k < n; k++) {
            int d = s[k];
            for (int i = 1; i <= l; i++) {
                if (c[i] == 0 || s[k - i] == 0) {
                    continue;
                }
                d ^= gf.exp[(gf.log[c[i]] + gf.log[s[k - i]]) % gf.p];
            }

            int mult = gf.exp[(gf.p - gf.log[bb] + gf.log[d]) % gf.p];

            if (d == 0) {
                m++;
            } else if (l * 2 <= k) {
                int[] t = c.clone();
                polyAdd(c, b, mult, m, gf);
                System.arraycopy(t, 0, b, 0, MAX_POLY);
                l = k + 1 - l;
                bb = d;
                m = 1;
            } else {
                polyAdd(c, b, mult, m, gf);
                m++;
            }
        }
        System.arraycopy(c, 0, sigma, 0, MAX_POLY);
    }


    // --- code stream error correction; generator polynomial for GF(2^8) is x^8 + x^4 + x^3 + x^2 + 1 ---

    private static boolean blockSyndromes(int[] data, int off, int bs, int npar, int[] s)
    {
        boolean nonzero = false;
        Arrays.fill(s, 0);
        for (int i = 0; i < npar; i++) {
            for (int j = 0; j < bs; j++) {
                int c = data[off + bs - j - 1];
                if (c == 0) {
                    continue;
                }
                s[i] ^= GF256_EXP[(GF256_LOG[c] + i * j) % 255];
            }
            if (s[i] != 0) {
                nonzero = true;
            }
        }
        return nonzero;
    }


    private static void elocPoly(int[] omega, int[] s, int[] sigma, int npar)
    {
        Arrays.fill(omega, 0);
        for (int i = 0; i < npar; i++) {
            int a = sigma[i];
            if (a == 0) {
                continue;
            }
            int logA = GF256_LOG[a];
            for (int j = 0; j + 1 < MAX_POLY; j++) {
                int b = s[j + 1];
                if (i + j >= npar) {
                    break;
                }
                if (b == 0) {
                    continue;
                }
                omega[i + j] ^= GF256_EXP[(logA + GF256_LOG[b]) % 255];
            }
        }
    }


    /** Corrects one block of bs code words, of which dw are data words. */
    /** @return true if the block contained errors (which were corrected) */
    private static boolean correctBlock(int[] data, int off, int bs, int dw) throws DecodeException
    {
        int npar = bs - dw;
        int[] s = new int[MAX_POLY];
        int[] sigma = new int[MAX_POLY];
        int[] sigmaDeriv = new int[MAX_POLY];
        int[] omega = new int[MAX_POLY];

        if (!blockSyndromes(data, off, bs, npar, s)) {
            return false;
        }
        berlekampMassey(s, npar, GF256, sigma);

        for (int i = 0; i + 1 < MAX_POLY; i += 2) {
            sigmaDeriv[i] = sigma[i + 1];
        }
        elocPoly(omega, s, sigma, npar - 1);

        for (int i = 0; i < bs; i++) {
            int xinv = GF256_EXP[255 - i];
            if (polyEval(sigma, xinv, GF256) == 0) {
                int sdX = polyEval(sigmaDeriv, xinv, GF256);
                int omegaX = polyEval(omega, xinv, GF256);
                int error = GF256_EXP[(255 - GF256_LOG[sdX] + GF256_LOG[omegaX]) % 255];
                data[off + bs - i - 1] ^= error;
            }
        }

        if (blockSyndromes(data, off, bs, npar, s)) {
            throw new DecodeException("ECC failure");
        }
        return true;
    }


    // --- format value error correction; generator polynomial for GF(2^4) is x^4 + x + 1 ---

    private static final int FORMAT_MAX_ERROR = 3;
    private static final int FORMAT_SYNDROMES = FORMAT_MAX_ERROR * 2;
    private static final int FORMAT_BITS = 15;

    private static boolean formatSyndromes(int u, int[] s)
    {
        boolean nonzero = false;
        Arrays.fill(s, 0);
        for (int i = 0; i < FORMAT_SYNDROMES; i++) {
            s[i] = 0;
            for (int j = 0; j < FORMAT_BITS; j++) {
                if ((u & (1 << j)) != 0) {
                    s[i] ^= GF16_EXP[((i + 1) * j) % 15];
                }
            }
            if (s[i] != 0) {
                nonzero = true;
            }
        }
        return nonzero;
    }


    private static int correctFormat(int u) throws DecodeException
    {
        int[] s = new int[MAX_POLY];
        int[] sigma = new int[MAX_POLY];

        if (!formatSyndromes(u, s)) {
            return u;
        }
        berlekampMassey(s, FORMAT_SYNDROMES, GF16, sigma);

        for (int i = 0; i < 15; i++) {
            if (polyEval(sigma, GF16_EXP[15 - i], GF16) == 0) {
                u ^= 1 << i;
            }
        }
        if (formatSyndromes(u, s)) {
            throw new DecodeException("Format data ECC failure");
        }
        return u;
    }


    // --- decoder algorithm ---

    private static final class DataStream
    {
        int[] raw;
        int dataBits;
        int ptr;
        final int[] data = new int[MAX_PAYLOAD];
    }


    private static int gridBit(QuircCode code, int x, int y)
    {
        return code.cell(x, y) ? 1 : 0;
    }


    private static void readFormat(QuircCode code, Data data, boolean which) throws DecodeException
    {
        int format = 0;
        if (which) {
            for (int i = 0; i < 7; i++) {
                format = (format << 1) | gridBit(code, 8, code.size - 1 - i);
            }
            for (int i = 0; i < 8; i++) {
                format = (format << 1) | gridBit(code, code.size - 8 + i, 8);
            }
        } else {
            final int[] xs = {8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0};
            final int[] ys = {0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8};
            for (int i = 14; i >= 0; i--) {
                format = (format << 1) | gridBit(code, xs[i], ys[i]);
            }
        }
        format ^= 0x5412;
        format = correctFormat(format) & 0xffff;

        int fdata = format >> 10;
        data.eccLevel = fdata >> 3;
        data.mask = fdata & 7;
    }


    private static boolean maskBit(int mask, int i, int j)
    {
        switch (mask) {
            case 0: return (i + j) % 2 == 0;
            case 1: return i % 2 == 0;
            case 2: return j % 3 == 0;
            case 3: return (i + j) % 3 == 0;
            case 4: return ((i / 2) + (j / 3)) % 2 == 0;
            case 5: return (i * j) % 2 + (i * j) % 3 == 0;
            case 6: return ((i * j) % 2 + (i * j) % 3) % 2 == 0;
            case 7: return ((i * j) % 3 + (i + j) % 2) % 2 == 0;
            default: return false;
        }
    }


    private static boolean reservedCell(int version, int i, int j)
    {
        int[] apat = QuircVersionDb.ALIGNMENT[version];
        int size = version * 4 + 17;
        int ai = -1;
        int aj = -1;

        // finder + format: top left, bottom left, top right
        if (i < 9 && j < 9) {
            return true;
        }
        if (i + 8 >= size && j < 9) {
            return true;
        }
        if (i < 9 && j + 8 >= size) {
            return true;
        }
        // timing patterns
        if (i == 6 || j == 6) {
            return true;
        }
        // version info, next to the top right and bottom left finders
        if (version >= 7) {
            if (i < 6 && j + 11 >= size) {
                return true;
            }
            if (i + 11 >= size && j < 6) {
                return true;
            }
        }
        // alignment patterns
        int a;
        for (a = 0; a < apat.length; a++) {
            int p = apat[a];
            if (Math.abs(p - i) < 3) {
                ai = a;
            }
            if (Math.abs(p - j) < 3) {
                aj = a;
            }
        }
        if (ai >= 0 && aj >= 0) {
            a--;
            if (ai > 0 && ai < a) {
                return true;
            }
            if (aj > 0 && aj < a) {
                return true;
            }
            if (aj == a && ai == a) {
                return true;
            }
        }
        return false;
    }


    private static void readBit(QuircCode code, Data data, DataStream ds, int i, int j)
    {
        int bitpos = ds.dataBits & 7;
        int bytepos = ds.dataBits >> 3;
        int v = gridBit(code, j, i);
        if (maskBit(data.mask, i, j)) {
            v ^= 1;
        }
        if (v != 0) {
            ds.raw[bytepos] |= 0x80 >> bitpos;
        }
        ds.dataBits++;
    }


    private static void readData(QuircCode code, Data data, DataStream ds)
    {
        int y = code.size - 1;
        int x = code.size - 1;
        int dir = -1;

        while (x > 0) {
            if (x == 6) {
                x--;
            }
            if (!reservedCell(data.version, y, x)) {
                readBit(code, data, ds, y, x);
            }
            if (!reservedCell(data.version, y, x - 1)) {
                readBit(code, data, ds, y, x - 1);
            }
            y += dir;
            if (y < 0 || y >= code.size) {
                dir = -dir;
                x -= 2;
                y += dir;
            }
        }
    }


    private static void codestreamEcc(Data data, DataStream ds) throws DecodeException
    {
        int[] sb = QuircVersionDb.ECC[data.version][data.eccLevel];
        int sbBs = sb[0];
        int sbDw = sb[1];
        int sbNs = sb[2];
        int lbCount = (QuircVersionDb.DATA_BYTES[data.version] - sbBs * sbNs) / (sbBs + 1);
        int bc = lbCount + sbNs;
        int eccOffset = sbDw * bc + lbCount;
        int dstOffset = 0;

        for (int i = 0; i < bc; i++) {
            boolean small = i < sbNs;
            int bs = small ? sbBs : sbBs + 1;
            int dw = small ? sbDw : sbDw + 1;
            int numEc = bs - dw;

            for (int j = 0; j < sbDw; j++) {
                ds.data[dstOffset + j] = ds.raw[j * bc + i];
            }
            if (!small) {
                // the extra data code word exists only in the large blocks, which follow the small ones.
                // quirc reads it at sbDw * bc + i, which takes an error correction code word instead and
                // costs one correctable error per large block; this is the position of ISO/IEC 18004.
                ds.data[dstOffset + sbDw] = ds.raw[sbDw * bc + (i - sbNs)];
            }
            for (int j = 0; j < numEc; j++) {
                ds.data[dstOffset + dw + j] = ds.raw[eccOffset + j * bc + i];
            }
            if (correctBlock(ds.data, dstOffset, bs, dw)) {
                data.correctedBlocks++;
            }
            dstOffset += dw;
        }
        ds.dataBits = dstOffset * 8;
    }


    private static int bitsRemaining(DataStream ds)
    {
        return ds.dataBits - ds.ptr;
    }


    private static int takeBits(DataStream ds, int len)
    {
        int ret = 0;
        while (len > 0 && ds.ptr < ds.dataBits) {
            int b = ds.data[ds.ptr >> 3];
            int bitpos = ds.ptr & 7;
            ret <<= 1;
            if (((b << bitpos) & 0x80) != 0) {
                ret |= 1;
            }
            ds.ptr++;
            len--;
        }
        return ret;
    }


    private static boolean numericTuple(Data data, DataStream ds, int bits, int digits)
    {
        if (bitsRemaining(ds) < bits) {
            return false;
        }
        int tuple = takeBits(ds, bits);
        for (int i = digits - 1; i >= 0; i--) {
            data.payload[data.payloadLen + i] = (byte) (tuple % 10 + '0');
            tuple /= 10;
        }
        data.payloadLen += digits;
        return true;
    }


    private static void decodeNumeric(Data data, DataStream ds) throws DecodeException
    {
        int bits = data.version < 10 ? 10 : data.version < 27 ? 12 : 14;
        int count = takeBits(ds, bits);
        if (data.payloadLen + count + 1 > MAX_PAYLOAD) {
            throw new DecodeException("Data overflow");
        }
        while (count >= 3) {
            if (!numericTuple(data, ds, 10, 3)) {
                throw new DecodeException("Data underflow");
            }
            count -= 3;
        }
        if (count >= 2) {
            if (!numericTuple(data, ds, 7, 2)) {
                throw new DecodeException("Data underflow");
            }
            count -= 2;
        }
        if (count > 0) {
            if (!numericTuple(data, ds, 4, 1)) {
                throw new DecodeException("Data underflow");
            }
        }
    }


    private static final String ALPHA_MAP = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private static boolean alphaTuple(Data data, DataStream ds, int bits, int digits)
    {
        if (bitsRemaining(ds) < bits) {
            return false;
        }
        int tuple = takeBits(ds, bits);
        for (int i = 0; i < digits; i++) {
            data.payload[data.payloadLen + digits - i - 1] = (byte) ALPHA_MAP.charAt(tuple % 45);
            tuple /= 45;
        }
        data.payloadLen += digits;
        return true;
    }


    private static void decodeAlpha(Data data, DataStream ds) throws DecodeException
    {
        int bits = data.version < 10 ? 9 : data.version < 27 ? 11 : 13;
        int count = takeBits(ds, bits);
        if (data.payloadLen + count + 1 > MAX_PAYLOAD) {
            throw new DecodeException("Data overflow");
        }
        while (count >= 2) {
            if (!alphaTuple(data, ds, 11, 2)) {
                throw new DecodeException("Data underflow");
            }
            count -= 2;
        }
        if (count > 0) {
            if (!alphaTuple(data, ds, 6, 1)) {
                throw new DecodeException("Data underflow");
            }
        }
    }


    private static void decodeByte(Data data, DataStream ds) throws DecodeException
    {
        int bits = data.version < 10 ? 8 : 16;
        int count = takeBits(ds, bits);
        if (data.payloadLen + count + 1 > MAX_PAYLOAD) {
            throw new DecodeException("Data overflow");
        }
        if (bitsRemaining(ds) < count * 8) {
            throw new DecodeException("Data underflow");
        }
        for (int i = 0; i < count; i++) {
            data.payload[data.payloadLen++] = (byte) takeBits(ds, 8);
        }
    }


    private static void decodeKanji(Data data, DataStream ds) throws DecodeException
    {
        int bits = data.version < 10 ? 8 : data.version < 27 ? 10 : 12;
        int count = takeBits(ds, bits);
        if (data.payloadLen + count * 2 + 1 > MAX_PAYLOAD) {
            throw new DecodeException("Data overflow");
        }
        if (bitsRemaining(ds) < count * 13) {
            throw new DecodeException("Data underflow");
        }
        for (int i = 0; i < count; i++) {
            int d = takeBits(ds, 13);
            int msB = d / 0xc0;
            int lsB = d % 0xc0;
            int intermediate = (msB << 8) | lsB;
            int sjw = intermediate + 0x8140 <= 0x9ffc ? intermediate + 0x8140 : intermediate + 0xc140;
            data.payload[data.payloadLen++] = (byte) (sjw >> 8);
            data.payload[data.payloadLen++] = (byte) sjw;
        }
    }


    private static void decodeEci(Data data, DataStream ds) throws DecodeException
    {
        if (bitsRemaining(ds) < 8) {
            throw new DecodeException("Data underflow");
        }
        data.eci = takeBits(ds, 8);
        if ((data.eci & 0xc0) == 0x80) {
            if (bitsRemaining(ds) < 8) {
                throw new DecodeException("Data underflow");
            }
            data.eci = (data.eci << 8) | takeBits(ds, 8);
        } else if ((data.eci & 0xe0) == 0xc0) {
            if (bitsRemaining(ds) < 16) {
                throw new DecodeException("Data underflow");
            }
            data.eci = (data.eci << 16) | takeBits(ds, 16);
        }
    }


    private static void decodePayload(Data data, DataStream ds) throws DecodeException
    {
        while (bitsRemaining(ds) >= 4) {
            int type = takeBits(ds, 4);
            switch (type) {
                case DATA_TYPE_NUMERIC:
                    decodeNumeric(data, ds);
                    break;
                case DATA_TYPE_ALPHA:
                    decodeAlpha(data, ds);
                    break;
                case DATA_TYPE_BYTE:
                    decodeByte(data, ds);
                    break;
                case DATA_TYPE_KANJI:
                    decodeKanji(data, ds);
                    break;
                case 7:
                    decodeEci(data, ds);
                    break;
                default:
                    return;
            }
            if ((type & (type - 1)) == 0 && type > data.dataType) {
                data.dataType = type;
            }
        }
    }


    /** Decodes a sampled grid (quirc_decode). */
    static Data decode(QuircCode code) throws DecodeException
    {
        if (code.size > Quirc.MAX_GRID_SIZE || (code.size - 17) % 4 != 0) {
            throw new DecodeException("Invalid grid size");
        }
        Data data = new Data();
        data.version = (code.size - 17) / 4;
        if (data.version < 1 || data.version > QuircVersionDb.MAX_VERSION) {
            throw new DecodeException("Invalid version");
        }

        // format information: try both locations
        try {
            readFormat(code, data, false);
        } catch (DecodeException e) {
            readFormat(code, data, true);
        }

        DataStream ds = new DataStream();
        ds.raw = new int[MAX_PAYLOAD];
        readData(code, data, ds);
        codestreamEcc(data, ds);
        ds.raw = null;

        decodePayload(data, ds);
        return data;
    }
}
