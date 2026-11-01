package dev.spm.core.acceleration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module-local coverage for the pure Java scalar batch operation. The {@code acceleration-api}
 * module previously had no tests, so "green" only meant "compiles"; this pins the numeric contract
 * that the Rust differential tests are measured against.
 */
final class ScalarTransformsTest {
    /** Column-major affine: scale x2 on x, translate +5 on y. */
    private static final float[] MATRIX = {
            2, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 5, 0, 1,
    };

    @Test void appliesColumnMajorAffineToEachPosition() {
        float[] output = ScalarTransforms.transform(MATRIX, new float[] {1, 2, 3, 4, 5, 6});
        assertArrayEquals(new float[] {2, 7, 3, 8, 10, 6}, output, 0f);
    }

    @Test void emptyInputProducesEmptyOutput() {
        assertArrayEquals(new float[0], ScalarTransforms.transform(MATRIX, new float[0]), 0f);
    }

    @Test void dimensionAndNonFiniteInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(new float[15], new float[3]));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(MATRIX, new float[] {1, 2}));
        float[] nanMatrix = MATRIX.clone();
        nanMatrix[0] = Float.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(nanMatrix, new float[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(MATRIX, new float[] {1, Float.NaN, 3}));
    }

    @Test void overflowIsRejectedInsteadOfReturningInfinity() {
        float[] huge = MATRIX.clone();
        huge[0] = Float.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,
                () -> ScalarTransforms.transform(huge, new float[] {Float.MAX_VALUE, 0, 0}));
    }

    @Test void validationDoesNotMutateCallerArrays() {
        float[] matrix = MATRIX.clone();
        float[] positions = {1, 2, 3};
        ScalarTransforms.transform(matrix, positions);
        assertArrayEquals(MATRIX, matrix, 0f);
        assertArrayEquals(new float[] {1, 2, 3}, positions, 0f);
    }

    @Test void isaCodesAreStableAndAutoIsNeverAnObservedIsa() {
        // Codes are a wire contract shared with Rust spm-native-abi; they must not be renumbered.
        assertEquals(0, Isa.AUTO.code());
        assertEquals(1, Isa.SCALAR.code());
        assertEquals(2, Isa.SSE2.code());
        assertEquals(3, Isa.AVX.code());
        assertEquals(4, Isa.AVX2.code());
        assertEquals(5, Isa.AVX2_FMA.code());
        assertEquals(6, Isa.NEON.code());
        for (Isa isa : Isa.values()) assertEquals(isa, Isa.fromCode(isa.code()));
        assertThrows(IllegalArgumentException.class, () -> Isa.fromCode(-1));
        assertThrows(IllegalArgumentException.class, () -> Isa.fromCode(9999));
    }

    @Test void transformResultIsAnImmutableSnapshotAndRejectsAuto() {
        var result = new TransformResult(new float[] {1, 2, 3}, 1, Isa.SCALAR);
        float[] returned = result.positions();
        returned[0] = 99;
        assertArrayEquals(new float[] {1, 2, 3}, result.positions(), 0f);
        assertThrows(IllegalArgumentException.class,
                () -> new TransformResult(new float[] {1, 2, 3}, 1, Isa.AUTO));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformResult(new float[] {1, 2}, 1, Isa.SCALAR));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformResult(new float[] {1, Float.NaN, 3}, 1, Isa.SCALAR));
    }
}
