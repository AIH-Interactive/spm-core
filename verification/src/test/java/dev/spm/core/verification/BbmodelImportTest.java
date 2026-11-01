package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import dev.spm.core.render.cpu.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BbmodelImportTest {
    private static final String CUBE="""
        {"uuid":"cube","type":"cube","name":"Box","from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],
        "faces":{"east":{"uv":[0,0,16,16],"texture":0},"west":{"texture":null},"up":{"texture":null},"down":{"texture":null},"south":{"texture":null},"north":{"texture":null}}}
        """;
    private static String project(String version,String elements,String outliner,String extras) {
        return "{\"meta\":{\"format_version\":\""+version+"\",\"model_format\":\"free\"},\"name\":\"demo\",\"resolution\":{\"width\":32,\"height\":32},\"textures\":[{\"uuid\":\"tex\",\"relative_path\":\"textures/skin.png\",\"uv_width\":32,\"uv_height\":16}],\"elements\":["+elements+"],\"outliner\":"+outliner+extras+"}";
    }
    private static ModelDocument read(String source) throws IOException {
        return BbmodelJsonImporter.importJson(source.getBytes(StandardCharsets.UTF_8),DecodePolicy.conservative(),new ResourceBudget(32L<<20),new CancellationToken());
    }
    @Test void genericCoordinatesFaceWindingAndPerTextureUvAreDirectCoreUnits() throws Exception {
        var model=read(project("4.10",CUBE,"[\"cube\"]",""));
        assertEquals("bbmodel",model.sourceFormat()); assertEquals("demo",model.identity());
        var mesh=model.meshes().get(0);
        assertArrayEquals(new float[] {1,1,0,1,1,1,1,0,1,1,0,0},mesh.vertices().positions());
        assertArrayEquals(new float[] {1,0,0,1,0,0,1,0,0,1,0,0},mesh.vertices().normals());
        assertArrayEquals(new float[] {.5f,0,0,0,0,1,.5f,1},mesh.uvs());
        assertArrayEquals(new int[] {0,1,2,0,2,3},mesh.indices());
        assertEquals(new ResourceKey("textures/skin.png"),model.materials().get(mesh.materialIndex()).resources().get(0));
        assertEquals(new ModelDocument.Bounds(1,0,0,1,1,1),mesh.bounds());
    }
    @Test void oldAndV5OutlinersPreserveGroupParentsPivotsAndElementRotation() throws Exception {
        String cube=CUBE.replace("\"origin\":[0,0,0]","\"origin\":[0,0,0],\"rotation\":[0,0,90]");
        String old=project("4.10",cube,"[{\"uuid\":\"group\",\"name\":\"Body\",\"origin\":[16,0,0],\"rotation\":[0,0,90],\"children\":[\"cube\"]}]","");
        String modern=project("5.0",cube,"[{\"uuid\":\"group\",\"children\":[\"cube\"]}]",",\"groups\":[{\"uuid\":\"group\",\"name\":\"Body\",\"origin\":[16,0,0],\"rotation\":[0,0,90]}]");
        for (String source : new String[] {old,modern}) {
            var model=read(source); assertEquals(0,model.skeleton().parent(1));
            assertArrayEquals(new float[] {1,0,0,0,0,0},model.skeleton().pivots());
            var output=JavaCpuRenderer.transform(model.meshes().get(0).vertices(),BoneEvaluator.evaluate(model.skeleton(),model.bindPose()));
            assertArrayEquals(new float[] {0,-2,0,0,-2,1,0,-1,1,0,-1,0},output.positions(),0.000003f);
        }
    }
    @Test void explicitXyzAuthoringIsConvertedByMatricesRatherThanSwappedEulerComponents() throws Exception {
        String source=project("4.10",CUBE.replace("\"origin\":[0,0,0]","\"origin\":[0,0,0],\"rotation\":[90,90,0]"),"[\"cube\"]","");
        var defaultModel=read(source);
        var xyz=BbmodelJsonImporter.importJson(source.getBytes(StandardCharsets.UTF_8),DecodePolicy.conservative(),new ResourceBudget(32L<<20),new CancellationToken(),BbmodelJsonImporter.RotationOrder.XYZ);
        var axes=new VertexBatch(new float[] {1,0,0,0,1,0,0,0,1},new float[9],new int[] {0,0,0});
        assertArrayEquals(new float[] {0,0,-1,1,0,0,0,-1,0},JavaCpuRenderer.transform(axes,BoneEvaluator.evaluate(defaultModel.skeleton(),defaultModel.bindPose())).positions(),0.000003f);
        assertArrayEquals(new float[] {0,1,0,0,0,1,1,0,0},JavaCpuRenderer.transform(axes,BoneEvaluator.evaluate(xyz.skeleton(),xyz.bindPose())).positions(),0.000003f);
    }
    @Test void uvQuarterTurnsAndNullFacesPreserveDirectionalMapping() throws Exception {
        var model=read(project("4.10",CUBE.replace("\"uv\":[0,0,16,16]","\"uv\":[0,0,16,16],\"rotation\":90"),"[\"cube\"]",""));
        assertEquals(4,model.meshes().get(0).vertices().vertexCount());
        assertArrayEquals(new float[] {0,0,0,1,.5f,1,.5f,0},model.meshes().get(0).uvs());
    }
    @Test void boxUvMirrorAndInflateHaveLiteralAtlasAndGeometryValues() throws Exception {
        String cube="""
            {"uuid":"cube","type":"cube","from":[0,0,0],"to":[16,16,16],"inflate":1,"box_uv":true,"mirror_uv":true,"uv_offset":[0,0],"faces":{"east":{"texture":0},"west":{"texture":0},"up":{"texture":0},"down":{"texture":0},"south":{"texture":0},"north":{"texture":0}}}
            """;
        var mesh=read(project("4.10",cube,"[\"cube\"]","")).meshes().get(0);
        assertEquals(24,mesh.vertices().vertexCount());
        assertArrayEquals(new float[] {1.0625f,1.0625f,-.0625f,1.0625f,1.0625f,1.0625f,1.0625f,-.0625f,1.0625f,1.0625f,-.0625f,-.0625f},Arrays.copyOf(mesh.vertices().positions(),12));
        assertArrayEquals(new float[] {1,1,1.5f,1,1.5f,2,1,2},Arrays.copyOf(mesh.uvs(),8));
    }
    @Test void hiddenGroupsLocatorsAnimationsAndUnsafeAssetPathsRemainExplicitData() throws Exception {
        String locator="{\"uuid\":\"locator\",\"type\":\"locator\",\"name\":\"tip\",\"position\":[16,32,48]}";
        String source=project("4.10",CUBE+","+locator,"[{\"uuid\":\"group\",\"name\":\"Body\",\"visibility\":false,\"children\":[\"cube\",\"locator\"]}]",",\"animations\":[{\"name\":\"walk\",\"loop\":\"loop\"}]")
                .replace("textures/skin.png","../outside.png");
        var model=read(source);
        assertEquals(0,JavaCpuRenderer.transform(model.meshes().get(0).vertices(),BoneEvaluator.evaluate(model.skeleton(),model.bindPose())).vertexCount());
        assertEquals("tip",model.locators().get(0).name()); assertArrayEquals(new float[] {1,2,3},model.locators().get(0).offset());
        assertTrue(model.materials().get(1).resources().isEmpty());
        assertInstanceOf(ModelDocument.JsonArray.class,model.metadata().values().get("animations"));
        assertThrows(UnsupportedOperationException.class,()->model.metadata().values().clear());
    }
    @Test void unsupportedMeshesUnknownReferencesDuplicateUuidsAndUnplacedElementsFail() {
        for (String source : new String[] {
                project("4.10","{\"uuid\":\"cube\",\"type\":\"mesh\"}","[\"cube\"]",""),
                project("4.10",CUBE,"[\"absent\"]",""),project("4.10",CUBE,"[\"cube\",\"cube\"]",""),
                project("4.10",CUBE+","+CUBE,"[\"cube\"]",""),project("4.10",CUBE,"[]",""),
                project("99.0",CUBE,"[\"cube\"]",""),
                project("4.10",CUBE.replace("\"from\":[0,0,0]","\"from\":[1e999,0,0]"),"[\"cube\"]",""),
                project("4.10",CUBE.replace("\"type\":\"cube\"","\"type\":\"cube\",\"rescale\":true"),"[\"cube\"]","")})
            assertThrows(IOException.class,()->read(source));
    }
    @Test void reusedStrictReaderChecksBudgetDepthUtf8DuplicatesAndCancellation() throws Exception {
        byte[] input=project("4.10",CUBE,"[\"cube\"]","").getBytes(StandardCharsets.UTF_8);
        var token=new CancellationToken(); token.cancel();
        assertThrows(CancellationException.class,()->BbmodelJsonImporter.importJson(input,DecodePolicy.conservative(),new ResourceBudget(1<<20),token));
        var budget=new ResourceBudget(1);
        assertThrows(IOException.class,()->BbmodelJsonImporter.importJson(input,DecodePolicy.conservative(),budget,new CancellationToken())); assertEquals(0,budget.used());
        assertThrows(IOException.class,()->BbmodelJsonImporter.importJson(input,new DecodePolicy(1<<20,1<<20,10000,10000,2),new ResourceBudget(1<<20),new CancellationToken()));
        assertThrows(IOException.class,()->BbmodelJsonImporter.importJson(new byte[] {(byte)0xff},DecodePolicy.conservative(),new ResourceBudget(1<<20),new CancellationToken()));
        assertThrows(IOException.class,()->read(project("4.10",CUBE,"[\"cube\"]",",\"name\":\"duplicate\"")));
    }
    @Test void bedrockProjectUsesGlobalUvSizeRatherThanGenericPerTextureSize() throws Exception {
        String source=project("4.10",CUBE,"[\"cube\"]","").replace("\"model_format\":\"free\"","\"model_format\":\"bedrock\"");
        assertArrayEquals(new float[] {.5f,0,0,0,0,.5f,.5f,.5f},read(source).meshes().get(0).uvs());
    }
    @Test void xyzConversionHandlesThreeAxesAndGimbalLockWithLiteralMatrixColumns() throws Exception {
        float[][] authored={{30,45,60},{0,90,0},{90,0,90}};
        float[][] expected={{.3535534f,.9267767f,.1268265f,-.6123724f,.1268265f,.7803301f,.7071068f,-.3535534f,.6123724f},
                {0,0,-1,0,1,0,1,0,0},{0,0,1,-1,0,0,0,-1,0}};
        for (int i=0;i<authored.length;i++) {
            float[] degrees=authored[i];
            String cube=CUBE.replace("\"origin\":[0,0,0]","\"origin\":[0,0,0],\"rotation\":["+degrees[0]+","+degrees[1]+","+degrees[2]+"]");
            var model=BbmodelJsonImporter.importJson(project("4.10",cube,"[\"cube\"]","").getBytes(StandardCharsets.UTF_8),DecodePolicy.conservative(),new ResourceBudget(32L<<20),new CancellationToken(),BbmodelJsonImporter.RotationOrder.XYZ);
            var axes=new VertexBatch(new float[] {1,0,0,0,1,0,0,0,1},new float[9],new int[] {0,0,0});
            assertArrayEquals(expected[i],JavaCpuRenderer.transform(axes,BoneEvaluator.evaluate(model.skeleton(),model.bindPose())).positions(),0.000003f);
        }
    }
}
