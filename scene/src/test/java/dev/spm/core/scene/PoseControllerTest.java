package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.api.Skeleton;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoseControllerTest {
    static ScriptBudget budget() { return new ScriptBudget(1000000,128,new CancellationToken()); }
    static JsonObject obj(Object... pairs) {
        Map<String,JsonValue> map=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],json(pairs[i+1]));
        return new JsonObject(map);
    }
    static JsonValue json(Object value) {
        if(value instanceof JsonValue json) return json;
        if(value instanceof String string) return new JsonString(string);
        if(value instanceof Boolean bool) return new JsonBoolean(bool);
        if(value instanceof Number number) return new JsonNumber(new BigDecimal(number.toString()));
        if(value instanceof List<?> list) return new JsonArray(list.stream().map(PoseControllerTest::json).toList());
        throw new IllegalArgumentException();
    }
    static PoseAnimationLibrary library(JsonObject clips) {
        return PoseAnimationLibrary.decode(obj("animations",clips),new Skeleton(List.of("root"),new int[]{-1},new float[3]),budget());
    }
    static PoseAnimationController controller(PoseAnimationLibrary library,JsonObject states) {
        var graph=PoseControllerDefinition.decode(obj("animation_controllers",obj("controller.test",obj("states",states))),
                "controller.test",library,budget());
        return new PoseAnimationController(library,graph,BonePose.identity(1),120,42);
    }
    static PoseFrame frame(PoseAnimationController controller,long id,long nanos,Map<String,Double> queries) {
        return controller.evaluate(id,nanos,new SceneInputs(queries),budget());
    }
    @Test void orderedTransitionsEntryExitAndTypedWeightedClips() {
        var library=library(obj("a",obj("loop","hold_on_last_frame","animation_length",1,"bones",obj("root",obj("position",List.of(16,0,0),"rotation",List.of(0,0,90),"scale",2))),
                "b",obj("loop",true,"animation_length",1,"bones",obj("root",obj("position",List.of(0,16,0),"scale",3)))));
        var controller=controller(library,obj("default",obj("animations",List.of("a"),"on_exit",List.of("v.exit=v.exit+1;"),
                        "transitions",List.of(obj("mixed","q.go"),obj("wrong",1))),
                "mixed",obj("on_entry",List.of("v.entry=v.entry+1;"),"animations",List.of(obj("a",0.5),obj("b",0.5))),
                "wrong",obj()));
        frame(controller,0,0,Map.of());
        var mixed=frame(controller,1,16666667,Map.of("go",1.0));
        assertEquals("mixed",controller.controllerSnapshot().state());
        assertArrayEquals(new float[]{-0.5f,0.5f,0},mixed.pose().translations(),1e-6f);
        assertEquals(Math.PI/4,mixed.pose().rotations()[2],1e-6);
        assertEquals(3,mixed.pose().scales()[0],1e-6); // (1+(2-1)/2)*(1+(3-1)/2)
        assertEquals(1,mixed.variables().get("exit")); assertEquals(1,mixed.variables().get("entry"));
        assertSame(mixed,frame(controller,1,99999999,Map.of()));
    }
    @Test void rejectedFrameRollsBackClockVariablesRandomEffectsAndState() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"bones",obj("root",obj("position",List.of("q.fail ? math.sqrt(-1) : math.random(0,16)",0,0))),
                "sound_effects",obj("0","test.sound"),"timeline",obj("0","v.starts=v.starts+1;"))));
        JsonObject states=obj("default",obj("transitions",List.of(obj("playing","q.go"))),
                "playing",obj("animations",List.of("a"),"on_entry",List.of("v.enter=v.enter+1;")));
        var rejected=controller(library,states); var clean=controller(library,states);
        frame(rejected,0,0,Map.of()); frame(clean,0,0,Map.of());
        assertThrows(MolangException.class,()->frame(rejected,1,16666667,Map.of("go",1.0,"fail",1.0)));
        assertEquals("default",rejected.controllerSnapshot().state());
        var retry=frame(rejected,1,16666667,Map.of("go",1.0));
        var expected=frame(clean,1,16666667,Map.of("go",1.0));
        assertArrayEquals(expected.pose().translations(),retry.pose().translations());
        assertEquals(expected.variables(),retry.variables()); assertEquals(1,retry.variables().get("enter"));
        assertEquals(1,retry.effects().size()); assertEquals(0,retry.effects().get(0).sequence());
        assertEquals(expected.step(),retry.step());
    }
    @Test void emptyCyclesAreBoundedAndSharedBudgetCannotBeMultiplied() {
        var library=library(obj("a",obj("bones",obj("root",obj("rotation",List.of(0,0,"math.sin(q.anim_time)"))))));
        var cyclic=controller(library,obj("default",obj("transitions",List.of(obj("other",1))),"other",obj("transitions",List.of(obj("default",1)))));
        assertEquals(MolangException.Reason.CONTROLLER_CYCLE,assertThrows(MolangException.class,()->frame(cyclic,0,0,Map.of())).reason());
        assertEquals("default",cyclic.controllerSnapshot().state());
        var active=controller(library,obj("default",obj("animations",List.of("a"))));
        ScriptBudget tiny=new ScriptBudget(12,128,new CancellationToken());
        assertThrows(MolangException.class,()->active.evaluate(0,0,new SceneInputs(Map.of()),tiny));
        assertEquals(0,tiny.remainingSteps());
        assertEquals(0,frame(active,0,0,Map.of()).step());
    }
    @Test void holdLoopAndFinishedQueriesDriveTypedTransitions() {
        var library=library(obj("hold",obj("animation_length",0.05,"loop","hold_on_last_frame","bones",obj("root",obj("position",List.of(16,0,0)))),
                "loop",obj("animation_length",0.05,"loop",true,"bones",obj("root",obj("position",obj("0",List.of(0,0,0),"0.05",List.of(0,16,0)))))));
        var controller=controller(library,obj("default",obj("animations",List.of("hold"),"transitions",List.of(obj("looping","q.all_animations_finished"))),
                "looping",obj("animations",List.of("loop"))));
        frame(controller,0,0,Map.of());
        var transition=frame(controller,1,50000000,Map.of());
        assertEquals("looping",controller.controllerSnapshot().state()); assertArrayEquals(new float[3],transition.pose().translations());
        var loop=frame(controller,2,116666667,Map.of()); assertEquals(1.0/3,loop.pose().translations()[1],1e-5);
    }
    @Test void historicalProcessorUsesUniformCatmullRomAndPreviousKeyFallback() {
        var library=PoseAnimationLibrary.decode(obj("animations",obj("a",obj("bones",obj("root",obj("position",obj("0",List.of(0,0,0),"1",obj("vector",List.of(0,16,0),"lerp_mode","catmullrom"),"4",List.of(0,0,0))))))),
                new Skeleton(List.of("root"),new int[]{-1},new float[3]),budget(),PoseAnimationLibrary.InterpolationProfile.HISTORICAL_YSM);
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        assertEquals(0.5625,library.sample("a",0.5,context,BonePose.identity(1)).translations()[1],1e-6);
        // BoneKeyFrameProcessor carries the previous marker when the destination is linear.
        assertEquals(0.5625,library.sample("a",2.5,context,BonePose.identity(1)).translations()[1],1e-6);
    }
    @Test void blendUsesFinalLocalQuaternionAndPreservesBindAndNonTrsChannels() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"bones",obj("root",obj("rotation",List.of(0,0,170),"position",List.of(16,0,0)))),
                "b",obj("animation_length",1,"loop",true,"bones",obj("root",obj("rotation",List.of(0,0,-170),"position",List.of(0,16,0))))));
        var definition=PoseControllerDefinition.decode(obj("animation_controllers",obj("c",obj("states",obj("default",obj("animations",List.of("a"),"transitions",List.of(obj("next","q.go"))),
                "next",obj("animations",List.of("b"),"blend_transition",0.1))))),"c",library,budget());
        BonePose bind=new BonePose(new float[]{0,0,0.1f},new float[]{2,3,4},new float[]{0,2,3},new boolean[]{true},new boolean[]{true},new int[]{7},new int[]{0x12345678});
        var controller=new PoseAnimationController(library,definition,bind,120);
        var initial=frame(controller,0,0,Map.of());
        var start=frame(controller,1,16666667,Map.of("go",1.0));
        assertArrayEquals(initial.pose().rotations(),start.pose().rotations());
        var middle=frame(controller,2,66666667,Map.of());
        assertEquals(0.5,controller.controllerSnapshot().blendProgress(),1e-8);
        assertEquals(Math.cos(Math.PI+0.1),Math.cos(middle.pose().rotations()[2]),1e-6);
        assertArrayEquals(new float[]{1.5f,3.5f,4},middle.pose().translations(),1e-6f);
        assertArrayEquals(bind.scales(),middle.pose().scales()); assertArrayEquals(bind.glow(),middle.pose().glow());
        assertArrayEquals(bind.colorsRgba(),middle.pose().colorsRgba()); assertArrayEquals(bind.ownHidden(),middle.pose().ownHidden());
    }
    @Test void failedPendingPlayerCommandAndTimelineHaveNoPartialPublication() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"bones",obj("root",obj("position",List.of("q.fail ? math.sqrt(-1) : v.started",0,0))),
                "timeline",obj("0","v.started=v.started+1;"),"sound_effects",obj("0","sound.a"))));
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0);
        player.play("a");
        assertThrows(MolangException.class,()->player.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),budget()));
        var retry=player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals("a",retry.animation()); assertEquals(1,retry.variables().get("started"));
        assertEquals(1,retry.effects().size()); assertEquals(0,retry.effects().get(0).sequence());
        assertEquals(-1f/16,retry.pose().translations()[0]);
    }
    @Test void soundEntryAndZeroTimeInstructionsAreOrderedAndFailuresAreTransactional() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"sound_effects",obj("0","clip.sound"),"timeline",obj("0","v.order=v.order*10+3;"))));
        var controller=controller(library,obj("default",obj("on_exit",List.of("v.order=1;"),"transitions",List.of(obj("empty","q.go"))),
                "empty",obj("on_entry",List.of("v.order=v.order*10+2;"),"transitions",List.of(obj("playing",1))),
                "playing",obj("animations",List.of("a"),"sound_effects",List.of(obj("effect","state.sound")))));
        frame(controller,0,0,Map.of()); var result=frame(controller,1,16666667,Map.of("go",1.0));
        assertEquals("playing",controller.controllerSnapshot().state()); assertEquals(123,result.variables().get("order"));
        assertEquals(List.of("state.sound","clip.sound"),result.effects().stream().map(EffectCommand::resource).toList());
    }
    @Test void unsupportedControllerFieldsAndBadReferencesFailBeforeConstruction() {
        var library=library(obj());
        assertEquals(MolangException.Reason.UNSUPPORTED,assertThrows(MolangException.class,()->controller(library,obj("default",obj("particle_effects",List.of())))).reason());
        assertThrows(IllegalArgumentException.class,()->controller(library,obj("default",obj("animations",List.of("missing")))));
        assertThrows(IllegalArgumentException.class,()->controller(library,obj("default",obj("transitions",List.of(obj("missing",1))))));
    }
    @Test void typedInitialEmptyStateTraversesImmediatelyAndAliasesResolveExplicitly() {
        var library=library(obj("full.clip",obj("animation_length",1,"loop",true,"bones",obj("root",obj("position",List.of(16,0,0))))));
        JsonObject states=obj("default",obj("transitions",List.of(obj("active",1))),"active",obj("animations",List.of("local")));
        var graph=PoseControllerDefinition.decode(obj("animation_controllers",obj("c",obj("states",states))),"c",library,Map.of("local","full.clip"),budget());
        var controller=new PoseAnimationController(library,graph,BonePose.identity(1),120);
        var initial=frame(controller,0,0,Map.of());
        assertEquals("active",controller.controllerSnapshot().state()); assertEquals(-1,initial.pose().translations()[0]);
    }
    @Test void lateBudgetFailureAfterScriptsAndEffectsCanRetryDeterministically() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"bones",obj("root",obj("position",List.of("math.random(0,16)",0,0))),
                "timeline",obj("0","v.timeline=v.timeline+1;"),"sound_effects",obj("0","clip.sound"))));
        JsonObject states=obj("default",obj("animations",List.of("a"),"on_entry",List.of("v.entry=math.random(0,100);"),"sound_effects",List.of("entry.sound")));
        var clean=controller(library,states); ScriptBudget measured=budget(); long before=measured.remainingSteps();
        var expected=clean.evaluate(0,0,new SceneInputs(Map.of()),measured); long consumed=before-measured.remainingSteps();
        var retried=controller(library,states); ScriptBudget insufficient=new ScriptBudget(consumed-1,128,new CancellationToken());
        assertEquals(MolangException.Reason.STEPS,assertThrows(MolangException.class,()->retried.evaluate(0,0,new SceneInputs(Map.of()),insufficient)).reason());
        var actual=frame(retried,0,0,Map.of());
        assertEquals(expected.variables(),actual.variables()); assertArrayEquals(expected.pose().translations(),actual.pose().translations());
        assertEquals(expected.effects().stream().map(EffectCommand::resource).toList(),actual.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(List.of(0L,1L),actual.effects().stream().map(EffectCommand::sequence).toList());
    }
    @Test void historicalInfinityDurationDoesNotInferAFiniteLastKey() {
        JsonObject source=obj("animations",obj("a",obj("animation_length","Infinity","loop",true,"bones",obj("root",obj("position",obj("0",List.of(0,0,0),"1",List.of(0,16,0)))))));
        Skeleton skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        var historical=PoseAnimationLibrary.decode(source,skeleton,budget(),PoseAnimationLibrary.InterpolationProfile.HISTORICAL_YSM);
        assertEquals(1,historical.sample("a",2,new MolangContext(Map.of(),Map.of(),Map.of(),budget()),BonePose.identity(1)).translations()[1]);
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(source,skeleton,budget()));
    }
}
