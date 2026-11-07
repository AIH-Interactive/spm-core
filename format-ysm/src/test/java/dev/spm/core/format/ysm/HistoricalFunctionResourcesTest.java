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
}
