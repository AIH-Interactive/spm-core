package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.YsmArchiveCodec;
import dev.spm.core.parser.SpmModelParser;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import dev.spm.core.scene.PoseAnimationLibrary;
import dev.spm.core.scene.PoseAnimationPlayer;
import dev.spm.core.scene.SceneInputs;
import dev.spm.core.scene.ScriptBudget;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R05 cross-format equivalence: verifies IR and evaluated CPU outputs (non-zero pivots, Euler
 * rotations, negative scales, material resources and animation trajectories) rather than mere load
 * completion.
 */
final class CrossFormatEquivalenceTest {
    private static final float EPS = 1e-5f;

    @Test
    void bbmodelFreeAndStaticFiguraZipProduceEquivalentIrAndCpuFrame() throws Exception {
        String bbmodel = """
                {
                  "meta": {"format_version": "4.10", "model_format": "free"},
                  "name": "avatar_cube",
                  "resolution": {"width": 16, "height": 16},
                  "textures": [{"uuid": "t0", "relative_path": "textures/skin.png", "uv_width": 16, "uv_height": 16}],
                  "elements": [{
                    "uuid": "cube0",
                    "type": "cube",
                    "from": [0, 0, 0],
                    "to": [16, 16, 16],
                    "origin": [8, 0, 0],
                    "rotation": [0, 0, 90],
                    "faces": {
                      "east": {"uv": [0, 0, 16, 16], "texture": 0},
                      "west": {"texture": null},
                      "up": {"texture": null},
                      "down": {"texture": null},
                      "south": {"texture": null},
                      "north": {"texture": null}
                    }
                  }],
                  "outliner": [{"uuid": "grp0", "name": "arm", "origin": [16, 0, 0], "rotation": [0, 0, 45], "children": ["cube0"]}]
                }
                """;
        byte[] pngBytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("avatar.json"));
            zip.write("{\"name\":\"avatar_cube\"}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("model.bbmodel"));
            zip.write(bbmodel.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("textures/skin.png"));
            zip.write(pngBytes);
            zip.closeEntry();
        }
        byte[] figuraBytes = out.toByteArray();

        var parser = new SpmModelParser();
        var budget = new ResourceBudget(32L << 20);
        var token = new CancellationToken();
        try (var bbResult = parser.importModel(new ImportRequest(
                     new ModelSource("bb", new ResourceKey("model.bbmodel"),
                             k -> new ByteArrayInputStream(bbmodel.getBytes(StandardCharsets.UTF_8))),
                     FormatHint.BBMODEL, DecodePolicy.conservative(), budget, token));
             var figResult = parser.importModel(new ImportRequest(
                     new ModelSource("fig", new ResourceKey("demo.figura.zip"),
                             k -> new ByteArrayInputStream(figuraBytes)),
                     FormatHint.AUTO, DecodePolicy.conservative(), budget, token))) {
            var bbDoc = bbResult.models().get(0).asset().document();
            var figDoc = figResult.models().get(0).asset().document();
            assertEquals("bbmodel", bbDoc.sourceFormat());
            assertEquals("bbmodel", figDoc.sourceFormat());
            assertEquals("model.bbmodel", figResult.models().get(0).key());
            assertEquals(bbDoc.skeleton().names(), figDoc.skeleton().names());
            assertArrayEquals(bbDoc.skeleton().pivots(), figDoc.skeleton().pivots(), EPS);
            assertArrayEquals(bbDoc.bindPose().rotations(), figDoc.bindPose().rotations(), EPS);
            assertEquals(bbDoc.materials(), figDoc.materials());
            assertEquals(new ResourceKey("textures/skin.png"), figDoc.materials().get(1).resources().get(0));
            try (var stream = figResult.resources().open(new ResourceKey("textures/skin.png"))) {
                assertArrayEquals(pngBytes, stream.readAllBytes());
            }

            try (CpuFrame bbFrame = ModelFrameRenderer.render(bbDoc, bbDoc.bindPose(), budget, token);
                 CpuFrame figFrame = ModelFrameRenderer.render(figDoc, figDoc.bindPose(), budget, token)) {
                assertArrayEquals(bbFrame.meshes().get(0).vertices().positions(),
                        figFrame.meshes().get(0).vertices().positions(), EPS);
                assertArrayEquals(bbFrame.meshes().get(0).vertices().normals(),
                        figFrame.meshes().get(0).vertices().normals(), EPS);
                assertArrayEquals(bbFrame.meshes().get(0).uvs(), figFrame.meshes().get(0).uvs(), EPS);
                assertArrayEquals(bbFrame.meshes().get(0).indices(), figFrame.meshes().get(0).indices());
            }
        }
        assertEquals(0, budget.used());
    }

    @Test
    void bedrockJsonAndYsmV1V2ArchivesPreserveGeometryAnimationsAndNegativeScaleCpuOutputs() throws Exception {
        String geoJson = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {"identifier": "geometry.equiv", "texture_width": 16, "texture_height": 16},
                    "bones": [{
                      "name": "root",
                      "pivot": [16, 0, 0],
                      "rotation": [0, 0, 90],
                      "cubes": [{"origin": [0, 0, 0], "size": [16, 16, 16], "uv": [0, 0]}]
                    }]
                  }]
                }
                """;
        String animJson = """
                {
                  "format_version": "1.8.0",
                  "animations": {
                    "pose_neg": {
                      "loop": true,
                      "animation_length": 1,
                      "bones": {
                        "root": {
                          "position": [16, 0, 0],
                          "rotation": [0, 0, 90],
                          "scale": [-1, 2, 1]
                        }
                      }
                    }
                  }
                }
                """;
        String trimmedGeo = geoJson.stripTrailing();
        String mainGeoWithAnim = trimmedGeo.substring(0, trimmedGeo.length() - 1)
                + ",\"animations\":{\"pose_neg\":{\"loop\":true,\"animation_length\":1,\"bones\":{\"root\":{\"position\":[16,0,0],\"rotation\":[0,0,90],\"scale\":[-1,2,1]}}}}}";
        byte[] texture = new byte[] {1, 2, 3, 4};

        var parser = new SpmModelParser();
        var budget = new ResourceBudget(32L << 20);
        var token = new CancellationToken();

        var ysmEntries = new LinkedHashMap<ResourceKey, byte[]>();
        ysmEntries.put(new ResourceKey("main.json"), geoJson.getBytes(StandardCharsets.UTF_8));
        ysmEntries.put(new ResourceKey("arm.json"), geoJson.getBytes(StandardCharsets.UTF_8));
        ysmEntries.put(new ResourceKey("main.animation.json"), animJson.getBytes(StandardCharsets.UTF_8));
        ysmEntries.put(new ResourceKey("skin.png"), texture);
        byte[] ysmV2;
        try (var encoded = YsmArchiveCodec.encode(2, ysmEntries, DecodePolicy.conservative(), budget, token)) {
            ysmV2 = encoded.bytes();
        }

        try (var bedrockRes = parser.importModel(new ImportRequest(
                     new ModelSource("bedrock", new ResourceKey("model.geo.json"),
                             k -> new ByteArrayInputStream(mainGeoWithAnim.getBytes(StandardCharsets.UTF_8))),
                     FormatHint.BEDROCK, DecodePolicy.conservative(), budget, token));
             var ysmRes = parser.importModel(new ImportRequest(
                     new ModelSource("ysm-v2", new ResourceKey("avatar.ysm"),
                             k -> new ByteArrayInputStream(ysmV2)),
                     FormatHint.AUTO, DecodePolicy.conservative(), budget, token))) {
            var bedrockDoc = bedrockRes.models().get(0).asset().document();
            var ysmDoc = ysmRes.models().get(0).asset().document();

            assertEquals(bedrockDoc.skeleton().names(), ysmDoc.skeleton().names());
            assertArrayEquals(bedrockDoc.skeleton().pivots(), ysmDoc.skeleton().pivots(), EPS);
            assertArrayEquals(bedrockDoc.meshes().get(0).vertices().positions(),
                    ysmDoc.meshes().get(0).vertices().positions(), EPS);
            assertArrayEquals(bedrockDoc.meshes().get(0).uvs(), ysmDoc.meshes().get(0).uvs(), EPS);

            // Evaluate the same clip with negative scale and rotation on both models.
            var scriptBudget = new ScriptBudget(100_000, 128, token);
            var libBedrock = PoseAnimationLibrary.fromModel(bedrockDoc, scriptBudget);
            var libYsm = PoseAnimationLibrary.fromModel(ysmDoc, scriptBudget);
            var playerBedrock = new PoseAnimationPlayer(libBedrock, bedrockDoc.bindPose(), 60, 0, 1);
            var playerYsm = new PoseAnimationPlayer(libYsm, ysmDoc.bindPose(), 60, 0, 1);
            playerBedrock.play("pose_neg");
            playerYsm.play("pose_neg");

            var poseBedrock = playerBedrock.evaluate(0, 0, new SceneInputs(Map.of()), scriptBudget).pose();
            var poseYsm = playerYsm.evaluate(0, 0, new SceneInputs(Map.of()), scriptBudget).pose();
            assertArrayEquals(poseBedrock.scales(), poseYsm.scales(), EPS);
            assertArrayEquals(poseBedrock.translations(), poseYsm.translations(), EPS);
            assertArrayEquals(poseBedrock.rotations(), poseYsm.rotations(), EPS);

            try (CpuFrame f1 = ModelFrameRenderer.render(bedrockDoc, poseBedrock, budget, token);
                 CpuFrame f2 = ModelFrameRenderer.render(ysmDoc, poseYsm, budget, token)) {
                assertArrayEquals(f1.meshes().get(0).vertices().positions(),
                        f2.meshes().get(0).vertices().positions(), EPS);
                assertArrayEquals(f1.meshes().get(0).vertices().normals(),
                        f2.meshes().get(0).vertices().normals(), EPS);
            }
        }
        assertEquals(0, budget.used());
    }

    @Test
    void gltfWithExternalBufferAndGlbBinaryContainerMatchBitForBit() throws Exception {
        var bin = ByteBuffer.allocate(72 + 8).order(ByteOrder.LITTLE_ENDIAN);
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1};
        for (float v : positions) bin.putFloat(v);
        for (float v : normals) bin.putFloat(v);
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        byte[] binBytes = bin.array();

        String gltfJson = """
                {
                  "asset": {"version": "2.0"},
                  "scene": 0,
                  "scenes": [{"nodes": [0]}],
                  "nodes": [{"mesh": 0}],
                  "meshes": [{"name": "tri", "primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}, "indices": 2}]}],
                  "buffers": [{"byteLength": 80, "uri": "mesh.bin"}],
                  "bufferViews": [
                    {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                    {"buffer": 0, "byteOffset": 36, "byteLength": 36},
                    {"buffer": 0, "byteOffset": 72, "byteLength": 6}
                  ],
                  "accessors": [
                    {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3", "min": [0, 0, 0], "max": [1, 1, 0]},
                    {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC3"},
                    {"bufferView": 2, "componentType": 5123, "count": 3, "type": "SCALAR"}
                  ]
                }
                """;
        String glbJson = gltfJson.replace(", \"uri\": \"mesh.bin\"", "").replace("\n", "").replace(" ", "");
        byte[] jsonText = glbJson.getBytes(StandardCharsets.UTF_8);
        int jsonLen = (jsonText.length + 3) & ~3;
        int total = 12 + 8 + jsonLen + 8 + binBytes.length;
        var glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67).putInt(2).putInt(total).putInt(jsonLen).putInt(0x4e4f534a);
        glb.put(jsonText);
        while (glb.position() < 20 + jsonLen) glb.put((byte) 0x20);
        glb.putInt(binBytes.length).putInt(0x004e4942).put(binBytes);
        byte[] glbBytes = glb.array();

        var parser = new SpmModelParser();
        var budget = new ResourceBudget(8L << 20);
        var token = new CancellationToken();
        try (var gltfRes = parser.importModel(new ImportRequest(
                     new ModelSource("gltf", new ResourceKey("dir/model.gltf"), key -> switch (key.path()) {
                         case "dir/model.gltf" -> new ByteArrayInputStream(gltfJson.getBytes(StandardCharsets.UTF_8));
                         case "dir/mesh.bin" -> new ByteArrayInputStream(binBytes);
                         default -> throw new IOException("Unexpected key: " + key.path());
                     }), FormatHint.AUTO, DecodePolicy.conservative(), budget, token));
             var glbRes = parser.importModel(new ImportRequest(
                     new ModelSource("glb", new ResourceKey("model.glb"),
                             key -> new ByteArrayInputStream(glbBytes)),
                     FormatHint.AUTO, DecodePolicy.conservative(), budget, token))) {
            var gltfDoc = gltfRes.models().get(0).asset().document();
            var glbDoc = glbRes.models().get(0).asset().document();
            assertArrayEquals(gltfDoc.meshes().get(0).vertices().positions(),
                    glbDoc.meshes().get(0).vertices().positions(), 0f);
            assertArrayEquals(gltfDoc.meshes().get(0).vertices().normals(),
                    glbDoc.meshes().get(0).vertices().normals(), 0f);
            assertArrayEquals(gltfDoc.meshes().get(0).indices(), glbDoc.meshes().get(0).indices());
            try (CpuFrame fGltf = ModelFrameRenderer.render(gltfDoc, gltfDoc.bindPose(), budget, token);
                 CpuFrame fGlb = ModelFrameRenderer.render(glbDoc, glbDoc.bindPose(), budget, token)) {
                assertArrayEquals(fGltf.meshes().get(0).vertices().positions(),
                        fGlb.meshes().get(0).vertices().positions(), 0f);
                assertArrayEquals(fGltf.meshes().get(0).vertices().normals(),
                        fGlb.meshes().get(0).vertices().normals(), 0f);
            }
        }
        assertEquals(0, budget.used());
    }
}
