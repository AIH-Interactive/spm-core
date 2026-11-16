package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.Arrays;

/** Unpublished coded member. Time is seconds internally; authored transition lengths use twenty ticks/second. */
final class CodedPlayback implements CodedControl {
    enum Phase { IDLE, BEGINNING, RUNNING, ENDING }
    private static final double ENDING_SECONDS=3.0/20;
    private final PoseAnimationLibrary library;
    final java.util.Map<String,MolangValue> contextVariables=new java.util.HashMap<>();
    final java.util.List<java.util.List<MolangValue>> deferred=new java.util.ArrayList<>();
    PoseClipPlayback schedule;
    double previousRunning;
    String selected="";
    CodedLoopMode mode=CodedLoopMode.CLIP_DEFAULT;
    PoseAnimationLibrary.Clip clip;
    long startStep;
    long pulseStep;
    long endingStep;
    double transitionTicks;
    boolean changed;
    boolean initial=true;
    boolean contributing;
    boolean finished=true;
    Phase phase=Phase.IDLE;
    BonePose contribution;
    BonePose transitionFrom;
    BonePose selectionAnchor;
    boolean[] mask;
    double localTime;
    double endingProgress;

    CodedPlayback(PoseAnimationLibrary library,int bones,double ticks) {
        this.library=library;transitionTicks=ticks;contribution=BonePose.identity(bones);transitionFrom=contribution;selectionAnchor=contribution;mask=new boolean[bones*3];
    }
    private CodedPlayback(CodedPlayback other) {
        contextVariables.putAll(other.contextVariables);deferred.addAll(other.deferred);schedule=other.schedule==null?null:other.schedule.copy();previousRunning=other.previousRunning;library=other.library;selected=other.selected;mode=other.mode;clip=other.clip;startStep=other.startStep;
        pulseStep=other.pulseStep;endingStep=other.endingStep;transitionTicks=other.transitionTicks;changed=other.changed;initial=other.initial;phase=other.phase;finished=other.finished;
        contributing=other.contributing;contribution=other.contribution;transitionFrom=other.transitionFrom;selectionAnchor=other.selectionAnchor;mask=other.mask.clone();localTime=other.localTime;endingProgress=other.endingProgress;
    }
    CodedPlayback copy() {return new CodedPlayback(this);}
    @Override public void select(String name,CodedLoopMode override) {
        if(selected.equals(name)&&mode==override)return;
        selected=name;mode=override;transitionFrom=selectionAnchor;clip=library.names().contains(name)?library.clip(name):null;
        startStep=pulseStep;endingProgress=0;changed=true;phase=Phase.IDLE;finished=true;
        if(clip!=null&&override!=CodedLoopMode.CLIP_DEFAULT)clip=new PoseAnimationLibrary.Clip(clip.name(),clip.duration(),
                switch(override){case LOOP->PoseAnimationLibrary.Loop.LOOP;case PLAY_ONCE->PoseAnimationLibrary.Loop.ONCE;case HOLD_ON_LAST_FRAME->PoseAnimationLibrary.Loop.HOLD;default->clip.loop();},
                clip.blendWeight(),clip.startDelay(),clip.loopDelay(),clip.overridePrevious(),clip.bones(),clip.events());
    }
    @Override public void transition(double ticks) {if(Double.isFinite(ticks)&&ticks>=0)transitionTicks=ticks;}
    @Override public void resetPlayback() {selected="";clip=null;changed=true;phase=Phase.IDLE;finished=true;localTime=0;}
    @Override public void reload() {selected="";}
    private void stop(long step) {phase=Phase.ENDING;endingStep=step;transitionFrom=contribution;finished=true;}
    void sample(long step,CodedPlayDecision decision,MolangContext context,int bones,boolean emit) {
        if(decision==CodedPlayDecision.STOP) {
            Phase previous=phase;
            if(previous==Phase.RUNNING||previous==Phase.BEGINNING){context.resetAnimationContext();stop(step);reload();return;}
            if(previous!=Phase.ENDING){contributing=false;Arrays.fill(mask,false);return;}
        }
        if(phase==Phase.ENDING) {
            double progress=Math.min(1,(step-endingStep)/60.0/ENDING_SECONDS);
            if(progress>=1){phase=Phase.IDLE;clip=null;contributing=false;Arrays.fill(mask,false);return;}
            endingProgress=progress;contribution=transitionFrom;
            contributing=decision!=CodedPlayDecision.PAUSE;return;
        }
        if(phase==Phase.IDLE||changed){context.resetAnimationContext();schedule=null;previousRunning=0;initial=true;}
        if(clip==null){contributing=false;Arrays.fill(mask,false);return;}
        double beginSeconds=transitionTicks/20.0;
        if(changed){phase=beginSeconds>0?Phase.BEGINNING:Phase.RUNNING;finished=false;schedule=clip.delayed()?new PoseClipPlayback(clip,context):null;}
        double elapsed=Math.max(0,(step-startStep)/60.0);
        if(phase==Phase.BEGINNING&&elapsed>=beginSeconds)phase=Phase.RUNNING;
        if(phase==Phase.BEGINNING)localTime=0;
        else {
            double running=Math.max(0,elapsed-beginSeconds);
            boolean emitNow=emit&&decision!=CodedPlayDecision.PAUSE;
            var pending=new java.util.ArrayList<EffectTimeline.Pending>();
            if(clip.loop()==PoseAnimationLibrary.Loop.ONCE&&running>=clip.duration()&&!changed) {
                if(emitNow)PoseClipTimeline.range(clip,previousRunning,clip.duration(),initial,step,context,pending);
                context.timelineEffects(pending);context.resetAnimationContext();stop(step);contribution=transitionFrom;contributing=decision!=CodedPlayDecision.PAUSE;return;
            }
            if(schedule!=null){schedule.advance(running,step,context,pending,emitNow);localTime=schedule.localTime();}
            else if(clip.loop()==PoseAnimationLibrary.Loop.LOOP&&Double.isFinite(clip.duration())&&clip.duration()>0) {
                double duration=clip.duration();
                long before=previousRunning>duration?(long)Math.ceil(previousRunning/duration)-1:0;
                long after=running>duration?(long)Math.ceil(running/duration)-1:0;
                if(after-before>4096)throw new MolangException(MolangException.Reason.CAPACITY,"Too many coded playback cycles");
                double lower=initial?0:previousRunning-before*duration;
                for(long cycle=before;cycle<=after;cycle++) {
                    context.budget().step();double end=cycle==after?running-cycle*duration:duration;
                    if(emitNow){if(initial||cycle>before)PoseClipTimeline.range(clip,0,0,true,step,context,pending);PoseClipTimeline.range(clip,lower,end,false,step,context,pending);}
                    if(cycle<after){context.timelineEffects(pending);pending.clear();context.resetAnimationContext();lower=0;}
                }
                localTime=running-after*duration;
            } else {
                localTime=Math.min(running,clip.duration());
                if(emitNow){if(initial)PoseClipTimeline.range(clip,0,0,true,step,context,pending);PoseClipTimeline.range(clip,previousRunning,running,false,step,context,pending);}
            }
            context.timelineEffects(pending);previousRunning=running;initial=false;
            finished=running>clip.duration();
        }
        var sample=library.sample(clip,localTime,context.overlay(java.util.Map.of("anim_time",localTime)),BonePose.identity(bones),true);
        contribution=sample.pose();mask=sample.driven();
        if(phase==Phase.BEGINNING)contribution=blend(transitionFrom,contribution,elapsed/beginSeconds,true,context.budget());
        contributing=decision!=CodedPlayDecision.PAUSE;changed=false;
    }
    private static BonePose blend(BonePose from,BonePose target,double progress,boolean immediateScale,ScriptBudget budget) {
        float[] r=target.rotations(),p=target.translations(),s=target.scales();
        float[] fr=from.rotations(),fp=from.translations(),fs=from.scales();
        for(int bone=0;bone<target.boneCount();bone++) {
            budget.step();int offset=bone*3;PoseRotation.blend(fr,r,offset,progress,r);
            for(int axis=0;axis<3;axis++){int i=offset+axis;p[i]=PoseAnimationLibrary.representable(fp[i]*(1-progress)+p[i]*progress);if(!immediateScale)s[i]=PoseAnimationLibrary.representable(fs[i]*(1-progress)+s[i]*progress);}
        }
        return new BonePose(r,p,s,target.ownHidden(),target.childrenHidden(),target.glow(),target.colorsRgba());
    }
}
