/* Historical ChaCha/HChaCha semantics. See META-INF/spm-core/legacy-third-party.txt. */
package dev.spm.core.codec.legacy;

import dev.spm.core.api.CancellationToken;
import java.util.Objects;

/** Exact dynamic historical XChaCha plus MT19937-64 masking; not a general encryption API. */
public final class LegacyCipher {
    private static final long DYNAMIC_SEED = 0xa62b1a2c43842bc3L;
    private static final long MASK_SEED = 0xd017cbba7b5d3581L;
    private LegacyCipher() { }
    public static void encrypt(byte[] data, int offset, int length, byte[] password, CancellationToken token) {
        check(data, offset, length, password, token);
        mask(data, offset, length, password, token);
        var stream = new Stream(password);
        for (int done = 0; done < length;) {
            token.check();
            int full = stream.blocks * 64, count = Math.min(full, length - done);
            // Historical mutation hashes the post-MT plaintext, before encryption.
            long next = count == full ? LegacyHash.seeded(data, offset + done, count, DYNAMIC_SEED) : 0;
            stream.apply(data, offset + done, count);
            if (count == full) stream.advance(next);
            done += count;
        }
    }

    public static void decrypt(byte[] data, int offset, int length, byte[] password, CancellationToken token) {
        check(data, offset, length, password, token);
        var stream = new Stream(password);
        for (int done = 0; done < length;) {
            token.check();
            int full = stream.blocks * 64;
            int n = Math.min(length - done, full);
            stream.apply(data, offset + done, n);
            if (n == full) stream.advance(LegacyHash.seeded(data, offset + done, n, DYNAMIC_SEED));
            done += n;
        }
        mask(data, offset, length, password, token);
    }
    private static void check(byte[] data, int offset, int length, byte[] password, CancellationToken token) {
        Objects.checkFromIndexSize(offset, length, data.length);
        if (password.length != 56) throw new IllegalArgumentException("Expected 56-byte legacy password");
        Objects.requireNonNull(token).check();
    }
    private static void mask(byte[] data, int offset, int length, byte[] password, CancellationToken token) {
        var random = new Random64(LegacyHash.seeded(password, 0, password.length, MASK_SEED));
        long bits = 0;
        for (int i = 0; i < length; i++) {
            if ((i & 8191) == 0) token.check();
            if ((i & 7) == 0) bits = random.next();
            data[offset + i] ^= (byte) bits;
            bits >>>= 8;
        }
    }
    private static int[] initial() {
        return new int[] {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574, 0,0,0,0,0,0,0,0,0,0,0,0};
    }
    private static final class Stream {
        private final int[] state;
        private int blocks, rounds;
        Stream(byte[] password) {
            parameters(LegacyHash.seeded(password, 0, password.length, DYNAMIC_SEED));
            int[] keyState = initial();
            for (int i = 0; i < 12; i++) keyState[4 + i] = LegacyHash.littleInt(password, i * 4);
            int[] expanded = permute(keyState, rounds);
            state = initial();
            for (int i = 0; i < 4; i++) { state[4 + i] = expanded[i]; state[8 + i] = expanded[12 + i]; }
            state[14] = LegacyHash.littleInt(password, 48); state[15] = LegacyHash.littleInt(password, 52);
        }
        void parameters(long hash) {
            blocks = 64 + (int) Long.remainderUnsigned(hash, 64);
            rounds = 10 * (1 + (int) Long.remainderUnsigned(hash, 3));
        }
        void apply(byte[] bytes, int offset, int size) {
            for (int cursor = 0; cursor < size; cursor += 64) {
                int[] word = permute(state, rounds);
                for (int i = 0; i < 16; i++) word[i] += state[i];
                for (int i = 0; i < Math.min(64, size - cursor); i++) {
                    bytes[offset + cursor + i] ^= (byte) (word[i >>> 2] >>> ((i & 3) * 8));
                }
                state[12]++;
                if (state[12] == 0) state[13]++;
            }
        }
        void advance(long hash) {
            parameters(hash);
            for (int i = 4; i < 16; i++) state[i] ^= (int) (hash >>> ((i & 1) * 32));
        }
    }
    private static int[] permute(int[] state, int rounds) {
        int[] work = state.clone();
        for (int r = 0; r < rounds; r += 2) {
            for (int i = 0; i < 4; i++) quarter(work, i, i + 4, i + 8, i + 12);
            for (int i = 0; i < 4; i++) quarter(work, i, 4 + (i + 1) % 4, 8 + (i + 2) % 4, 12 + (i + 3) % 4);
        }
        return work;
    }
    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }
    private static final class Random64 {
        private final long[] words = new long[312];
        private int position = 312;
        Random64(long seed) {
            words[0] = seed;
            for (int i = 1; i < words.length; i++) words[i] = 6364136223846793005L * (words[i - 1] ^ (words[i - 1] >>> 62)) + i;
        }
        long next() {
            if (position == 312) {
                for (int i = 0; i < 312; i++) {
                    long merged = (words[i] & 0xffffffff80000000L) | (words[(i + 1) % 312] & 0x7fffffffL);
                    words[i] = words[(i + 156) % 312] ^ (merged >>> 1) ^ ((merged & 1) == 0 ? 0 : 0xb5026f5aa96619e9L);
                }
                position = 0;
            }
            long result = words[position++];
            result ^= (result >>> 29) & 0x5555555555555555L;
            result ^= (result << 17) & 0x71d67fffeda60000L;
            result ^= (result << 37) & 0xfff7eee000000000L;
            return result ^ (result >>> 43);
        }
    }
}
