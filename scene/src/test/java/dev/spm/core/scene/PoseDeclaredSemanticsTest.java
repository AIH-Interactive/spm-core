package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.ModelDocument.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.spm.core.scene.PoseControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** The explicitly declared Core contract is independent of fields dropped by historical YSM. */
class PoseDeclaredSemanticsTest {
    static PoseFrame at(PoseAnimationPlayer player,long step) {
        return player.evaluate(step,(step*1_000_000_000L+59)/60,new SceneInputs(Map.of()),budget());
    }
    static JsonObject delayedClip() {
        return obj("animation_length",0.025,"loop",true,
                "start_delay","v.starts=v.starts+1; return 0.025;",
                "loop_delay","v.loops=v.loops+1; return 0.025;",
                "bones",obj("root",obj("position",obj("0",List.of(0,0,0),"0.025",List.of(0,16,0)))),
                "sound_effects",obj("0","begin","0.0125","middle","0.025","end"),
                "timeline",obj("0","v.zeros=v.zeros+1;","0.0125","v.middles=v.middles+1;","0.025","v.ends=v.ends+1;"));
    }
    @Test void fractionalDelayedLoopsHoldTheFinalPoseAndEmitBoundaryEventsExactlyOnce() {
        var player=new PoseAnimationPlayer(library(obj("a",delayedClip())),BonePose.identity(1),120,0);
        player.play("a"); List<String> sounds=new ArrayList<>();
        var first=at(player,0); assertEquals(1,first.variables().get("starts")); assertTrue(first.effects().isEmpty());
        assertArrayEquals(new float[3],at(player,1).pose().translations());
        var active=at(player,2); sounds.addAll(active.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(1.0/3,active.pose().translations()[1],1e-6);
        var end=at(player,3); sounds.addAll(end.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(1,end.pose().translations()[1]); assertEquals(1,end.variables().get("loops"));
        var waiting=at(player,4); assertEquals(1,waiting.pose().translations()[1]); assertTrue(waiting.effects().isEmpty());
        var next=at(player,5); sounds.addAll(next.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(1.0/3,next.pose().translations()[1],1e-6);
        var secondEnd=at(player,6); sounds.addAll(secondEnd.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(List.of("begin","middle","end","begin","middle","end"),sounds);
        assertEquals(1,secondEnd.variables().get("starts")); assertEquals(2,secondEnd.variables().get("loops"));
        assertEquals(2,secondEnd.variables().get("zeros")); assertEquals(2,secondEnd.variables().get("ends"));
        assertSame(secondEnd,at(player,6));
        player.play("a"); assertEquals(1,at(player,7).variables().get("starts"));
        player.restart("a",0); var restarted=at(player,8);
        assertEquals(2,restarted.variables().get("starts")); assertTrue(restarted.effects().isEmpty());
    }
    @Test void delayedOncePlayerSamplesItsTerminalKeyBeforeResetWhenBoundaryFallsBetweenTicks() {
        var library=library(obj("a",obj("start_delay",0.01,"animation_length",0.02,
                "bones",obj("root",obj("position",obj("0",List.of(0,0,0),"0.02",List.of(0,16,0)))),
                "sound_effects",obj("0.02","end"))));
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0); player.play("a");
        at(player,0); at(player,1); var finalKey=at(player,2);
        assertTrue(finalKey.finished()); assertEquals(0.02,finalKey.animationTimeSeconds());
        assertEquals(1,finalKey.pose().translations()[1]);
        assertEquals(List.of("end"),finalKey.effects().stream().map(EffectCommand::resource).toList());
        var reset=at(player,3); assertEquals(0,reset.pose().translations()[1]); assertTrue(reset.effects().isEmpty());
    }
    @Test void controllerUsesPerBindingDelayStateAndFinishedIncludesTheStartDelay() {
        var library=library(obj("a",obj("animation_length",0.025,"start_delay",0.025,
                "loop_delay","v.never=v.never+1; return 1;","bones",obj("root",obj("position",List.of(16,0,0))),
                "sound_effects",obj("0","begin","0.025","end"),"timeline",obj("0.025","v.finished=q.animation_finished;"))));
        var controller=controller(library,obj("default",obj("animations",List.of("a"),"transitions",List.of(obj("done","q.all_animations_finished"))),"done",obj()));
        assertTrue(frame(controller,0,0,Map.of()).effects().isEmpty());
        assertEquals("default",controller.controllerSnapshot().state());
        var active=frame(controller,1,33333334,Map.of());
        assertEquals("default",controller.controllerSnapshot().state()); assertEquals(-1,active.pose().translations()[0]);
        assertEquals(List.of("begin"),active.effects().stream().map(EffectCommand::resource).toList());
        var done=frame(controller,2,66666667,Map.of());
        assertEquals("done",controller.controllerSnapshot().state()); assertFalse(done.variables().containsKey("never"));
        assertEquals(List.of("end"),done.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(1,done.variables().get("finished"));
    }
    @Test void controllerDelayFailureRestoresItsBindingScheduleAndEndEffects() {
        var library=library(obj("a",obj("animation_length",0.025,"loop",true,"start_delay",0.025,
                "loop_delay","v.ends=v.ends+1; v.random=math.random(0,1); return q.fail ? math.sqrt(-1) : 0.025;",
                "sound_effects",obj("0","begin","0.025","end"))));
        var states=obj("default",obj("animations",List.of("a")));
        var clean=controller(library,states); var rejected=controller(library,states);
        frame(clean,0,0,Map.of()); frame(rejected,0,0,Map.of());
        assertThrows(MolangException.class,()->frame(rejected,1,50000000,Map.of("fail",1.0)));
        var expected=frame(clean,1,50000000,Map.of()); var retry=frame(rejected,1,50000000,Map.of());
        assertEquals(expected.variables(),retry.variables()); assertEquals(expected.step(),retry.step());
        assertEquals(List.of("begin","end"),retry.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(List.of(0L,1L),retry.effects().stream().map(EffectCommand::sequence).toList());
    }
    @Test void duplicateClipBindingsOwnIndependentDelaysAndDoNotShareSideEffects() {
        var library=library(obj("a",obj("animation_length",0.05,"start_delay","v.delays=v.delays+1; return v.delays / 60;",
                "sound_effects",obj("0","begin"))));
        var controller=controller(library,obj("default",obj("animations",List.of("a","a"))));
        var initial=frame(controller,0,0,Map.of()); assertEquals(2,initial.variables().get("delays"));
        assertTrue(initial.effects().isEmpty());
        assertEquals(1,frame(controller,1,16666667,Map.of()).effects().size());
        assertEquals(1,frame(controller,2,33333334,Map.of()).effects().size());
        assertTrue(frame(controller,3,50000000,Map.of()).effects().isEmpty());
    }
    @Test void disabledControllerBindingKeepsItsDelayScheduleWithoutReplayingMutedEffects() {
        var library=library(obj("a",delayedClip()));
        var controller=controller(library,obj("default",obj("animations",List.of(obj("a","q.enabled")))));
        frame(controller,0,0,Map.of());
        var muted=frame(controller,1,50000000,Map.of());
        assertTrue(muted.effects().isEmpty()); assertEquals(1,muted.variables().get("loops"));
        var enabled=frame(controller,2,83333334,Map.of("enabled",1.0));
        assertEquals(List.of("begin"),enabled.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(1,enabled.variables().get("zeros")); assertFalse(enabled.variables().containsKey("ends"));
        assertEquals(1.0/3,enabled.pose().translations()[1],1e-6);
    }
    @Test void statelessDelayedSamplesReconstructScheduleWithoutExecutingTimelineCommands() {
        var library=library(obj("a",delayedClip()));
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        assertEquals(0,library.sample("a",0.02,context,BonePose.identity(1)).translations()[1]);
        assertEquals(1,library.sample("a",0.06,context,BonePose.identity(1)).translations()[1]);
        assertEquals(0.5,library.sample("a",0.0875,context,BonePose.identity(1)).translations()[1],1e-6);
        assertFalse(context.variables().containsKey("zeros")); assertFalse(context.variables().containsKey("ends"));
        assertEquals(3,context.variables().get("starts")); assertEquals(2,context.variables().get("loops"));
    }
    @Test void rejectedDelayEvaluationRollsBackClockCommandVariablesRandomAndEffects() {
        var library=library(obj("a",obj("animation_length",0.025,"loop",true,
                "start_delay","v.starts=v.starts+1; return math.random(0.02,0.025);",
                "loop_delay","v.loops=v.loops+1; v.random=math.random(0,1); return q.fail ? math.sqrt(-1) : 0.025;",
                "sound_effects",obj("0","begin","0.025","end"),"timeline",obj("0.025","v.ends=v.ends+1;"))));
        var clean=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,42);
        var rejected=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,42);
        clean.play("a"); rejected.play("a"); at(clean,0); at(rejected,0);
        assertThrows(MolangException.class,()->rejected.evaluate(1,50000000,new SceneInputs(Map.of("fail",1.0)),budget()));
        var expected=clean.evaluate(1,50000000,new SceneInputs(Map.of()),budget());
        var retry=rejected.evaluate(1,50000000,new SceneInputs(Map.of()),budget());
        assertEquals(expected.variables(),retry.variables()); assertEquals(expected.step(),retry.step());
        assertEquals(List.of("begin","end"),retry.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(List.of(0L,1L),retry.effects().stream().map(EffectCommand::sequence).toList());
        assertEquals(1,retry.variables().get("starts")); assertEquals(1,retry.variables().get("loops"));
    }
    @Test void delayBoundsCancellationAndLoopWorkShareTheCallersBudget() {
        for(double invalid:new double[]{-0.1,1_000_001}) {
            var library=library(obj("a",obj("start_delay",invalid)));
            var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0); player.play("a");
            assertThrows(MolangException.class,()->at(player,0));
        }
        var library=library(obj("a",obj("animation_length",0.000001,"loop",true,"loop_delay",0)));
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0); player.play("a"); at(player,0);
        assertEquals(MolangException.Reason.STEPS,assertThrows(MolangException.class,
                ()->player.evaluate(1,16666667,new SceneInputs(Map.of()),new ScriptBudget(32,128,new CancellationToken()))).reason());
        var token=new CancellationToken(); token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                ()->player.evaluate(1,16666667,new SceneInputs(Map.of()),new ScriptBudget(1000000,128,token)));
    }
    @Test void overrideResetsOnlyAuthoredChannelsAndFalseKeepsEarlierLayers() {
        for(boolean override:new boolean[]{true,false}) {
            var library=library(obj("base",obj("loop",true,"bones",obj("root",obj("position",List.of(16,0,0),"rotation",List.of(0,0,80),"scale",2))),
                    "upper",obj("loop",true,"override_previous_animation",override,"blend_weight",0.5,
                            "bones",obj("root",obj("position",List.of(0,16,0),"scale",3)))));
            var controller=controller(library,obj("default",obj("animations",List.of("base","upper"))));
            var pose=frame(controller,0,0,Map.of()).pose();
            assertArrayEquals(new float[]{override?0:-1,0.5f,0},pose.translations(),1e-6f);
            assertEquals(Math.toRadians(80),pose.rotations()[2],1e-6);
            assertEquals(override?2:4,pose.scales()[0]);
        }
    }
    @Test void explicitShortestFlagUsesOutgoingStateAndEulerAxisArcs() {
        for(boolean shortest:new boolean[]{false,true}) {
            var library=library(obj("a",obj("loop",true,"bones",obj("root",obj("rotation",List.of(30,40,170)))),
                    "b",obj("loop",true,"bones",obj("root",obj("rotation",List.of(-30,-40,-170))))));
            var controller=controller(library,obj("default",obj("animations",List.of("a"),"blend_transition",0.1,"blend_via_shortest_path",shortest,
                            "transitions",List.of(obj("next","q.go"))),"next",obj("animations",List.of("b"))));
            frame(controller,0,0,Map.of()); frame(controller,1,16666667,Map.of("go",1.0));
            var middle=frame(controller,2,66666667,Map.of());
            assertEquals(0.5,controller.controllerSnapshot().blendProgress(),1e-8);
            assertEquals(0,middle.pose().rotations()[0],1e-6); assertEquals(0,middle.pose().rotations()[1],1e-6);
            assertEquals(shortest?Math.PI:0,middle.pose().rotations()[2],1e-6);
        }
    }
}
