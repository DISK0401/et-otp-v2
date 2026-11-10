/*
 * Quirc.java - et-otp-v2: finds QR codes in a grayscale image.
 *
 * Java port of identify.c and quirc.c from quirc (https://github.com/dlbeer/quirc),
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
 * Ported to Java 2026 by DISK0401 for et-otp-v2. Differences to the C code: the region limit is
 * raised from 254 to 65534 (pixels are int instead of uint8_t), so screenshots with many dark
 * shapes do not run out of regions; the struct pointers are object references.
 */
package net.disk0401.etotp.qr;


/**
 * Locates QR codes: thresholds the image, finds the finder patterns ("capstones"), groups them
 * into grids, fits a perspective transform and samples the cells.
 * <p>
 * Usage: {@code new Quirc(w, h, gray)}, then {@link #count()} and {@link #extract(int)}.
 */
final class Quirc
{
    static final int MAX_GRID_SIZE = QuircVersionDb.MAX_VERSION * 4 + 17;

    private static final int PIXEL_WHITE = 0;
    private static final int PIXEL_BLACK = 1;
    private static final int PIXEL_REGION = 2;
    private static final int MAX_REGIONS = 65534;
    private static final int MAX_CAPSTONES = 32;
    private static final int MAX_GRIDS = MAX_CAPSTONES * 2;

    private static final class Point
    {
        int x;
        int y;

        Point() { }

        Point(Point p)
        {
            x = p.x;
            y = p.y;
        }

        void set(Point p)
        {
            x = p.x;
            y = p.y;
        }
    }

    private static final class Region
    {
        final Point seed = new Point();
        int count;
        int capstone = -1;
    }

    private static final class Capstone
    {
        int ring;
        int stone;
        final Point[] corners = {new Point(), new Point(), new Point(), new Point()};
        final Point center = new Point();
        final double[] c = new double[8];
        int qrGrid = -1;
    }

    private static final class Grid
    {
        final int[] caps = new int[3];
        int alignRegion = -1;
        final Point align = new Point();
        int gridSize;
        final double[] c = new double[8];
    }

    /** Receives each horizontal span filled by the flood fill. */
    private interface SpanFunc
    {
        void span(int y, int left, int right);
    }

    private final int w;
    private final int h;
    private final int[] pixels;
    private final java.util.List<Region> regions = new java.util.ArrayList<>();
    private final java.util.List<Capstone> capstones = new java.util.ArrayList<>();
    private final java.util.List<Grid> grids = new java.util.ArrayList<>();

    // flood fill work area ("stack" of contexts)
    private final int numFloodFillVars;
    private final int[] ffY;
    private final int[] ffRight;
    private final int[] ffLeftUp;
    private final int[] ffLeftDown;


    /**
     * Identifies the QR codes in the image (quirc_begin() and quirc_end()).
     *
     * @param gray w * h luminance values (0 = black, 255 = white), row by row
     */
    Quirc(int w, int h, byte[] gray)
    {
        if (w <= 0 || h <= 0 || gray.length < (long) w * h) {
            throw new IllegalArgumentException("invalid image size");
        }
        this.w = w;
        this.h = h;
        this.pixels = new int[w * h];
        // regions 0 and 1 stand for the white and black pixel values
        regions.add(new Region());
        regions.add(new Region());

        int vars = h * 2 / 3;
        numFloodFillVars = vars == 0 ? 1 : vars;
        ffY = new int[numFloodFillVars];
        ffRight = new int[numFloodFillVars];
        ffLeftUp = new int[numFloodFillVars];
        ffLeftDown = new int[numFloodFillVars];

        int threshold = otsu(gray);
        for (int i = 0; i < w * h; i++) {
            pixels[i] = (gray[i] & 0xff) < threshold ? PIXEL_BLACK : PIXEL_WHITE;
        }
        for (int y = 0; y < h; y++) {
            finderScan(y);
        }
        for (int i = 0; i < capstones.size(); i++) {
            testGrouping(i);
        }
    }


    /** @return the number of QR codes found (quirc_count) */
    int count()
    {
        return grids.size();
    }


    // --- linear algebra ---

    private static boolean lineIntersect(Point p0, Point p1, Point q0, Point q1, Point r)
    {
        // (a, b) is perpendicular to line p, (c, d) to line q; long, as the products overflow int
        // for large images (quirc uses int)
        long a = -(p1.y - p0.y);
        long b = p1.x - p0.x;
        long c = -(q1.y - q0.y);
        long d = q1.x - q0.x;
        // e and f are dot products of the respective vectors with p and q
        long e = a * p1.x + b * p1.y;
        long f = c * q1.x + d * q1.y;
        long det = (a * d) - (b * c);
        if (det == 0) {
            return false;
        }
        long x = (d * e - b * f) / det;
        long y = (-c * e + a * f) / det;
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE) {
            return false;
        }
        r.x = (int) x;
        r.y = (int) y;
        return true;
    }


    private static void perspectiveSetup(double[] c, Point[] rect, double w, double h)
    {
        double x0 = rect[0].x;
        double y0 = rect[0].y;
        double x1 = rect[1].x;
        double y1 = rect[1].y;
        double x2 = rect[2].x;
        double y2 = rect[2].y;
        double x3 = rect[3].x;
        double y3 = rect[3].y;

        double wden = 1.0 / (w * (x2 * y3 - x3 * y2 + (x3 - x2) * y1 + x1 * (y2 - y3)));
        double hden = 1.0 / (h * (x2 * y3 + x1 * (y2 - y3) - x3 * y2 + (x3 - x2) * y1));

        c[0] = (x1 * (x2 * y3 - x3 * y2) + x0 * (-x2 * y3 + x3 * y2 + (x2 - x3) * y1)
                + x1 * (x3 - x2) * y0) * wden;
        c[1] = -(x0 * (x2 * y3 + x1 * (y2 - y3) - x2 * y1) - x1 * x3 * y2 + x2 * x3 * y1
                 + (x1 * x3 - x2 * x3) * y0) * hden;
        c[2] = x0;
        c[3] = (y0 * (x1 * (y3 - y2) - x2 * y3 + x3 * y2) + y1 * (x2 * y3 - x3 * y2)
                + x0 * y1 * (y2 - y3)) * wden;
        c[4] = (x0 * (y1 * y3 - y2 * y3) + x1 * y2 * y3 - x2 * y1 * y3
                + y0 * (x3 * y2 - x1 * y2 + (x2 - x3) * y1)) * hden;
        c[5] = y0;
        c[6] = (x1 * (y3 - y2) + x0 * (y2 - y3) + (x2 - x3) * y1 + (x3 - x2) * y0) * wden;
        c[7] = (-x2 * y3 + x1 * y3 + x3 * y2 + x0 * (y1 - y2) - x3 * y1 + (x2 - x1) * y0) * hden;
    }


    private static void perspectiveMap(double[] c, double u, double v, Point ret)
    {
        double den = 1.0 / (c[6] * u + c[7] * v + 1.0);
        double x = (c[0] * u + c[1] * v + c[2]) * den;
        double y = (c[3] * u + c[4] * v + c[5]) * den;
        ret.x = (int) Math.rint(x);
        ret.y = (int) Math.rint(y);
    }


    /** @return {u, v} */
    private static double[] perspectiveUnmap(double[] c, Point in)
    {
        double x = in.x;
        double y = in.y;
        double den = 1.0 / (-c[0] * c[7] * y + c[1] * c[6] * y + (c[3] * c[7] - c[4] * c[6]) * x
                            + c[0] * c[4] - c[1] * c[3]);
        double u = -(c[1] * (y - c[5]) - c[2] * c[7] * y + (c[5] * c[7] - c[4]) * x + c[2] * c[4]) * den;
        double v = (c[0] * (y - c[5]) - c[2] * c[6] * y + (c[5] * c[6] - c[3]) * x + c[2] * c[3]) * den;
        return new double[] {u, v};
    }


    // --- span-based flood fill ---

    private int[] floodFillLine(int x, int y, int from, int to, SpanFunc func)
    {
        int row = y * w;
        int left = x;
        int right = x;
        while (left > 0 && pixels[row + left - 1] == from) {
            left--;
        }
        while (right < w - 1 && pixels[row + right + 1] == from) {
            right++;
        }
        for (int i = left; i <= right; i++) {
            pixels[row + i] = to;
        }
        if (func != null) {
            func.span(y, left, right);
        }
        return new int[] {left, right};
    }


    /** @return the index of the next context, or -1 */
    private int floodFillCallNext(int rowY, int from, int to, SpanFunc func, int vars, int direction)
    {
        int[] leftp = direction < 0 ? ffLeftUp : ffLeftDown;
        int row = rowY * w;
        while (leftp[vars] <= ffRight[vars]) {
            if (pixels[row + leftp[vars]] == from) {
                int next = vars + 1;
                ffY[next] = ffY[vars] + direction;
                int[] lr = floodFillLine(leftp[vars], ffY[next], from, to, func);
                ffRight[next] = lr[1];
                ffLeftDown[next] = lr[0];
                ffLeftUp[next] = lr[0];
                return next;
            }
            leftp[vars]++;
        }
        return -1;
    }


    private void floodFillSeed(int x0, int y0, int from, int to, SpanFunc func)
    {
        int last = numFloodFillVars - 1;

        int next = 0;
        ffY[next] = y0;
        int[] lr = floodFillLine(x0, y0, from, to, func);
        ffRight[next] = lr[1];
        ffLeftDown[next] = lr[0];
        ffLeftUp[next] = lr[0];

        while (true) {
            int vars = next;
            if (vars == last) {
                // "stack overflow": very complex shapes, not likely part of a valid QR code
                break;
            }
            if (ffY[vars] > 0) {
                next = floodFillCallNext(ffY[vars] - 1, from, to, func, vars, -1);
                if (next >= 0) {
                    continue;
                }
            }
            if (ffY[vars] < h - 1) {
                next = floodFillCallNext(ffY[vars] + 1, from, to, func, vars, 1);
                if (next >= 0) {
                    continue;
                }
            }
            if (vars > 0) {
                next = vars - 1;
                continue;
            }
            break;
        }
    }


    // --- adaptive thresholding ---

    private int otsu(byte[] gray)
    {
        long numPixels = (long) w * h;
        long[] histogram = new long[256];
        for (int i = 0; i < numPixels; i++) {
            histogram[gray[i] & 0xff]++;
        }
        double sum = 0;
        for (int i = 0; i <= 255; i++) {
            sum += (double) i * histogram[i];
        }
        double sumB = 0;
        long q1 = 0;
        double max = 0;
        int threshold = 0;
        for (int i = 0; i <= 255; i++) {
            q1 += histogram[i];
            if (q1 == 0) {
                continue;
            }
            long q2 = numPixels - q1;
            if (q2 == 0) {
                break;
            }
            sumB += (double) i * histogram[i];
            double m1 = sumB / q1;
            double m2 = (sum - sumB) / q2;
            double m1m2 = m1 - m2;
            double variance = m1m2 * m1m2 * q1 * q2;
            if (variance >= max) {
                threshold = i;
                max = variance;
            }
        }
        return threshold;
    }


    private int regionCode(int x, int y)
    {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return -1;
        }
        int pixel = pixels[y * w + x];
        if (pixel >= PIXEL_REGION) {
            return pixel;
        }
        if (pixel == PIXEL_WHITE) {
            return -1;
        }
        if (regions.size() >= MAX_REGIONS) {
            return -1;
        }
        int region = regions.size();
        Region box = new Region();
        regions.add(box);
        box.seed.x = x;
        box.seed.y = y;
        box.capstone = -1;
        floodFillSeed(x, y, pixel, region, (yy, left, right) -> box.count += right - left + 1);
        return region;
    }


    /** Scores of the corner search (struct polygon_score_data). */
    private static final class PolygonScore
    {
        final Point ref = new Point();
        final int[] scores = new int[4];
        Point[] corners;
    }


    private void findRegionCorners(int rcode, Point ref, Point[] corners)
    {
        Region region = regions.get(rcode);
        PolygonScore psd = new PolygonScore();
        psd.corners = corners;
        psd.ref.set(ref);
        psd.scores[0] = -1;

        // find_one_corner: the point farthest from the reference
        floodFillSeed(region.seed.x, region.seed.y, rcode, PIXEL_BLACK, (y, left, right) -> {
            int[] xs = {left, right};
            int dy = y - psd.ref.y;
            for (int i = 0; i < 2; i++) {
                int dx = xs[i] - psd.ref.x;
                int d = dx * dx + dy * dy;
                if (d > psd.scores[0]) {
                    psd.scores[0] = d;
                    psd.corners[0].x = xs[i];
                    psd.corners[0].y = y;
                }
            }
        });

        psd.ref.x = psd.corners[0].x - psd.ref.x;
        psd.ref.y = psd.corners[0].y - psd.ref.y;

        for (int i = 0; i < 4; i++) {
            psd.corners[i].set(region.seed);
        }

        int i = region.seed.x * psd.ref.x + region.seed.y * psd.ref.y;
        psd.scores[0] = i;
        psd.scores[2] = -i;
        i = region.seed.x * -psd.ref.y + region.seed.y * psd.ref.x;
        psd.scores[1] = i;
        psd.scores[3] = -i;

        // find_other_corners: extreme points along the reference direction and its normal
        floodFillSeed(region.seed.x, region.seed.y, PIXEL_BLACK, rcode, (y, left, right) -> {
            int[] xs = {left, right};
            for (int k = 0; k < 2; k++) {
                int up = xs[k] * psd.ref.x + y * psd.ref.y;
                int rt = xs[k] * -psd.ref.y + y * psd.ref.x;
                int[] scores = {up, rt, -up, -rt};
                for (int j = 0; j < 4; j++) {
                    if (scores[j] > psd.scores[j]) {
                        psd.scores[j] = scores[j];
                        psd.corners[j].x = xs[k];
                        psd.corners[j].y = y;
                    }
                }
            }
        });
    }


    private void recordCapstone(int ring, int stone)
    {
        if (capstones.size() >= MAX_CAPSTONES) {
            return;
        }
        Region stoneReg = regions.get(stone);
        Region ringReg = regions.get(ring);
        int csIndex = capstones.size();
        Capstone capstone = new Capstone();
        capstones.add(capstone);

        capstone.qrGrid = -1;
        capstone.ring = ring;
        capstone.stone = stone;
        stoneReg.capstone = csIndex;
        ringReg.capstone = csIndex;

        findRegionCorners(ring, stoneReg.seed, capstone.corners);
        perspectiveSetup(capstone.c, capstone.corners, 7.0, 7.0);
        perspectiveMap(capstone.c, 3.5, 3.5, capstone.center);
    }


    private void testCapstone(int x, int y, int[] pb)
    {
        int ringRight = regionCode(x - pb[4], y);
        int stone = regionCode(x - pb[4] - pb[3] - pb[2], y);
        int ringLeft = regionCode(x - pb[4] - pb[3] - pb[2] - pb[1] - pb[0], y);

        if (ringLeft < 0 || ringRight < 0 || stone < 0) {
            return;
        }
        // left and right of the ring should be connected, and the ring disconnected from the stone
        if (ringLeft != ringRight || ringLeft == stone) {
            return;
        }
        Region stoneReg = regions.get(stone);
        Region ringReg = regions.get(ringLeft);
        if (stoneReg.capstone >= 0 || ringReg.capstone >= 0) {
            return;
        }
        // ratio should ideally be 37.5
        long ratio = (long) stoneReg.count * 100 / ringReg.count;
        if (ratio < 10 || ratio > 70) {
            return;
        }
        recordCapstone(ringLeft, stone);
    }


    private void finderScan(int y)
    {
        int row = y * w;
        int lastColor = 0;
        int runLength = 0;
        int runCount = 0;
        int[] pb = new int[5];
        final int[] check = {1, 1, 3, 1, 1};
        final int scale = 16;

        for (int x = 0; x < w; x++) {
            int color = pixels[row + x] != 0 ? 1 : 0;

            if (x != 0 && color != lastColor) {
                System.arraycopy(pb, 1, pb, 0, 4);
                pb[4] = runLength;
                runLength = 0;
                runCount++;

                if (color == 0 && runCount >= 5) {
                    long avg = (long) (pb[0] + pb[1] + pb[3] + pb[4]) * scale / 4;
                    long err = avg * 3 / 4;
                    boolean ok = true;
                    for (int i = 0; i < 5; i++) {
                        long v = (long) pb[i] * scale;
                        if (v < check[i] * avg - err || v > check[i] * avg + err) {
                            ok = false;
                        }
                    }
                    if (ok) {
                        testCapstone(x, y, pb);
                    }
                }
            }
            runLength++;
            lastColor = color;
        }
    }


    private void findAlignmentPattern(int index)
    {
        Grid qr = grids.get(index);
        Capstone c0 = capstones.get(qr.caps[0]);
        Capstone c2 = capstones.get(qr.caps[2]);
        Point a = new Point();
        Point b = new Point(qr.align);
        Point c = new Point();
        int stepSize = 1;
        int dir = 0;

        // guess another two corners of the alignment pattern to estimate its size
        double[] uv = perspectiveUnmap(c0.c, b);
        perspectiveMap(c0.c, uv[0], uv[1] + 1.0, a);
        uv = perspectiveUnmap(c2.c, b);
        perspectiveMap(c2.c, uv[0] + 1.0, uv[1], c);

        long sizeEstimate = Math.abs((long) (a.x - b.x) * -(c.y - b.y) + (long) (a.y - b.y) * (c.x - b.x));

        // spiral outwards from the estimate; the pattern must lie within the image
        final int[] dxMap = {1, 0, -1, 0};
        final int[] dyMap = {0, -1, 0, 1};
        int maxStep = Math.max(w, h) + 1;
        while ((long) stepSize * stepSize < sizeEstimate * 100 && stepSize <= maxStep) {
            for (int i = 0; i < stepSize; i++) {
                int code = regionCode(b.x, b.y);
                if (code >= 0) {
                    Region reg = regions.get(code);
                    if (reg.count >= sizeEstimate / 2 && reg.count <= sizeEstimate * 2) {
                        qr.alignRegion = code;
                        return;
                    }
                }
                b.x += dxMap[dir];
                b.y += dyMap[dir];
            }
            dir = (dir + 1) % 4;
            if ((dir & 1) == 0) {
                stepSize++;
            }
        }
    }


    private static double length(Point a, Point b)
    {
        double x = Math.abs(a.x - b.x) + 1;
        double y = Math.abs(a.y - b.y) + 1;
        return Math.sqrt(x * x + y * y);
    }


    /** Estimates the grid size from the distance between the capstones. */
    private void measureGridSize(int index)
    {
        Grid qr = grids.get(index);
        Capstone a = capstones.get(qr.caps[0]);
        Capstone b = capstones.get(qr.caps[1]);
        Capstone c = capstones.get(qr.caps[2]);

        double ab = length(b.corners[0], a.corners[3]);
        double capstoneAbSize = (length(b.corners[0], b.corners[3]) + length(a.corners[0], a.corners[3])) / 2.0;
        double verGrid = 7.0 * ab / capstoneAbSize;

        double bc = length(b.corners[0], c.corners[1]);
        double capstoneBcSize = (length(b.corners[0], b.corners[1]) + length(c.corners[0], c.corners[1])) / 2.0;
        double horGrid = 7.0 * bc / capstoneBcSize;

        double gridSizeEstimate = (verGrid + horGrid) * 0.5;
        int ver = (int) ((gridSizeEstimate - 15.0) * 0.25);
        qr.gridSize = 4 * ver + 17;
    }


    /** @return +1 / -1 for a black / white cell, 0 for cells outside the image */
    private int readCell(int index, int x, int y)
    {
        Grid qr = grids.get(index);
        Point p = new Point();
        perspectiveMap(qr.c, x + 0.5, y + 0.5, p);
        if (p.y < 0 || p.y >= h || p.x < 0 || p.x >= w) {
            return 0;
        }
        return pixels[p.y * w + p.x] != 0 ? 1 : -1;
    }


    private static final double[] OFFSETS = {0.3, 0.5, 0.7};

    private int fitnessCell(int index, int x, int y)
    {
        Grid qr = grids.get(index);
        int score = 0;
        Point p = new Point();
        for (int v = 0; v < 3; v++) {
            for (int u = 0; u < 3; u++) {
                perspectiveMap(qr.c, x + OFFSETS[u], y + OFFSETS[v], p);
                if (p.y < 0 || p.y >= h || p.x < 0 || p.x >= w) {
                    continue;
                }
                score += pixels[p.y * w + p.x] != 0 ? 1 : -1;
            }
        }
        return score;
    }


    private int fitnessRing(int index, int cx, int cy, int radius)
    {
        int score = 0;
        for (int i = 0; i < radius * 2; i++) {
            score += fitnessCell(index, cx - radius + i, cy - radius);
            score += fitnessCell(index, cx - radius, cy + radius - i);
            score += fitnessCell(index, cx + radius, cy - radius + i);
            score += fitnessCell(index, cx + radius - i, cy + radius);
        }
        return score;
    }


    private int fitnessApat(int index, int cx, int cy)
    {
        return fitnessCell(index, cx, cy) - fitnessRing(index, cx, cy, 1) + fitnessRing(index, cx, cy, 2);
    }


    private int fitnessCapstone(int index, int x, int y)
    {
        x += 3;
        y += 3;
        return fitnessCell(index, x, y) + fitnessRing(index, x, y, 1) - fitnessRing(index, x, y, 2)
               + fitnessRing(index, x, y, 3);
    }


    /** Fitness of the current perspective transform, from the features expected in the grid. */
    private int fitnessAll(int index)
    {
        Grid qr = grids.get(index);
        int version = (qr.gridSize - 17) / 4;
        int score = 0;

        // timing pattern
        for (int i = 0; i < qr.gridSize - 14; i++) {
            int expect = (i & 1) != 0 ? 1 : -1;
            score += fitnessCell(index, i + 7, 6) * expect;
            score += fitnessCell(index, 6, i + 7) * expect;
        }
        // capstones
        score += fitnessCapstone(index, 0, 0);
        score += fitnessCapstone(index, qr.gridSize - 7, 0);
        score += fitnessCapstone(index, 0, qr.gridSize - 7);

        if (version < 0 || version > QuircVersionDb.MAX_VERSION) {
            return score;
        }
        // alignment patterns
        int[] apat = QuircVersionDb.ALIGNMENT[version];
        int apCount = apat.length;
        for (int i = 1; i + 1 < apCount; i++) {
            score += fitnessApat(index, 6, apat[i]);
            score += fitnessApat(index, apat[i], 6);
        }
        for (int i = 1; i < apCount; i++) {
            for (int j = 1; j < apCount; j++) {
                score += fitnessApat(index, apat[i], apat[j]);
            }
        }
        return score;
    }


    private void jigglePerspective(int index)
    {
        Grid qr = grids.get(index);
        int best = fitnessAll(index);
        double[] adjustments = new double[8];
        for (int i = 0; i < 8; i++) {
            adjustments[i] = qr.c[i] * 0.02;
        }
        for (int pass = 0; pass < 5; pass++) {
            for (int i = 0; i < 16; i++) {
                int j = i >> 1;
                double old = qr.c[j];
                double step = adjustments[j];
                qr.c[j] = (i & 1) != 0 ? old + step : old - step;
                int test = fitnessAll(index);
                if (test > best) {
                    best = test;
                } else {
                    qr.c[j] = old;
                }
            }
            for (int i = 0; i < 8; i++) {
                adjustments[i] *= 0.5;
            }
        }
    }


    /** Sets up the grid-reading perspective transform once capstones and alignment point are known. */
    private void setupQrPerspective(int index)
    {
        Grid qr = grids.get(index);
        Point[] rect = {
            new Point(capstones.get(qr.caps[1]).corners[0]),
            new Point(capstones.get(qr.caps[2]).corners[0]),
            new Point(qr.align),
            new Point(capstones.get(qr.caps[0]).corners[0]),
        };
        perspectiveSetup(qr.c, rect, qr.gridSize - 7, qr.gridSize - 7);
        jigglePerspective(index);
    }


    /** Rotates the capstone so that corner 0 is the leftmost with respect to the reference line. */
    private static void rotateCapstone(Capstone cap, Point h0, Point hd)
    {
        int best = 0;
        int bestScore = Integer.MAX_VALUE;
        for (int j = 0; j < 4; j++) {
            Point p = cap.corners[j];
            int score = (p.x - h0.x) * -hd.y + (p.y - h0.y) * hd.x;
            if (j == 0 || score < bestScore) {
                best = j;
                bestScore = score;
            }
        }
        Point[] copy = new Point[4];
        for (int j = 0; j < 4; j++) {
            copy[j] = new Point(cap.corners[(j + best) % 4]);
        }
        for (int j = 0; j < 4; j++) {
            cap.corners[j].set(copy[j]);
        }
        perspectiveSetup(cap.c, cap.corners, 7.0, 7.0);
    }


    private void recordQrGrid(int a, int b, int c)
    {
        if (grids.size() >= MAX_GRIDS) {
            return;
        }
        // hypotenuse line from A to C; B should be to the left of this line
        Point h0 = new Point(capstones.get(a).center);
        Point hd = new Point();
        hd.x = capstones.get(c).center.x - capstones.get(a).center.x;
        hd.y = capstones.get(c).center.y - capstones.get(a).center.y;

        // make sure A-B-C is clockwise
        if ((capstones.get(b).center.x - h0.x) * -hd.y + (capstones.get(b).center.y - h0.y) * hd.x > 0) {
            int swap = a;
            a = c;
            c = swap;
            hd.x = -hd.x;
            hd.y = -hd.y;
        }

        int qrIndex = grids.size();
        Grid qr = new Grid();
        grids.add(qr);
        qr.caps[0] = a;
        qr.caps[1] = b;
        qr.caps[2] = c;
        qr.alignRegion = -1;

        // rotate each capstone so that corner 0 is top-left with respect to the grid
        for (int i = 0; i < 3; i++) {
            Capstone cap = capstones.get(qr.caps[i]);
            rotateCapstone(cap, h0, hd);
            cap.qrGrid = qrIndex;
        }

        measureGridSize(qrIndex);

        // estimate the alignment pattern by extending lines from capstones A and C.
        // Unlike quirc, impossible grid sizes are dropped here: on screenshots, unrelated capstones
        // can give huge estimates whose perspective fitting would take very long.
        if (qr.gridSize < 21 || qr.gridSize > MAX_GRID_SIZE
            || !lineIntersect(capstones.get(a).corners[0], capstones.get(a).corners[1],
                              capstones.get(c).corners[0], capstones.get(c).corners[3], qr.align)) {
            // unable to complete the setup for this grid: undo it
            for (int i = 0; i < 3; i++) {
                capstones.get(qr.caps[i]).qrGrid = -1;
            }
            grids.remove(grids.size() - 1);
            return;
        }

        // on version 2 and later, use the alignment pattern
        if (qr.gridSize > 21) {
            findAlignmentPattern(qrIndex);

            // the point of the alignment pattern closest to the top-left of the grid
            if (qr.alignRegion >= 0) {
                Region reg = regions.get(qr.alignRegion);
                qr.align.set(reg.seed);

                PolygonScore psd = new PolygonScore();
                psd.ref.set(hd);
                psd.corners = new Point[] {qr.align};
                psd.scores[0] = -hd.y * qr.align.x + hd.x * qr.align.y;

                floodFillSeed(reg.seed.x, reg.seed.y, qr.alignRegion, PIXEL_BLACK, null);
                // find_leftmost_to_line
                floodFillSeed(reg.seed.x, reg.seed.y, PIXEL_BLACK, qr.alignRegion, (y, left, right) -> {
                    int[] xs = {left, right};
                    for (int i = 0; i < 2; i++) {
                        int d = -psd.ref.y * xs[i] + psd.ref.x * y;
                        if (d < psd.scores[0]) {
                            psd.scores[0] = d;
                            psd.corners[0].x = xs[i];
                            psd.corners[0].y = y;
                        }
                    }
                });
            }
        }
        setupQrPerspective(qrIndex);
    }


    private void testNeighbours(int i, double[][] hlist, int hcount, double[][] vlist, int vcount)
    {
        // test each possible grouping; [k] = {index, distance}
        for (int j = 0; j < hcount; j++) {
            for (int k = 0; k < vcount; k++) {
                double squareness = Math.abs(1.0 - hlist[j][1] / vlist[k][1]);
                if (squareness < 0.2) {
                    recordQrGrid((int) hlist[j][0], i, (int) vlist[k][0]);
                }
            }
        }
    }


    private void testGrouping(int i)
    {
        Capstone c1 = capstones.get(i);
        double[][] hlist = new double[MAX_CAPSTONES][];
        double[][] vlist = new double[MAX_CAPSTONES][];
        int hcount = 0;
        int vcount = 0;

        // potential neighbours by the relative gradients from this capstone to others
        for (int j = 0; j < capstones.size(); j++) {
            if (i == j) {
                continue;
            }
            Capstone c2 = capstones.get(j);
            double[] uv = perspectiveUnmap(c1.c, c2.center);
            double u = Math.abs(uv[0] - 3.5);
            double v = Math.abs(uv[1] - 3.5);
            if (u < 0.2 * v) {
                hlist[hcount++] = new double[] {j, v};
            }
            if (v < 0.2 * u) {
                vlist[vcount++] = new double[] {j, u};
            }
        }
        if (hcount == 0 || vcount == 0) {
            return;
        }
        testNeighbours(i, hlist, hcount, vlist, vcount);
    }


    /** Samples the cells of the code with the given index (quirc_extract). */
    QuircCode extract(int index)
    {
        Grid qr = grids.get(index);
        if (qr.gridSize > MAX_GRID_SIZE || qr.gridSize < 0) {
            // quirc_decode reports the invalid size
            return new QuircCode(qr.gridSize > MAX_GRID_SIZE ? MAX_GRID_SIZE + 1 : 0);
        }
        QuircCode code = new QuircCode(qr.gridSize);
        for (int y = 0; y < qr.gridSize; y++) {
            for (int x = 0; x < qr.gridSize; x++) {
                if (readCell(index, x, y) > 0) {
                    code.set(x, y, true);
                }
            }
        }
        return code;
    }
}
