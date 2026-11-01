package dev.spm.core.api;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ModelAssetTest {
    @Test void immutableDocumentAndResourceOwnerHaveSeparateLifetimes() {
        var budget=new ResourceBudget(100);
        var reservation=budget.reserve(80);
        var document=new ModelDocument("asset","test",new Skeleton(List.of(),new int[0],new float[0]),List.of(),BonePose.identity(0),List.of(),List.of(),new ModelDocument.JsonObject(Map.of()));
        var asset=new ModelAsset(document,List.of(reservation));
        assertSame(document,asset.document());assertEquals(80,budget.used());
        asset.close();asset.close();assertEquals(0,budget.used());
        assertThrows(IllegalStateException.class,asset::document);
    }
}
