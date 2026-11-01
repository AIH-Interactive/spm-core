/*
 * Derived from the historical modified CityHash algorithm documented by
 * AndreaFrederica/OhMyYesSteveModel d2536542747a1aa5c02594827f579171c525843c.
 * CityHash Copyright (c) 2011 Google, Inc. MIT permission and warranty text
 * are retained in META-INF/spm-core/legacy-third-party.txt.
 */
package dev.spm.core.codec.legacy;

import java.util.Objects;

/** Wire-specific CityHash variant. Its constants and two-word order are not standard CityHash. */
public final class LegacyHash {
    private static final long A = 0xe4986a230e5aaa17L;
    private static final long B = 0x91af10802cab25a5L;
    private static final long C = 0xaf29ce778879d9c7L;
    private static final long REDUCTION = 0xde0f6ee09bdbab91L;
    private LegacyHash() { }

    public static long seeded(byte[] bytes, int offset, int length, long seed) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        return pair(seed, fingerprint(bytes, offset, length) - C, REDUCTION);
    }
    public static int littleInt(byte[] data, int offset) {
        return (data[offset] & 255) | (data[offset + 1] & 255) << 8
                | (data[offset + 2] & 255) << 16 | data[offset + 3] << 24;
    }
    public static long littleLong(byte[] data, int offset) {
        return Integer.toUnsignedLong(littleInt(data, offset)) | ((long) littleInt(data, offset + 4) << 32);
    }
    private static long rotate(long x, int distance) { return Long.rotateRight(x, distance); }
    private static long shiftMix(long x) { return x ^ (x >>> 47); }
    private static long pair(long first, long second, long multiplier) {
        long x = shiftMix((first ^ second) * multiplier);
        return shiftMix((second ^ x) * multiplier) * multiplier;
    }
    private static long historicPair(long first, long second) { return pair(second, first, REDUCTION); }

    private static long fingerprint(byte[] d, int p, int size) {
        if (size <= 16) return small(d, p, size);
        if (size <= 32) return medium(d, p, size);
        if (size <= 64) return large(d, p, size);
        return many(d, p, size);
    }
    private static long small(byte[] d, int p, int size) {
        long m = C + 2L * size;
        if (size >= 8) {
            long head = littleLong(d, p) + C, tail = littleLong(d, p + size - 8);
            return pair(rotate(tail, 37) * m + head, (rotate(head, 25) + tail) * m, m);
        }
        if (size >= 4) return pair(size + (Integer.toUnsignedLong(littleInt(d, p)) << 3),
                Integer.toUnsignedLong(littleInt(d, p + size - 4)), m);
        if (size == 0) return C;
        long x = (d[p] & 255) + ((d[p + size / 2] & 255) << 8);
        long y = size + ((d[p + size - 1] & 255) << 2);
        return shiftMix(x * C ^ y * A) * C;
    }
    private static long medium(byte[] d, int p, int size) {
        long m = C + 2L * size;
        long h0 = littleLong(d, p) * B, h1 = littleLong(d, p + 8);
        long t0 = littleLong(d, p + size - 8) * m, t1 = littleLong(d, p + size - 16) * C;
        return pair(rotate(h0 + h1, 43) + rotate(t0, 30) + t1,
                h0 + rotate(h1 + C, 18) + t0, m);
    }
    private static long large(byte[] d, int p, int size) {
        long m = C + 2L * size;
        long a = littleLong(d, p) * C, b = littleLong(d, p + 8), c = littleLong(d, p + size - 24);
        long e = littleLong(d, p + size - 32), f = littleLong(d, p + 16) * C;
        long g = littleLong(d, p + 24) * 9, h = littleLong(d, p + size - 8), i = littleLong(d, p + size - 16) * m;
        long u = rotate(a + h, 43) + (rotate(b, 30) + c) * 9, v = ((a + h) ^ e) + g + 1;
        long w = Long.reverseBytes((u + v) * m) + i, x = rotate(f + g, 42) + c;
        long y = (Long.reverseBytes((v + w) * m) + h) * m, z = f + g + c;
        long nextA = Long.reverseBytes((x + z) * m + y) + b;
        return shiftMix((z + nextA) * m + e + i) * m + x;
    }
    private static long[] weak(byte[] d, int p, long x, long y) {
        long w0 = littleLong(d, p), w1 = littleLong(d, p + 8);
        long w2 = littleLong(d, p + 16), w3 = littleLong(d, p + 24);
        x += w0;
        y = rotate(y + x + w3, 21);
        long saved = x;
        x += w1 + w2;
        y += rotate(x, 44);
        return new long[] {x + w3, y + saved};
    }
    private static long many(byte[] d, int p, int size) {
        int tail = p + size - 64, end = p + ((size - 1) & ~63);
        long x = littleLong(d, tail + 24), y = littleLong(d, tail + 48) + littleLong(d, tail + 8);
        long z = historicPair(littleLong(d, tail + 16) + size, littleLong(d, tail + 40));
        long[] v = weak(d, tail, size, z), w = weak(d, tail + 32, y + B, x);
        x = x * B + littleLong(d, p);
        for (int cursor = p; cursor < end; cursor += 64) {
            x = rotate(x + y + v[0] + littleLong(d, cursor + 8), 37) * B;
            y = rotate(y + v[1] + littleLong(d, cursor + 48), 42) * B;
            x ^= w[1]; y += v[0] + littleLong(d, cursor + 40);
            z = rotate(z + w[0], 33) * B;
            v = weak(d, cursor, v[1] * B, x + w[0]);
            w = weak(d, cursor + 32, z + w[1], y + littleLong(d, cursor + 16));
            long swap = x; x = z; z = swap;
        }
        return historicPair(historicPair(v[0], w[0]) + shiftMix(y) * B + z,
                historicPair(v[1], w[1]) + x);
    }
}
