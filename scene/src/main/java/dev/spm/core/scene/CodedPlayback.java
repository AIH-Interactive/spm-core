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
    double speed=1.0;
    double scaledRunning;
    double lastUnscaledRunning;
    boolean pureUnitSpeed=true;
    final boolean additive;
    boolean changed;
    boolean initial=true;
    boolean contributing;
    boolean finished=true;
    Phase phase=Phase.IDLE;
    PoseAnimationLibrary.SparseSample sparse;
    PoseAnimationLibrary.SparseSample transFromSparse;
    float[] contribRot;
    float[] contribPos;
    float[] contribScale;
    float[] transFromRot;
    float[] transFromPos;
    float[] transFromScale;
    CodedPoseMixer anchorSource;
    boolean[] mask;
    double localTime;
    double endingProgress;
    ControllerRuntime<PoseAnimationLibrary.SparseSample> jsonRuntime;
    boolean jsonInitialized;
    boolean jsonActive;
    String jsonState="";
    double jsonStateTime;

    CodedPlayback(PoseAnimationLibrary library,int bones,double ticks) {
        this(library,bones,ticks,false,null,null);
    }
    CodedPlayback(PoseAnimationLibrary library,int bones,double ticks,boolean additive) {
        this(library,bones,ticks,additive,null,null);
    }
    CodedPlayback(PoseAnimationLibrary library,int bones,double ticks,boolean additive,BonePose bind,PoseControllerDefinition jsonDefinition) {
        this.library=library;transitionTicks=ticks;this.additive=additive;
        contribRot=library.identityZero();contribPos=library.identityZero();contribScale=library.identityOne();
        transFromRot=contribRot;transFromPos=contribPos;transFromScale=contribScale;
        mask=library.emptyMask();
        if (jsonDefinition != null) {
            this.jsonRuntime = new ControllerRuntime<>(jsonDefinition.graph, new LayeredPoseExecution(library, bind, jsonDefinition));
        }
    }
    private CodedPlayback(CodedPlayback other) {
        contextVariables.putAll(other.contextVariables);deferred.addAll(other.deferred);schedule=other.schedule==null?null:other.schedule.copy();previousRunning=other.previousRunning;library=other.library;selected=other.selected;mode=other.mode;clip=other.clip;startStep=other.startStep;
        pulseStep=other.pulseStep;endingStep=other.endingStep;transitionTicks=other.transitionTicks;speed=other.speed;scaledRunning=other.scaledRunning;lastUnscaledRunning=other.lastUnscaledRunning;pureUnitSpeed=other.pureUnitSpeed;additive=other.additive;changed=other.changed;initial=other.initial;phase=other.phase;finished=other.finished;
        contributing=other.contributing;
        sparse=other.sparse;transFromSparse=other.transFromSparse;
        contribRot=other.contribRot;contribPos=other.contribPos;contribScale=other.contribScale;
        transFromRot=other.transFromRot;transFromPos=other.transFromPos;transFromScale=other.transFromScale;
        mask=other.mask;localTime=other.localTime;endingProgress=other.endingProgress;
        jsonRuntime=other.jsonRuntime==null?null:other.jsonRuntime.copy();
        jsonInitialized=other.jsonInitialized;jsonActive=other.jsonActive;jsonState=other.jsonState;jsonStateTime=other.jsonStateTime;
    }
    private static boolean isBuiltinState(String state) {
        return state == null || "ysm-builtin".equals(state) || state.startsWith("builtin");
    }
    boolean tickJson(long step, MolangContext context, boolean emit) {
        if (jsonRuntime == null) return false;
        var pending = new java.util.ArrayList<EffectTimeline.Pending>();
        if (!jsonInitialized) {
            jsonRuntime.initialize(context, pending);
            jsonInitialized = true;
        } else {
            jsonRuntime.tick(step, context, pending);
        }
        if (emit && !pending.isEmpty()) {
            context.timelineEffects(pending);
        }
        var snap = jsonRuntime.snapshot();
        String st = snap.state();
        if (isBuiltinState(st)) {
            jsonActive = false;
            jsonState = "";
            jsonStateTime = 0;
            return false;
        }
        if (phase != Phase.IDLE || clip != null) {
            context.resetAnimationContext();
            resetPlayback();
        }
        jsonActive = true;
        jsonState = st;
        jsonStateTime = snap.stateTimeSeconds();
        sparse = jsonRuntime.pose();
        mask = library.emptyMask();
        endingProgress = 0.0;
        contributing = sparse != null && sparse.count() > 0;
        return true;
    }
    CodedPlayback copy() {return new CodedPlayback(this);}
    void speed(double value) {
        if (Double.isFinite(value) && value > 0) {
            if (value != 1.0) pureUnitSpeed = false;
            this.speed = value;
        }
    }
    @Override public void select(String name,CodedLoopMode override) {
        if(selected.equals(name)&&mode==override)return;
        selected=name;mode=override;
        if (anchorSource != null) {
            float[][] anchor = anchorSource.relativeAnchorArrays();
            transFromRot = anchor[0];
            transFromPos = anchor[1];
            transFromScale = anchor[2];
        } else {
            transFromRot = library.identityZero();
            transFromPos = library.identityZero();
            transFromScale = library.identityOne();
        }
        clip=library.names().contains(name)?library.clip(name):null;
        startStep=pulseStep;endingProgress=0;changed=true;phase=Phase.IDLE;finished=true;
        if(clip!=null&&override!=CodedLoopMode.CLIP_DEFAULT)clip=new PoseAnimationLibrary.Clip(clip.name(),clip.duration(),
                switch(override){case LOOP->PoseAnimationLibrary.Loop.LOOP;case PLAY_ONCE->PoseAnimationLibrary.Loop.ONCE;case HOLD_ON_LAST_FRAME->PoseAnimationLibrary.Loop.HOLD;default->clip.loop();},
                clip.blendWeight(),clip.startDelay(),clip.loopDelay(),clip.overridePrevious(),clip.bones(),clip.events());
    }
    @Override public void transition(double ticks) {if(Double.isFinite(ticks)&&ticks>=0)transitionTicks=ticks;}
    @Override public void resetPlayback() {selected="";clip=null;changed=true;phase=Phase.IDLE;finished=true;localTime=0;}
    @Override public void reload() {selected="";}
    private void stop(long step) {phase=Phase.ENDING;endingStep=step;transFromSparse=sparse;transFromRot=contribRot;transFromPos=contribPos;transFromScale=contribScale;finished=true;}
    void sample(long step,CodedPlayDecision decision,MolangContext context,int bones,boolean emit) {
        if(decision==CodedPlayDecision.STOP) {
            Phase previous=phase;
            if(previous==Phase.RUNNING||previous==Phase.BEGINNING){context.resetAnimationContext();stop(step);reload();return;}
            if(previous!=Phase.ENDING){contributing=false;sparse=null;mask=library.emptyMask();return;}
        }
        if(phase==Phase.ENDING) {
            double progress=Math.min(1,(step-endingStep)/60.0/ENDING_SECONDS);
            if(progress>=1){phase=Phase.IDLE;clip=null;contributing=false;sparse=null;mask=library.emptyMask();return;}
            endingProgress=progress;sparse=transFromSparse;contribRot=transFromRot;contribPos=transFromPos;contribScale=transFromScale;
            contributing=decision!=CodedPlayDecision.PAUSE;return;
        }
        if(phase==Phase.IDLE||changed){context.resetAnimationContext();schedule=null;previousRunning=0;scaledRunning=0;lastUnscaledRunning=0;pureUnitSpeed=(speed==1.0);initial=true;}
        if(clip==null){contributing=false;sparse=null;mask=library.emptyMask();return;}
        double beginSeconds=transitionTicks/20.0;
        if(changed){phase=beginSeconds>0?Phase.BEGINNING:Phase.RUNNING;finished=false;schedule=clip.delayed()?new PoseClipPlayback(clip,context):null;}
        double elapsed=Math.max(0,(step-startStep)/60.0);
        if(phase==Phase.BEGINNING&&elapsed>=beginSeconds)phase=Phase.RUNNING;
        if(phase==Phase.BEGINNING)localTime=0;
        else {
            double unscaledRunning=Math.max(0,elapsed-beginSeconds);
            double running;
            if (pureUnitSpeed) {
                running=unscaledRunning;
                scaledRunning=unscaledRunning;
                lastUnscaledRunning=unscaledRunning;
            } else {
                double delta=Math.max(0,unscaledRunning-lastUnscaledRunning);
                scaledRunning+=delta*speed;
                lastUnscaledRunning=unscaledRunning;
                running=scaledRunning;
            }
            boolean emitNow=emit&&decision!=CodedPlayDecision.PAUSE;
            var pending=new java.util.ArrayList<EffectTimeline.Pending>();
            if(clip.loop()==PoseAnimationLibrary.Loop.ONCE&&running>=clip.duration()&&!changed) {
                if(emitNow)PoseClipTimeline.range(clip,previousRunning,clip.duration(),initial,step,context,pending);
                context.timelineEffects(pending);context.resetAnimationContext();stop(step);sparse=transFromSparse;contribRot=transFromRot;contribPos=transFromPos;contribScale=transFromScale;contributing=decision!=CodedPlayDecision.PAUSE;return;
            }
            if(schedule!=null){schedule.advance(running,step,context,pending,emitNow);localTime=schedule.localTime();}
            else if(clip.loop()==PoseAnimationLibrary.Loop.LOOP&&Double.isFinite(clip.duration())&&clip.duration()>0) {
                double duration=clip.duration();
                long before=previousRunning>duration?(long)Math.ceil(previousRunning/duration)-1:0;
                long after=running>duration?(long)Math.ceil(running/duration)-1:0;
                if(after-before>4096)throw new MolangException(MolangException.Reason.CAPACITY,"Too many coded playback cycles");
                double stepDelta=running-previousRunning;
                if(!initial && duration<(1.0/60.0) && stepDelta<=(1.0/60.0)+1e-9 && after>before) {
                    // Sub-frame-period looping clips (e.g. 0.01s Blockbench physics ticks) fire once per 60Hz step,
                    // matching legacy YSM instead of bursting 1-2x per step with zero-delta duplicate passes.
                    double prevLocal=previousRunning-before*duration;
                    double nextLocal=running-after*duration;
                    if(emitNow) {
                        if(prevLocal<duration) PoseClipTimeline.range(clip,prevLocal,duration,false,step,context,pending);
                        context.timelineEffects(pending);pending.clear();context.resetAnimationContext();
                        PoseClipTimeline.range(clip,0,0,true,step,context,pending);
                        double scanEnd=nextLocal<prevLocal?prevLocal:nextLocal;
                        if(scanEnd>0) PoseClipTimeline.range(clip,0,scanEnd,false,step,context,pending);
                    } else {
                        context.resetAnimationContext();
                    }
                } else {
                    double lower=initial?0:previousRunning-before*duration;
                    for(long cycle=before;cycle<=after;cycle++) {
                        context.budget().step();double end=cycle==after?running-cycle*duration:duration;
                        if(emitNow){if(initial||cycle>before)PoseClipTimeline.range(clip,0,0,true,step,context,pending);PoseClipTimeline.range(clip,lower,end,false,step,context,pending);}
                        if(cycle<after){context.timelineEffects(pending);pending.clear();context.resetAnimationContext();lower=0;}
                    }
                }
                localTime=running-after*duration;
            } else {
                localTime=Math.min(running,clip.duration());
                if(emitNow){if(initial)PoseClipTimeline.range(clip,0,0,true,step,context,pending);PoseClipTimeline.range(clip,previousRunning,running,false,step,context,pending);}
            }
            context.timelineEffects(pending);previousRunning=running;initial=false;
            finished=running>clip.duration();
        }
        MolangContext sampleCtx = context.overlayKnown(java.util.Map.of("anim_time",localTime));
        if(phase==Phase.BEGINNING) {
            var sample=library.sampleIdentity(clip,localTime,sampleCtx,true);
            sparse=null;
            contribRot=sample.rotations();contribPos=sample.translations();contribScale=sample.scales();
            mask=sample.driven();
            blendInPlace(transFromRot,transFromPos,transFromScale,contribRot,contribPos,contribScale,bones,elapsed/beginSeconds,true,context.budget());
        } else {
            sparse=library.sampleSparse(clip,localTime,sampleCtx,true);
            mask=library.emptyMask();
        }
        contributing=decision!=CodedPlayDecision.PAUSE;changed=false;
    }
    private static void blendInPlace(float[] fr,float[] fp,float[] fs,float[] r,float[] p,float[] s,int boneCount,double progress,boolean immediateScale,ScriptBudget budget) {
        budget.steps(boneCount);
        double oneMinus = 1 - progress;
        for(int bone=0;bone<boneCount;bone++) {
            int offset=bone*3;PoseRotation.blend(fr,r,offset,progress,r);
            for(int axis=0;axis<3;axis++){int i=offset+axis;p[i]=PoseAnimationLibrary.representable(fp[i]*oneMinus+p[i]*progress);if(!immediateScale)s[i]=PoseAnimationLibrary.representable(fs[i]*oneMinus+s[i]*progress);}
        }
    }
}
