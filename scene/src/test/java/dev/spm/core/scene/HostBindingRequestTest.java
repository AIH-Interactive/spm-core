package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostBindingRequestTest {
    private ScriptBudget budget() { return new ScriptBudget(100000,128,new CancellationToken()); }
    @Test void missingHostCallCarriesImmutableExactRequestForAnExternalSampler() {
        var failure=assertThrows(MolangException.class,()->MolangBindings.empty().call("q.position_delta",List.of(MolangValue.number(1))));
        var request=failure.bindingRequest().orElseThrow();
        assertEquals("query.position_delta",request.name());
        assertEquals(-1,request.occurrence());
        assertEquals(List.of(MolangValue.number(1)),request.arguments());
        assertThrows(UnsupportedOperationException.class,()->request.arguments().clear());
        assertTrue(new MolangException(MolangException.Reason.STEPS,"exhausted").bindingRequest().isEmpty());
    }
    @Test void failedRequestDoesNotPublishVariablesEffectsOrRefundTheFrameMeter() {
        var scene=new SceneInstance(new SceneDefinition(new AnimationClip("test",1,true,Map.of(),List.of()),
                MolangProgram.compile("v.n=(v.n ?? 0)+1; ctrl.set_animation('F',v.n)"),
                Map.of("result",MolangProgram.compile("q.external(v.n)")),Map.of()),60);
        var firstBinding=new MolangBindings.Call("query.external",List.of(MolangValue.missing()));
        var input=new SceneInputs(Map.of(),new MolangBindings(Map.of(),Map.of(firstBinding,MolangValue.number(0))));
        scene.evaluate(0,0,input,budget());
        var meter=budget(); long before=meter.remainingSteps();
        var failure=assertThrows(MolangException.class,()->scene.evaluate(1,16_666_667,input,meter));
        assertTrue(scene.typedVariables().isEmpty());
        long afterFailure=meter.remainingSteps(); assertTrue(afterFailure<before);
        var enriched=new SceneInputs(Map.of(),new MolangBindings(Map.of(),Map.of(firstBinding,MolangValue.number(0),
                failure.bindingRequest().orElseThrow(),MolangValue.number(6))));
        var committed=scene.evaluate(1,16_666_667,enriched,meter);
        assertEquals(6,committed.channels().get("result"));
        assertEquals(1,committed.effects().size());
        assertEquals(1,committed.variables().get("n"));
        assertTrue(meter.remainingSteps()<afterFailure);
    }
    @Test void repeatedStatefulEffectCallsHaveDifferentRequestsAndSampledResults() {
        var arguments=List.<MolangValue>of(MolangValue.number(0),MolangValue.text("sound"));
        var first=new MolangBindings.Call("ysm.play_sound",arguments,0);
        var second=new MolangBindings.Call("ysm.play_sound",arguments,1);
        var context=new MolangContext(new SceneInputs(Map.of(),new MolangBindings(Map.of(),
                Map.of(first,MolangValue.bool(true),second,MolangValue.bool(false)))),Map.of(),Map.of(),budget());
        assertEquals(MolangValue.bool(false),MolangProgram.compile("ysm.play_sound(0,'sound'); ysm.play_sound(0,'sound')").evaluateValue(context));
        assertNotEquals(first,second);
        assertThrows(IllegalArgumentException.class,()->new MolangBindings.Call("q.x",List.of(),-2));
    }
}
