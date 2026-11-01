package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.spm.core.scene.PoseControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic behavior cases, independently authored from the certified clip-weight call chain. */
class PoseClipWeightTest {
    @Test void fractionalTimeWeightScalesAuthoredOffsetsRelativeToTheBindPose() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"blend_weight","q.anim_time * 2",
                "bones",obj("root",obj("rotation",List.of(0,0,90),"position",List.of(16,0,0),"scale",3)))));
        BonePose bind=new BonePose(new float[]{0.1f,0.2f,0.3f},new float[]{2,3,4},new float[]{0,2,3},
                new boolean[]{true},new boolean[]{true},new int[]{7},new int[]{0x12345678});
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        BonePose result=library.sample("a",0.25,context,bind);
        assertArrayEquals(new float[]{2-0.5f,3,4},result.translations(),1e-6f);
        assertEquals(0.3+Math.PI/4,result.rotations()[2],1e-6);
        assertArrayEquals(new float[]{0,4,6},result.scales(),1e-6f);
        assertArrayEquals(bind.glow(),result.glow()); assertArrayEquals(bind.colorsRgba(),result.colorsRgba());
        assertArrayEquals(bind.ownHidden(),result.ownHidden()); assertArrayEquals(bind.childrenHidden(),result.childrenHidden());
        assertEquals(-1,library.sample("a",1.5,context,BonePose.identity(1)).translations()[0],1e-6);
    }

    @Test void controllerBindingAndClipWeightsComposeWithoutNormalizingLayers() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,"blend_weight",0.5,
                        "bones",obj("root",obj("position",List.of(16,0,0),"rotation",List.of(0,0,80),"scale",3))),
                "b",obj("animation_length",1,"loop",true,"blend_weight",2,
                        "bones",obj("root",obj("position",List.of(0,16,0),"scale",2)))));
        var controller=controller(library,obj("default",obj("animations",List.of(obj("a",0.5),obj("b",0.25)))));
        BonePose result=frame(controller,0,0,Map.of()).pose();
        assertArrayEquals(new float[]{-0.25f,0.5f,0},result.translations(),1e-6f);
        assertEquals(Math.toRadians(20),result.rotations()[2],1e-6);
        assertArrayEquals(new float[]{2.25f,2.25f,2.25f},result.scales(),1e-6f);
    }

    @Test void nonPositiveClipWeightDoesNotSuppressTimelineOrDuplicateBoundaryEvents() {
        var library=library(obj("a",obj("animation_length",0.05,"loop",true,"blend_weight","v.weights=v.weights+1; return -2;",
                "bones",obj("root",obj("position",List.of(16,0,0))),
                "sound_effects",obj("0","start","0.025","middle","0.05","end"),
                "timeline",obj("0","v.starts=v.starts+1;","0.025","v.middles=v.middles+1;","0.05","v.ends=v.ends+1;"))));
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0);
        player.play("a");
        var first=player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var next=player.evaluate(1,100000000,new SceneInputs(Map.of()),budget());
        assertEquals(List.of("start"),first.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(List.of("middle","end","start","middle","end","start"),next.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(3,next.variables().get("starts")); assertEquals(2,next.variables().get("middles")); assertEquals(2,next.variables().get("ends"));
        assertEquals(7,next.variables().get("weights"));
        assertArrayEquals(new float[3],next.pose().translations());
        assertSame(next,player.evaluate(1,200000000,new SceneInputs(Map.of()),budget()));
    }

    @Test void failedWeightRollsBackPendingCommandClockRandomVariablesAndEffects() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,
                "blend_weight","v.weight=v.weight+math.random(0,1); return q.fail ? math.sqrt(-1) : v.weight;",
                "bones",obj("root",obj("position",List.of(16,0,0))),
                "sound_effects",obj("0","start","0.025","middle"),"timeline",obj("0","v.starts=v.starts+1;"))));
        var rejected=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,42);
        var clean=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,42);
        rejected.play("a"); clean.play("a");
        assertEquals(MolangException.Reason.NON_FINITE,assertThrows(MolangException.class,
                ()->rejected.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),budget())).reason());
        assertEquivalent(clean.evaluate(0,0,new SceneInputs(Map.of()),budget()),rejected.evaluate(0,0,new SceneInputs(Map.of()),budget()));
        assertEquals(MolangException.Reason.NON_FINITE,assertThrows(MolangException.class,
                ()->rejected.evaluate(1,50000000,new SceneInputs(Map.of("fail",1.0)),budget())).reason());
        assertEquivalent(clean.evaluate(1,50000000,new SceneInputs(Map.of()),budget()),rejected.evaluate(1,50000000,new SceneInputs(Map.of()),budget()));
    }

    @Test void weightProgramsShareTheFrameBudgetAndFailedBudgetCanRetry() {
        var library=library(obj("a",obj("animation_length",1,"loop",true,
                "blend_weight","v.calls=v.calls+1; return math.random(0,1);",
                "bones",obj("root",obj("position",List.of(16,0,0))),"sound_effects",obj("0","start"))));
        var measured=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,19); measured.play("a");
        var allowance=budget(); long initial=allowance.remainingSteps();
        var expected=measured.evaluate(0,0,new SceneInputs(Map.of()),allowance);
        var rejected=new PoseAnimationPlayer(library,BonePose.identity(1),120,0,19); rejected.play("a");
        var shortBudget=new ScriptBudget(initial-allowance.remainingSteps()-1,128,new dev.spm.core.api.CancellationToken());
        assertEquals(MolangException.Reason.STEPS,assertThrows(MolangException.class,
                ()->rejected.evaluate(0,0,new SceneInputs(Map.of()),shortBudget)).reason());
        assertEquals(0,shortBudget.remainingSteps());
        assertEquivalent(expected,rejected.evaluate(0,0,new SceneInputs(Map.of()),budget()));
    }

    private static void assertEquivalent(PoseFrame expected,PoseFrame actual) {
        assertEquals(expected.animation(),actual.animation()); assertEquals(expected.step(),actual.step());
        assertEquals(expected.animationTimeSeconds(),actual.animationTimeSeconds());
        assertEquals(expected.variables(),actual.variables()); assertArrayEquals(expected.pose().translations(),actual.pose().translations());
        assertEquals(expected.effects().stream().map(EffectCommand::resource).toList(),actual.effects().stream().map(EffectCommand::resource).toList());
        assertEquals(expected.effects().stream().map(EffectCommand::sequence).toList(),actual.effects().stream().map(EffectCommand::sequence).toList());
    }
}
