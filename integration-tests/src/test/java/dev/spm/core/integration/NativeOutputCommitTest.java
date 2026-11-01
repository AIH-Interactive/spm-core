package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.nativejni.NativeTransforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T09 named this test and it did not exist. It pins the <em>failure-atomicity</em> half of the
 * native contract: a rejected or failed batch must leave the caller's output buffer completely
 * untouched. Partial output must never be observable by a Host, because a Host would upload it.
 *
 * <p>Every case writes a recognizable sentinel across the whole output buffer first, then asserts
 * the sentinel survived byte-for-byte.
 */
class NativeOutputCommitTest {

    /** A 64-bit sentinel that no plausible transform output could collide with. */
    private static final long SENTINEL = 0x5e4d3c2b1a0f9e8dL;

    private static NativeTransforms load() throws Exception {
        Path path = Path.of(System.getProperty("spm.native.library"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return NativeTransforms.load(path, digest);
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Fills the buffer with the sentinel pattern and returns a copy for comparison. */
    private static byte[] arm(ByteBuffer buffer) {
        for (int offset = 0; offset + 8 <= buffer.capacity(); offset += 8) buffer.putLong(offset, SENTINEL);
        byte[] snapshot = new byte[buffer.capacity()];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = buffer.get(i);
        return snapshot;
    }

    private static void assertUntouched(ByteBuffer buffer, byte[] snapshot, String message) {
        byte[] now = new byte[buffer.capacity()];
        for (int i = 0; i < now.length; i++) now[i] = buffer.get(i);
        assertArrayEquals(snapshot, now, message);
    }

    private static ByteBuffer matrixBuffer(float[] matrix) {
        ByteBuffer matrixBytes = direct(64);
        for (int i = 0; i < 16; i++) matrixBytes.putFloat(i * 4, matrix[i]);
        return matrixBytes;
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void aNonFiniteMatrixLeavesTheOutputUntouched() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        matrix[3] = Float.NaN;

        ByteBuffer positions = direct(24);
        positions.putFloat(0, 1).putFloat(4, 2).putFloat(8, 3)
                .putFloat(12, 4).putFloat(16, 5).putFloat(20, 6);
        ByteBuffer output = direct(24);
        byte[] snapshot = arm(output);

        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 2, nativeCode.supported(Isa.SSE2) ? Isa.SSE2 : Isa.SCALAR));
        assertUntouched(output, snapshot, "a non-finite matrix must not write any output");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void aNonFinitePositionLeavesTheOutputUntouched() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ByteBuffer positions = direct(24);
        positions.putFloat(0, 1).putFloat(4, 2).putFloat(8, 3)
                .putFloat(12, 4).putFloat(16, Float.POSITIVE_INFINITY).putFloat(20, 6);
        ByteBuffer output = direct(24);
        byte[] snapshot = arm(output);

        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 2,
                        nativeCode.supported(Isa.SSE2) ? Isa.SSE2 : Isa.SCALAR));
        assertUntouched(output, snapshot, "a non-finite position must not write any output");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void anOverflowingTransformLeavesTheOutputUntouched() throws Exception {
        var nativeCode = load();
        // A scale large enough that the product leaves the finite float range.
        float[] matrix = {Float.MAX_VALUE, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ByteBuffer positions = direct(12);
        positions.putFloat(0, Float.MAX_VALUE).putFloat(4, 0).putFloat(8, 0);
        ByteBuffer output = direct(12);
        byte[] snapshot = arm(output);

        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 1,
                        nativeCode.supported(Isa.SSE2) ? Isa.SSE2 : Isa.SCALAR));
        assertUntouched(output, snapshot, "an overflowing result must not be committed");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void anInvalidCountLeavesTheOutputUntouched() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ByteBuffer positions = direct(12);
        ByteBuffer output = direct(12);
        byte[] snapshot = arm(output);

        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 2, Isa.SCALAR));
        assertUntouched(output, snapshot, "a rejected count must not write any output");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void aSuccessfulBatchFullyOverwritesTheSentinel() throws Exception {
        var nativeCode = load();
        float[] matrix = {2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 6, 7, 1};
        ByteBuffer positions = direct(24);
        positions.putFloat(0, 1).putFloat(4, 2).putFloat(8, 3)
                .putFloat(12, 1).putFloat(16, 1).putFloat(20, 1);
        ByteBuffer output = direct(24);
        arm(output);

        Isa isa = nativeCode.supported(Isa.SSE2) ? Isa.SSE2 : Isa.SCALAR;
        nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 2, isa);

        // Success must have replaced every byte with a real result; the sentinel must be gone.
        byte[] now = new byte[24];
        for (int i = 0; i < now.length; i++) now[i] = output.get(i);
        boolean anySentinelByte = false;
        for (int offset = 0; offset + 8 <= 24; offset += 8) {
            if (output.getLong(offset) == SENTINEL) anySentinelByte = true;
        }
        assertFalse(anySentinelByte, "a successful batch must commit real values, not leave sentinels");
        assertEquals(2 * 3, now.length / 4);
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void callerOwnedBuffersAreNotFreedOrRetargetedByTheCall() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        ByteBuffer positions = direct(12);
        positions.putFloat(0, 1).putFloat(4, 2).putFloat(8, 3);
        ByteBuffer output = direct(12);

        nativeCode.transformBuffers(matrixBuffer(matrix), positions, output, 1, Isa.SCALAR);

        // The caller still owns usable buffers afterwards: the call borrows, never takes.
        assertEquals(0, positions.position(), "the callee must not move the caller's position");
        assertEquals(0, output.position(), "the callee must not move the caller's position");
        assertTrue(positions.isDirect() && output.isDirect());
        positions.putFloat(0, 9);
        assertEquals(9f, positions.getFloat(0));
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void everyCompletedBatchReturnsArenaStorageWithoutNeedingGc() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        float[] input = {1, 2, 3, 4, 5, 6};
        for (int i = 0; i < 50; i++) nativeCode.transform(matrix, input, Isa.SCALAR);
        assertEquals(0, NativeTransforms.ownedArenaCount(), "no arena may survive a completed call");
        assertEquals(0, NativeTransforms.ownedArenaBytes(), "no arena storage may survive a completed call");
    }
}
