package dev.spm.core.codec.legacy;

import dev.spm.core.api.CancellationToken;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module-local coverage for the historical cipher. Cross-module wire round-trips live in
 * {@code verification}; this class pins the cipher's own contract so the {@code codec} module is no
 * longer a zero-test module.
 */
final class LegacyCipherTest {
    /** The historical envelope uses a fixed 56-byte password; anything else must be refused. */
    private static byte[] password(int seed) {
        byte[] value = new byte[56];
        for (int i = 0; i < value.length; i++) value[i] = (byte) (i * 37 + seed);
        return value;
    }

    private static byte[] payload(int length) {
        byte[] value = new byte[length];
        for (int i = 0; i < value.length; i++) value[i] = (byte) (i * 131 + 7);
        return value;
    }

    @Test void encryptThenDecryptRestoresEveryByteAcrossBlockBoundaries() {
        var token = new CancellationToken();
        // 64 is the stream block size; cover exact, one-under, one-over and multi-block sizes.
        for (int length : new int[] {0, 1, 63, 64, 65, 127, 128, 129, 4096}) {
            byte[] original = payload(length);
            byte[] data = original.clone();
            LegacyCipher.encrypt(data, 0, data.length, password(11), token);
            if (length > 0) {
                assertFalse(Arrays.equals(original, data), "ciphertext must differ for length=" + length);
            }
            LegacyCipher.decrypt(data, 0, data.length, password(11), token);
            assertArrayEquals(original, data, "round trip failed for length=" + length);
        }
    }

    @Test void encryptionIsDeterministicForTheSamePasswordAndInput() {
        var token = new CancellationToken();
        byte[] first = payload(300);
        byte[] second = payload(300);
        LegacyCipher.encrypt(first, 0, first.length, password(5), token);
        LegacyCipher.encrypt(second, 0, second.length, password(5), token);
        assertArrayEquals(first, second);
    }

    @Test void aDifferentPasswordDoesNotRoundTrip() {
        var token = new CancellationToken();
        byte[] original = payload(200);
        byte[] data = original.clone();
        LegacyCipher.encrypt(data, 0, data.length, password(1), token);
        LegacyCipher.decrypt(data, 0, data.length, password(2), token);
        assertFalse(Arrays.equals(original, data));
    }

    @Test void passwordLengthIsEnforcedRatherThanTruncated() {
        var token = new CancellationToken();
        byte[] data = payload(64);
        for (int length : new int[] {0, 1, 55, 57}) {
            byte[] wrong = new byte[length];
            assertThrows(IllegalArgumentException.class,
                    () -> LegacyCipher.encrypt(data, 0, data.length, wrong, token), "length=" + length);
            assertThrows(IllegalArgumentException.class,
                    () -> LegacyCipher.decrypt(data, 0, data.length, wrong, token), "length=" + length);
        }
    }

    @Test void boundsAreCheckedAndAnAlreadyCancelledTokenAbortsBeforeWork() {
        var token = new CancellationToken();
        byte[] data = payload(32);
        byte[] password = password(3);
        assertThrows(IndexOutOfBoundsException.class,
                () -> LegacyCipher.encrypt(data, 16, 32, password, token));
        assertThrows(IndexOutOfBoundsException.class,
                () -> LegacyCipher.decrypt(data, 16, 32, password, token));

        var cancelled = new CancellationToken();
        cancelled.cancel();
        byte[] untouched = payload(64);
        byte[] snapshot = untouched.clone();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> LegacyCipher.encrypt(untouched, 0, untouched.length, password, cancelled));
        assertArrayEquals(snapshot, untouched, "a cancelled call must not mutate caller data");
    }
}
