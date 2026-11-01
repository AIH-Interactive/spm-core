package dev.spm.core.verification;

import dev.spm.core.api.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentImmutabilityTest {
    @Test void constructorsAndAccessorsDoNotAliasCallerGeometryOrNestedMetadata() {
        float[] positions={1,2,3},normals={0,1,0},uv={.2f,.3f};int[] bones={0};
        var vertices=new VertexBatch(positions,normals,bones);
        var mesh=new ModelDocument.Mesh("m",vertices,uv,new int[0],List.of(),new ModelDocument.Bounds(1,2,3,1,2,3),0);
        var children=new ArrayList<ModelDocument.JsonValue>(List.of(new ModelDocument.JsonString("kept")));
        var source=new LinkedHashMap<String,ModelDocument.JsonValue>();source.put("nested",new ModelDocument.JsonArray(children));
        var metadata=new ModelDocument.JsonObject(source);
        var meshes=new ArrayList<>(List.of(mesh));
        var model=new ModelDocument("owned","test",new Skeleton(List.of("root"),new int[]{-1},new float[3]),meshes,
                BonePose.identity(1),List.of(new ModelDocument.Material("mat",1,1,List.of())),List.of(),metadata);
        positions[0]=99;normals[1]=99;bones[0]=99;uv[0]=99;source.clear();children.clear();meshes.clear();
        model.meshes().get(0).vertices().positions()[0]=88;model.meshes().get(0).uvs()[0]=88;
        assertArrayEquals(new float[]{1,2,3},model.meshes().get(0).vertices().positions());
        assertArrayEquals(new float[]{0,1,0},model.meshes().get(0).vertices().normals());
        assertArrayEquals(new int[]{0},model.meshes().get(0).vertices().boneIndices());
        assertArrayEquals(new float[]{.2f,.3f},model.meshes().get(0).uvs());
        assertEquals(List.of(new ModelDocument.JsonString("kept")),((ModelDocument.JsonArray)model.metadata().values().get("nested")).values());
        assertThrows(UnsupportedOperationException.class,()->model.meshes().clear());
        assertThrows(UnsupportedOperationException.class,()->model.metadata().values().clear());
    }
}
