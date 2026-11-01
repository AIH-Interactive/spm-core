package dev.spm.core.verification;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.*;
import dev.spm.core.render.cpu.JavaComputeProvider;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T08 named this test and it did not exist. It pins that the Java scalar path produces a
 * complete, self-consistent frame <em>without any native library present</em>, and that a failed or
 * cancelled render publishes no frame at all.
 *
 * <p>"Parity" here means parity with the documented CPU contract — visible-bone compaction, index
 * remapping, normal normalization, material association, credit accounting — not parity with any GPU
 * or with the historical implementation, neither of which is an available oracle.
 */
final class JavaRenderParityTest {

    /** Two triangles: vertices 0..2 on bone 0, vertices 3..5 on bone 1. */
    private static ModelDocument.Mesh twoBoneTriangles() {
        var vertices = new VertexBatch(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 3, 0, 0, 2, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[] {0, 0, 0, 1, 1, 1});
        return new ModelDocument.Mesh("m", vertices, new float[12],
                new int[] {0, 1, 2, 3, 4, 5}, List.of(),
                new ModelDocument.Bounds(0, 0, 0, 3, 1, 0), 0);
    }

    private static ModelDocument document() {
        return new ModelDocument("parity", "test",
                new Skeleton(List.of("root", "child"), new int[] {-1, 0}, new float[6]),
                List.of(twoBoneTriangles()), BonePose.identity(2),
                List.of(new ModelDocument.Material("mat", 1, 1, List.of())), List.of(),
                new ModelDocument.JsonObject(Map.of()));
    }

    /** A pose that hides bone 1, so its triangle must be dropped and indices remapped. */
    private static BonePose hideSecondBone() {
        int n = 2;
        float[] zero = new float[n * 3];
        float[] scale = {1, 1, 1, 1, 1, 1};
        boolean[] own = {false, true};
        boolean[] children = {false, false};
        int[] glow = {255, 255};
        return new BonePose(zero, zero, scale, own, children, glow);
    }

    private static CpuComputeProvider lyingProvider(VertexBatch replacement) {
        return new CpuComputeProvider() {
            @Override public String backend() { return "LYING"; }
            @Override public BoneMatrices evaluateBones(Skeleton skeleton, BonePose pose) {
                return new JavaComputeProvider().evaluateBones(skeleton, pose);
            }
            @Override public VertexBatch renderVertices(VertexBatch vertices, BoneMatrices matrices) {
                return replacement;
            }
        };
    }

    private static CpuComputeProvider overReportingProvider() {
        return new CpuComputeProvider() {
            @Override public String backend() { return "OVERREPORTING"; }
            @Override public BoneMatrices evaluateBones(Skeleton skeleton, BonePose pose) {
                var real = new JavaComputeProvider().evaluateBones(skeleton, pose);
                // Claim one extra bone: the renderer must reject a bone-count mismatch.
                return new BoneMatrices(
                        Arrays.copyOf(real.transforms(), 3 * 16),
                        Arrays.copyOf(real.normals(), 3 * 9),
                        new boolean[] {true, true, true},
                        new boolean[] {true, true, true},
                        Arrays.copyOf(real.locatorPositions(), 3 * 3),
                        new int[] {255, 255, 255});
            }
            @Override public VertexBatch renderVertices(VertexBatch vertices, BoneMatrices matrices) {
                return new JavaComputeProvider().renderVertices(vertices, matrices);
            }
        };
    }

    @Test void theJavaScalarBackendIsAlwaysAvailableAndReportsItself() {
        assertEquals("JAVA_SCALAR", new JavaComputeProvider().backend());
        var matrices = new JavaComputeProvider().evaluateBones(document().skeleton(), BonePose.identity(2));
        assertEquals(2, matrices.boneCount());
        assertTrue(matrices.visible()[0] && matrices.visible()[1], "identity pose leaves both bones visible");
    }

    @Test void aRigidFrameCompactsHiddenBonesAndRemapsIndices() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(1024)));
        try (asset;
             CpuFrame frame = ModelFrameRenderer.render(asset, hideSecondBone(), budget, new CancellationToken())) {
            assertEquals("JAVA_SCALAR", frame.backend());
            assertEquals("parity", frame.identity());
            var mesh = frame.meshes().get(0);
            assertEquals(3, mesh.vertices().vertexCount(), "the hidden bone's triangle is dropped");
            for (int index : mesh.indices()) {
                assertTrue(index >= 0 && index < 3, "indices must be remapped into the compacted range");
            }
            assertEquals(0, mesh.materialIndex());
            assertEquals(6, mesh.uvs().length, "UVs stay aligned with the surviving vertices");
            float[] normals = mesh.vertices().normals();
            for (int i = 0; i < normals.length; i += 3) {
                double length = Math.sqrt(normals[i] * normals[i] + normals[i + 1] * normals[i + 1]
                        + normals[i + 2] * normals[i + 2]);
                assertEquals(1.0, length, 1e-5, "normals must be normalized");
            }
        }
        assertEquals(0, budget.used(), "every credit is released after the frame and asset close");
    }

    @Test void hidingNothingKeepsAllDiagramsIntact() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of());
        try (asset; CpuFrame frame = ModelFrameRenderer.render(asset, BonePose.identity(2), budget, new CancellationToken())) {
            var mesh = frame.meshes().get(0);
            assertEquals(6, mesh.vertices().vertexCount());
            assertEquals(6, mesh.indices().length);
        }
        assertEquals(0, budget.used());
    }

    @Test void aProviderThatReturnsTheWrongVertexCountIsRejectedAndNothingIsPublished() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(1024)));
        var wrong = new VertexBatch(new float[] {0, 0, 0}, new float[] {0, 0, 1}, new int[] {0});
        try (asset) {
            assertThrows(IllegalArgumentException.class, () -> ModelFrameRenderer.render(
                    asset, BonePose.identity(2), lyingProvider(wrong), budget, new CancellationToken()));
        }
        assertEquals(0, budget.used(), "a rejected render must not leak its reservation");
    }

    @Test void aProviderThatReportsTheWrongBoneCountIsRejected() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(1024)));
        try (asset) {
            assertThrows(IllegalArgumentException.class, () -> ModelFrameRenderer.render(
                    asset, BonePose.identity(2), overReportingProvider(), budget, new CancellationToken()));
        }
        assertEquals(0, budget.used());
    }

    @Test void aCancelledRenderPublishesNoFrameAndReleasesCredits() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(1024)));
        var token = new CancellationToken();
        token.cancel();
        try (asset) {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> ModelFrameRenderer.render(asset, BonePose.identity(2), budget, token));
        }
        assertEquals(0, budget.used());
    }

    @Test void aTightBudgetFailsBeforePublishingAFrame() {
        var asset = new ModelAsset(document(), List.of());
        var budget = new ResourceBudget(1);
        try (asset) {
            assertThrows(ResourceBudgetExceededException.class,
                    () -> ModelFrameRenderer.render(asset, BonePose.identity(2), budget, new CancellationToken()));
        }
        assertEquals(0, budget.used(), "a budget rejection must not consume credits");
    }

    @Test void anOwnedFrameKeepsItsSourceAssetChargedUntilItCloses() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(4096)));
        assertEquals(4096, budget.used());
        var frame = ModelFrameRenderer.render(asset, BonePose.identity(2), budget, new CancellationToken());
        assertTrue(budget.used() > 4096, "the frame adds its own working credits");
        asset.close();
        assertTrue(budget.used() >= 4096, "a live frame keeps its source asset charged");
        frame.close();
        assertEquals(0, budget.used(), "closing the last frame releases everything");
    }

    @Test void frameMeshesAreBorrowedAndBecomeUnavailableAfterClose() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of());
        CpuFrame frame;
        try (asset) {
            frame = ModelFrameRenderer.render(asset, BonePose.identity(2), budget, new CancellationToken());
        }
        assertFalse(frame.meshes().isEmpty());
        frame.close();
        assertThrows(IllegalStateException.class, frame::meshes, "a closed frame exposes nothing");
        frame.close(); // idempotent
        assertEquals(0, budget.used());
    }

    @Test void theJavaPathNeverClaimsANativeBackend() {
        var budget = new ResourceBudget(64L << 20);
        var asset = new ModelAsset(document(), List.of());
        try (asset; CpuFrame frame = ModelFrameRenderer.render(asset, BonePose.identity(2), budget, new CancellationToken())) {
            assertEquals("JAVA_SCALAR", frame.backend(), "the native-free path must report the Java backend");
            assertFalse(frame.backend().startsWith("RUST_"), "Java path must not report a Rust backend");
            for (Isa isa : Isa.values()) {
                if (isa == Isa.AUTO) continue;
                assertNotEquals("RUST_" + isa.name(), frame.backend());
            }
        }
    }
}
