package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Serial transactional fixed60Hz pose owner. play/stop commands take effect at the next new frame. */
public final class PoseAnimationPlayer {
    private final long instanceId=SceneIdentity.next();
    private final PoseAnimationLibrary library;
    private final BonePose bindPose;
    private final double resetSeconds;
    private FixedStepClock clock;
    private State state;
    private PoseFrame lastFrame;
    private Map<String,Double> variables=Map.of();
    private long generation;
    private int maximumCatchUp;
    private long initialSeed;
    public synchronized long generation() { return generation; }
    private MolangState scriptState=new MolangState();
    public synchronized Map<String,MolangValue> typedVariables() { return scriptState.variables; }
    private long randomState;
    private long effectSequence;
    private String requested;
    private double requestedBlend;
    private boolean pending;

    public PoseAnimationPlayer(PoseAnimationLibrary library,BonePose bindPose,int maximumCatchUpSteps,double resetSeconds) {
        this(library,bindPose,maximumCatchUpSteps,resetSeconds,0);
    }
    public PoseAnimationPlayer(PoseAnimationLibrary library,BonePose bindPose,int maximumCatchUpSteps,double resetSeconds,long randomSeed) {
        this.library=Objects.requireNonNull(library);
        this.bindPose=Objects.requireNonNull(bindPose);
        if (library.boneCount()!=bindPose.boneCount()) throw new IllegalArgumentException("Player bind pose does not match animation skeleton");
        if (!Double.isFinite(resetSeconds) || resetSeconds<0 || resetSeconds>60) throw new IllegalArgumentException("Reset duration must be 0..60 seconds");
        this.resetSeconds=resetSeconds;
        randomState=randomSeed;
        maximumCatchUp=maximumCatchUpSteps; initialSeed=randomSeed;
        clock=new FixedStepClock(maximumCatchUpSteps);
        state=new State(bindPose);
    }
    public synchronized void play(String name) { play(name,0); }
    public synchronized void play(String name,double blendSeconds) {
        library.clip(Objects.requireNonNull(name));
        validateBlend(blendSeconds);
        if (name.equals(requested)) return;
        requested=name; requestedBlend=blendSeconds; pending=true;
    }
    public synchronized void restart(String name,double blendSeconds) {
        library.clip(Objects.requireNonNull(name)); validateBlend(blendSeconds);
        requested=name; requestedBlend=blendSeconds; pending=true;
    }
    public synchronized void stop() { if (requested!=null) { requested=null; requestedBlend=0; pending=true; } }
    private static void validateBlend(double seconds) {
        if (!Double.isFinite(seconds) || seconds<0 || seconds>60) throw new IllegalArgumentException("Blend duration must be 0..60 seconds");
    }
    public synchronized PoseFrame evaluate(long frameId,long nowNanos,SceneInputs inputs,ScriptBudget budget) {
        if (frameId<0 || lastFrame!=null && frameId<lastFrame.frameId()) throw new IllegalArgumentException("Frame id must increase");
        if (lastFrame!=null && lastFrame.frameId()==frameId) return lastFrame;
        Objects.requireNonNull(inputs); Objects.requireNonNull(budget).step();
        FixedStepClock candidateClock=clock.copy();
        FixedStepClock.Advance advance=candidateClock.advance(nowNanos);
        long firstStep=advance.totalSteps()-advance.executedSteps();
        State candidate=state.copy();
        MolangContext context=new MolangContext(inputs,scriptState,Map.of(),budget,randomState);
        List<EffectCommand> effects=new ArrayList<>();
        if (pending) {
            candidate.clip=requested==null?null:library.clip(requested);
            candidate.startedStep=firstStep;
            candidate.playback=null;
            candidate.playback=candidate.clip!=null && candidate.clip.delayed()?new PoseClipPlayback(candidate.clip,local(candidate,firstStep,0,context)):null;
            candidate.blendFrom=candidate.pose;
            candidate.blendSeconds=requestedBlend;
            candidate.onceEnded=false;
            emit(candidate,0,0,true,firstStep,context,effects);
            sample(candidate,firstStep,context);
        } else if (lastFrame==null) sample(candidate,firstStep,context);
        for (int offset=1;offset<=advance.executedSteps();offset++) {
            budget.step();
            long step=firstStep+offset;
            if (!candidate.onceEnded) emit(candidate,Math.max(0,(step-1-candidate.startedStep)/60.0),
                    (step-candidate.startedStep)/60.0,false,step,context,effects);
            sample(candidate,step,context);
        }
        context.appendEffects(effects,instanceId,generation,effectSequence);
        if(!inputs.effectsAllowed()) effects.clear();
        budget.checkCancelled();
        double elapsed=Math.max(0,(advance.totalSteps()-candidate.startedStep)/60.0);
        String name=candidate.clip==null?"":candidate.clip.name();
        double animationTime=candidate.clip==null?0:candidate.playback!=null?candidate.playback.localTime():candidate.clip.localTime(elapsed);
        boolean finished=finished(candidate,elapsed);
        PoseFrame frame=new PoseFrame(instanceId,generation,frameId,advance.totalSteps(),advance.totalSteps()/60.0,
                advance.interpolation(),advance.droppedSteps(),name,animationTime,finished,candidate.pose,context.variables(),effects);
        long nextSequence=Math.addExact(effectSequence,effects.size());
        clock=candidateClock; state=candidate; variables=frame.variables(); scriptState=context.snapshot(); randomState=context.randomState();
        effectSequence=nextSequence; lastFrame=frame; pending=false;
        return frame;
    }
    public synchronized void reset() {
        generation=Math.incrementExact(generation); clock=new FixedStepClock(maximumCatchUp);
        scriptState=new MolangState(); variables=Map.of(); randomState=initialSeed;
        effectSequence=0; lastFrame=null; state=new State(bindPose); requested=null; requestedBlend=0; pending=false;
    }
    private MolangContext local(State candidate,long step,double localTime,MolangContext context) {
        return context.overlay(Map.of("anim_time",localTime,"life_time",step/60.0,"delta_time",1.0/60,
                "animation_finished",finished(candidate,(step-candidate.startedStep)/60.0)?1.0:0.0));
    }
    private static boolean finished(State candidate,double elapsed) {
        return candidate.clip==null || (candidate.playback!=null?candidate.playback.finished(elapsed):
                candidate.clip.loop()!=PoseAnimationLibrary.Loop.LOOP && elapsed>=candidate.clip.duration());
    }
    private void sample(State candidate,long step,MolangContext context) {
        double elapsed=Math.max(0,(step-candidate.startedStep)/60.0);
        double localTime=candidate.clip==null?0:candidate.playback!=null?candidate.playback.localTime():candidate.clip.localTime(elapsed);
        boolean drive=candidate.clip!=null && !candidate.onceEnded && (candidate.playback==null || candidate.playback.started());
        PoseAnimationLibrary.Sample sampled=library.sample(candidate.clip,localTime,local(candidate,step,localTime,context),bindPose,drive);
        float[] rotations=sampled.pose().rotations(),positions=sampled.pose().translations(),scales=sampled.pose().scales();
        float[][] target={rotations,positions,scales};
        float[][] previous={candidate.pose.rotations(),candidate.pose.translations(),candidate.pose.scales()};
        float[][] neutral={bindPose.rotations(),bindPose.translations(),bindPose.scales()};
        float[][] from={candidate.blendFrom.rotations(),candidate.blendFrom.translations(),candidate.blendFrom.scales()};
        double blend=candidate.blendSeconds==0?1:Math.min(1,elapsed/candidate.blendSeconds);
        for (int bone=0;bone<library.boneCount();bone++) {
            context.budget().step();
            int offset=bone*3;
            for (int channel=0;channel<3;channel++) {
                int slot=offset+channel;
                if (sampled.driven()[slot]) {
                    candidate.driven[slot]=true; candidate.resetStart[slot]=-1; candidate.lastDriven[slot]=step;
                    if (blend<1) blend(channel,from[channel],target[channel],offset,blend,target[channel]);
                } else {
                    if (candidate.driven[slot]) {
                        candidate.driven[slot]=false;
                        candidate.resetStart[slot]=candidate.lastDriven[slot];
                        System.arraycopy(previous[channel],offset,candidate.anchors[channel],offset,3);
                    }
                    if (candidate.resetStart[slot]>=0) {
                        double progress=resetSeconds==0?1:Math.min(1,(step-candidate.resetStart[slot])/60.0/resetSeconds);
                        blend(channel,candidate.anchors[channel],neutral[channel],offset,progress,target[channel]);
                        if (progress==1) candidate.resetStart[slot]=-1;
                    }
                }
            }
        }
        candidate.pose=new BonePose(rotations,positions,scales,bindPose.ownHidden(),bindPose.childrenHidden(),bindPose.glow(),bindPose.colorsRgba());
        if (candidate.clip!=null && candidate.clip.loop()==PoseAnimationLibrary.Loop.ONCE && finished(candidate,elapsed)) candidate.onceEnded=true;
    }
    private static void blend(int channel,float[] start,float[] end,int offset,double progress,float[] target) {
        if (channel==0) PoseRotation.blend(start,end,offset,progress,target);
        else for (int axis=0;axis<3;axis++) target[offset+axis]=PoseAnimationLibrary.representable(start[offset+axis]*(1-progress)+end[offset+axis]*progress);
    }
    private void emit(State candidate,double start,double end,boolean initial,long step,MolangContext context,List<EffectCommand> effects) {
        List<EffectTimeline.Pending> pendingEffects=new ArrayList<>();
        if (candidate.playback!=null) candidate.playback.advance(end,step,local(candidate,step,start,context),pendingEffects,true);
        else PoseClipTimeline.append(candidate.clip,start,end,initial,step,local(candidate,step,start,context),pendingEffects);
        for(var effect:pendingEffects) {
            if(effects.size()>=4096) throw new MolangException(MolangException.Reason.CAPACITY,"Too many effects in one frame");
            var cue=effect.cue();
            effects.add(new EffectCommand(instanceId,generation,Math.addExact(effectSequence,effects.size()),step,cue.kind(),cue.resource(),cue.parameters()));
        }
    }
    private static final class State {
        PoseAnimationLibrary.Clip clip;
        PoseClipPlayback playback;
        long startedStep;
        BonePose pose;
        BonePose blendFrom;
        double blendSeconds;
        boolean onceEnded;
        final boolean[] driven;
        final long[] lastDriven;
        final long[] resetStart;
        final float[][] anchors;
        State(BonePose bind) {
            pose=bind; blendFrom=bind;
            int size=Math.multiplyExact(bind.boneCount(),3);
            driven=new boolean[size]; lastDriven=new long[size]; resetStart=new long[size]; Arrays.fill(resetStart,-1);
            anchors=new float[][]{new float[size],new float[size],new float[size]};
        }
        private State(State other) {
            clip=other.clip; startedStep=other.startedStep; pose=other.pose; blendFrom=other.blendFrom;
            playback=other.playback==null?null:other.playback.copy();
            blendSeconds=other.blendSeconds; onceEnded=other.onceEnded;
            driven=other.driven.clone(); lastDriven=other.lastDriven.clone(); resetStart=other.resetStart.clone();
            anchors=new float[][]{other.anchors[0].clone(),other.anchors[1].clone(),other.anchors[2].clone()};
        }
        State copy() { return new State(this); }
    }
}
