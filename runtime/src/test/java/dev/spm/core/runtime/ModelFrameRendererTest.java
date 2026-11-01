package dev.spm.core.runtime;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelFrameRendererTest {
    @Test void hidesTrianglesAndRemapsIndicesUvAndMaterialsTogether() {
        var skeleton=new Skeleton(List.of("hidden","visible"),new int[]{-1,-1},new float[6]);
        float[] positions={0,0,0,1,0,0,0,1,0, 2,0,0,3,0,0,2,1,0};
        float[] normals={0,0,1,0,0,1,0,0,1, 0,0,1,0,0,1,0,0,1};
        float[] uv={0,0,1,0,0,1, .2f,0,.3f,0,.2f,1};
        var vertices=new VertexBatch(positions,normals,new int[]{0,0,0,1,1,1});
        var mesh=new Mesh("mesh",vertices,uv,new int[]{0,1,2,3,4,5},List.of(),new Bounds(0,0,0,3,1,0),0);
        var bind=BonePose.identity(2);
        var document=new ModelDocument("own-test","synthetic",skeleton,List.of(mesh),bind,List.of(new Material("skin",1,1,List.of())),List.of(),new JsonObject(Map.of()));
        var pose=new BonePose(bind.rotations(),bind.translations(),bind.scales(),new boolean[]{true,false},bind.childrenHidden(),bind.glow());
        var budget=new ResourceBudget(1<<20);
        try(var frame=ModelFrameRenderer.render(document,pose,budget,new CancellationToken())) {
            assertEquals("own-test",frame.identity());assertEquals(1,frame.meshes().size());
            var output=frame.meshes().get(0);
            assertEquals(3,output.vertices().vertexCount());
            assertArrayEquals(new int[]{0,1,2},output.indices());
            assertArrayEquals(new float[]{.2f,0,.3f,0,.2f,1},output.uvs());
            assertArrayEquals(new float[]{2,0,0,3,0,0,2,1,0},output.vertices().positions());
            assertTrue(budget.used()>0);
        }
        assertEquals(0,budget.used());
        var cancelled=new CancellationToken();cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->ModelFrameRenderer.render(document,pose,budget,cancelled));
        assertEquals(0,budget.used());
    }
}
