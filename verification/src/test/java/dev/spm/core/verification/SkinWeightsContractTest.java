package dev.spm.core.verification;

import dev.spm.core.api.LayoutDescriptor;
import dev.spm.core.api.MeshSkin;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.Bounds;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.Material;
import dev.spm.core.api.MorphTarget;
import dev.spm.core.api.MorphWeights;
import dev.spm.core.api.SdefParameters;
import dev.spm.core.api.SkinWeights;
import dev.spm.core.api.SkinningMethod;
import dev.spm.core.api.SkinningRig;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.VertexBatch;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Contract tests for the weighted-skin representation: canonical packing, strict validation, layout. */
class SkinWeightsContractTest {
    @Test void packedWeightsAreCanonicalAddressableAndImmutable() {
        var weights = SkinWeights.of(2, new int[][] {{1, 0}, {1}}, new float[][] {{0.25f, 0.75f}, {1}});
        assertEquals(2, weights.vertexCount());
        assertEquals(2, weights.boneCount());
        assertEquals(2, weights.influenceCount(0));
        assertEquals(1, weights.influenceCount(1));
        assertEquals(0, weights.dominantBone(0));
        assertEquals(1, weights.dominantBone(1));
        assertEquals(0.25f, weights.weight(0, 0));
        assertEquals(0.75f, weights.weight(0, 1));
        // Padding is canonical: unused slots hold weight zero and bone zero.
        assertEquals(0f, weights.weight(1, 1));
        assertEquals(0, weights.boneIndex(1, 1));
        assertEquals(0f, weights.weight(0, 3));
        // Accessors are snapshots, not views.
        weights.weights()[0] = 100;
        weights.boneIndices()[0] = 99;
        assertEquals(0.25f, weights.weight(0, 0));
        assertEquals(1, weights.boneIndex(0, 0));
    }

    @Test void rigidHelperExpressesOneFullInfluencePerVertex() {
        var weights = SkinWeights.rigid(3, new int[] {2, 0, 1});
        assertEquals(3, weights.vertexCount());
        for (int vertex = 0; vertex < 3; vertex++) {
            assertEquals(1, weights.influenceCount(vertex));
            assertEquals(1f, weights.weight(vertex, 0));
        }
        assertEquals(2, weights.boneIndex(0, 0));
        assertEquals(0, weights.boneIndex(1, 0));
        assertEquals(1, weights.boneIndex(2, 0));
    }

    @Test void rejectsUnnormalizedWeights() {
        var tooHigh = assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.6f}}));
        assertEquals("Unnormalized skin weight", tooHigh.getMessage());
        var tooLow = assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.2f, 0.2f}}));
        assertEquals("Unnormalized skin weight", tooLow.getMessage());
        // The accepted tolerance is absolute, so a 1e-5 deviation must still pass.
        assertDoesNotThrow(() -> SkinWeights.of(2, new int[][] {{0, 1}},
                new float[][] {{0.5f, 0.50001f}}));
    }

    @Test void rejectsZeroWeightNegativeWeightAndEmptyInfluence() {
        assertEquals("Zero skin weight", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0}}, new float[][] {{0}})).getMessage());
        assertEquals("Zero skin weight", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{}}, new float[][] {{}})).getMessage());
        assertEquals("Invalid skin weight", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{1.5f, -0.5f}})).getMessage());
    }

    @Test void rejectsMoreThanTheInfluenceCap() {
        assertEquals("Too many skin influences", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(6, new int[][] {{0, 1, 2, 3, 4}},
                        new float[][] {{0.2f, 0.2f, 0.2f, 0.2f, 0.2f}})).getMessage());
        // Exactly the cap is legal.
        assertDoesNotThrow(() -> SkinWeights.of(4, new int[][] {{0, 1, 2, 3}},
                new float[][] {{0.25f, 0.25f, 0.25f, 0.25f}}));
        assertEquals(4, SkinWeights.MAX_INFLUENCES);
    }

    @Test void rejectsNonFiniteWeightAndOutOfRangeBone() {
        assertEquals("Invalid skin weight", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, Float.NaN}})).getMessage());
        assertEquals("Invalid skin weight", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 1}},
                        new float[][] {{1f, Float.POSITIVE_INFINITY}})).getMessage());
        assertEquals("Skin bone index out of range", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{0, 5}}, new float[][] {{0.5f, 0.5f}})).getMessage());
        assertEquals("Skin bone index out of range", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{-1, 1}}, new float[][] {{0.5f, 0.5f}})).getMessage());
    }

    @Test void rejectsDuplicateBoneInOneVertex() {
        // The same transform counted twice is a silent weight amplifier, not an authored influence.
        assertEquals("Duplicate skin bone", assertThrows(IllegalArgumentException.class,
                () -> SkinWeights.of(2, new int[][] {{1, 1}}, new float[][] {{0.5f, 0.5f}})).getMessage());
    }

    @Test void rejectsNonCanonicalPackedInput() {
        // Padding slot carrying a bone while its weight is zero.
        assertEquals("Unused skin influence slot", assertThrows(IllegalArgumentException.class,
                () -> new SkinWeights(2, new int[] {0, 1, 0, 0}, new float[] {1, 0, 0, 0})).getMessage());
        // A zero-weight slot before a used one breaks the leading-slot packing rule.
        assertEquals("Unused skin influence slot precedes a used one", assertThrows(IllegalArgumentException.class,
                () -> new SkinWeights(2, new int[] {0, 0, 1, 0}, new float[] {0, 0, 1, 0})).getMessage());
        assertEquals("Skin weight sizes disagree", assertThrows(IllegalArgumentException.class,
                () -> new SkinWeights(2, new int[] {0, 1}, new float[] {1, 0})).getMessage());
        assertEquals("Negative bone count", assertThrows(IllegalArgumentException.class,
                () -> new SkinWeights(-1, new int[0], new float[0])).getMessage());
    }

    @Test void layoutDescriptorRecordsThePackedSkinWire() {
        var layout = LayoutDescriptor.skinWeightsV1();
        assertEquals("spm.logical-skin-weights", layout.id());
        assertEquals(1, layout.version());
        assertEquals(32, layout.stride());
        assertEquals(ByteOrder.LITTLE_ENDIAN, layout.byteOrder());
        assertEquals(0, layout.field("bones").offset());
        assertEquals(LayoutDescriptor.Scalar.U32, layout.field("bones").scalar());
        assertEquals(SkinWeights.MAX_INFLUENCES, layout.field("bones").components());
        assertEquals(16, layout.field("weights").offset());
        assertEquals(LayoutDescriptor.Scalar.F32, layout.field("weights").scalar());
        assertEquals(SkinWeights.MAX_INFLUENCES, layout.field("weights").components());
        // Four indices plus four floats are exactly the stride, so the layout tiles without slack.
        assertEquals(32, layout.field("bones").bytes() + layout.field("weights").bytes());
        assertFalse(layout.equals(LayoutDescriptor.boneWireV1()));
        assertThrows(IllegalArgumentException.class,
                () -> layout.requireCompatible(LayoutDescriptor.boneWireV1()));
    }

    @Test void morphTargetsAndWeightsAreBoundedImmutableAndValidated() {
        var target = new MorphTarget("jaw", new float[] {0, 1, 0, 0, 0, 0});
        assertEquals(2, target.vertexCount());
        assertFalse(target.morphsNormals());
        target.positionDeltas()[0] = 99;
        assertEquals(0, target.positionDeltas()[0]);
        assertEquals("Non-finite morph position delta", assertThrows(IllegalArgumentException.class,
                () -> new MorphTarget("bad", new float[] {Float.NaN, 0, 0})).getMessage());
        assertEquals("Morph position sizes disagree", assertThrows(IllegalArgumentException.class,
                () -> new MorphTarget("bad", new float[] {1, 2})).getMessage());
        assertEquals("Morph normal sizes disagree", assertThrows(IllegalArgumentException.class,
                () -> new MorphTarget("bad", new float[] {0, 0, 0}, new float[] {0, 0})).getMessage());
        assertEquals("Blank morph target name", assertThrows(IllegalArgumentException.class,
                () -> new MorphTarget(" ", new float[] {0, 0, 0})).getMessage());

        assertEquals("Morph weight out of range", assertThrows(IllegalArgumentException.class,
                () -> new MorphWeights(new float[] {1.5f})).getMessage());
        assertEquals("Non-finite morph weight", assertThrows(IllegalArgumentException.class,
                () -> new MorphWeights(new float[] {Float.NaN})).getMessage());
        assertEquals(2, MorphWeights.none(2).targetCount());
        assertTrue(MorphWeights.none(2).isIdentity());
        assertFalse(new MorphWeights(new float[] {0, 0.5f}).isIdentity());
        var weights = new MorphWeights(new float[] {0.5f});
        weights.values()[0] = 100;
        assertEquals(0.5f, weights.weight(0));
        assertEquals("Morph weight count does not match the target list", assertThrows(
                IllegalArgumentException.class, () -> new MorphWeights(new float[] {1, 1}).requireCount(1)).getMessage());
    }

    @Test void rigBindsByMeshNameAndRefusesInconsistentWeightedSkins() {
        var document = document(2, 2);
        var rig = SkinningRig.of(document, Map.of("mesh", weighted(2, 2)));
        assertTrue(rig.hasWeights());
        assertFalse(rig.isEmpty());
        assertEquals(List.of("mesh"), rig.weightedMeshes());
        assertTrue(rig.skin("mesh") instanceof MeshSkin.Weighted);

        assertEquals("Skin rig names an unknown mesh: other", assertThrows(IllegalArgumentException.class,
                () -> SkinningRig.of(document, Map.of("other", weighted(2, 2)))).getMessage());
        assertEquals("Skin weights do not match the mesh vertex count", assertThrows(IllegalArgumentException.class,
                () -> SkinningRig.of(document, Map.of("mesh", weighted(2, 1)))).getMessage());
        assertEquals("Skin weights address an unknown skeleton", assertThrows(IllegalArgumentException.class,
                () -> SkinningRig.of(document, Map.of("mesh", weighted(3, 2)))).getMessage());
        // SDEF without authored sphere centres has no data to run on.
        var sdef = new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {0, 0}), SkinningMethod.SDEF);
        assertEquals("SDEF requires authored sphere centres", assertThrows(IllegalArgumentException.class,
                () -> SkinningRig.of(document, Map.of("mesh", sdef))).getMessage());
    }

    @Test void unboundMeshesStayRigidAndBindingsAreImmutable() {
        var document = document(2, 2);
        var rig = SkinningRig.rigid(document);
        assertFalse(rig.hasWeights());
        assertTrue(rig.isEmpty());
        assertSame(MeshSkin.Rigid.INSTANCE, rig.skin("mesh"));
        assertEquals(List.of(), rig.weightedMeshes());
    }

    @Test void sdefParametersCarryCentresButNotRadii() {
        var parameters = SdefParameters.zeros(2);
        assertTrue(parameters.hasCentres());
        assertEquals(6, parameters.centres().length);
        assertEquals(0, parameters.radii().length);
        parameters.requireVertexCount(2);
        assertEquals("SDEF centres do not match the vertex count", assertThrows(IllegalArgumentException.class,
                () -> parameters.requireVertexCount(3)).getMessage());
        assertEquals("SDEF centre sizes disagree", assertThrows(IllegalArgumentException.class,
                () -> new SdefParameters(new float[] {1, 2}, new float[0])).getMessage());
        assertEquals("Invalid SDEF radius", assertThrows(IllegalArgumentException.class,
                () -> new SdefParameters(new float[] {0, 0, 0}, new float[] {0, 1})).getMessage());
        assertEquals("Non-finite SDEF centre", assertThrows(IllegalArgumentException.class,
                () -> new SdefParameters(new float[] {Float.NaN, 0, 0}, new float[0])).getMessage());
        assertFalse(SdefParameters.none().hasCentres());
    }

    private static MeshSkin.Weighted weighted(int boneCount, int vertexCount) {
        int[] bones = new int[vertexCount];
        return new MeshSkin.Weighted(SkinWeights.rigid(boneCount, bones), SkinningMethod.BDEF);
    }

    static ModelDocument document(int boneCount, int vertexCount) {
        String[] names = new String[boneCount];
        for (int bone = 0; bone < boneCount; bone++) names[bone] = "bone" + bone;
        int[] parents = new int[boneCount];
        java.util.Arrays.fill(parents, -1);
        var skeleton = new Skeleton(List.of(names), parents, new float[boneCount * 3]);
        var vertices = new VertexBatch(new float[vertexCount * 3], new float[vertexCount * 3], new int[vertexCount]);
        var uvs = new float[vertexCount * 2];
        var mesh = new ModelDocument.Mesh("mesh", vertices, uvs, new int[0], List.of(),
                new Bounds(0, 0, 0, 1, 1, 1), 0);
        return new ModelDocument("skin-test", "synthetic", skeleton, List.of(mesh), BonePose.identity(boneCount),
                List.of(new Material("skin", 1, 1, List.of())), List.of(), new JsonObject(Map.of()));
    }
}
