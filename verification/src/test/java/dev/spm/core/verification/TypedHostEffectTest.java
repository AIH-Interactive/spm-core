package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedHostEffectTest {
    private static ScriptBudget budget() { return new ScriptBudget(100000,128,new CancellationToken()); }
    private static SceneInstance scene() {
        return new SceneInstance(new SceneDefinition(new AnimationClip("test",1,true,Map.of(),List.of()),
                MolangProgram.compile("ctrl.set_animation('OnTick',1); v.x=(v.x ?? 0)+1"),
                Map.of("x",MolangProgram.compile("q.fail ? math.sqrt(-1) : v.x")),Map.of()),60);
    }
    @Test void typedEffectsAreDataAndAreCommittedOnceWithTheFrame() {
        var scene=scene();
        scene.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var frame=scene.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget());
        assertEquals(1,frame.effects().size());
        assertEquals(List.of(MolangValue.text("OnTick"),MolangValue.number(1)),frame.effects().get(0).arguments());
        assertSame(frame,scene.evaluate(1,17_000_000,new SceneInputs(Map.of()),budget()));
        var cursor=new EffectCursor();
        assertEquals(1,cursor.claim(frame).size());
        assertTrue(cursor.claim(frame).isEmpty());
        assertThrows(MolangException.class,()->scene.evaluate(2,33_333_334,new SceneInputs(Map.of("fail",1.0)),budget()));
        var retry=scene.evaluate(2,33_333_334,new SceneInputs(Map.of()),budget());
        assertEquals(1,retry.effects().size());
        assertEquals(frame.effects().get(0).sequence()+1,retry.effects().get(0).sequence());
    }
    @Test void realDeferredHookRemainsInternalAndCanEmitTypedHostEffects() {
        var hooks=new MolangHooks(List.of(MolangProgram.compile("ysm.defer('event',7)")),List.of(),List.of(),List.of(),
                List.of(MolangProgram.compile("v.value=args[0]; ctrl.set_animation('hook',args[0])")),List.of());
        var owner=new SceneInstance(new SceneDefinition(new AnimationClip("test",1,true,Map.of(),List.of()),
                MolangProgram.compile("0"),Map.of(),Map.of(),hooks),60);
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(7,frame.variables().get("value"));
        assertEquals(1,frame.effects().size());assertEquals("ctrl.set_animation",frame.effects().get(0).kind());
        assertEquals(List.of(MolangValue.text("hook"),MolangValue.number(7)),frame.effects().get(0).arguments());
        assertTrue(owner.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).effects().isEmpty());
    }
    @Test void hostCanSuppressEmissionWithoutSuppressingSimulation() {
        var scene=scene();
        var input=new SceneInputs(Map.of(),MolangBindings.empty(),false);
        scene.evaluate(0,0,input,budget());
        var frame=scene.evaluate(1,16_666_667,input,budget());
        assertTrue(frame.effects().isEmpty());
        assertEquals(1,frame.variables().get("x"));
    }
    @Test void scriptAndTimelineEffectsKeepTickOrderDuringCatchUp() {
        var clip=new AnimationClip("test",1,true,Map.of(),List.of(new AnimationClip.Cue(0.025,"sound","test",Map.of())));
        var scene=new SceneInstance(new SceneDefinition(clip,MolangProgram.compile("ctrl.set_animation('OnTick')"),Map.of(),Map.of()),60);
        scene.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var effects=scene.evaluate(1,33_333_334,new SceneInputs(Map.of()),budget()).effects();
        assertEquals(List.of(1L,2L,2L),effects.stream().map(EffectCommand::step).toList());
        assertEquals(List.of(0L,1L,2L),effects.stream().map(EffectCommand::sequence).toList());
    }
    @Test void suppressedEffectsDoNotEvaluateArgumentsAndSoundReturnsFalse() {
        var context=new MolangContext(new SceneInputs(Map.of(),MolangBindings.empty(),false),Map.of(),Map.of(),budget());
        assertEquals(0,MolangProgram.compile("ctrl.set_animation('On',v.x=1); ysm.play_sound(0,'test') ?? 9").evaluate(context));
        assertFalse(context.typedVariables().containsKey("x"));
        assertEquals(MolangValue.missing(),MolangProgram.compile("ysm.defer()").evaluateValue(context));
        assertThrows(MolangException.class,()->MolangProgram.compile("ctrl.reset(1)"));
    }
    @Test void hostDependentEffectResultsRequireExplicitSampledResults() {
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        assertThrows(MolangException.class,()->MolangProgram.compile("ysm.play_sound(0,'test')").evaluate(context));
        var call=new MolangBindings.Call("ysm.play_sound",List.of(MolangValue.number(0),MolangValue.text("test")),0);
        var sampled=new MolangContext(new SceneInputs(Map.of(),new MolangBindings(Map.of(),Map.of(call,MolangValue.bool(true)))),Map.of(),Map.of(),budget());
        assertEquals(MolangValue.bool(true),MolangProgram.compile("ysm.play_sound(0,'test')").evaluateValue(sampled));
    }
    @Test void resetCreatesNewGenerationAndRetiresOldEffectBatches() {
        var scene=scene(); var inputs=new SceneInputs(Map.of()); var cursor=new EffectCursor();
        scene.evaluate(0,0,inputs,budget());
        var old=scene.evaluate(1,16_666_667,inputs,budget());
        cursor.claim(old);
        scene.reset();
        assertTrue(cursor.claim(old,scene.generation()).isEmpty());
        var initial=scene.evaluate(0,0,inputs,budget());
        var next=scene.evaluate(1,16_666_667,inputs,budget());
        assertEquals(old.generation()+1,initial.generation());
        assertEquals(MolangValue.number(1),scene.typedVariables().get("x"));
        assertEquals(1,cursor.claim(next).size());
        assertTrue(cursor.claim(old).isEmpty());
    }
}
