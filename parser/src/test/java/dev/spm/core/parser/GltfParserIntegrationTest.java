package dev.spm.core.parser;

import dev.spm.core.api.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GltfParserIntegrationTest {
    private static byte[] glb() throws Exception {
        var bin=ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putShort((short)0).putShort((short)1).putShort((short)2).array();
        String json="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"name\":\"tri\",\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}],\"buffers\":[{\"byteLength\":42}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}]}";
        byte[] j=json.getBytes(StandardCharsets.UTF_8);int jl=(j.length+3)&~3,bl=(bin.length+3)&~3,total=12+8+jl+8+bl;var out=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46546c67).putInt(2).putInt(total).putInt(jl).putInt(0x4e4f534a).put(j);while(out.position()<20+jl)out.put((byte)0x20);out.putInt(bl).putInt(0x004e4942).put(bin);while(out.hasRemaining())out.put((byte)0);return out.array();
    }
    @Test void defaultParserRecognizesGlbAndKeepsSourceAliveForResult() throws Exception {
        byte[] bytes=glb();var budget=new ResourceBudget(1<<20);var key=new ResourceKey("models/a.glb");
        var request=new ImportRequest(new ModelSource("gltf",key,k->new ByteArrayInputStream(bytes)),FormatHint.AUTO,DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new SpmModelParser().importModel(request)){assertEquals(1,result.models().size());assertEquals("tri",result.models().get(0).asset().document().meshes().get(0).name());assertTrue(budget.used()>0);}
        assertEquals(0,budget.used());
    }
    @Test void explicitGltfHintParsesJsonWithGlbEntryNameAsJson() throws Exception {
        String json="{\"asset\":{\"version\":\"2.0\"},\"meshes\":[{\"name\":\"plain\",\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}],\"buffers\":[{\"uri\":\"p.bin\",\"byteLength\":36}],\"bufferViews\":[{\"buffer\":0,\"byteLength\":36}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"}]}";
        byte[] binary=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).array();var key=new ResourceKey("models/a.glb");var budget=new ResourceBudget(1<<20);
        var request=new ImportRequest(new ModelSource("json",key,k->new ByteArrayInputStream(k.equals(key)?json.getBytes(StandardCharsets.UTF_8):binary)),FormatHint.GLTF,DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new SpmModelParser().importModel(request)){assertEquals("plain",result.models().get(0).asset().document().meshes().get(0).name());}
        assertEquals(0,budget.used());
    }
}


