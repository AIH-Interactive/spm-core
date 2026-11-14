package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.runtime.*;
import dev.spm.core.scene.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, bounded-memory synthetic Core ownership/CPU stress; never substitutes for real corpus. */
@EnabledIfSystemProperty(named="spm.stability.seconds",matches="[1-9][0-9]*")
final class CoreStabilitySoakTest {
    @Test void repeatedWeightedMorphReplaceCancelAndBudgetFailureReleaseEveryOwner() throws Exception {
        int seconds=Integer.parseInt(System.getProperty("spm.stability.seconds"));
        if(seconds>86400)throw new IllegalArgumentException("Stability duration exceeds one day");
        var skeleton=new Skeleton(List.of("root","other"),new int[]{-1,-1},new float[6]);
        var vertices=new VertexBatch(new float[]{1,0,0,0,1,0,0,0,1},new float[]{0,1,0,0,1,0,0,1,0},new int[]{0,1,0});
        var mesh=new ModelDocument.Mesh("mesh",vertices,new float[6],new int[]{0,1,2},List.of(),new ModelDocument.Bounds(0,0,0,1,1,1),0);
        var document=new ModelDocument("soak-owned-fixture","synthetic",skeleton,List.of(mesh),BonePose.identity(2),List.of(new ModelDocument.Material("default",1,1,List.of())),List.of(),new ModelDocument.JsonObject(Map.of()));
        var weights=SkinWeights.of(2,new int[][]{{0,1},{0,1},{0,1}},new float[][]{{.5f,.5f},{.25f,.75f},{.75f,.25f}});
        var target=new MorphTarget("offset",new float[]{.1f,0,0,0,.2f,0,0,0,.3f});
        var rig=SkinningRig.of(document,Map.of("mesh",new MeshSkin.Weighted(weights,SkinningMethod.BDEF,List.of(target))));
        var pose=new BonePose(new float[]{0,0,0,0,0,.5f},new float[]{0,0,0,1,0,0},new float[]{1,1,1,1,1,1},new boolean[2],new boolean[2],BonePose.identity(2).glow());
        var state=SkinState.of(Map.of("mesh",new MorphWeights(new float[]{-.5f})));
        var budget=new ResourceBudget(1<<20);
        long start=System.nanoTime(),end=start+seconds*1_000_000_000L,nextSample=start;
        long rounds=0,peakHeap=0;long[] samples=new long[4096];int sampled=0;
        var memory=ManagementFactory.getMemoryMXBean();var trend=new StringBuilder();
        do {
            long roundStart=System.nanoTime();var released=new AtomicInteger();
            try(var registry=new ResourceRegistry<ModelAsset>()) {
                var asset=new ModelAsset(document,List.of(),released::incrementAndGet);
                registry.publish("model",asset,asset::close);
                try(var old=registry.acquire("model").orElseThrow()) {
                    var replacement=new ModelAsset(document,List.of(),released::incrementAndGet);
                    registry.publish("model",replacement,replacement::close);
                    assertEquals(0,released.get());
                    try(var frame=ModelFrameRenderer.render(old.value(),pose,rig,state,budget,new CancellationToken())) {
                        assertEquals("JAVA_SCALAR",frame.backend());
                        for(float value:frame.meshes().get(0).vertices().positions())assertTrue(Float.isFinite(value));
                    }
                    assertEquals(0,budget.used());
                    var cancelled=new CancellationToken();cancelled.cancel();
                    assertThrows(java.util.concurrent.CancellationException.class,()->ModelFrameRenderer.render(replacement,pose,rig,state,budget,cancelled));
                    var refused=new ResourceBudget(0);
                    assertThrows(ResourceBudgetExceededException.class,()->ModelFrameRenderer.render(replacement,pose,rig,state,refused,new CancellationToken()));
                    assertEquals(0,refused.used());
                }
                assertEquals(1,released.get());
            }
            assertEquals(2,released.get());assertEquals(0,budget.used());
            var program=MolangProgram.compile("v.x=math.sin(q.angle); return v.x;");
            assertTrue(Double.isFinite(program.evaluate(new MolangContext(Map.of("angle",(double)(rounds%360)),Map.of(),Map.of(),new ScriptBudget(1000,64,new CancellationToken())))));
            samples[(int)(rounds%samples.length)]=System.nanoTime()-roundStart;sampled=Math.min(samples.length,sampled+1);rounds++;
            long now=System.nanoTime();long heap=memory.getHeapMemoryUsage().getUsed();peakHeap=Math.max(peakHeap,heap);
            if(now>=nextSample) {
                if(trend.length()>0)trend.append(',');
                trend.append("{\"elapsed_ns\":").append(now-start).append(",\"heap_used\":").append(heap).append(",\"rounds\":").append(rounds).append('}');
                System.out.println("CORE_STABILITY rounds="+rounds+" heap="+heap+" elapsed_seconds="+(now-start)/1_000_000_000L);
                nextSample=now+60_000_000_000L;
            }
        } while(System.nanoTime()<end);
        long[] sorted=Arrays.copyOf(samples,sampled);Arrays.sort(sorted);
        long gcCount=0,gcMillis=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){gcCount+=Math.max(0,gc.getCollectionCount());gcMillis+=Math.max(0,gc.getCollectionTime());}
        String report="{\"schema\":1,\"scope\":\"synthetic weighted morph, replace/lease, cancel, budget refusal, Molang compile; no real corpus or native\",\"seconds\":"+seconds+",\"elapsed_ns\":"+(System.nanoTime()-start)+",\"rounds\":"+rounds+",\"last_window_samples\":"+sampled+",\"p50_ns\":"+sorted[(sampled-1)/2]+",\"p95_ns\":"+sorted[(sampled-1)*95/100]+",\"p99_ns\":"+sorted[(sampled-1)*99/100]+",\"peak_observed_heap\":"+peakHeap+",\"gc_count\":"+gcCount+",\"gc_millis\":"+gcMillis+",\"budget_peak\":"+budget.peakUsed()+",\"budget_final\":"+budget.used()+",\"heap_trend\":["+trend+"]}";
        Path output=Path.of("build/reports/stability/soak.json");Files.createDirectories(output.getParent());Files.writeString(output,report);
    }
}
