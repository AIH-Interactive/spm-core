package dev.spm.core.runtime;

import dev.spm.core.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeGenerationLeaseTest {
    private static ModelAsset asset(ResourceBudget budget) {
        var document=new ModelDocument("same","test",new Skeleton(List.of(),new int[0],new float[0]),
                List.of(),BonePose.identity(0),List.of(),List.of(),new ModelDocument.JsonObject(Map.of()));
        return new ModelAsset(document,List.of(budget.reserve(80)));
    }
    @Test void retiringAssetKeepsItsCreditsUntilLastFrameCloses() {
        var budget=new ResourceBudget(1000);var old=asset(budget);
        var first=ModelFrameRenderer.render(old,BonePose.identity(0),budget,new CancellationToken());
        var second=ModelFrameRenderer.render(old,BonePose.identity(0),budget,new CancellationToken());
        old.close();old.close();assertEquals(80,budget.used());
        assertThrows(IllegalStateException.class,()->ModelFrameRenderer.render(old,BonePose.identity(0),budget,new CancellationToken()));
        var replacement=asset(budget);assertEquals(160,budget.used());
        assertTrue(first.meshes().isEmpty());
        first.close();first.close();assertEquals(160,budget.used());
        second.close();assertEquals(80,budget.used(),"old frame must not release replacement credits");
        assertThrows(IllegalStateException.class,second::meshes);
        replacement.close();assertEquals(0,budget.used());
    }
    @Test void cancelledFrameReturnsBorrowAndDoesNotLeakRetiredAsset() {
        var budget=new ResourceBudget(1000);var owned=asset(budget);var token=new CancellationToken();token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->ModelFrameRenderer.render(owned,BonePose.identity(0),budget,token));
        owned.close();assertEquals(0,budget.used());
    }
}
