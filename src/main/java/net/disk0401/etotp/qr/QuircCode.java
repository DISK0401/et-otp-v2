/*
 * QuircCode.java - et-otp-v2: a sampled QR code grid.
 *
 * Java port of struct quirc_code and quirc_flip() from quirc (https://github.com/dlbeer/quirc),
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
 * Ported to Java 2026 by DISK0401 for et-otp-v2.
 */
package net.disk0401.etotp.qr;


/**
 * The cells of a QR code as read from the image: size x size cells, true for black.
 */
final class QuircCode
{
    final int size;
    private final boolean[] cells;


    QuircCode(int size)
    {
        this.size = size;
        this.cells = new boolean[Math.max(0, size) * Math.max(0, size)];
    }


    boolean cell(int x, int y)
    {
        return cells[y * size + x];
    }


    void set(int x, int y, boolean black)
    {
        cells[y * size + x] = black;
    }


    /** @return the code mirrored along the diagonal, for codes that were printed or scanned mirrored */
    QuircCode flipped()
    {
        QuircCode f = new QuircCode(size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                f.set(x, y, cell(y, x));
            }
        }
        return f;
    }
}
