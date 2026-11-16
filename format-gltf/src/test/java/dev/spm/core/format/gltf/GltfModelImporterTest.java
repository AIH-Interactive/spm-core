package dev.spm.core.format.gltf;

import dev.spm.core.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GltfModelImporterTest {
    @Test void preservesAuthoredNormalsAndRejectsMismatchedCountsWithoutLeaking() throws Exception {
        var bytes=ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:new float[]{0,0,0,1,0,0,0,1,0, 0,1,0,0,1,0,0,1,0})bytes.putFloat(v);
        String data=java.util.Base64.getEncoder().encodeToString(bytes.array());
        for(int count:new int[]{3,2}) {
            String json="""
                {"asset":{"version":"2.0"},"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}],
                 "buffers":[{"byteLength":72,"uri":"data:application/octet-stream;base64,%s"}],
                 "bufferViews":[{"buffer":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":1,"componentType":5126,"count":%d,"type":"VEC3"}]}
                """.formatted(data,count);
            var budget=new ResourceBudget(1<<20);var key=new ResourceKey("authored.gltf");
            var req=new ImportRequest(new ModelSource("authored",key,k->new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8))),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
            if(count==3)try(var imported=new GltfModelImporter().importModel(req)) {
                assertArrayEquals(new float[]{0,1,0,0,1,0,0,1,0},imported.models().get(0).asset().document().meshes().get(0).vertices().normals());
            } else assertEquals(ImportErrorCode.MALFORMED_INPUT,assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(req)).diagnostic().code());
            assertEquals(0,budget.used());
        }
    }
    private static byte[] glb(int[] indices) throws IOException {
        var bin=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bin.putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0);
        bin.putShort((short)indices[0]).putShort((short)indices[1]).putShort((short)indices[2]).putShort((short)0);
        String json="""
          {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
           "meshes":[{"name":"tri","primitives":[{"attributes":{"POSITION":0},"indices":1,"mode":4}]}],
           "buffers":[{"byteLength":42}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
           "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                         {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}
          """.replace("\n","").replace(" ","");
        byte[] text=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);int jsonLength=(text.length+3)&~3;
        int total=12+8+jsonLength+8+44;var out=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546c67).putInt(2).putInt(total).putInt(jsonLength).putInt(0x4e4f534a);
        out.put(text);while(out.position()<20+jsonLength)out.put((byte)0x20);
        out.putInt(44).putInt(0x004e4942).put(bin.array());return out.array();
    }
    private static ImportRequest request(byte[] bytes,ResourceBudget budget) {
        var key=new ResourceKey("model.glb");
        return new ImportRequest(new ModelSource("fixture",key,k->new java.io.ByteArrayInputStream(bytes)),FormatHint.GLB,
                DecodePolicy.conservative(),budget,new CancellationToken());
    }
    @Test void importsStaticTriangleFromGlbAndOwnsBudgetUntilClose() throws Exception {
        var budget=new ResourceBudget(1<<20);var result=new GltfModelImporter().importModel(request(glb(new int[]{0,1,2}),budget));
        try {
            var doc=result.models().get(0).asset().document();
            assertEquals("tri",doc.meshes().get(0).name());assertArrayEquals(new float[]{0,0,0,1,0,0,0,1,0},doc.meshes().get(0).vertices().positions());
            assertArrayEquals(new int[]{0,1,2},doc.meshes().get(0).indices());assertTrue(budget.used()>0);
        } finally {result.close();}
        assertEquals(0,budget.used());
    }
    @Test void multipleMeshesShareOneImportBudgetTransfer() throws Exception {
        String json="""
          {"asset":{"version":"2.0"},"meshes":[{"name":"a","primitives":[{"attributes":{"POSITION":0}},{"attributes":{"POSITION":0}}]},{"name":"b","primitives":[{"attributes":{"POSITION":0}},{"attributes":{"POSITION":0}}]}],
           "buffers":[{"uri":"buf.bin","byteLength":36}],"bufferViews":[{"buffer":0,"byteLength":36}],
           "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}]}
          """.replace("\n","").replace(" ","");
        byte[] positions=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).array();
        var key=new ResourceKey("dir/model.gltf");var budget=new ResourceBudget(1<<20);
        var request=new ImportRequest(new ModelSource("multi",key,k->new java.io.ByteArrayInputStream(k.path().equals("dir/buf.bin")?positions:json.getBytes(java.nio.charset.StandardCharsets.UTF_8))),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new GltfModelImporter().importModel(request)) { assertEquals(2,result.models().size());assertEquals(4,budget.used()>0?4:0); }
        assertEquals(0,budget.used());
    }    @Test void rejectsIndexOutsideVertexAccessor() throws Exception {
        var failure=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(request(glb(new int[]{0,1,9}),new ResourceBudget(1<<20))));
        assertEquals(ImportErrorCode.MALFORMED_INPUT,failure.diagnostic().code());
    }
    @Test void rejectsExternalBufferWithoutResolverAndNeverReadsFileOrNetworkDirectly() throws Exception {
        var key=new ResourceKey("model.gltf");byte[] json="{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"file:///secret\",\"byteLength\":4}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=new ImportRequest(new ModelSource("fixture",key,k->{throw new IOException("resolver denied");}),FormatHint.GLTF,DecodePolicy.conservative(),new ResourceBudget(1<<20),new CancellationToken());
        assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(request));
    }
    @Test void supportsBoundedDataUriAndSharesRetentionUntilEveryModelCloses() throws Exception {
        byte[] positions=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).array();
        String encoded=java.util.Base64.getEncoder().encodeToString(positions);
        String json="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"name\":\"a\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]},{\"name\":\"b\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"+encoded+"\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";
        byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);var budget=new ResourceBudget(1<<20);var key=new ResourceKey("model.gltf");
        var req=new ImportRequest(new ModelSource("data",key,k->new java.io.ByteArrayInputStream(bytes)),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        var result=new GltfModelImporter().importModel(req);var first=result.models().get(0).asset();var second=result.models().get(1).asset();long retained=budget.used();
        first.close();assertEquals(retained,budget.used());assertTrue(second.isOpen());second.close();assertEquals(0,budget.used());result.close();
    }
    @Test void rejectsSparseAccessorsAndExtensionsExplicitly() throws Exception {
        String sparse="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\",\"sparse\":{\"count\":1,\"indices\":{\"bufferView\":0,\"componentType\":5123},\"values\":{\"bufferView\":0}}}]}";
        var key=new ResourceKey("sparse.gltf");var budget=new ResourceBudget(1<<20);
        var req=new ImportRequest(new ModelSource("sparse",key,k->new java.io.ByteArrayInputStream(sparse.getBytes(java.nio.charset.StandardCharsets.UTF_8))),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        var failure=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(req));
        assertEquals(ImportErrorCode.UNSUPPORTED_FEATURE,failure.diagnostic().code());
        assertEquals(0,budget.used());
        String ext="{\"asset\":{\"version\":\"2.0\"},\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]}";
        var extReq=new ImportRequest(new ModelSource("ext",key,k->new java.io.ByteArrayInputStream(ext.getBytes(java.nio.charset.StandardCharsets.UTF_8))),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        assertEquals(ImportErrorCode.UNSUPPORTED_FEATURE,assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(extReq)).diagnostic().code());
        assertEquals(0,budget.used());
    }
    @Test void importsNodeTransformsSkinMorphMaterialsTexturesAndAnimations() throws Exception {
        byte[] bin=buildSkinnedMorphedGltfBin();
        String binB64=java.util.Base64.getEncoder().encodeToString(bin);
        String pngB64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X2XkAAAAASUVORK5CYII=";
        String json="""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[
               {"name":"root","translation":[1,2,3],"children":[1]},
               {"name":"arm","rotation":[0,0,0,1],"scale":[2,2,2],"mesh":0,"skin":0}
             ],
             "skins":[{"inverseBindMatrices":4,"joints":[0,1]}],
             "meshes":[{"name":"skinned","weights":[0.5],"primitives":[{"attributes":{"POSITION":0,"JOINTS_0":1,"WEIGHTS_0":2},"targets":[{"POSITION":3}],"material":0}]}],
             "materials":[{"name":"mat0","alphaMode":"MASK","alphaCutoff":0.4,"doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0.8,0.6,0.4,1.0],"baseColorTexture":{"index":0}}}],
             "textures":[{"source":0}],"images":[{"mimeType":"image/png","uri":"data:image/png;base64,%s"}],
             "animations":[{"name":"wave","channels":[{"sampler":0,"target":{"node":1,"path":"rotation"}}],"samplers":[{"input":5,"interpolation":"LINEAR","output":6}]}],
             "buffers":[{"byteLength":%d,"uri":"data:application/octet-stream;base64,%s"}],
             "bufferViews":[
               {"buffer":0,"byteOffset":0,"byteLength":36},
               {"buffer":0,"byteOffset":36,"byteLength":12},
               {"buffer":0,"byteOffset":48,"byteLength":48},
               {"buffer":0,"byteOffset":96,"byteLength":36},
               {"buffer":0,"byteOffset":132,"byteLength":128},
               {"buffer":0,"byteOffset":260,"byteLength":8},
               {"buffer":0,"byteOffset":268,"byteLength":32}
             ],
             "accessors":[
               {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
               {"bufferView":1,"componentType":5121,"count":3,"type":"VEC4"},
               {"bufferView":2,"componentType":5126,"count":3,"type":"VEC4"},
               {"bufferView":3,"componentType":5126,"count":3,"type":"VEC3"},
               {"bufferView":4,"componentType":5126,"count":2,"type":"MAT4"},
               {"bufferView":5,"componentType":5126,"count":2,"type":"SCALAR"},
               {"bufferView":6,"componentType":5126,"count":2,"type":"VEC4"}
             ]}
            """.formatted(pngB64,bin.length,binB64);
        byte[] bytes=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var budget=new ResourceBudget(1<<20);var key=new ResourceKey("skinned.gltf");
        var req=new ImportRequest(new ModelSource("skinned",key,k->new java.io.ByteArrayInputStream(bytes)),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new GltfModelImporter().importModel(req)) {
            assertEquals(1,result.models().size());
            var asset=result.models().get(0).asset();
            var doc=asset.document();
            assertEquals("gltf2",doc.sourceFormat());
            assertEquals(2,doc.skeleton().boneCount());
            assertEquals("root",doc.skeleton().names().get(0));
            assertArrayEquals(new float[]{1,2,3},new float[]{
                    doc.bindPose().translations()[0],doc.bindPose().translations()[1],doc.bindPose().translations()[2]},1e-5f);
            assertEquals("arm",doc.skeleton().names().get(1));
            assertEquals(0,doc.skeleton().parent(1));
            assertArrayEquals(new float[]{2,2,2},new float[]{
                    doc.bindPose().scales()[3],doc.bindPose().scales()[4],doc.bindPose().scales()[5]},1e-5f);
            var rig=asset.skinningRig().orElseThrow();
            String meshName=doc.meshes().get(0).name();
            assertInstanceOf(MeshSkin.Weighted.class,rig.skin(meshName));
            var skin=(MeshSkin.Weighted)rig.skin(meshName);
            assertTrue(skin.hasInverseBindMatrices());
            assertEquals(32,skin.inverseBindMatrices().length);
            assertEquals(1,skin.morphTargets().size());
            assertEquals(0.5f,asset.defaultSkinState().morph(meshName,1).values()[0],1e-5f);
            assertTrue(doc.materials().size()>=2);
            var mat1=doc.materials().get(1);
            assertFalse(mat1.resources().isEmpty());
            try(var texStream=result.resources().open(mat1.resources().get(0))) {
                byte[] png=texStream.readAllBytes();
                assertTrue(png.length>0);
            }
            assertTrue(doc.metadata().values().containsKey("gltfMaterials"));
            assertTrue(doc.metadata().values().containsKey("gltfAnimations"));
        }
        assertEquals(0,budget.used());
    }
    private static byte[] buildSkinnedMorphedGltfBin() {
        var bin=ByteBuffer.allocate(300).order(ByteOrder.LITTLE_ENDIAN);
        // 0: POSITION (3*3 floats = 36 bytes)
        for(float v:new float[]{0,0,0, 1,0,0, 0,1,0})bin.putFloat(v);
        // 1: JOINTS_0 (3*4 ubytes = 12 bytes)
        bin.put(new byte[]{0,0,0,0, 1,0,0,0, 0,1,0,0});
        // 2: WEIGHTS_0 (3*4 floats = 48 bytes)
        for(float v:new float[]{1,0,0,0, 1,0,0,0, 0.5f,0.5f,0,0})bin.putFloat(v);
        // 3: morph target POSITION (3*3 floats = 36 bytes)
        for(float v:new float[]{0,1,0, 0,1,0, 0,1,0})bin.putFloat(v);
        // 4: inverseBindMatrices (2*16 floats = 128 bytes, identity)
        for(int m=0;m<2;m++)for(int c=0;c<4;c++)for(int r=0;r<4;r++)bin.putFloat(c==r?1f:0f);
        // 5: animation times (2 floats = 8 bytes)
        bin.putFloat(0f).putFloat(1f);
        // 6: animation rotations (2*4 floats = 32 bytes)
        for(float v:new float[]{0,0,0,1, 0,0.70710677f,0,0.70710677f})bin.putFloat(v);
        return bin.array();
    }
    @Test void rejectsTruncatedGlbChunkAndUnsafeEncodedUriWithoutResolverEscape() throws Exception {
        byte[] valid=glb(new int[]{0,1,2});byte[] truncated=java.util.Arrays.copyOf(valid,valid.length-1);
        var error=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(request(truncated,new ResourceBudget(1<<20))));assertEquals(ImportErrorCode.MALFORMED_INPUT,error.diagnostic().code());
        String json="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"uri\":\"%2e%2e/private.bin\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";var key=new ResourceKey("models/a.gltf");var touched=new java.util.concurrent.atomic.AtomicBoolean();
        var req=new ImportRequest(new ModelSource("escape",key,k->{if(k.equals(key))return new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));touched.set(true);return new java.io.ByteArrayInputStream(new byte[36]);}),FormatHint.GLTF,DecodePolicy.conservative(),new ResourceBudget(1<<20),new CancellationToken());
        var resolverFailure=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(req));assertFalse(touched.get());
    }

    @Test void appliesInputLimitAcrossJsonAndExternalBuffers() throws Exception {
        byte[] positions=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0)
                .putFloat(0).putFloat(1).putFloat(0).array();
        String json="{\"asset\":{\"version\":\"2.0\",\"extras\":{\"pad\":\""+"x".repeat(400)+"\"}},"
                +"\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                +"\"buffers\":[{\"uri\":\"buf.bin\",\"byteLength\":36}],"
                +"\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                +"\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";
        byte[] source=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var key=new ResourceKey("model.gltf");var policy=new DecodePolicy(source.length+8,1<<20,1024,1024,32);
        var budget=new ResourceBudget(1<<20);
        var req=new ImportRequest(new ModelSource("aggregate-input",key,k->new java.io.ByteArrayInputStream(k.equals(key)?source:positions)),
                FormatHint.GLTF,policy,budget,new CancellationToken());
        var failure=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(req));
        assertTrue(failure.getMessage().contains("aggregate glTF input limit"),failure.getMessage());
        assertEquals(0,budget.used());
    }

    @Test void computesMissingNormalsFromTriangleOrientation() throws Exception {
        byte[] positions=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0)
                .putFloat(0).putFloat(0).putFloat(1).array();
        String encoded=java.util.Base64.getEncoder().encodeToString(positions);
        String json="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],"
                +"\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,"+encoded+"\",\"byteLength\":36}],"
                +"\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],"
                +"\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";
        byte[] source=json.getBytes(java.nio.charset.StandardCharsets.UTF_8);var key=new ResourceKey("normal.gltf");var budget=new ResourceBudget(1<<20);
        var req=new ImportRequest(new ModelSource("normal",key,k->new java.io.ByteArrayInputStream(source)),FormatHint.GLTF,
                DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new GltfModelImporter().importModel(req)) {
            assertArrayEquals(new float[]{1,0,0,1,0,0,1,0,0},result.models().get(0).asset().document().meshes().get(0).vertices().normals(),1e-6f);
        }
        assertEquals(0,budget.used());
    }
}

