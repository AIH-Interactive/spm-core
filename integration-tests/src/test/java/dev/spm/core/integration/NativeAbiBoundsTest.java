package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.acceleration.ScalarTransforms;
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
 * Plan T09 named this test and it did not exist. It pins the <em>bounds</em> half of the native
 * contract: capacities, byte order, directness, read-only flags and count arithmetic are all checked
 * on the Java side before anything reaches Rust, and an out-of-range request must be refused rather
 * than truncated, rounded or silently accepted.
 *
 * <p>Every test that needs the library is gated on {@code spm.native.library}; the ones that do not
 * still run so the Java-side guard logic is covered even with no provider present.
 */
class NativeAbiBoundsTest {

    private static NativeTransforms load() throws Exception {
        Path path = Path.of(System.getProperty("spm.native.library"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return NativeTransforms.load(path, digest);
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test void theJniAbiVersionIsPinnedAndMatchesTheLibraryContract() {
        // The Java constant is the contract; the Rust side must agree or loading fails.
        assertEquals(4, NativeTransforms.ABI_VERSION,
                "JNI ABI 4 adds mandatory NativeArena exports; changing this needs a coordinated Rust change");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void aCountThatDoesNotMatchTheCapacityIsRejected() throws Exception {
        var nativeCode = load();
        ByteBuffer matrix = direct(64);
        for (int i = 0; i < 16; i++) matrix.putFloat(i, (i % 5 == 0) ? 1f : 0f);
        ByteBuffer positions = direct(12);
        positions.putFloat(0, 1).putFloat(4, 2).putFloat(8, 3);
        ByteBuffer output = direct(12);

        // One vertex fits; two do not.
        assertDoesNotThrow(() -> nativeCode.transformBuffers(matrix, positions, output, 1, Isa.SCALAR));
        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrix, positions, output, 2, Isa.SCALAR),
                "a count larger than the buffer can hold must be refused");
        // A negative count is also refused.
        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrix, positions, output, -1, Isa.SCALAR));
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void heapBuffersAndWrongByteOrderAreRejected() throws Exception {
        var nativeCode = load();
        ByteBuffer matrix = direct(64);
        ByteBuffer positions = direct(12);
        ByteBuffer output = direct(12);

        ByteBuffer heap = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrix, heap, output, 1, Isa.SCALAR),
                "a non-direct buffer has no stable address and must be refused");

        ByteBuffer bigEndian = ByteBuffer.allocateDirect(12).order(ByteOrder.BIG_ENDIAN);
        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrix, bigEndian, output, 1, Isa.SCALAR),
                "the wire is little-endian; a big-endian buffer must be refused");

        ByteBuffer readOnly = direct(12).asReadOnlyBuffer();
        assertThrows(IllegalArgumentException.class,
                () -> nativeCode.transformBuffers(matrix, positions, readOnly, 1, Isa.SCALAR),
                "an output must be writable");
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void aForcedUnsupportedIsaIsRefusedAndNeverSilentlyFallsBack() throws Exception {
        var nativeCode = load();
        for (Isa isa : Isa.values()) {
            if (isa == Isa.AUTO) continue;
            if (nativeCode.supported(isa)) continue;
            // If this machine does not have the ISA, a forced request must be an error, not a fallback.
            float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
            assertThrows(IllegalArgumentException.class,
                    () -> nativeCode.transform(matrix, new float[] {1, 2, 3}, isa),
                    "unsupported forced ISA must be rejected: " + isa);
        }
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void theReportedIsaNeverExceedsWhatTheMachineActuallySupports() throws Exception {
        var nativeCode = load();
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        float[] input = {1, 2, 3, 4, 5, 6};
        for (Isa isa : Isa.values()) {
            if (!nativeCode.supported(isa)) continue;
            var result = nativeCode.transform(matrix, input, isa);
            assertNotEquals(Isa.AUTO, result.isa(), "AUTO is a request, never an observed ISA");
            assertTrue(nativeCode.supported(result.isa()),
                    "the reported ISA must itself be a supported ISA: " + result.isa());
        }
    }

    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void digestAndPathChecksPrecedeAnyLoad() throws Exception {
        Path path = Path.of(System.getProperty("spm.native.library"));
        // Wrong length / non-hex digests are rejected before touching the file.
        assertThrows(IllegalArgumentException.class, () -> NativeTransforms.load(path, "abc"));
        assertThrows(IllegalArgumentException.class, () -> NativeTransforms.load(path, "z".repeat(64)));
        // A well-formed but wrong digest is rejected.
        assertThrows(IllegalArgumentException.class, () -> NativeTransforms.load(path, "0".repeat(64)));
        // A missing file is an IOException, not a crash.
        assertThrows(java.io.IOException.class,
                () -> NativeTransforms.load(path.resolveSibling("nonexistent-spm-native.dll"), "0".repeat(64)));
    }

    @Test void theScalarOracleAgreesOnTheSameCorpusWithoutAnyLibrary() {
        // Independent of the native library: the bounds rules must hold for the Java implementation
        // too, so a Java-only build still rejects the same malformed requests.
        float[] matrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(matrix, new float[] {1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(new float[15], new float[] {1, 2, 3}));
        assertArrayEquals(new float[] {1, 2, 3}, ScalarTransforms.transform(matrix, new float[] {1, 2, 3}));
    }
}
