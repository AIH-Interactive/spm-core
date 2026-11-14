package dev.spm.core.runtime;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.render.cpu.JavaComputeProvider;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelFrameRendererTest {
    @Test void cancellationWhileAssetRetiresReturnsLeaseAndAllCredits() throws Exception {
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        var document=new ModelDocument("race","synthetic",skeleton,List.of(),BonePose.identity(1),List.of(),List.of(),new JsonObject(Map.of()));
        var budget=new ResourceBudget(1<<20);var released=new AtomicInteger();
        var asset=new ModelAsset(document,List.of(budget.reserve(256)),released::incrementAndGet);
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        var token=new CancellationToken();var scalar=new JavaComputeProvider();
        CpuComputeProvider provider=new CpuComputeProvider() {
            public String backend(){return "latch";}
            public BoneMatrices evaluateBones(Skeleton s,BonePose p){
                entered.countDown();
                try {if(!resume.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("worker timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
                return scalar.evaluateBones(s,p);
            }
            public VertexBatch renderVertices(VertexBatch v,BoneMatrices b){return scalar.renderVertices(v,b);}
        };
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=worker.submit(()->ModelFrameRenderer.render(asset,BonePose.identity(1),provider,budget,token));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            asset.close();token.cancel();assertEquals(0,released.get());resume.countDown();
            var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class,failure.getCause());
            assertEquals(0,budget.used());assertEquals(1,released.get());asset.close();assertEquals(1,released.get());
        } finally {resume.countDown();worker.shutdownNow();asset.close();}
    }
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
    @Test void rendererFailureReleasesWorkingReservationAndAssetLease() {
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        var vertices=new VertexBatch(new float[]{0,0,0},new float[]{0,0,1},new int[]{0});
        var mesh=new Mesh("mesh",vertices,new float[]{0,0},new int[0],List.of(),new Bounds(0,0,0,0,0,0),0);
        var document=new ModelDocument("failure","synthetic",skeleton,List.of(mesh),BonePose.identity(1),
                List.of(new Material("skin",1,1,List.of())),List.of(),new JsonObject(Map.of()));
        var budget=new ResourceBudget(1<<20);var retained=budget.reserve(256);var released=new AtomicInteger();
        var asset=new ModelAsset(document,List.of(retained),released::incrementAndGet);
        var java=new JavaComputeProvider();
        CpuComputeProvider failing=new CpuComputeProvider() {
            public String backend(){return "injected";}
            public BoneMatrices evaluateBones(Skeleton s,BonePose p){return java.evaluateBones(s,p);}
            public VertexBatch renderVertices(VertexBatch v,BoneMatrices b){throw new IllegalStateException("injected renderer failure");}
        };
        assertThrows(IllegalStateException.class,()->ModelFrameRenderer.render(asset,BonePose.identity(1),failing,budget,new CancellationToken()));
        assertEquals(256,budget.used(),"failure must release only the temporary frame reservation");
        assertEquals(0,released.get(),"failed rendering must return its lease while the asset remains open");
        asset.close();assertEquals(0,budget.used());assertEquals(1,released.get());
    }
}
