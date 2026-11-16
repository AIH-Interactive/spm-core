package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.parser.SpmModelParser;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import dev.spm.core.scene.GltfAnimationLibrary;
import dev.spm.core.scene.ScriptBudget;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GltfFullPipelineTest {
    @Test void importsSkinnedMorphedAnimatedGltfAndRendersAllInterpolationModes() throws Exception {
        byte[] bin = buildFixtureBin();
        String b64 = Base64.getEncoder().encodeToString(bin);
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[
               {"name":"root","translation":[1,0,0],"children":[1]},
               {"name":"arm","mesh":0,"skin":0}
             ],
             "skins":[{"inverseBindMatrices":4,"joints":[0,1]}],
             "meshes":[{"name":"body","weights":[0.0],
               "primitives":[{"attributes":{"POSITION":0,"JOINTS_0":1,"WEIGHTS_0":2},"targets":[{"POSITION":3}]}]}],
             "animations":[
               {"name":"stepClip","channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}],
                "samplers":[{"input":5,"interpolation":"STEP","output":6}]},
               {"name":"linearRot","channels":[
                  {"sampler":0,"target":{"node":1,"path":"rotation"}},
                  {"sampler":1,"target":{"node":1,"path":"weights"}}],
                "samplers":[
                  {"input":5,"interpolation":"LINEAR","output":7},
                  {"input":5,"interpolation":"LINEAR","output":8}]},
               {"name":"cubicMorph","channels":[{"sampler":0,"target":{"node":1,"path":"weights"}}],
                "samplers":[{"input":5,"interpolation":"CUBICSPLINE","output":9}]}
             ],
             "buffers":[{"byteLength":%d,"uri":"data:application/octet-stream;base64,%s"}],
             "bufferViews":[
               {"buffer":0,"byteOffset":0,"byteLength":36},
               {"buffer":0,"byteOffset":36,"byteLength":12},
               {"buffer":0,"byteOffset":48,"byteLength":48},
               {"buffer":0,"byteOffset":96,"byteLength":36},
               {"buffer":0,"byteOffset":132,"byteLength":128},
               {"buffer":0,"byteOffset":260,"byteLength":8},
               {"buffer":0,"byteOffset":268,"byteLength":24},
               {"buffer":0,"byteOffset":292,"byteLength":32},
               {"buffer":0,"byteOffset":324,"byteLength":8},
               {"buffer":0,"byteOffset":332,"byteLength":24}
             ],
             "accessors":[
               {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
               {"bufferView":1,"componentType":5121,"count":3,"type":"VEC4"},
               {"bufferView":2,"componentType":5126,"count":3,"type":"VEC4"},
               {"bufferView":3,"componentType":5126,"count":3,"type":"VEC3"},
               {"bufferView":4,"componentType":5126,"count":2,"type":"MAT4"},
               {"bufferView":5,"componentType":5126,"count":2,"type":"SCALAR"},
               {"bufferView":6,"componentType":5126,"count":2,"type":"VEC3"},
               {"bufferView":7,"componentType":5126,"count":2,"type":"VEC4"},
               {"bufferView":8,"componentType":5126,"count":2,"type":"SCALAR"},
               {"bufferView":9,"componentType":5126,"count":6,"type":"SCALAR"}
             ]}
            """.formatted(bin.length, b64);

        var parser = new SpmModelParser();
        var budget = new ResourceBudget(4 << 20);
        var token = new CancellationToken();
        var key = new ResourceKey("animated.gltf");
        var req = new ImportRequest(new ModelSource("gltf-full", key,
                k -> new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))),
                FormatHint.GLTF, DecodePolicy.conservative(), budget, token);

        try (var imported = parser.importModel(req)) {
            assertEquals(1, imported.models().size());
            var asset = imported.models().get(0).asset();
            var rig = asset.skinningRig().orElseThrow();
            var lib = GltfAnimationLibrary.compile(asset.document());
            assertEquals(List.of("stepClip", "linearRot", "cubicMorph"), lib.clipNames());
            assertEquals(1.0, lib.durationSeconds("stepClip"), 1e-6);

            var sb = new ScriptBudget(10_000, 32, token);

            // STEP at t=0.5 holds translation [1, 0, 0] -> joint1 world = [1,0,0] * invBind[-1,0,0] = identity
            var stepMid = lib.evaluate(asset, "stepClip", 0.5, false, sb);
            try (CpuFrame f = ModelFrameRenderer.render(asset, stepMid.pose(), rig, stepMid.skinState(), budget, token)) {
                float[] pos = f.meshes().get(0).vertices().positions();
                // Vertex 1 authored at (1, 0, 0), weighted to joint 1 (effective transform = I)
                assertArrayEquals(new float[] {1f, 0f, 0f}, new float[] {pos[3], pos[4], pos[5]}, 1e-4f);
            }

            // STEP at t=1.0 jumps root translation to [5, 0, 0] (+4 delta for joint0 and joint1)
            var stepEnd = lib.evaluate(asset, "stepClip", 1.0, false, sb);
            try (CpuFrame f = ModelFrameRenderer.render(asset, stepEnd.pose(), rig, stepEnd.skinState(), budget, token)) {
                float[] pos = f.meshes().get(0).vertices().positions();
                // Vertex 0 (joint 0): (0,0,0) * T(5,0,0) = (5, 0, 0)
                assertArrayEquals(new float[] {5f, 0f, 0f}, new float[] {pos[0], pos[1], pos[2]}, 1e-4f);
                // Vertex 1 (joint 1): (1,0,0) * T(5,0,0)*T_inv(-1,0,0) = (5, 0, 0)
                assertArrayEquals(new float[] {5f, 0f, 0f}, new float[] {pos[3], pos[4], pos[5]}, 1e-4f);
            }

            // LINEAR at t=0.5: arm rotated 45 deg around Z, morph weight = 0.5 -> morph shifts +Y by 1.0
            var linMid = lib.evaluate(asset, "linearRot", 0.5, false, sb);
            assertEquals(0.5f, linMid.skinState().morph(asset.document().meshes().get(0).name(), 1).values()[0], 1e-4f);
            try (CpuFrame f = ModelFrameRenderer.render(asset, linMid.pose(), rig, linMid.skinState(), budget, token)) {
                float[] pos = f.meshes().get(0).vertices().positions();
                // Vertex 1 before skin: (1, 0, 0) + 0.5*(0, 2, 0) = (1, 1, 0), minus arm bind (1,0,0) = (0,1,0) local to arm,
                // rotated +45deg Z -> (-sin45, cos45, 0) + root(1,0,0) = (1 - sqrt2/2, sqrt2/2, 0)
                float invSqrt2 = (float) (Math.sqrt(2.0) / 2.0);
                assertArrayEquals(new float[] {1f - invSqrt2, invSqrt2, 0f}, new float[] {pos[3], pos[4], pos[5]}, 1e-4f);
            }

            // CUBICSPLINE morph at t=0.5 stays smooth & clamped inside [-1, 1]
            var cubicMid = lib.evaluate(asset, "cubicMorph", 0.5, false, sb);
            cubicMid.skinState().validate(rig);
            float wCubic = cubicMid.skinState().morph(asset.document().meshes().get(0).name(), 1).values()[0];
            assertEquals(0.5f, wCubic, 1e-4f);
        }
        assertEquals(0, budget.used());
    }

    private static byte[] buildFixtureBin() {
        var bin = ByteBuffer.allocate(356).order(ByteOrder.LITTLE_ENDIAN);
        // 0: POSITION (36 bytes): v0=(0,0,0), v1=(1,0,0), v2=(0,1,0)
        for (float v : new float[] {0,0,0, 1,0,0, 0,1,0}) bin.putFloat(v);
        // 1: JOINTS_0 (12 bytes)
        bin.put(new byte[] {0,0,0,0, 1,0,0,0, 0,1,0,0});
        // 2: WEIGHTS_0 (48 bytes)
        for (float v : new float[] {1,0,0,0, 1,0,0,0, 0.5f,0.5f,0,0}) bin.putFloat(v);
        // 3: morph target POSITION (36 bytes): +2 on Y
        for (float v : new float[] {0,2,0, 0,2,0, 0,2,0}) bin.putFloat(v);
        // 4: IBM (2 * 16 = 128 bytes): joint0 = I, joint1 = T(-1, 0, 0) column-major
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) bin.putFloat(c == r ? 1f : 0f);
        for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) {
            bin.putFloat(c == 3 && r == 0 ? -1f : (c == r ? 1f : 0f));
        }
        // 5: times [0, 1] (8 bytes)
        bin.putFloat(0f).putFloat(1f);
        // 6: step translations [1,0,0], [5,0,0] (24 bytes)
        for (float v : new float[] {1,0,0, 5,0,0}) bin.putFloat(v);
        // 7: rotations [0,0,0,1], [0,0,sin45,cos45] (32 bytes)
        float s45 = (float) Math.sin(Math.PI / 4.0);
        for (float v : new float[] {0,0,0,1, 0,0,s45,s45}) bin.putFloat(v);
        // 8: linear weights [0, 1] (8 bytes)
        bin.putFloat(0f).putFloat(1f);
        // 9: cubicspline weights (2 keys * 3 * 1 float = 24 bytes): [in0=0, val0=0, out0=0], [in1=0, val1=1, out1=0]
        for (float v : new float[] {0,0,0, 0,1,0}) bin.putFloat(v);
        return bin.array();
    }
}
