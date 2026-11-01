package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.nativejni.NativeComputeProvider;
import dev.spm.core.nativejni.NativeTransforms;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="spm.native.library", matches=".+")
class NativeComputeProviderTest {
    private static final float[] IDENTITY = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    private static Path library() { return Path.of(System.getProperty("spm.native.library")); }
    private static String digest() throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library())));
    }
    private static NativeComputeProvider provider(Isa requested) throws Exception {
        return NativeComputeProvider.load(library(), digest(), requested);
    }
    private static NativeTransforms capabilities() throws Exception {
        return NativeTransforms.load(library(), digest());
    }

    @Test void autoResolvesOnceToActualNativeSelectionAndForcedRequestsStayFixed() throws Exception {
        var nativeApi = capabilities();
        Isa selected = nativeApi.transform(IDENTITY, new float[]{1,2,3}, Isa.AUTO).isa();
        var auto = provider(Isa.AUTO);
        System.out.println("NativeComputeProvider java.runtime.version=" + System.getProperty("java.runtime.version")
                + "; os.arch=" + System.getProperty("os.arch") + "; selected=" + auto.isa()
                + "; supported=" + java.util.Arrays.stream(Isa.values()).filter(isa -> isa != Isa.AUTO)
                        .filter(nativeApi::supported).toList());
        assertEquals(selected, auto.isa());
        assertEquals("RUST_" + selected.name(), auto.backend());
        assertNotEquals(Isa.AUTO, auto.isa());
        var document = document();
        auto.evaluateBones(document.skeleton(), pose(0));
        assertEquals(selected, auto.isa());
        for (Isa isa : Isa.values()) if (isa != Isa.AUTO && nativeApi.supported(isa)) {
            var forced = provider(isa);
            assertEquals(isa, forced.isa());
            assertEquals("RUST_" + isa.name(), forced.backend());
        }
    }

    @Test void unsupportedForcedIsaIsRejectedBeforeAnyFrameCanBePublished() throws Exception {
        var nativeApi = capabilities();
        int rejected = 0;
        for (Isa isa : Isa.values()) if (isa != Isa.AUTO && !nativeApi.supported(isa)) {
            assertThrows(IllegalArgumentException.class, () -> provider(isa));
            rejected++;
        }
        assertTrue(rejected > 0, "a platform cannot advertise both x86-only and ARM-only instructions");
        assertThrows(IllegalArgumentException.class,
                () -> NativeComputeProvider.load(library(), "0".repeat(64), Isa.SCALAR));
    }

    @Test void fullFramesMatchJavaForEverySupportedIsaWithMirrorsHidingNormalsTintAndIndexUvCompaction() throws Exception {
        var nativeApi = capabilities();
        var document = document();
        var budget = new ResourceBudget(1 << 20);
        for (Isa isa : Isa.values()) if (nativeApi.supported(isa)) {
            var provider = provider(isa);
            for (int scenario = 0; scenario < 4; scenario++) {
                try (var javaFrame = ModelFrameRenderer.render(document, pose(scenario), budget, new CancellationToken());
                     var rustFrame = ModelFrameRenderer.render(document, pose(scenario), provider, budget, new CancellationToken())) {
                    assertEquals("JAVA_SCALAR", javaFrame.backend());
                    assertEquals(provider.backend(), rustFrame.backend());
                    equal(javaFrame, rustFrame);
                    var output = rustFrame.meshes().get(0);
                    if (scenario == 0) {
                        assertEquals(12, output.vertices().vertexCount());
                        assertEquals(0x40202004, output.vertices().colorsRgba()[0]);
                        assertEquals(7, output.vertices().glow()[0]);
                        assertTrue(output.vertices().positions()[0] < 0, "the mirrored child reverses its X axis");
                    } else if (scenario == 1) {
                        assertArrayEquals(new int[]{0,1,2,3,4,5,6,7,8}, output.indices());
                        assertArrayEquals(new int[]{1,1,1,2,2,2,3,3,3}, output.vertices().boneIndices());
                        assertArrayEquals(new int[]{255,255,255,3,3,3,0,0,0}, output.vertices().glow());
                        assertArrayEquals(new float[]{.25f,0,.5f,0,.25f,.5f, .5f,0,.75f,0,.5f,.5f, .75f,0,1,0,.75f,.5f}, output.uvs());
                    } else if (scenario == 2) {
                        assertArrayEquals(new int[]{0,0,0,1,1,1,3,3,3}, output.vertices().boneIndices());
                    } else {
                        assertArrayEquals(new int[]{1,1,1,3,3,3}, output.vertices().boneIndices());
                    }
                }
                assertEquals(0, budget.used());
            }
        }
    }

    @Test void framePinsRetiredAssetAndReleasesOnlyItsOwnBudgetGeneration() throws Exception {
        var provider = provider(Isa.AUTO);
        var budget = new ResourceBudget(1 << 20);
        var asset = new ModelAsset(document(), List.of(budget.reserve(512)));
        CpuFrame frame = null;
        ModelAsset replacement = null;
        try {
            frame = ModelFrameRenderer.render(asset, pose(1), provider, budget, new CancellationToken());
            long frameAndAsset = budget.used();
            assertTrue(frameAndAsset > 512);
            asset.close();
            assertEquals(frameAndAsset, budget.used());
            assertEquals(9, frame.meshes().get(0).vertices().vertexCount());
            assertThrows(IllegalStateException.class, asset::acquire);
            replacement = new ModelAsset(document(), List.of(budget.reserve(128)));
            assertEquals(frameAndAsset + 128, budget.used());
            frame.close();
            frame.close();
            assertEquals(128, budget.used());
            assertThrows(IllegalStateException.class, frame::meshes);
            assertEquals("native-provider-synthetic", replacement.document().identity());
        } finally {
            if (frame != null) frame.close();
            asset.close();
            if (replacement != null) replacement.close();
        }
        assertEquals(0, budget.used());
    }

    @Test void rejectedAndCancelledFramesReturnWorkingBudgetAndTheirSourceLease() throws Exception {
        var provider = provider(Isa.SCALAR);
        var budget = new ResourceBudget(1 << 20);
        try (var asset = new ModelAsset(document(), List.of(budget.reserve(512)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModelFrameRenderer.render(asset, BonePose.identity(0), provider, budget, new CancellationToken()));
            assertEquals(512, budget.used());
            var cancelled = new CancellationToken();
            cancelled.cancel();
            assertThrows(CancellationException.class,
                    () -> ModelFrameRenderer.render(asset, pose(0), provider, budget, cancelled));
            assertEquals(512, budget.used());
        }
        assertEquals(0, budget.used(), "failed renders must not leave an asset lease pinned");
    }

    private static void equal(CpuFrame expected, CpuFrame actual) {
        assertEquals(expected.identity(), actual.identity());
        assertEquals(expected.meshes().size(), actual.meshes().size());
        for (int i = 0; i < expected.meshes().size(); i++) {
            var left = expected.meshes().get(i);
            var right = actual.meshes().get(i);
            assertEquals(left.name(), right.name());
            assertEquals(left.materialIndex(), right.materialIndex());
            assertArrayEquals(left.uvs(), right.uvs());
            assertArrayEquals(left.indices(), right.indices());
            assertArrayEquals(left.vertices().positions(), right.vertices().positions(), .0001f);
            assertArrayEquals(left.vertices().normals(), right.vertices().normals(), .0001f);
            assertArrayEquals(left.vertices().boneIndices(), right.vertices().boneIndices());
            assertArrayEquals(left.vertices().glow(), right.vertices().glow());
            assertArrayEquals(left.vertices().colorsRgba(), right.vertices().colorsRgba());
        }
    }

    private static ModelDocument document() {
        var skeleton = new Skeleton(List.of("child", "root", "leaf", "independent"),
                new int[]{1,-1,0,-1}, new float[12]);
        float[] points = {1,0,0,2,0,0,1,1,0, 1,0,0,2,0,0,1,1,0,
                         1,0,0,2,0,0,1,1,0, 1,0,0,2,0,0,1,1,0};
        float[] normals = {1,1,0,1,1,0,1,1,0, 1,1,0,1,1,0,1,1,0,
                          1,1,0,1,1,0,1,1,0, 1,1,0,1,1,0,1,1,0};
        int[] colors = new int[12]; java.util.Arrays.fill(colors, 0x8080ff40);
        int[] glow = new int[12]; java.util.Arrays.fill(glow, 15);
        var vertices = new VertexBatch(points, normals, new int[]{0,0,0,1,1,1,2,2,2,3,3,3}, glow, colors);
        float[] uv = {0,0,.25f,0,0,.5f, .25f,0,.5f,0,.25f,.5f,
                      .5f,0,.75f,0,.5f,.5f, .75f,0,1,0,.75f,.5f};
        var mesh = new Mesh("surface", vertices, uv, new int[]{0,1,2,3,4,5,6,7,8,9,10,11,0,3,9},
                List.of(), new Bounds(1,0,0,2,1,0), 0);
        return new ModelDocument("native-provider-synthetic", "hand-authored", skeleton, List.of(mesh),
                BonePose.identity(4), List.of(new Material("skin",16,16,List.of())), List.of(), new JsonObject(Map.of()));
    }

    private static BonePose pose(int scenario) {
        float[] scales = {-2,3,4, 2,1,1, 1,2,3, 1,1,1};
        if (scenario == 3) scales[0] = 0;
        return new BonePose(new float[]{0,0,.25f, 0,.1f,0, .2f,0,0, 0,0,0},
                new float[]{0,0,0, 0,0,0, 1,2,3, 0,1,0}, scales,
                new boolean[]{scenario == 1,false,false,false},
                new boolean[]{scenario == 2,false,false,false}, new int[]{7,255,3,0},
                new int[]{0x80402010,0xffffffff,0x0102feff,0xffffffff});
    }
}
