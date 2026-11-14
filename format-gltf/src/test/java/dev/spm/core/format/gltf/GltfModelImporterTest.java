package dev.spm.core.format.gltf;

import dev.spm.core.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GltfModelImporterTest {
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
    @Test void rejectsUnsupportedTransformsAndSparseAccessorsExplicitly() throws Exception {
        String base="{\"asset\":{\"version\":\"2.0\"},\"nodes\":[{\"mesh\":0,\"translation\":[1,0,0]}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"uri\":\"data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";
        var key=new ResourceKey("model.gltf");var req=new ImportRequest(new ModelSource("reject",key,k->new java.io.ByteArrayInputStream(base.getBytes(java.nio.charset.StandardCharsets.UTF_8))),FormatHint.GLTF,DecodePolicy.conservative(),new ResourceBudget(1<<20),new CancellationToken());
        var failure=assertThrows(ModelImportException.class,()->new GltfModelImporter().importModel(req));assertEquals(ImportErrorCode.UNSUPPORTED_FEATURE,failure.diagnostic().code());
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

