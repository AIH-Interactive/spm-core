package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.bedrock.BedrockJsonImporter;
import dev.spm.core.render.cpu.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BedrockImportTest {
    private static final String PREFIX="""
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test","texture_width":64,"texture_height":32},"bones":
        """;
    private static String document(String bones) { return PREFIX+bones+"}]}"; }
    private static ModelDocument read(String json) throws IOException {
        return BedrockJsonImporter.importJson(json.getBytes(StandardCharsets.UTF_8),DecodePolicy.conservative(),new ResourceBudget(16L<<20),new CancellationToken());
    }
    @Test void cubeGeometryCoordinatesWindingAndBoxUvHaveHandCalculatedValues() throws Exception {
        var model=read(document("""
            [{"name":"root","pivot":[16,32,48],"cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]}]
            """));
        assertEquals("geometry.test",model.identity()); assertEquals("bedrock-json",model.sourceFormat());
        assertArrayEquals(new float[] {-1,2,3},model.skeleton().pivots());
        var mesh=model.meshes().get(0); assertEquals(24,mesh.vertices().vertexCount()); assertEquals(36,mesh.indices().length);
        assertArrayEquals(new float[] {-1,1,1,-1,1,0,-1,0,0,-1,0,1},java.util.Arrays.copyOf(mesh.vertices().positions(),12));
        assertArrayEquals(new float[] {-1,0,0,-1,0,0,-1,0,0,-1,0,0},java.util.Arrays.copyOf(mesh.vertices().normals(),12));
        assertArrayEquals(new float[] {.75f,.5f,.5f,.5f,.5f,1,.75f,1},java.util.Arrays.copyOf(mesh.uvs(),8));
        assertArrayEquals(new int[] {0,1,2,0,2,3},java.util.Arrays.copyOf(mesh.indices(),6));
        assertEquals(new ModelDocument.Bounds(-1,0,0,0,1,1),mesh.bounds());
        assertEquals("west",mesh.faces().get(0).direction());
    }
    @Test void importedInitialRotationFeedsTheActualScalarPipelineOnce() throws Exception {
        var model=read(document("""
            [{"name":"child","parent":"root","cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":{"east":{"uv":[0,0],"uv_size":[16,16]}}}]},
             {"name":"root","rotation":[0,0,90]}]
            """));
        assertEquals(1,model.skeleton().parent(0));
        var output=JavaCpuRenderer.transform(model.meshes().get(0).vertices(),BoneEvaluator.evaluate(model.skeleton(),model.bindPose()));
        assertArrayEquals(new float[] {-1,0,0,-1,0,1,0,0,1,0,0,0},output.positions(),0.000002f);
        assertEquals(4,output.vertexCount());
    }
    @Test void perFaceUvMirrorInflateAndCubeRotationAreImported() throws Exception {
        var model=read(document("""
            [{"name":"root","inflate":1,"mirror":true,"cubes":[{"origin":[0,0,0],"size":[16,16,16],"rotation":[0,0,90],"pivot":[0,0,0],"mirror":true,"uv":{"west":{"uv":[8,4],"uv_size":[16,8]}}}]}]
            """));
        var mesh=model.meshes().get(0); assertEquals(4,mesh.vertices().vertexCount());
        assertArrayEquals(new float[] {-1.0625f,.0625f,-.0625f,-1.0625f,.0625f,1.0625f,.0625f,.0625f,1.0625f,.0625f,.0625f,-.0625f},mesh.vertices().positions(),0.000002f);
        assertArrayEquals(new float[] {.125f,.125f,.375f,.125f,.375f,.375f,.125f,.375f},mesh.uvs());
        assertArrayEquals(new float[] {0,1,0,0,1,0,0,1,0,0,1,0},mesh.vertices().normals(),0.000002f);
    }
    @Test void namedResourcesLocatorsFlagsAndAnimationsArePreservedImmutably() throws Exception {
        String json=document("""
            [{"name":"ysmGlowBone","debug":true,"cubes_hidden":true,"children_hidden":false,"locators":{"tip":{"offset":[16,32,48],"rotation":[90,0,0]}}}]
            """) ;
        json=json.substring(0,json.length()-1)+",\"textures\":{\"default\":\"textures/skin.png\"},\"animations\":{\"walk\":{\"loop\":true}}}";
        var model=read(json);
        assertTrue(model.bindPose().ownHidden()[0]); assertFalse(model.bindPose().childrenHidden()[0]); assertEquals(15,model.bindPose().glow()[0]);
        assertArrayEquals(new float[] {-1,2,3},model.locators().get(0).offset());
        assertEquals("tip",model.locators().get(0).name());
        assertEquals(new ResourceKey("textures/skin.png"),model.materials().get(0).resources().get(0));
        assertInstanceOf(ModelDocument.JsonObject.class,model.metadata().values().get("animations"));
        assertThrows(UnsupportedOperationException.class,()->model.metadata().values().clear());
        model.locators().get(0).offset()[0]=99; assertEquals(-1,model.locators().get(0).offset()[0]);
    }
    @Test void malformedGraphsFormatsDimensionsAndNumbersAreRejected() {
        for (String bones : new String[] {"[{\"name\":\"a\"},{\"name\":\"a\"}]","[{\"name\":\"a\",\"parent\":\"absent\"}]","[{\"name\":\"a\",\"parent\":\"b\"},{\"name\":\"b\",\"parent\":\"a\"}]","[{\"name\":\"a\",\"pivot\":[1e999,0,0]}]","[{\"name\":\"a\",\"rotation\":[0,0]}]"})
            assertThrows(IOException.class,()->read(document(bones)));
        assertThrows(IOException.class,()->read(document("[]").replace("1.12.0","wrong")));
        assertThrows(IOException.class,()->read(document("[]").replace("\"texture_width\":64","\"texture_width\":0")));
        assertThrows(IOException.class,()->read(document("[]").replace("\"format_version\":\"1.12.0\"","\"format_version\":\"1.12.0\",\"format_version\":\"1.12.0\"")));
        assertThrows(IOException.class,()->read(document("[]")+" true"));
        assertThrows(IOException.class,()->read(document("[]").replace("\"bones\":","\"bones\":NaN,\"ignored\":")));
    }
    @Test void cancellationInputDepthStringAndWorkingBudgetLimitsAreEnforcedAndReleased() throws Exception {
        byte[] bytes=document("[{\"name\":\"root\"}]").getBytes(StandardCharsets.UTF_8);
        var cancelled=new CancellationToken(); cancelled.cancel();
        assertThrows(CancellationException.class,()->BedrockJsonImporter.importJson(bytes,DecodePolicy.conservative(),new ResourceBudget(1<<20),cancelled));
        for (DecodePolicy policy : new DecodePolicy[] {new DecodePolicy(1,1<<20,1000,1000,32),new DecodePolicy(1<<20,1,1000,1000,32),new DecodePolicy(1<<20,1<<20,1,1000,32),new DecodePolicy(1<<20,1<<20,1000,2,32),new DecodePolicy(1<<20,1<<20,1000,1000,2)}) {
            var budget=new ResourceBudget(1<<20);
            assertThrows(IOException.class,()->BedrockJsonImporter.importJson(bytes,policy,budget,new CancellationToken()));
            assertEquals(0,budget.used());
        }
        var tiny=new ResourceBudget(1);
        assertThrows(IOException.class,()->BedrockJsonImporter.importJson(bytes,DecodePolicy.conservative(),tiny,new CancellationToken()));
        assertEquals(0,tiny.used());
        var budget=new ResourceBudget(1<<20);
        BedrockJsonImporter.importJson(bytes,DecodePolicy.conservative(),budget,new CancellationToken()); assertEquals(0,budget.used());
        assertThrows(IOException.class,()->BedrockJsonImporter.importJson(new byte[] {(byte)0xc0,(byte)0xaf},DecodePolicy.conservative(),budget,new CancellationToken()));
    }
    @Test void importNeverSilentlyChoosesAmongGeometriesOrAuthorizesResourceTraversal() {
        String two="""
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"a"},"bones":[]},{"description":{"identifier":"b"},"bones":[]}]}
            """;
        assertThrows(IOException.class,()->read(two));
        String traversal=document("[]");
        traversal=traversal.substring(0,traversal.length()-1)+",\"textures\":{\"default\":\"../skin.png\"}}";
        String rejected=traversal;
        assertThrows(IOException.class,()->read(rejected));
        assertThrows(IOException.class,()->read(document("[]").replace("64","1.5")));
    }
    @Test void strictJsonRejectsControlsNonstandardLiteralsAndUnpairedSurrogates() {
        String[] invalid={document("[{\"name\":\"line\nfeed\"}]"),document("[{\"name\":\"a\",\"debug\":TRUE}]"),
                document("[{\"name\":\"a\\uD800\"}]"),document("[{\"name\":\"a\\'b\"}]")};
        for (String json : invalid) assertThrows(IOException.class,()->read(json));
    }
}
