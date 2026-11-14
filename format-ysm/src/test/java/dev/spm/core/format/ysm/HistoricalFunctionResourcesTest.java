package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalSceneProjectionTest.*;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalFunctionResourcesTest {
    private static HistoricalMeshCollector geometryCollector() {
        return geometryCollector("");
    }
    private static HistoricalMeshCollector geometryCollector(String parent) {
        var mesh=new HistoricalMeshCollector();int bone=mesh.bone("root",parent,new float[3],new float[3],false);
        mesh.quad(bone,0,0,1,new float[]{0,0,0,1,0,0,1,1,0,0,1,0},new float[]{0,0,1,0,1,1,0,1});
        return mesh;
    }
    private static YsmHistoricalModel model(int version,Mapping functions) {
        Struct root=version<19?s("identity",t("id"),"textures",map(),"geos",map(),"animations",map(),"functions",functions)
                :s("info",s("hash",t("id")),"common",s("functions",functions),"player",no());
        return new YsmHistoricalModel(version,root,List.of());
    }
    @Test void preservesLegacyAndModernWrappedBodiesInOwnedLogicalResources() throws Exception {
        for(int version:new int[]{16,19}) {
            HistoricalValue body=version<19?t("return '雪';"):s("hash",t("hash"),"value",t("return '雪';"));
            var budget=new ResourceBudget(1<<20);
            try(var imported=HistoricalLowering.lower(model(version,map(new Entry(t("Echo@player_init"),body))),DecodePolicy.localModel(),budget,new CancellationToken())) {
                var key=new ResourceKey("functions/Echo@player_init.molang");
                assertEquals(Set.of(key),imported.resources());
                assertEquals("return '雪';",new String(imported.read(key),StandardCharsets.UTF_8));
                assertTrue(budget.used()>0);
            }
            assertEquals(0,budget.used());
        }
    }
    @Test void unsafeAndCollidingFunctionNamesFailWithoutLeakingReservations() {
        for(Mapping functions:new Mapping[]{map(new Entry(t("../outside"),t("1"))),
                map(new Entry(t("a"),t("1")),new Entry(t("A"),t("2")))}) {
            var budget=new ResourceBudget(1<<20);
            try(var source=model(16,functions)) {
                assertThrows(IOException.class,()->HistoricalLowering.lower(source,DecodePolicy.localModel(),budget,new CancellationToken()));
                assertEquals(0,budget.used());
            }
        }
    }

    @Test void failedLoweringLeavesHistoricalCollectorsAvailableForRetry() throws Exception {
        var animations=HistoricalSceneProjection.collector(19,DecodePolicy.localModel(),new CancellationToken());
        var animationSets=map(new Entry(u(1),yes(file(animation("walk")))));
        HistoricalSceneProjection.feed(animations,animationSets);
        var mesh=geometryCollector();
        var meshes=Map.of(1L,mesh);
        var image=s("width",u(1),"height",u(1),"bytes",new Binary(new byte[]{1,2,3,4}));
        var texture=s("uv",yes(image),"pbr",map());
        var root=s("info",s("hash",t("id")),"common",s("functions",map()),
                "player",yes(s("textures",map(new Entry(t("default"),yes(texture))),"geos",map(new Entry(u(1),yes(s("bones",seq())))),
                        "animations",animationSets,"controllers",map())));
        var source=new YsmHistoricalModel(19,root,
                animations,meshes,List.of());
        var cancelled=new CancellationToken();cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->YsmModelImporter.importPayload(source,
                DecodePolicy.localModel(),new ResourceBudget(1<<20),cancelled));
        assertSame(animations,source.animationCollector());
        assertSame(meshes,source.meshCollectors());

        var tooSmall=new ResourceBudget(0);
        assertThrows(IOException.class,()->YsmModelImporter.importPayload(source,DecodePolicy.localModel(),tooSmall,new CancellationToken()));
        assertSame(animations,source.animationCollector());
        assertSame(meshes,source.meshCollectors());
        assertEquals(0,tooSmall.used());

        var retryBudget=new ResourceBudget(1<<20);
        try(var result=YsmModelImporter.importPayload(source,DecodePolicy.localModel(),retryBudget,new CancellationToken())) {
            var document=result.variants().get(0).document().orElseThrow();
            var metadata=document.metadata();
            assertTrue(((ModelDocument.JsonObject)metadata.values().get("animations")).values().containsKey("walk"));
            assertEquals(4,document.meshes().get(0).vertices().vertexCount());
            assertArrayEquals(new float[]{0,0,0,1,0,0,1,1,0,0,1,0},document.meshes().get(0).vertices().positions());
        }
        assertEquals(0,retryBudget.used());

        var freshAnimations=HistoricalSceneProjection.collector(19,DecodePolicy.localModel(),new CancellationToken());
        HistoricalSceneProjection.feed(freshAnimations,animationSets);
        var freshMesh=geometryCollector();
        var fresh=new YsmHistoricalModel(19,root,freshAnimations,Map.of(1L,freshMesh),List.of());
        var freshBudget=new ResourceBudget(1<<20);
        var actualAnimations=HistoricalSceneProjection.collector(19,DecodePolicy.localModel(),new CancellationToken());
        HistoricalSceneProjection.feed(actualAnimations,animationSets);
        var actualSource=new YsmHistoricalModel(19,root,actualAnimations,Map.of(1L,geometryCollector()),List.of());
        var actualBudget=new ResourceBudget(1<<20);
        try(var expected=YsmModelImporter.importPayload(fresh,DecodePolicy.localModel(),freshBudget,new CancellationToken())) {
            var actual=YsmModelImporter.importPayload(actualSource,DecodePolicy.localModel(),actualBudget,new CancellationToken());
            try(actual) {
                var expectedDocument=expected.variants().get(0).document().orElseThrow();
                var actualDocument=actual.variants().get(0).document().orElseThrow();
                assertEquals(expectedDocument.metadata(),actualDocument.metadata());
                assertArrayEquals(expectedDocument.meshes().get(0).vertices().positions(),actualDocument.meshes().get(0).vertices().positions());
                assertArrayEquals(expectedDocument.meshes().get(0).uvs(),actualDocument.meshes().get(0).uvs());
                assertArrayEquals(expectedDocument.meshes().get(0).indices(),actualDocument.meshes().get(0).indices());
                assertEquals(expected.resources(),actual.resources());
                for(ResourceKey key:expected.resources())assertArrayEquals(expected.read(key),actual.read(key));
            }
        }
        assertEquals(0,freshBudget.used());
        assertEquals(0,actualBudget.used());
    }

    @Test void invalidLaterMeshKeepsBorrowedCollectorsAndReleasesPartialReservations() throws IOException {
        var animations=HistoricalSceneProjection.collector(19,DecodePolicy.localModel(),new CancellationToken());
        var animationSets=map(new Entry(u(1),yes(file(animation("walk")))));HistoricalSceneProjection.feed(animations,animationSets);
        var meshes=Map.of(1L,geometryCollector(),2L,geometryCollector("absent-parent"));
        var image=s("width",u(1),"height",u(1),"bytes",new Binary(new byte[]{1,2,3,4}));
        var root=s("info",s("hash",t("id")),"common",s("functions",map()),
                "player",yes(s("textures",map(new Entry(t("default"),yes(s("uv",yes(image),"pbr",map())))),
                        "geos",map(new Entry(u(1),yes(s("bones",seq()))),new Entry(u(2),yes(s("bones",seq())))),
                        "animations",animationSets,"controllers",map())));
        var source=new YsmHistoricalModel(19,root,animations,meshes,List.of());var budget=new ResourceBudget(1<<20);
        assertThrows(IOException.class,()->YsmModelImporter.importPayload(source,DecodePolicy.localModel(),budget,new CancellationToken()));
        assertNotNull(source.root());assertSame(animations,source.animationCollector());assertSame(meshes,source.meshCollectors());assertEquals(0,budget.used());
        source.close();source.close();assertEquals(0,budget.used());
    }
}
