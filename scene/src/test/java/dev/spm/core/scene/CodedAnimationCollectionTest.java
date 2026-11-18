package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.spm.core.scene.PoseControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CodedAnimationCollectionTest {
    @Test void collectionResetStartsANewGenerationAndRetiresOldEffects() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"sound_effects",obj("0","start.sound"))));
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(controller("main","ctrl.set_animation('a'); return 2;","a")),60,42);
        var old=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());var cursor=new EffectCursor();
        assertEquals(1,cursor.claim(old,owner.generation()).size());owner.reset();
        assertTrue(cursor.claim(old,owner.generation()).isEmpty());
        var fresh=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(old.instanceId(),fresh.instanceId());assertEquals(old.generation()+1,fresh.generation());
        assertEquals(1,cursor.claim(fresh,owner.generation()).size());assertTrue(cursor.claim(fresh).isEmpty());
    }
    @Test void playOnceEmitsItsFinalCueOnceWhileHoldRetainsTheLastPose() {
        var library=library(obj("short",obj("loop",false,"animation_length",.05,"bones",obj("root",obj("position",List.of(0,16,0))),"sound_effects",obj("0.05","final.sound"))));
        var once=new CodedAnimationCollection(library,BonePose.identity(1),List.of(controller("main","ctrl.set_animation('short',11); return 2;","short")),60,42);
        assertTrue(once.evaluate(0,0,new SceneInputs(Map.of()),budget()).effects().isEmpty());
        var end=once.evaluate(1,50_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(1,end.effects().size());assertEquals("final.sound",end.effects().get(0).resource());
        assertTrue(once.evaluate(2,66_666_667,new SceneInputs(Map.of()),budget()).effects().isEmpty());
        var hold=new CodedAnimationCollection(library,BonePose.identity(1),List.of(controller("main","ctrl.set_animation('short',12); return 2;","short")),60,42);
        hold.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var held=hold.evaluate(1,500_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(.05,held.controllers().get(0).animationTimeSeconds(),1e-8);
        assertTrue(held.controllers().get(0).contributing());assertEquals(1,held.pose().translations()[1]);
    }
    @Test void failingDeferredStopRollsBackAndRetriesTheCommittedQueueExactlyOnce() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"timeline",obj("0","ysm.defer('local',1)"))));
        var hooks=new MolangHooks(List.of(),List.of(),List.of(),List.of(),List.of(MolangProgram.compile("v.drained=(v.drained??0)+args[0]; q.fail?math.sqrt(-1):0")),List.of());
        var definition=controller("main","q.stop?0:ctrl.set_animation('a'); return q.stop?3:2;","a");
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(definition),60,42,Map.of(),hooks);
        var initial=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertThrows(MolangException.class,()->owner.evaluate(1,16_666_667,new SceneInputs(Map.of("stop",1.0,"fail",1.0)),budget()));
        assertSame(initial,owner.evaluate(0,0,new SceneInputs(Map.of()),budget()));assertFalse(owner.typedVariables().containsKey("drained"));
        var retried=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("stop",1.0)),budget());assertEquals(1,retried.variables().get("drained"));
        assertEquals(1,owner.evaluate(2,33_333_334,new SceneInputs(Map.of("stop",1.0)),budget()).variables().get("drained"));
    }
    @Test void deferredCapacityIsSharedByAllMembersOfOneCandidate() {
        String source="loop(q.calls,ysm.defer('local',1)); ctrl.set_animation('a'); return 2;";
        var owner=owner(controller("first",source,"a"),controller("second",source,"a"));
        var initial=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var generous=new ScriptBudget(1_000_000,128,new dev.spm.core.api.CancellationToken());
        var failure=assertThrows(MolangException.class,()->owner.evaluate(1,16_666_667,new SceneInputs(Map.of("calls",3000.0)),generous));
        assertEquals(MolangException.Reason.CAPACITY,failure.reason());
        assertSame(initial,owner.evaluate(0,0,new SceneInputs(Map.of()),budget()));
        assertDoesNotThrow(()->owner.evaluate(1,16_666_667,new SceneInputs(Map.of("calls",1500.0)),new ScriptBudget(1_000_000,128,new dev.spm.core.api.CancellationToken())));
        var retainedFailure=assertThrows(MolangException.class,()->owner.evaluate(2,33_333_334,new SceneInputs(Map.of("calls",1000.0)),new ScriptBudget(1_000_000,128,new dev.spm.core.api.CancellationToken())));
        assertEquals(MolangException.Reason.CAPACITY,retainedFailure.reason());
        assertDoesNotThrow(()->owner.evaluate(2,33_333_334,new SceneInputs(Map.of()),budget()));
    }
    private static PoseAnimationLibrary clips() {
        return library(obj("a",obj("loop",true,"animation_length",1,"bones",obj("root",obj("position",List.of(0,16,0)))),
                "b",obj("loop",true,"animation_length",1,"bones",obj("root",obj("position",List.of(0,32,0))))));
    }
    private static CodedFallbackPlan fallback(String animation) {
        return new CodedFallbackPlan(List.of(new CodedFallbackPlan.Branch(null,
                List.of(new CodedAction.SelectAnimation(animation,CodedLoopMode.CLIP_DEFAULT)),CodedPlayDecision.CONTINUE)));
    }
    private static CodedControllerDefinition controller(String name,String script,String fallback) {
        return new CodedControllerDefinition(name,MolangProgram.compile(script),fallback(fallback),0);
    }
    private static CodedAnimationCollection owner(CodedControllerDefinition... controllers) {
        return new CodedAnimationCollection(clips(),BonePose.identity(1),List.of(controllers),60,42);
    }
    @Test void initialPredicateSelectsAndSamplesTheSameUnpublishedCandidate() {
        var owner=owner(controller("main","ctrl.set_animation('a'); return ctrl.state_continue;","b"));
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(1,frame.pose().translations()[1]);
        assertEquals("a",frame.controllers().get(0).selectedClip());
        assertTrue(frame.effects().isEmpty());
        assertSame(frame,owner.evaluate(0,999_999_999,new SceneInputs(Map.of()),budget()));
        assertThrows(UnsupportedOperationException.class,()->frame.controllers().clear());
    }
    @Test void nestedTypedFunctionsKeepArgumentsAndReturnValuesInsideTheCandidate() {
        var functions=Map.of("outer",MolangProgram.compile("return fn.inner(args[0])+1;"),
                "inner",MolangProgram.compile("return args[0]+1;"),"name",MolangProgram.compile("return args[0];"));
        var definition=controller("main","v.result=fn.outer(3); v.clip=fn.name('a'); ctrl.set_animation(v.clip); return 2;","b");
        var owner=new CodedAnimationCollection(clips(),BonePose.identity(1),List.of(definition),60,42,functions,MolangHooks.empty());
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(5,frame.variables().get("result"));
        assertEquals(MolangValue.text("a"),owner.typedVariables().get("clip"));
        assertEquals("a",frame.controllers().get(0).selectedClip());
        assertEquals(1,frame.pose().translations()[1]);
        assertFalse(frame.variables().containsKey("arg0"));
    }
    @Test void missingOptionalFunctionDoesNotEvaluateItsArguments() {
        var owner=owner(controller("main","v.result=fn.absent(v.unwanted=1); ctrl.set_animation('a'); return 2;","b"));
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertFalse(frame.variables().containsKey("unwanted"));
        assertFalse(frame.variables().containsKey("result"));
        assertEquals("a",frame.controllers().get(0).selectedClip());
    }
    @Test void bypassRunsFrozenFallbackAfterScriptMutationsAndZeroStepDoesNotRepeat() {
        var owner=owner(controller("main","v.calls=(v.calls??0)+1; ctrl.set_animation('a'); return ctrl.state_bypass;","b"));
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(2,frame.pose().translations()[1]);assertEquals(1,frame.variables().get("calls"));
        var zero=owner.evaluate(1,1,new SceneInputs(Map.of()),budget());
        assertEquals(1,zero.variables().get("calls"));
        assertEquals(2,owner.evaluate(2,16_666_667,new SceneInputs(Map.of()),budget()).variables().get("calls"));
    }
    @Test void rejectedInitialCandidateDoesNotPublishSelectionOrVariables() {
        var owner=owner(controller("main","v.calls=(v.calls??0)+1; ctrl.set_animation(q.fail?'missing':'a'); q.fail ? math.sqrt(-1) : 0; return 2;","b"));
        assertThrows(MolangException.class,()->owner.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),budget()));
        assertTrue(owner.typedVariables().isEmpty());
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(1,frame.variables().get("calls"));assertEquals(1,frame.pose().translations()[1]);
    }
    @Test void inactiveControlAndEmptySelectionSkipArguments() {
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        assertEquals(MolangValue.missing(),MolangProgram.compile("ctrl.set_animation(v.unwanted=1)").evaluateValue(context));
        assertTrue(context.typedVariables().isEmpty());
        var owner=owner(controller("main","ctrl.set_animation('',v.unwanted=1); ctrl.set_animation('a'); return 2;","b"));
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertFalse(frame.variables().containsKey("unwanted"));assertEquals(1,frame.pose().translations()[1]);
    }
    @Test void entityVariablesAreSharedButAnimationContextsAreIndependent() {
        var owner=owner(controller("a","ctrl.set_animation('a'); v.shared=(v.shared??0)+1; c.local=(c.local??0)+1; v.a=c.local; return 2;","a"),
                controller("b","ctrl.set_animation('b'); v.seen=v.shared; c.local=(c.local??0)+10; v.b=c.local; return 2;","b"));
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(1,frame.variables().get("seen"));assertEquals(1,frame.variables().get("a"));assertEquals(10,frame.variables().get("b"));
        var next=owner.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget());
        assertEquals(1,next.variables().get("a"));assertEquals(10,next.variables().get("b"));
        var third=owner.evaluate(2,33_333_334,new SceneInputs(Map.of()),budget());assertEquals(2,third.variables().get("a"));assertEquals(20,third.variables().get("b"));
    }
    @Test void scopedHostRequestsAndEffectsPreserveOriginalArgumentsAndRollbackAllMembers() {
        var owner=owner(controller("a","v.count=(v.count??0)+1; ysm.bone_glow('Case',1); ysm.play_sound(0,'same'); return 2;","a"),
                controller("b","ysm.play_sound(0,'same'); return 2;","b"));
        var args=List.<MolangValue>of(MolangValue.number(0),MolangValue.text("same"));
        var sampled=new java.util.HashMap<MolangBindings.Call,MolangValue>();
        for(String scope:List.of("a","b")) {
            var failure=assertThrows(MolangException.class,()->owner.evaluate(0,0,new SceneInputs(Map.of(),new MolangBindings(Map.of(),sampled)),budget()));
            var request=failure.bindingRequest().orElseThrow();assertEquals(scope,request.controllerScope());assertEquals(args,request.arguments());
            sampled.put(request,MolangValue.bool(true));assertTrue(owner.typedVariables().isEmpty());
        }
        var frame=owner.evaluate(0,0,new SceneInputs(Map.of(),new MolangBindings(Map.of(),sampled)),budget());
        assertEquals(1,frame.variables().get("count"));
        assertEquals(List.of("a","a","b"),frame.effects().stream().map(EffectCommand::controllerScope).toList());
        var cursor=new EffectCursor();assertEquals(3,cursor.claim(frame,owner.generation()).size());assertTrue(cursor.claim(frame).isEmpty());
    }
    @Test void sameSelectionIsIdempotentReloadOnlyWaitsForNextSelectionAndLoopOverridesApply() {
        var owner=owner(controller("main","q.reload ? ctrl.indicate_reload() : 0; q.select ? ctrl.set_animation('a',q.mode) : 0; return 2;","b"));
        owner.evaluate(0,0,new SceneInputs(Map.of("select",1.0,"mode",12.0)),budget());
        var first=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("select",1.0,"mode",12.0)),budget());
        assertEquals(1.0/60,first.controllers().get(0).animationTimeSeconds(),1e-9);
        var reload=owner.evaluate(2,33_333_334,new SceneInputs(Map.of("reload",1.0)),budget());
        assertEquals("a",reload.controllers().get(0).selectedClip());assertEquals(2.0/60,reload.controllers().get(0).animationTimeSeconds(),1e-9);
        var restart=owner.evaluate(3,50_000_000,new SceneInputs(Map.of("select",1.0,"mode",12.0)),budget());
        assertEquals(0,restart.controllers().get(0).animationTimeSeconds());
    }
    @Test void pauseAdvancesWithoutContributionAndStopFadesBeforeIdle() {
        var owner=owner(controller("main","q.stop?0:ctrl.set_animation('a'); return q.pause?4:q.stop?3:2;","b"));
        owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var paused=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("pause",1.0)),budget());
        assertFalse(paused.controllers().get(0).contributing());assertEquals(8.0/9,paused.pose().translations()[1],1e-6);assertEquals(1.0/60,paused.controllers().get(0).animationTimeSeconds(),1e-9);
        var resumed=owner.evaluate(2,33_333_334,new SceneInputs(Map.of()),budget());assertEquals(1,resumed.pose().translations()[1]);
        var stop=owner.evaluate(3,50_000_000,new SceneInputs(Map.of("stop",1.0)),budget());assertEquals(1,stop.pose().translations()[1]);
        var ending=owner.evaluate(4,66_666_667,new SceneInputs(Map.of("stop",1.0)),budget());
        assertTrue(ending.pose().translations()[1]>0&&ending.pose().translations()[1]<1);
    }
    @Test void beginningTransitionUsesTwentyTickUnitsAndScaleIsImmediate() {
        var definition=new CodedControllerDefinition("main",MolangProgram.compile("ctrl.set_animation('a'); return 2;"),fallback("b"),1);
        var owner=owner(definition);
        assertEquals(0,owner.evaluate(0,0,new SceneInputs(Map.of()),budget()).pose().translations()[1]);
        assertEquals(1.0/3,owner.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).pose().translations()[1],1e-6);
        assertEquals(1,owner.evaluate(2,50_000_000,new SceneInputs(Map.of()),budget()).pose().translations()[1],1e-6);
    }
    @Test void timelineInstructionsAndDeferredContextFlushOnlyAtPlaybackBoundary() {
        var library=library(obj("a",obj("loop",true,"animation_length",0.05,"bones",obj("root",obj("position",List.of(0,16,0))),
                "timeline",obj("0","ysm.defer('ignored',1); c.value=7; v.starts=(v.starts??0)+1;"),"sound_effects",obj("0","test.sound"))));
        var hooks=new MolangHooks(List.of(),List.of(),List.of(),List.of(),List.of(MolangProgram.compile("v.drained=(v.drained??0)+args[0]; v.context=c.value")),List.of());
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(controller("main","ctrl.set_animation('a'); return 2;","a")),60,42,Map.of(),hooks);
        var first=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(1,first.variables().get("starts"));assertFalse(first.variables().containsKey("drained"));
        assertEquals("main",first.effects().get(0).controllerScope());
        assertEquals(1,owner.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).variables().get("starts"));
        var loop=owner.evaluate(2,66_666_667,new SceneInputs(Map.of()),budget());
        assertEquals(1,loop.variables().get("drained"));assertEquals(7,loop.variables().get("context"));assertEquals(2,loop.variables().get("starts"));
    }
    @Test void stopDrainsMemberDeferredCallsBeforeClearingItsContext() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"timeline",obj("0","c.marker=7; ysm.defer('ignored',c.marker)"))));
        var hooks=new MolangHooks(List.of(),List.of(),List.of(),List.of(),List.of(MolangProgram.compile("v.drained=args[0]; v.context=c.marker")),List.of());
        var definition=controller("main","q.stop?0:ctrl.set_animation('a'); return q.stop?3:2;","a");
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(definition),60,42,Map.of(),hooks);
        owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var stopped=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("stop",1.0)),budget());
        assertEquals(7,stopped.variables().get("drained"));
        assertEquals(7,stopped.variables().get("context"));
    }
    @Test void lateMemberFailureDiscardsTimelineEffectsAndSharedMeterIsNotRefunded() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"timeline",obj("0","v.start=(v.start??0)+1;"),"sound_effects",obj("0","test.sound"))));
        var definitions=List.of(controller("a","v.roll=math.random(0,100); ctrl.set_animation('a'); return 2;","a"),controller("b","q.fail ? math.sqrt(-1) : 0; return 2;","a"));
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),definitions,60,42);
        var meter=budget();long before=meter.remainingSteps();
        assertThrows(MolangException.class,()->owner.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),meter));
        assertTrue(owner.typedVariables().isEmpty());assertTrue(meter.remainingSteps()<before);
        var retry=owner.evaluate(0,0,new SceneInputs(Map.of()),meter);
        assertEquals(1,retry.variables().get("start"));assertEquals(1,retry.effects().size());assertEquals(0,retry.effects().get(0).sequence());
        var clean=new CodedAnimationCollection(library,BonePose.identity(1),definitions,60,42).evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(clean.variables(),retry.variables());assertEquals(clean.controllers(),retry.controllers());
        assertArrayEquals(clean.pose().rotations(),retry.pose().rotations());
        assertArrayEquals(clean.pose().translations(),retry.pose().translations());
        assertArrayEquals(clean.pose().scales(),retry.pose().scales());assertEquals(clean.effects().size(),retry.effects().size());
        for(int i=0;i<clean.effects().size();i++) {
            var a=clean.effects().get(i);var b=retry.effects().get(i);
            assertEquals(a.sequence(),b.sequence());assertEquals(a.step(),b.step());assertEquals(a.kind(),b.kind());
            assertEquals(a.resource(),b.resource());assertEquals(a.parameters(),b.parameters());assertEquals(a.arguments(),b.arguments());
            assertEquals(a.controllerScope(),b.controllerScope());
        }
    }
    @Test void codedMaskCompositionOverridesOnlyDrivenChannelsAndEndingRevealsLowerLayer() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"bones",obj("root",obj("position",List.of(0,16,0),"rotation",List.of(0,0,90),"scale",2))),
                "b",obj("loop",true,"animation_length",1,"bones",obj("root",obj("position",List.of(0,32,0),"scale",3)))));
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(controller("a","ctrl.set_animation('a'); return 2;","a"),
                controller("b","q.stop?0:ctrl.set_animation('b'); return q.stop?3:2;","b")),60,42);
        var initial=owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(2,initial.pose().translations()[1]);assertEquals(Math.PI/2,initial.pose().rotations()[2],1e-6);assertEquals(3,initial.pose().scales()[0]);
        owner.evaluate(1,16_666_667,new SceneInputs(Map.of("stop",1.0)),budget());
        var ending=owner.evaluate(2,33_333_334,new SceneInputs(Map.of("stop",1.0)),budget());
        assertEquals(2-1.0/9,ending.pose().translations()[1],1e-6);assertEquals(3-1.0/9,ending.pose().scales()[0],1e-6);
    }
    @Test void physicsKeysAreSharedWithinTheEntityLaneAndSeparateAcrossOwners() {
        var a=controller("a","v.a=ysm.first_order('same',1); return 2;","a");
        var b=controller("b","v.b=ysm.first_order('same',9); return 2;","b");
        var frame=owner(a,b).evaluate(0,0,new SceneInputs(Map.of()),budget());assertEquals(0,frame.variables().get("b")); // Existing Core filter returns its stored initial position on a repeated same-step key.
        var preview=owner(b).evaluate(0,0,new SceneInputs(Map.of()),budget());assertEquals(9,preview.variables().get("b"));
    }
    @Test void resettingLastDrivenControllerUsesModelChannelResetTransition() {
        var owner=owner(controller("main","q.reset?ctrl.reset():ctrl.set_animation('a'); return 2;","a"));
        owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var first=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("reset",1.0)),budget());
        assertEquals(8.0/9,first.pose().translations()[1],1e-6);
        assertEquals(7.0/9,owner.evaluate(2,33_333_334,new SceneInputs(Map.of("reset",1.0)),budget()).pose().translations()[1],1e-6);
    }
    @Test void negativeAndNonFiniteTransitionLengthsAreIgnoredWhilePreservingPriorValue() {
        var definition=new CodedControllerDefinition("main",MolangProgram.compile(
                "ctrl.set_beginning_transition_length(-5); ctrl.set_animation('a'); return 2;"),
                fallback("b"),1);
        var owner=owner(definition);
        assertEquals(0,owner.evaluate(0,0,new SceneInputs(Map.of()),budget()).pose().translations()[1]);
        // Preserves the 1-tick (1/20s = 3 steps at 60Hz) transition despite negative call.
        assertEquals(1.0/3,owner.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).pose().translations()[1],1e-6);
        var playback=new CodedPlayback(clips(),1,1);
        playback.transition(-5);
        playback.transition(Double.NaN);
        playback.transition(Double.POSITIVE_INFINITY);
        assertEquals(1.0,playback.transitionTicks);
    }
    @Test void codedPredicatesObserveAnimTimeFinishedQueriesAndBeforeStepHookArguments() {
        var library=library(obj("short",obj("loop",false,"animation_length",0.05,"bones",obj("root",obj("position",List.of(0,16,0))))));
        var hooks=new MolangHooks(List.of(),List.of(MolangProgram.compile("v.tickcalls=(v.tickcalls??0)+1; v.lastshouldtick=args[0];")),
                List.of(),List.of(),List.of(),List.of());
        var definition=controller("main","v.seentime=q.anim_time; v.allfin=q.all_animations_finished; v.anyfin=q.any_animation_finished; ctrl.set_animation('short',11); return 2;","short");
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(definition),60,42,Map.of(),hooks);
        var inputs=new SceneInputs(Map.of(),MolangBindings.empty(),true,SceneInputs.SyncMode.HOST,
                Map.of(MolangHooks.Phase.BEFORE_STEP,List.of(MolangValue.bool(true))));
        var f0=owner.evaluate(0,0,inputs,budget());
        assertEquals(1,f0.variables().get("tickcalls"));
        assertEquals(1,f0.variables().get("lastshouldtick"));
        assertEquals(1,f0.variables().get("allfin"));
        assertEquals(1,f0.variables().get("anyfin"));
        // Zero-step frame does not re-run BEFORE_STEP hooks.
        var zero=owner.evaluate(1,1,inputs,budget());
        assertEquals(1,zero.variables().get("tickcalls"));
        // Step 1 (1/60s < 0.05s): clip is actively running so finished queries are false; snapshot advances to 1/60s.
        var playing=owner.evaluate(2,16_666_667,inputs,budget());
        assertEquals(0,playing.variables().get("allfin"));
        assertEquals(0,playing.variables().get("anyfin"));
        assertEquals(1.0/60,playing.controllers().get(0).animationTimeSeconds(),1e-6);
        // Step 2 predicate sees the prior step's localTime (1/60s).
        var second=owner.evaluate(3,33_333_334,inputs,budget());
        assertEquals(1.0/60,second.variables().get("seentime"),1e-6);
        owner.evaluate(4,50_000_000,inputs,budget());
        var after=owner.evaluate(5,66_666_667,inputs,budget());
        assertEquals(1,after.variables().get("allfin"));
        assertEquals(1,after.variables().get("anyfin"));
        assertEquals(1.0/30,after.variables().get("seentime"),1e-6);
    }
    @Test void pauseSuppressesTimelineAndScriptEffectsAndDoesNotReplayOnResume() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,
                "timeline",obj("0.01","v.cuehit=(v.cuehit??0)+1;"),
                "sound_effects",obj("0.01","cue.sound"))));
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),
                List.of(controller("main","ctrl.set_animation('a'); return q.pause ? 4 : 2;","a")),60,42);
        owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var paused=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("pause",1.0)),budget());
        assertFalse(paused.controllers().get(0).contributing());
        // PAUSE suppresses timeline cues and instructions while advancing playback time past 0.01s.
        assertFalse(paused.variables().containsKey("cuehit"));
        assertTrue(paused.effects().stream().noneMatch(e->e.resource().equals("cue.sound")));
        var resumed=owner.evaluate(2,33_333_334,new SceneInputs(Map.of()),budget());
        assertTrue(resumed.controllers().get(0).contributing());
        assertFalse(resumed.variables().containsKey("cuehit"));
        assertTrue(resumed.effects().stream().noneMatch(e->e.resource().equals("cue.sound")));
    }
    @Test void ctrlResetDrainsMemberDeferredCallsBeforeClearingContext() {
        var library=library(obj("a",obj("loop",true,"animation_length",1,"timeline",obj("0","c.marker=9; ysm.defer('local',c.marker)"))));
        var hooks=new MolangHooks(List.of(),List.of(),List.of(),List.of(),List.of(MolangProgram.compile("v.drained=args[0]; v.contextatdrain=c.marker")),List.of());
        var definition=controller("main","q.reset ? ctrl.reset() : ctrl.set_animation('a'); return 2;","a");
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(definition),60,42,Map.of(),hooks);
        owner.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var afterReset=owner.evaluate(1,16_666_667,new SceneInputs(Map.of("reset",1.0)),budget());
        assertEquals(9,afterReset.variables().get("drained"));
        assertEquals(9,afterReset.variables().get("contextatdrain"));
        assertFalse(afterReset.controllers().get(0).contributing());
    }
    @Test void hostDrivenCommandsAdditiveLayerSpeedAndClipSummaryWork() {
        var doc=new dev.spm.core.api.ModelDocument("test","ysm-raw-v2",
                new dev.spm.core.api.Skeleton(List.of("root"),new int[]{-1},new float[]{0,0,0}),
                List.of(),BonePose.identity(1),List.of(),List.of(),
                obj("animations",obj(
                        "walk",obj("loop",true,"animation_length",1,"bones",obj("root",obj("rotation",obj("0",List.of(0,0,0),"1",List.of(0,90,0))))),
                        "wave",obj("loop",true,"animation_length",1,"particle_effects",obj("0.5",obj("effect","minecraft:heart")),"bones",obj("root",obj("rotation",obj("0",List.of(0,0,0),"1",List.of(10,0,0))))))));
        var library=PoseAnimationLibrary.fromModel(doc,budget());
        assertTrue(library.hasClip("walk"));
        var summary=library.clipSummary("wave");
        assertEquals(CodedLoopMode.LOOP,summary.loopMode());
        assertTrue(summary.rotationBones().contains("root"));
        assertTrue(summary.hasEvents());
        var main=CodedControllerDefinition.hostDriven("main",0);
        var parallel=CodedControllerDefinition.hostDriven("parallel",0,true);
        var owner=new CodedAnimationCollection(library,BonePose.identity(1),List.of(main,parallel),60,42);
        var cmds=Map.of(
                "main",CodedControllerCommand.play("walk",CodedLoopMode.LOOP,0,2.0),
                "parallel",CodedControllerCommand.play("wave",CodedLoopMode.LOOP,0,1.0));
        owner.evaluate(0,0,new SceneInputs(Map.of(),MolangBindings.empty(),true,SceneInputs.SyncMode.HOST,Map.of(),cmds),budget());
        var frame=owner.evaluate(1,500_000_000L,new SceneInputs(Map.of(),MolangBindings.empty(),true,SceneInputs.SyncMode.HOST,Map.of(),cmds),budget());
        assertTrue(frame.effects().stream().anyMatch(e->e.kind().equals("particle") && e.resource().equals("minecraft:heart")));
        assertTrue(Math.abs(frame.pose().rotations()[0])>0.01f);
        assertTrue(Math.abs(frame.pose().rotations()[1])>0.1f);
    }
    @Test void perlinNoiseEvaluatesDeterministicallyInMolang() {
        var p=MolangProgram.compile("ysm.perlin_noise(1.25, 2.5, 42)");
        var ctx=new MolangContext(Map.of(),Map.of(),Map.of(),budget());
        double first=p.evaluate(ctx);
        double second=p.evaluate(ctx);
        assertEquals(first,second,1e-12);
        assertTrue(Double.isFinite(first));
    }
}
