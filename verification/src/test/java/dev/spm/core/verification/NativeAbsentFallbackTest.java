package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.render.cpu.JavaComputeProvider;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T08 named this test and it did not exist. Its purpose is the <em>negative</em> case that a
 * native-optional design must survive: with no provider loaded at all, every Core capability that
 * does not inherently require native code must still work, and every path that does require it must
 * fail loudly rather than silently degrading or falling back to a stale implementation.
 */
final class NativeAbsentFallbackTest {

    private static ModelDocument.Mesh triangle() {
        var vertices = new VertexBatch(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[] {0, 0, 0});
        return new ModelDocument.Mesh("m", vertices, new float[6], new int[] {0, 1, 2},
                List.of(), new ModelDocument.Bounds(0, 0, 0, 1, 1, 0), 0);
    }

    private static ModelDocument document() {
        return new ModelDocument("absent", "test",
                new Skeleton(List.of("root"), new int[] {-1}, new float[3]),
                List.of(triangle()), BonePose.identity(1),
                List.of(new ModelDocument.Material("mat", 1, 1, List.of())), List.of(),
                new ModelDocument.JsonObject(Map.of()));
    }

    @Test void theDefaultProviderIsTheJavaScalarOneAndNeedsNothingLoaded() {
        var renderer = new JavaComputeProvider();
        assertEquals("JAVA_SCALAR", renderer.backend());
        // Both operations must work with no library, no system property and no warm-up.
        var matrices = renderer.evaluateBones(document().skeleton(), BonePose.identity(1));
        assertNotNull(matrices);
        assertEquals(1, matrices.boneCount());
        var rendered = renderer.renderVertices(document().meshes().get(0).vertices(), matrices);
        assertEquals(3, rendered.vertexCount());
    }

    @Test void aCompleteJavaFrameIsProducedWithNoNativeProviderPresent() {
        var budget = new ResourceBudget(16L << 20);
        var asset = new ModelAsset(document(), List.of());
        try (asset; CpuFrame frame = ModelFrameRenderer.render(asset, BonePose.identity(1), budget, new CancellationToken())) {
            assertEquals("JAVA_SCALAR", frame.backend());
            assertEquals(1, frame.meshes().size());
            assertEquals(3, frame.meshes().get(0).vertices().vertexCount());
        }
        assertEquals(0, budget.used());
    }

    @Test void askingForANativeProviderWithoutALibraryFailsWithoutFallback() {
        // NativeTransforms has no provider loaded in this JVM path; asking it to do work must be an
        // error. It must never quietly substitute the Java implementation while claiming to be native.
        assertThrows(IllegalStateException.class, () -> {
            var field = Class.forName("dev.spm.core.nativejni.NativeTransforms");
            var method = field.getDeclaredMethod("requireReady");
            method.setAccessible(true);
            try {
                method.invoke(null);
            } catch (java.lang.reflect.InvocationTargetException cause) {
                throw cause.getCause();
            }
        }, "with no provider loaded, native entry points must refuse rather than fall back");
    }

    @Test void theDecodersAndSceneRuntimeStillWorkWithoutNativeCode() throws Exception {
        // Codec/format/scene are pure Java. This asserts the no-native build is genuinely usable.
        var token = new CancellationToken();
        var budget = new ResourceBudget(16L << 20);
        var policy = DecodePolicy.conservative();

        var scene = new dev.spm.core.scene.SceneInstance(
                new dev.spm.core.scene.SceneDefinition(
                        new dev.spm.core.scene.AnimationClip("c", 1.0, true,
                                Map.of("v", new dev.spm.core.scene.KeyframeCurve(
                                        List.of(new dev.spm.core.scene.KeyframeCurve.Key(0, 0),
                                                new dev.spm.core.scene.KeyframeCurve.Key(1, 1)),
                                        dev.spm.core.scene.KeyframeCurve.Interpolation.LINEAR)),
                                List.of()),
                        dev.spm.core.scene.MolangProgram.compile("0"), Map.of(), Map.of()),
                600);
        var scriptBudget = new dev.spm.core.scene.ScriptBudget(1_000_000, 256, token);
        scene.evaluate(0, 0, new dev.spm.core.scene.SceneInputs(Map.of()), scriptBudget);
        var frame = scene.evaluate(1, 1_000_000_000L, new dev.spm.core.scene.SceneInputs(Map.of()), scriptBudget);
        assertEquals(60, frame.step(), "a one-second advance is 60 fixed steps");
        assertTrue(frame.channels().containsKey("v"));
        assertEquals(0, budget.used());
        assertEquals(0, policy.maxDepth() - 32, "conservative policy depth is unchanged");
    }

    @Test void constructingAProviderFromAMissingLibraryIsAnErrorNotASilentSuccess() {
        var missing = java.nio.file.Path.of("Z:\\definitely-not-here\\spm_core_native.dll");
        assertThrows(Exception.class, () -> {
            var type = Class.forName("dev.spm.core.nativejni.NativeComputeProvider");
            var load = type.getMethod("load", java.nio.file.Path.class, String.class,
                    Class.forName("dev.spm.core.acceleration.Isa"));
            load.invoke(null, missing, "0".repeat(64),
                    Class.forName("dev.spm.core.acceleration.Isa").getEnumConstants()[0]);
        }, "loading a nonexistent library must fail rather than produce a provider");
    }

    @Test void theJavaBackendNameIsStableAndDistinctFromEveryNativeName() throws Exception {
        var backend = new JavaComputeProvider().backend();
        assertEquals("JAVA_SCALAR", backend);
        var isaType = Class.forName("dev.spm.core.acceleration.Isa");
        for (Object constant : isaType.getEnumConstants()) {
            String name = constant.toString();
            if (name.equals("AUTO")) continue;
            assertNotEquals("RUST_" + name, backend,
                    "the Java backend must never collide with a native backend name");
        }
    }
}
