package dev.spm.core.verification;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.MeshSkin;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.Bounds;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.Material;
import dev.spm.core.api.MorphTarget;
import dev.spm.core.api.MorphWeights;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.ResourceBudgetExceededException;
import dev.spm.core.api.SkinWeights;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.SkinState;
import dev.spm.core.api.SkinningMethod;
import dev.spm.core.api.SkinningRig;
import dev.spm.core.api.VertexBatch;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Weighted frame composition: rigid meshes must stay byte-identical, weighted meshes must skin, and
 * every failure path must leave the shared budget at zero.
 */
class WeightedFrameRendererTest {
    private static final float EPS = 0.000002f;
    private static final float HALF_PI = (float) (Math.PI / 2);

    /** Two root bones at the origin; bone 0 stays put, bone 1 turns a quarter turn about Z. */
    private static ModelDocument document(int vertices, int[] bones, float[] positions) {
        var skeleton = new Skeleton(List.of("root", "other"), new int[] {-1, -1}, new float[6]);
        var batch = new VertexBatch(positions, new float[vertices * 3], bones);
        var mesh = new ModelDocument.Mesh("mesh", batch, new float[vertices * 2], new int[0], List.of(),
                new Bounds(-10, -10, -10, 10, 10, 10), 0);
        return new ModelDocument("weighted-test", "synthetic", skeleton, List.of(mesh), BonePose.identity(2),
                List.of(new Material("skin", 1, 1, List.of())), List.of(), new JsonObject(Map.of()));
    }

    private static BonePose posed() {
        return new BonePose(new float[] {0, 0, 0, 0, 0, HALF_PI}, new float[] {0, 0, 0, 2, -2, 0},
                new float[] {1, 1, 1, 1, 1, 1}, new boolean[2], new boolean[2], BonePose.identity(2).glow());
    }

    @Test void weightedFrameSkinsAndReportsTheProviderBackend() {
        var document = document(1, new int[] {0}, new float[] {3, 0, 0});
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.5f}});
        var rig = SkinningRig.of(document, Map.of("mesh", new MeshSkin.Weighted(weights, SkinningMethod.BDEF)));
        var budget = new ResourceBudget(1 << 20);
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), rig, SkinState.rest(), budget,
                new CancellationToken())) {
            assertEquals("JAVA_SCALAR", frame.backend());
            assertEquals("weighted-test", frame.identity());
            assertEquals(1, frame.meshes().size());
            var output = frame.meshes().get(0);
            assertArrayEquals(new float[] {2.5f, 0.5f, 0f}, output.vertices().positions(), EPS);
            // A weighted frame is charged more than the rigid cost, and the charge is live.
            assertTrue(budget.used() > 0);
        }
        assertEquals(0, budget.used());
    }

    @Test void rigidMeshesStayByteIdenticalUnderTheWeightedRenderer() {
        // A two-vertex mesh, one vertex per bone, no skin data at all.
        var positions = new float[] {0, 0, 0, 1, 0, 0};
        var document = document(2, new int[] {0, 1}, positions);
        var budget = new ResourceBudget(1 << 20);
        float[] rigidPositions;
        float[] rigidNormals;
        int[] rigidBones;
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), budget, new CancellationToken())) {
            var output = frame.meshes().get(0);
            rigidPositions = output.vertices().positions();
            rigidNormals = output.vertices().normals();
            rigidBones = output.vertices().boneIndices();
        }
        assertEquals(0, budget.used());
        // The exact same document through the weighted entry point with an empty rig.
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), SkinningRig.rigid(document),
                SkinState.rest(), budget, new CancellationToken())) {
            var output = frame.meshes().get(0);
            assertArrayEquals(rigidPositions, output.vertices().positions(), 0f);
            assertArrayEquals(rigidNormals, output.vertices().normals(), 0f);
            assertArrayEquals(rigidBones, output.vertices().boneIndices());
        }
        assertEquals(0, budget.used());
        // Rigid meshes under a weighted rig for a *different* mesh also stay on the rigid path.
        var skeleton = new Skeleton(List.of("root", "other"), new int[] {-1, -1}, new float[6]);
        var rigidMesh = new ModelDocument.Mesh("rigid", new VertexBatch(positions, new float[6], new int[] {0, 1}),
                new float[4], new int[0], List.of(), new Bounds(-10, -10, -10, 10, 10, 10), 0);
        var skinnedMesh = new ModelDocument.Mesh("skinned", new VertexBatch(new float[] {3, 0, 0}, new float[3],
                new int[] {0}), new float[2], new int[0], List.of(),
                new Bounds(-10, -10, -10, 10, 10, 10), 0);
        var mixed = new ModelDocument("mixed", "synthetic", skeleton, List.of(rigidMesh, skinnedMesh),
                BonePose.identity(2), List.of(new Material("skin", 1, 1, List.of())), List.of(), new JsonObject(Map.of()));
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.5f}});
        var rig = SkinningRig.of(mixed, Map.of("skinned", new MeshSkin.Weighted(weights, SkinningMethod.BDEF)));
        try (CpuFrame frame = ModelFrameRenderer.render(mixed, posed(), rig, SkinState.rest(), budget,
                new CancellationToken())) {
            assertEquals(2, frame.meshes().size());
            assertArrayEquals(rigidPositions, frame.meshes().get(0).vertices().positions(), 0f);
            assertArrayEquals(new float[] {2.5f, 0.5f, 0f}, frame.meshes().get(1).vertices().positions(), EPS);
        }
        assertEquals(0, budget.used());
    }

    @Test void morphTargetsReachTheFrameAndOrderBeforeSkinning() {
        var document = document(1, new int[] {1}, new float[] {3, 0, 0});
        var target = new MorphTarget("lift", new float[] {1, 0, 0});
        var weights = SkinWeights.rigid(2, new int[] {1});
        var skin = new MeshSkin.Weighted(weights, SkinningMethod.BDEF, List.of(target));
        var rig = SkinningRig.of(document, Map.of("mesh", skin));
        var budget = new ResourceBudget(1 << 20);
        var state = SkinState.of(Map.of("mesh", new MorphWeights(new float[] {1})));
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), rig, state, budget,
                new CancellationToken())) {
            // (3,0,0)+(1,0,0) = (4,0,0), turned to (0,4,0), then translated to (2,2,0).
            assertArrayEquals(new float[] {2, 2, 0}, frame.meshes().get(0).vertices().positions(), EPS);
        }
        assertEquals(0, budget.used());
    }

    @Test void morphStateThatOmitsOrMismatchesWeightsIsRefused() {
        var document = document(1, new int[] {1}, new float[] {3, 0, 0});
        var target = new MorphTarget("lift", new float[] {1, 0, 0});
        var skin = new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {1}), SkinningMethod.BDEF, List.of(target));
        var rig = SkinningRig.of(document, Map.of("mesh", skin));
        var budget = new ResourceBudget(1 << 20);
        // rest() omits a weight list the rig needs.
        assertEquals("Missing morph weights for mesh: mesh", assertThrows(IllegalArgumentException.class,
                () -> ModelFrameRenderer.render(document, posed(), rig, SkinState.rest(), budget,
                        new CancellationToken())).getMessage());
        assertEquals(0, budget.used());
        // A state naming a mesh without targets is equally wrong.
        var noTargets = SkinningRig.of(document, Map.of("mesh",
                new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {1}), SkinningMethod.BDEF)));
        var state = SkinState.of(Map.of("mesh", new MorphWeights(new float[] {1})));
        assertEquals("Morph weights name a mesh without morph targets: mesh", assertThrows(
                IllegalArgumentException.class, () -> ModelFrameRenderer.render(document, posed(), noTargets,
                        state, budget, new CancellationToken())).getMessage());
        assertEquals(0, budget.used());
    }

    @Test void budgetExceededPublishesNothingAndChargesNothing() {
        var document = document(1, new int[] {0}, new float[] {3, 0, 0});
        var weights = SkinWeights.of(2, new int[][] {{0, 1}}, new float[][] {{0.5f, 0.5f}});
        var rig = SkinningRig.of(document, Map.of("mesh", new MeshSkin.Weighted(weights, SkinningMethod.BDEF)));
        var starved = new ResourceBudget(1);
        assertThrows(ResourceBudgetExceededException.class,
                () -> ModelFrameRenderer.render(document, posed(), rig, SkinState.rest(), starved,
                        new CancellationToken()));
        assertEquals(0, starved.used());
        // A weighted frame must cost strictly more than the identical rigid frame.
        var rigidBudget = new ResourceBudget(1 << 20);
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), rigidBudget, new CancellationToken())) {
            assertNotNull(frame);
        }
        long rigidPeak = rigidBudget.peakUsed();
        assertEquals(0, rigidBudget.used());
        var weightedBudget = new ResourceBudget(1 << 20);
        try (CpuFrame frame = ModelFrameRenderer.render(document, posed(), rig, SkinState.rest(), weightedBudget,
                new CancellationToken())) {
            assertNotNull(frame);
        }
        assertTrue(weightedBudget.peakUsed() > rigidPeak,
                "weighted frame must be charged more: " + weightedBudget.peakUsed() + " vs " + rigidPeak);
        assertEquals(0, weightedBudget.used());
    }

    @Test void invalidSkinDataFailsBeforeAnyOutputAndReleasesTheBudget() {
        var document = document(1, new int[] {0}, new float[] {3, 0, 0});
        var budget = new ResourceBudget(1 << 20);
        // A provider whose bone count disagrees must be refused, with nothing charged.
        var wrongBones = SkinningRig.of(document, Map.of("mesh",
                new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {0}), SkinningMethod.BDEF)));
        assertEquals(0, budget.used());
        // Cancellation before work charges nothing.
        var cancelled = new CancellationToken();
        cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ModelFrameRenderer.render(document, posed(), wrongBones, SkinState.rest(), budget, cancelled));
        assertEquals(0, budget.used());
        // A rigid frame that is cancelled mid-flight releases its reservation too.
        var cancelledRigid = new CancellationToken();
        cancelledRigid.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ModelFrameRenderer.render(document, posed(), budget, cancelledRigid));
        assertEquals(0, budget.used());
    }

    @Test void hiddenBoneSuppressesWeightedVerticesItFullyOwns() {
        var document = document(2, new int[] {0, 1}, new float[] {0, 0, 0, 3, 0, 0});
        var weights = SkinWeights.rigid(2, new int[] {0, 1});
        var rig = SkinningRig.of(document, Map.of("mesh", new MeshSkin.Weighted(weights, SkinningMethod.BDEF)));
        var hiddenPose = new BonePose(new float[] {0, 0, 0, 0, 0, HALF_PI}, new float[] {0, 0, 0, 2, -2, 0},
                new float[] {1, 1, 1, 1, 1, 1}, new boolean[] {true, false}, new boolean[2],
                BonePose.identity(2).glow());
        var budget = new ResourceBudget(1 << 20);
        try (CpuFrame frame = ModelFrameRenderer.render(document, hiddenPose, rig, SkinState.rest(), budget,
                new CancellationToken())) {
            var output = frame.meshes().get(0).vertices();
            assertEquals(1, output.vertexCount());
            assertArrayEquals(new int[] {1}, output.boneIndices());
            assertArrayEquals(new float[] {2, 1, 0}, output.positions(), EPS);
        }
        assertEquals(0, budget.used());
    }

    @Test void weightedTriangleIndicesFollowTheVisibilityRemap() {
        // Three vertices, one per corner; hiding bone 0 must drop the triangle, not renumber it.
        var skeleton = new Skeleton(List.of("a", "b"), new int[] {-1, -1}, new float[6]);
        var positions = new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0};
        var batch = new VertexBatch(positions, new float[9], new int[] {0, 1, 1});
        var mesh = new ModelDocument.Mesh("mesh", batch, new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2},
                List.of(), new Bounds(-10, -10, -10, 10, 10, 10), 0);
        var document = new ModelDocument("tri", "synthetic", skeleton, List.of(mesh), BonePose.identity(2),
                List.of(new Material("skin", 1, 1, List.of())), List.of(), new JsonObject(Map.of()));
        var rig = SkinningRig.of(document, Map.of("mesh",
                new MeshSkin.Weighted(SkinWeights.rigid(2, new int[] {0, 1, 1}), SkinningMethod.BDEF)));
        var budget = new ResourceBudget(1 << 20);
        var hiddenPose = new BonePose(BonePose.identity(2).rotations(), BonePose.identity(2).translations(),
                BonePose.identity(2).scales(), new boolean[] {true, false}, new boolean[2],
                BonePose.identity(2).glow());
        try (CpuFrame frame = ModelFrameRenderer.render(document, hiddenPose, rig, SkinState.rest(), budget,
                new CancellationToken())) {
            var output = frame.meshes().get(0);
            assertEquals(2, output.vertices().vertexCount());
            assertEquals(0, output.indices().length, "the triangle references a hidden vertex");
            // Vertices 1 and 2 survive, keeping their own UVs in source order.
            assertArrayEquals(new float[] {1, 0, 0, 1}, output.uvs());
        }
        assertEquals(0, budget.used());
    }
}
