package dev.spm.core.verification;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.MeshSkin;
import dev.spm.core.api.MorphTarget;
import dev.spm.core.api.MorphWeights;
import dev.spm.core.api.SdefParameters;
import dev.spm.core.api.SkinWeights;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.SkinningMethod;
import dev.spm.core.api.VertexBatch;
import dev.spm.core.render.cpu.BindMatrices;
import dev.spm.core.render.cpu.BoneEvaluator;
import dev.spm.core.render.cpu.SkinningRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Weighted-skinning math against hand-computed oracles.
 *
 * <p>Two-bone oracle: bone 0 keeps the identity pose, bone 1 is a quarter turn about Z plus the
 * translation {@code (2,-2,0)}. The bind pose is the identity, so each skin transform is exactly its
 * pose transform. Skinning {@code (3,0,0)} gives bone 0 at {@code (3,0,0)} and bone 1 at
 * {@code Rz(90)·(3,0,0) + (2,-2,0) = (0,3,0) + (2,-2,0) = (2,1,0)}, so a 50/50 blend is
 * {@code (2.5,0.5,0)}.</p>
 */
class SkinningMathTest {
    private static final float EPS = 0.000002f;
    private static final float HALF_PI = (float) (Math.PI / 2);

    /** Two root bones with zero pivots, so a bind transform is exactly the identity. */
    private static Skeleton twoBones() {
        return new Skeleton(List.of("root", "other"), new int[] {-1, -1}, new float[6]);
    }

    private static BoneMatrices identityPose(int boneCount) {
        return BoneEvaluator.evaluate(skeleton(boneCount), BonePose.identity(boneCount));
    }

    private static Skeleton skeleton(int boneCount) {
        String[] names = new String[boneCount];
        int[] parents = new int[boneCount];
        for (int bone = 0; bone < boneCount; bone++) { names[bone] = "bone" + bone; parents[bone] = -1; }
        return new Skeleton(List.of(names), parents, new float[boneCount * 3]);
    }

    /** The posed skeleton: bone 1 rotated a quarter turn about Z and moved to (2,-2,0). */
    private static BoneMatrices posedTwoBones() {
        var pose = new BonePose(
                new float[] {0, 0, 0, 0, 0, HALF_PI},
                new float[] {0, 0, 0, 2, -2, 0},
                new float[] {1, 1, 1, 1, 1, 1},
                new boolean[2], new boolean[2], BonePose.identity(2).glow());
        return BoneEvaluator.evaluate(twoBones(), pose);
    }

    private static MeshSkin.Weighted skin(SkinWeights weights, SkinningMethod method) {
        return new MeshSkin.Weighted(weights, method);
    }

    @Test void twoBoneLinearBlendMatchesTheHandComputedOracle() {
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.5f}});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {1, 0, 0}, new int[] {0});
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), posedTwoBones(),
                identityPose(2), MorphWeights.none(0));
        assertEquals(1, output.vertexCount());
        assertArrayEquals(new float[] {2.5f, 0.5f, 0f}, output.positions(), EPS);
        // Bone 0 keeps +X, bone 1 turns it to +Y, so the blended normal is the normalized average.
        assertArrayEquals(new float[] {0.70710677f, 0.70710677f, 0f}, output.normals(), EPS);
    }

    @Test void singleInfluenceReducesToTheRigidResult() {
        var weights = SkinWeights.rigid(2, new int[] {1});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {1, 0, 0}, new int[] {1});
        var skinned = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), posedTwoBones(),
                identityPose(2), MorphWeights.none(0));
        // Exactly the rigid path's answer for the same bone.
        var rigid = dev.spm.core.render.cpu.JavaCpuRenderer.transform(batch, posedTwoBones());
        assertArrayEquals(rigid.positions(), skinned.positions(), EPS);
        assertArrayEquals(rigid.normals(), skinned.normals(), EPS);
        assertArrayEquals(new float[] {2, 1, 0}, skinned.positions(), EPS);
    }

    @Test void threeBoneBlendUsesEachBonesOwnTransform() {
        // Three bones, only the middle one posed, so the blend is a weighted chord of the endpoints.
        var skeleton = new Skeleton(List.of("a", "b", "c"), new int[] {-1, -1, -1}, new float[9]);
        var pose = new BonePose(
                new float[] {0, 0, 0, 0, 0, HALF_PI, 0, 0, 0},
                new float[] {0, 0, 0, 2, -2, 0, 0, 0, 0},
                new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1},
                new boolean[3], new boolean[3], BonePose.identity(3).glow());
        var posed = BoneEvaluator.evaluate(skeleton, pose);
        var bind = BoneEvaluator.evaluate(skeleton, BonePose.identity(3));
        var weights = SkinWeights.of(3, new int[][] {{0, 1, 2}}, new float[][] {{0.5f, 0.25f, 0.25f}});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {0, 1, 0}, new int[] {0});
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), posed, bind,
                MorphWeights.none(0));
        // 0.5*(3,0,0) + 0.25*(2,1,0) + 0.25*(3,0,0) = (1.5,0,0) + (0.5,0.25,0) + (0.75,0,0) = (2.75,0.25,0)
        assertArrayEquals(new float[] {2.75f, 0.25f, 0f}, output.positions(), EPS);
    }

    @Test void morphDeltasAreAppliedBeforeSkinningNotAfter() {
        // The delta lifts the vertex along +X before the quarter turn, so the turn carries it to +Y.
        // Applying the morph after skinning would leave it on the X axis instead.
        var target = new MorphTarget("lift", new float[] {1, 0, 0});
        var weights = SkinWeights.rigid(2, new int[] {1});
        var skin = new MeshSkin.Weighted(weights, SkinningMethod.BDEF, List.of(target));
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {0, 1, 0}, new int[] {1});
        var output = SkinningRenderer.transform(batch, skin, posedTwoBones(), identityPose(2),
                new MorphWeights(new float[] {1}));
        // (3,0,0) + (1,0,0) = (4,0,0); Rz90 -> (0,4,0); + (2,-2,0) = (2,2,0).
        assertArrayEquals(new float[] {2, 2, 0}, output.positions(), EPS);
        // Half weight lands halfway between the un-morphed and fully morphed positions.
        var half = SkinningRenderer.transform(batch, skin, posedTwoBones(), identityPose(2),
                new MorphWeights(new float[] {0.5f}));
        assertArrayEquals(new float[] {2, 1.5f, 0}, half.positions(), EPS);
        // A zero weight must reproduce the un-morphed result exactly.
        var none = SkinningRenderer.transform(batch, skin, posedTwoBones(), identityPose(2),
                new MorphWeights(new float[] {0}));
        assertArrayEquals(new float[] {2, 1, 0}, none.positions(), EPS);
    }

    @Test void morphWeightsMustAddressEveryTarget() {
        var target = new MorphTarget("lift", new float[] {1, 0, 0});
        var skin = new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {1}), SkinningMethod.BDEF, List.of(target));
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {0, 1, 0}, new int[] {1});
        assertEquals("Morph weight count does not match the target list", assertThrows(
                IllegalArgumentException.class,
                () -> SkinningRenderer.transform(batch, skin, posedTwoBones(), identityPose(2),
                        new MorphWeights(new float[] {0, 1}))).getMessage());
        // A target authored for a different vertex count is refused rather than truncated.
        assertEquals("Morph target does not match the skin vertex count", assertThrows(
                IllegalArgumentException.class, () -> new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {1}),
                        SkinningMethod.BDEF, List.of(new MorphTarget("lift", new float[] {1, 0, 0, 1, 0, 0}))))
                .getMessage());
    }

    @Test void dualQuaternionBlendMatchesLinearBlendForCompatibleTransforms() {
        // Both bones are pure rotations about the same axis, and a DLB of a single influence is exact.
        var weights = SkinWeights.rigid(2, new int[] {1});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {1, 0, 0}, new int[] {1});
        var qdef = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.QDEF), posedTwoBones(),
                identityPose(2), MorphWeights.none(0));
        assertArrayEquals(new float[] {2, 1, 0}, qdef.positions(), EPS);
        assertArrayEquals(new float[] {0, 1, 0}, qdef.normals(), EPS);
    }

    @Test void dualQuaternionBlendKeepsTheRotationSphereWhereLinearBlendCollapsesIt() {
        // One bone turns 120 degrees about Z, the other stays put, blended 50/50. Both methods land
        // the vertex at the midpoint angle, but only DLB keeps it on the unit sphere: LBS interpolates
        // the two positions linearly and shrinks the radius (the classic candy-wrapper).
        var skeleton = new Skeleton(List.of("a", "b"), new int[] {-1, -1}, new float[6]);
        var pose = new BonePose(new float[] {0, 0, 0, 0, 0, (float) (2 * Math.PI / 3)}, new float[6],
                new float[] {1, 1, 1, 1, 1, 1}, new boolean[2], new boolean[2], BonePose.identity(2).glow());
        var posed = BoneEvaluator.evaluate(skeleton, pose);
        var bind = BoneEvaluator.evaluate(skeleton, BonePose.identity(2));
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.5f}});
        var batch = new VertexBatch(new float[] {1, 0, 0}, new float[] {1, 0, 0}, new int[] {0});
        var lbs = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), posed, bind,
                MorphWeights.none(0));
        // 0.5*(1,0,0) + 0.5*(cos120, sin120, 0) = (0.25, 0.433, 0): the radius shrinks to 0.5.
        assertArrayEquals(new float[] {0.25f, 0.4330127f, 0}, lbs.positions(), 0.0001f);
        var dlb = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.QDEF), posed, bind,
                MorphWeights.none(0));
        // Half of 120 degrees is 60 degrees on the unit circle: the radius is preserved exactly.
        float[] dlbPosition = dlb.positions();
        assertEquals(1f, Math.hypot(dlbPosition[0], dlbPosition[1]), 0.0001f);
        assertArrayEquals(new float[] {0.5f, 0.8660254f, 0}, dlbPosition, 0.0001f);
    }

    @Test void nearlyAntipodalDualQuaternionBlendStaysUnitLengthInsteadOfDiverging() {
        // Two influences almost a half turn apart on opposite sides, blended with a small reference
        // weight. Their raw quaternion sum nearly cancels, so this is the closest the data model gets
        // to the documented degenerate case; the result must still be a unit rotation, not a blow-up.
        var skeleton = new Skeleton(List.of("ref", "plus", "minus"), new int[] {-1, -1, -1}, new float[9]);
        float nearHalf = (float) (Math.PI - 0.002);
        var pose = new BonePose(
                new float[] {0, 0, 0, nearHalf, 0, 0, -nearHalf, 0, 0},
                new float[9], new float[] {1, 1, 1, 1, 1, 1, 1, 1, 1},
                new boolean[3], new boolean[3], BonePose.identity(3).glow());
        var posed = BoneEvaluator.evaluate(skeleton, pose);
        var bind = BoneEvaluator.evaluate(skeleton, BonePose.identity(3));
        var weights = SkinWeights.of(3, new int[][] {{0, 1, 2}}, new float[][] {{0.02f, 0.49f, 0.49f}});
        var batch = new VertexBatch(new float[] {0, 1, 0}, new float[] {0, 1, 0}, new int[] {1});
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.QDEF), posed, bind,
                MorphWeights.none(0));
        float[] position = output.positions();
        for (float component : position) assertTrue(Float.isFinite(component), "no NaN or infinity");
        // A unit-radius rotation keeps the radius; it must not shrink toward the axis or explode.
        assertEquals(1f, Math.hypot(position[0], position[1]), 0.01f);
    }

    @Test void mirroredSkinTransformIsRefusedByQdefButAcceptedByLinearBlend() {
        // A negative scale is a reflection, which no unit dual quaternion can represent. BDEF is
        // defined for it, so the refusal must be specific to the rigid-double methods.
        var skeleton = new Skeleton(List.of("a"), new int[] {-1}, new float[3]);
        var bind = BoneEvaluator.evaluate(skeleton, BonePose.identity(1));
        var mirrored = BoneEvaluator.evaluate(skeleton, new BonePose(new float[3], new float[3],
                new float[] {-1, 1, 1}, new boolean[1], new boolean[1], BonePose.identity(1).glow()));
        var weights = SkinWeights.rigid(1, new int[] {0});
        var batch = new VertexBatch(new float[] {1, 2, 3}, new float[] {1, 0, 0}, new int[] {0});
        var lbs = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), mirrored, bind,
                MorphWeights.none(0));
        assertArrayEquals(new float[] {-1, 2, 3}, lbs.positions(), EPS);
        assertEquals("Mirrored skin transform", assertThrows(IllegalArgumentException.class,
                () -> SkinningRenderer.transform(batch, skin(weights, SkinningMethod.QDEF), mirrored, bind,
                        MorphWeights.none(0))).getMessage());
    }

    @Test void boneWithoutABindTransformIsRefusedByEveryMethod() {
        // A zero-scaled bone is never emitted, so its bind transform does not exist to be inverted.
        var skeleton = new Skeleton(List.of("a"), new int[] {-1}, new float[3]);
        var zeroPose = new BonePose(new float[3], new float[3], new float[] {0, 0, 0},
                new boolean[1], new boolean[1], BonePose.identity(1).glow());
        var bind = BoneEvaluator.evaluate(skeleton, zeroPose);
        var posed = BoneEvaluator.evaluate(skeleton, BonePose.identity(1));
        var weights = SkinWeights.rigid(1, new int[] {0});
        var batch = new VertexBatch(new float[] {1, 0, 0}, new float[] {1, 0, 0}, new int[] {0});
        for (SkinningMethod method : new SkinningMethod[] {SkinningMethod.BDEF, SkinningMethod.QDEF}) {
            assertEquals("Skin bone has no bind transform", assertThrows(IllegalArgumentException.class,
                    () -> SkinningRenderer.transform(batch, skin(weights, method), posed, bind,
                            MorphWeights.none(0))).getMessage());
        }
    }

    @Test void sphericalBlendCarriesTheCentreAndOnlyRotatesTheOffset() {
        // Centre on the bone pivot at the origin, vertex one unit out, bone rotated a quarter turn.
        var weights = SkinWeights.rigid(2, new int[] {1});
        var centres = new float[] {0, 0, 0};
        var sdef = new MeshSkin.Weighted(weights, SkinningMethod.SDEF, List.of(), new SdefParameters(centres, new float[0]));
        var batch = new VertexBatch(new float[] {1, 0, 0}, new float[] {1, 0, 0}, new int[] {1});
        var output = SkinningRenderer.transform(batch, sdef, posedTwoBones(), identityPose(2), MorphWeights.none(0));
        // centre (0,0,0) -> carried to (2,-2,0); offset (1,0,0) -> rotated to (0,1,0); sum (2,-1,0).
        assertArrayEquals(new float[] {2, -1, 0}, output.positions(), EPS);
        assertArrayEquals(new float[] {0, 1, 0}, output.normals(), EPS);
        // SDEF without authored centres has nothing to place the sphere on.
        var missing = new MeshSkin.Weighted(weights, SkinningMethod.SDEF);
        assertEquals("SDEF requires authored sphere centres", assertThrows(IllegalArgumentException.class,
                () -> SkinningRenderer.transform(batch, missing, posedTwoBones(), identityPose(2),
                        MorphWeights.none(0))).getMessage());
    }

    @Test void hiddenInfluencesAreDroppedAndTheRemainderRenormalized() {
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.25f, 0.75f}});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {1, 0, 0}, new int[] {0});
        var hiddenPose = new BonePose(
                new float[] {0, 0, 0, 0, 0, HALF_PI}, new float[] {0, 0, 0, 2, -2, 0},
                new float[] {1, 1, 1, 1, 1, 1}, new boolean[] {true, false}, new boolean[2],
                BonePose.identity(2).glow());
        var hidden = BoneEvaluator.evaluate(twoBones(), hiddenPose);
        // Bone 0 is hidden, so the whole weight transfers to bone 1 rather than shrinking the result.
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), hidden,
                identityPose(2), MorphWeights.none(0));
        assertArrayEquals(new float[] {2, 1, 0}, output.positions(), EPS);
        assertArrayEquals(new int[] {1}, output.boneIndices());
    }

    @Test void vertexWithEveryInfluenceHiddenIsNotEmitted() {
        var weights = SkinWeights.rigid(2, new int[] {0});
        var batch = new VertexBatch(new float[] {3, 0, 0}, new float[] {1, 0, 0}, new int[] {0});
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF),
                withVisibility(posedTwoBones(), new boolean[] {false, true}), identityPose(2), MorphWeights.none(0));
        assertEquals(0, output.vertexCount());
        assertEquals(0, output.positions().length);
    }

    @Test void skinTransformIsPoseTimesInverseBindSoANonIdentityBindIsHonoured() {
        // Bind pose moves the bone to (10,0,0); the pose leaves it there, so skinning must be identity.
        var skeleton = new Skeleton(List.of("a"), new int[] {-1}, new float[3]);
        var bind = BoneEvaluator.evaluate(skeleton, new BonePose(new float[3], new float[] {10, 0, 0},
                new float[] {1, 1, 1}, new boolean[1], new boolean[1], BonePose.identity(1).glow()));
        var posed = BoneEvaluator.evaluate(skeleton, new BonePose(new float[3], new float[] {10, 0, 0},
                new float[] {1, 1, 1}, new boolean[1], new boolean[1], BonePose.identity(1).glow()));
        var weights = SkinWeights.rigid(1, new int[] {0});
        var batch = new VertexBatch(new float[] {5, 7, 9}, new float[] {0, 0, 1}, new int[] {0});
        var output = SkinningRenderer.transform(batch, skin(weights, SkinningMethod.BDEF), posed, bind,
                MorphWeights.none(0));
        assertArrayEquals(new float[] {5, 7, 9}, output.positions(), EPS);
        assertArrayEquals(new float[] {0, 0, 1}, output.normals(), EPS);
        // And the inverse really is the inverse, not the identity.
        var inverse = BindMatrices.inverse(bind, 0);
        assertEquals(1f, inverse[0], EPS);
        assertEquals(-10f, inverse[12], EPS);
    }

    /**
     * The inverse of an affine transform must be a genuine two-sided inverse, including when the
     * bind has both a non-identity rotation and three distinct translation components.
     *
     * <p>This closes a gap the earlier bind test could not see. {@link #skinTransformIsPoseTimesInverseBindSoANonIdentityBindIsHonoured}
     * uses a pure-translation bind, so its rotation block is the identity and every row sum of the
     * inverse's linear part is exactly {@code 1}. An implementation that builds the inverse
     * translation out of {@code t[row] · (row sum)} — instead of {@code Σ_axis R⁻¹[row][axis]·t[axis]} —
     * therefore agrees with the correct answer on such a bind times the identity: same matrix,
     * nothing to catch. A rotated bind breaks the coincidence, so the assertions below are derived
     * from the defining property {@code M · M⁻¹ = I} rather than from any transcribed triple; the
     * explicit expectation exists only to make a failure legible and is cross-checked by that
     * property in the same test.</p>
     *
     * <p>Bind here is {@code Rz(90°)} with translation {@code (5,7,11)}: all three translations are
     * distinct and the rotation has two non-unit row sums, so a row/column mix-up cannot cancel.</p>
     */
    @Test void inverseOfARotatedBindIsTheTrueInverseNotARowSumApproximation() {
        var skeleton = new Skeleton(List.of("a"), new int[] {-1}, new float[3]);
        var bindPose = new BonePose(new float[] {0, 0, HALF_PI}, new float[] {5, 7, 11},
                new float[] {1, 1, 1}, new boolean[1], new boolean[1], BonePose.identity(1).glow());
        var bind = BoneEvaluator.evaluate(skeleton, bindPose);
        // Guard the fixture itself, so the test can never silently degenerate into the old gap:
        // a non-identity rotation block and three distinct translations are both required.
        assertEquals(0f, bind.matrix(0, 0), EPS);
        assertEquals(1f, bind.matrix(0, 1), EPS);
        assertEquals(-1f, bind.matrix(0, 4), EPS);
        assertEquals(5f, bind.matrix(0, 12), EPS);
        assertEquals(7f, bind.matrix(0, 13), EPS);
        assertEquals(11f, bind.matrix(0, 14), EPS);

        var inverse = BindMatrices.inverse(bind, 0);
        assertNotNull(inverse, "a rigid bind transform is invertible");

        // The property that actually defines an inverse: bind · inverse is the identity.
        assertArrayEquals(IDENTITY_4X4, multiplyColumnMajor(bind, inverse), EPS,
                "bind · inverse must be the identity");
        // Both orders, so a transposed product cannot pass by symmetry of the fixture.
        assertArrayEquals(IDENTITY_4X4, multiplyColumnMajor(inverse, bind), EPS,
                "inverse · bind must be the identity");

        // Rz(90°)⁻¹ = Rz(-90°) = [0 1 0; -1 0 0; 0 0 1], and t⁻¹ = -R⁻¹·t = (-7, 5, -11).
        // The row sums of R⁻¹ are (1, -1, 1), so the row-sum form would give (-5, 7, -11).
        assertArrayEquals(new float[] {0, -1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, -7, 5, -11, 1},
                inverse, EPS, "inverse of Rz(90°) with translation (5,7,11)");

        // And the same property observed through the public skinning path: skinning a vertex with a
        // pose equal to the bind pose must leave it exactly where the model authored it.
        var posed = BoneEvaluator.evaluate(skeleton, bindPose);
        var batch = new VertexBatch(new float[] {3, 4, 5}, new float[] {0, 0, 1}, new int[] {0});
        var output = SkinningRenderer.transform(batch, skin(SkinWeights.rigid(1, new int[] {0}),
                SkinningMethod.BDEF), posed, bind, MorphWeights.none(0));
        assertArrayEquals(new float[] {3, 4, 5}, output.positions(), EPS,
                "pose == bind must be the identity skinning transform");
        assertArrayEquals(new float[] {0, 0, 1}, output.normals(), EPS);
    }

    private static final float[] IDENTITY_4X4 = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /**
     * Independent column-major 4x4 product: deliberately a local reimplementation rather than the
     * production {@code SkinMath.multiply}, so a defect shared with the code under test cannot hide
     * behind its own helper.
     */
    private static float[] multiplyColumnMajor(BoneMatrices left, float[] right) {
        return multiplyColumnMajor(left.transforms(), right);
    }

    /** Same product with the bind matrix on the right, so both orders are expressible. */
    private static float[] multiplyColumnMajor(float[] left, BoneMatrices right) {
        return multiplyColumnMajor(left, right.transforms());
    }

    /** Same product from a bare column-major matrix, so a {@code float[]} operand needs no wrapper. */
    private static float[] multiplyColumnMajor(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0;
                for (int k = 0; k < 4; k++) value += (double) left[k * 4 + row] * right[column * 4 + k];
                result[column * 4 + row] = (float) value;
            }
        }
        return result;
    }

    private static BoneMatrices withVisibility(BoneMatrices matrices, boolean[] visible) {
        return new BoneMatrices(matrices.transforms(), matrices.normals(), visible,
                matrices.descendantVisible(), matrices.locatorPositions(), matrices.glow(), matrices.colorsRgba());
    }
}
