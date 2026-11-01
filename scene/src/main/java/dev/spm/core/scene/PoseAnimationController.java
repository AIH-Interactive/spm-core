package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Transactional fixed60Hz owner for a typed pose graph. Effects are data for a host to dispatch. */
public final class PoseAnimationController {
    private final long instanceId=SceneIdentity.next();
    private FixedStepClock clock;
    private ControllerRuntime<BonePose> controller;
    private Map<String,Double> variables=Map.of();
    private long randomState;
    private long sequence;
    private PoseFrame lastFrame;
    public PoseAnimationController(PoseAnimationLibrary library,PoseControllerDefinition definition,BonePose bindPose,int maximumCatchUpSteps) {
        this(library,definition,bindPose,maximumCatchUpSteps,0);
    }
    public PoseAnimationController(PoseAnimationLibrary library,PoseControllerDefinition definition,BonePose bindPose,int maximumCatchUpSteps,long randomSeed) {
        Objects.requireNonNull(library); Objects.requireNonNull(definition); Objects.requireNonNull(bindPose);
        if(definition.library!=library) throw new IllegalArgumentException("Controller was decoded against another animation library");
        if(bindPose.boneCount()!=library.boneCount()) throw new IllegalArgumentException("Controller bind pose does not match skeleton");
        controller=new ControllerRuntime<>(definition.graph,new PoseClipExecution(library,bindPose,definition));
        clock=new FixedStepClock(maximumCatchUpSteps); randomState=randomSeed;
    }
    public synchronized ControllerSnapshot controllerSnapshot() { return controller.snapshot(); }
    public synchronized PoseFrame evaluate(long frameId,long nowNanos,SceneInputs inputs,ScriptBudget budget) {
        if(frameId<0 || lastFrame!=null && frameId<lastFrame.frameId()) throw new IllegalArgumentException("Frame id must increase");
        if(lastFrame!=null && frameId==lastFrame.frameId()) return lastFrame;
        Objects.requireNonNull(inputs); Objects.requireNonNull(budget).step();
        FixedStepClock candidateClock=clock.copy();
        var advance=candidateClock.advance(nowNanos);
        long firstStep=advance.totalSteps()-advance.executedSteps();
        var candidate=controller.copy();
        var context=new MolangContext(inputs.queries(),variables,Map.of(),budget,randomState);
        List<EffectTimeline.Pending> pending=new ArrayList<>();
        if(lastFrame==null) candidate.initialize(at(context,0),pending);
        for(int offset=1;offset<=advance.executedSteps();offset++) {
            budget.step(); long step=firstStep+offset; candidate.tick(step,at(context,step),pending);
        }
        List<EffectCommand> effects=new ArrayList<>(pending.size());
        for(var effect:pending) {
            budget.step(); var cue=effect.cue();
            effects.add(new EffectCommand(instanceId,0,Math.addExact(sequence,effects.size()),effect.step(),cue.kind(),cue.resource(),cue.parameters()));
        }
        budget.checkCancelled(); var snapshot=candidate.snapshot();
        PoseFrame frame=new PoseFrame(instanceId,0,frameId,advance.totalSteps(),advance.totalSteps()/60.0,
                advance.interpolation(),advance.droppedSteps(),snapshot.state(),snapshot.stateTimeSeconds(),false,candidate.pose(),context.variables(),effects);
        long nextSequence=Math.addExact(sequence,effects.size());
        clock=candidateClock; controller=candidate; variables=frame.variables(); randomState=context.randomState(); sequence=nextSequence; lastFrame=frame;
        return frame;
    }
    private static MolangContext at(MolangContext context,long step) {
        return context.overlay(Map.of("life_time",step/60.0,"delta_time",1.0/60));
    }
}
