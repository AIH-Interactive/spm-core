package dev.spm.core.render.cpu;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollapsedSkinNormalTest {
    private static float[] diagonal(float x, float y, float z) {
        return new float[] {x,0,0,0, 0,y,0,0, 0,0,z,0, 0,0,0,1};
    }

    @Test void collapsedAxisRetainsTheSurvivingPlaneAreaNormal() {
        float[] normal = SkinMath.normalOf(diagonal(0, 1, 1));
        assertArrayEquals(new float[] {1,0,0, 0,0,0, 0,0,0}, normal);
        for (float value : normal) assertTrue(Float.isFinite(value));
    }

    @Test void collapsedLineAndPointHaveZeroAreaNormal() {
        for (float[] pose : new float[][] {diagonal(0,0,1), diagonal(0,0,0)}) {
            float[] normal = SkinMath.normalOf(pose);
            assertArrayEquals(new float[9], normal);
            for (float value : normal) assertTrue(Float.isFinite(value));
        }
    }

    @Test void nonsingularAndMirroredPosesKeepInverseScaling() {
        assertArrayEquals(new float[] {.5f,0,0, 0,.25f,0, 0,0,.125f},
                SkinMath.normalOf(diagonal(2,4,8)));
        float[] mirrored = SkinMath.normalOf(diagonal(-2,4,8));
        assertEquals(-.5f, mirrored[0]);
        assertEquals(.25f, mirrored[4]);
        assertEquals(.125f, mirrored[8]);
        for (float value : mirrored) assertTrue(Float.isFinite(value));
    }

    @Test void singularBindAndQuaternionStillRejectCollapsedPoses() {
        assertThrows(IllegalArgumentException.class, () -> SkinMath.invertAffine(diagonal(0,1,1)));
        assertThrows(IllegalArgumentException.class, () -> SkinMath.quaternion(diagonal(0,1,1)));
    }
}
