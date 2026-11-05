package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeerFunctionDepthTest {
    @Test void sixtyFourFunctionCallsDoNotSpendEachOthersAstDepthAllowance() {
        var policy=new PeerScriptPolicy();
        var budget=policy.frame(1,new CancellationToken());
        var count=PeerScriptPolicy.compile("c.arg0>0 ? fn.count(c.arg0-1) : 1");
        var context=new MolangContext(Map.of(),Map.of(),Map.of("count",count),budget);
        assertEquals(1,PeerScriptPolicy.compile("fn.count(63)").evaluate(context));
        assertEquals(MolangException.Reason.DEPTH,assertThrows(MolangException.class,
                ()->PeerScriptPolicy.compile("fn.count(64)").evaluate(context)).reason());
        assertEquals(0,budget.activeDepth());
    }
}
