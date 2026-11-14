package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Serial fixed60Hz coded collection. Every member participates in a single unpublished transaction. */
public final class CodedAnimationCollection {
    private final long instanceId=SceneIdentity.next();
    private final PoseAnimationLibrary library;
    private final BonePose bind;
    private final List<CodedControllerDefinition> definitions;
    private final int maximumCatchUp;
    private final long seed;
    private final Map<String,MolangProgram> functions;
    private final MolangHooks hooks;
    private FixedStepClock clock;
    private List<CodedPlayback> members;
    private CodedPoseMixer mixer;
    private MolangState scripts=new MolangState();
    private long random;
    private long generation;
    private long sequence;
    private CodedPoseFrame lastFrame;

    public CodedAnimationCollection(PoseAnimationLibrary library,BonePose bind,List<CodedControllerDefinition> definitions,int maximumCatchUpSteps,long seed) {
        this(library,bind,definitions,maximumCatchUpSteps,seed,Map.of(),MolangHooks.empty());
    }
    public CodedAnimationCollection(PoseAnimationLibrary library,BonePose bind,List<CodedControllerDefinition> definitions,int maximumCatchUpSteps,long seed,
                                    Map<String,MolangProgram> functions,MolangHooks hooks) {
        this.functions=Map.copyOf(functions);this.hooks=Objects.requireNonNull(hooks);
        this.library=Objects.requireNonNull(library);this.bind=Objects.requireNonNull(bind);this.definitions=List.copyOf(definitions);
        if(bind.boneCount()!=library.boneCount()||definitions.size()>4096)throw new IllegalArgumentException("Invalid coded collection shape");
        var names=new HashSet<String>();for(var definition:this.definitions)if(!names.add(definition.name()))throw new IllegalArgumentException("Duplicate coded controller name");
        maximumCatchUp=maximumCatchUpSteps;this.seed=seed;random=seed;clock=new FixedStepClock(maximumCatchUpSteps);members=initial();mixer=new CodedPoseMixer(bind);
    }
    private List<CodedPlayback> initial() {
        return definitions.stream().map(definition->new CodedPlayback(library,bind.boneCount(),definition.beginningTransitionTicks())).toList();
    }
    public synchronized long generation() {return generation;}
    public synchronized Map<String,MolangValue> typedVariables() {return scripts.variables;}
    public synchronized CodedPoseFrame evaluate(long frameId,long nowNanos,SceneInputs inputs,ScriptBudget budget) {
        if(frameId<0||lastFrame!=null&&frameId<lastFrame.frameId())throw new IllegalArgumentException("Frame id must increase");
        if(lastFrame!=null&&frameId==lastFrame.frameId())return lastFrame;
        Objects.requireNonNull(inputs);Objects.requireNonNull(budget).step();
        var nextClock=clock.copy();var advance=nextClock.advance(nowNanos);
        var nextMixer=mixer.copy();
        var nextMembers=members.stream().map(CodedPlayback::copy).toList();
        var context=new MolangContext(inputs,scripts,functions,budget,random,hooks);
        if(lastFrame==null){context.initializeHooks();pulse(0,nextMembers,context,inputs.effectsAllowed(),nextMixer);}
        for(int offset=advance.executedSteps()-1;offset>=0;offset--)pulse(advance.totalSteps()-offset,nextMembers,context,inputs.effectsAllowed(),nextMixer);
        var finalContext=context.overlay(Map.of("life_time",advance.totalSteps()/60.0));finalContext.afterFrameHooks();finalContext.flushDeferred();
        var snapshots=new ArrayList<CodedControllerSnapshot>();
        for(int index=0;index<nextMembers.size();index++) {
            budget.step();var member=nextMembers.get(index);
            snapshots.add(new CodedControllerSnapshot(definitions.get(index).name(),member.clip==null?"":member.clip.name(),member.localTime,member.contributing));
        }
        var pose=nextMixer.pose();
        var effects=new ArrayList<EffectCommand>();context.appendEffects(effects,instanceId,generation,sequence);
        if(!inputs.effectsAllowed())effects.clear();
        long nextSequence=Math.addExact(sequence,effects.size());
        var frame=new CodedPoseFrame(instanceId,generation,frameId,advance.totalSteps(),advance.totalSteps()/60.0,pose,context.variables(),snapshots,effects);
        var nextScripts=context.snapshot();long nextRandom=context.randomState();budget.checkCancelled();
        clock=nextClock;members=nextMembers;mixer=nextMixer;scripts=nextScripts;random=nextRandom;sequence=nextSequence;lastFrame=frame;return frame;
    }
    private void pulse(long step,List<CodedPlayback> candidates,MolangContext context,boolean emit,CodedPoseMixer candidateMixer) {
        context=context.overlay(Map.of("life_time",step/60.0,"delta_time",1.0/60));context.beforeStepHooks();
        for(int index=0;index<candidates.size();index++) {
            context.budget().step();var member=candidates.get(index);var definition=definitions.get(index);member.pulseStep=step;member.selectionAnchor=candidateMixer.relativeAnchor();
            var local=context.memberScope(definition.name(),member.contextVariables,member.deferred).overlay(Map.of("life_time",step/60.0,"delta_time",1.0/60,"anim_time",member.localTime,"all_animations_finished",member.finished?1.0:0.0,"any_animation_finished",member.finished?1.0:0.0));
            local.codedControl(member);
            CodedPlayDecision decision;
            try {
                int result=definition.predicate()==null?5:(int)definition.predicate().evaluate(local);
                decision=switch(result){case 2->CodedPlayDecision.CONTINUE;case 3->CodedPlayDecision.STOP;case 4->CodedPlayDecision.PAUSE;default->CodedPlayDecision.BYPASS;};
                if(decision==CodedPlayDecision.BYPASS)decision=fallback(definition.fallback(),local,member);
            } finally {local.codedControl(null);}
            member.sample(step,decision,local,bind.boneCount(),emit);
        }
        candidateMixer.mix(candidates,step,context.budget());
        context.afterStepHooks();
    }
    private static CodedPlayDecision fallback(CodedFallbackPlan plan,MolangContext context,CodedControl control) {
        for(var branch:plan.branches()) {
            context.budget().step();if(branch.condition()!=null&&branch.condition().evaluate(context)==0)continue;
            for(var action:branch.actions()) {
                context.budget().step();
                if(action instanceof CodedAction.SelectAnimation select)control.select(select.name(),select.mode());
                else if(action instanceof CodedAction.BeginningTransition transition)control.transition(transition.ticks());
                else if(action instanceof CodedAction.Reset)control.resetPlayback();
                else if(action instanceof CodedAction.Reload)control.reload();
            }
            return branch.decision();
        }
        throw new IllegalStateException("Missing terminal fallback");
    }
    public synchronized void reset() {
        generation=Math.incrementExact(generation);clock=new FixedStepClock(maximumCatchUp);members=initial();mixer=new CodedPoseMixer(bind);scripts=new MolangState();random=seed;sequence=0;lastFrame=null;
    }
}
