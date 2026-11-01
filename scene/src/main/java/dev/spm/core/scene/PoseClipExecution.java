package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/** TRS adapter for the shared scalar/typed controller scheduler, using the player's track sampler. */
final class PoseClipExecution implements ControllerRuntime.Execution<BonePose> {
    private final PoseAnimationLibrary library;
    private final BonePose bind;
    private final BonePose neutral;
    private final PoseControllerDefinition definition;
    private final float[] bindRotation;
    private final float[] bindPosition;
    private final float[] bindScale;
    private final Map<Integer,PoseClipPlayback> playbacks=new HashMap<>();
    private Boolean shortest;
    PoseClipExecution(PoseAnimationLibrary library,BonePose bind,PoseControllerDefinition definition) {
        this.library=library; this.bind=bind; this.definition=definition; neutral=BonePose.identity(bind.boneCount());
        bindRotation=bind.rotations(); bindPosition=bind.translations(); bindScale=bind.scales();
    }
    @Override public BonePose empty() { return bind; }
    @Override public ControllerRuntime.Execution<BonePose> copy() {
        PoseClipExecution result=new PoseClipExecution(this);
        playbacks.forEach((slot,playback)->result.playbacks.put(slot,playback.copy()));
        return result;
    }
    private PoseClipExecution(PoseClipExecution source) {
        library=source.library; bind=source.bind; definition=source.definition; neutral=source.neutral;
        bindRotation=source.bindRotation; bindPosition=source.bindPosition; bindScale=source.bindScale; shortest=source.shortest;
    }
    @Override public void begin(List<ControllerRuntime.WeightedClip> clips,MolangContext context) {
        playbacks.clear();
        for (var animation:clips) {
            context.budget().step();
            var clip=library.clip(animation.clip().name());
            if (clip.delayed()) playbacks.put(animation.slot(),new PoseClipPlayback(clip,context));
        }
    }
    @Override public double blendSeconds(String source,String target,double legacy) {
        shortest=definition.outgoingRotation.get(source);
        return shortest==null?legacy:definition.graph.states().get(source).blendSeconds();
    }
    @Override public void beforeExit(List<ControllerRuntime.WeightedClip> clips,double elapsed,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
        for (var animation:clips) {
            context.budget().step(); var playback=playbacks.get(animation.slot());
            if (playback!=null) playback.advance(elapsed,step,context.overlay(Map.of("animation_finished",finished(animation,elapsed)?1.0:0.0)),effects,animation.weight()>0);
        }
    }
    @Override public boolean traverseInitialEmptyStates() { return true; }
    @Override public boolean finished(ControllerRuntime.WeightedClip animation,double elapsed) {
        var clip=library.clip(animation.clip().name()); var playback=playbacks.get(animation.slot());
        return playback!=null?playback.finished(elapsed):clip.loop()!=PoseAnimationLibrary.Loop.LOOP && elapsed>=clip.duration();
    }
    @Override public void emit(ControllerRuntime.WeightedClip animation,double start,double end,boolean initial,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
        var clip=library.clip(animation.clip().name()); var playback=playbacks.get(animation.slot());
        var local=context.overlay(Map.of("animation_finished",finished(animation,end)?1.0:0.0));
        if (playback!=null) playback.advance(end,step,local,effects,animation.weight()>0);
        else if (animation.weight()>0) PoseClipTimeline.append(clip,start,end,initial,step,local,effects);
    }
    @Override public void enter(String state,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
        for(String sound:definition.sounds.getOrDefault(state,List.of())) { context.budget().step(); PoseClipTimeline.sound(sound,step,effects); }
    }
    @Override public BonePose sample(List<ControllerRuntime.WeightedClip> clips,double elapsed,MolangContext context) {
        float[] rotation=bind.rotations(),position=bind.translations(),scale=bind.scales();
        for(var animation:clips) {
            context.budget().step(); if(animation.weight()<=0) continue;
            var clip=library.clip(animation.clip().name());
            var playback=playbacks.get(animation.slot()); double time=playback==null?clip.localTime(elapsed):playback.localTime();
            var local=context.overlay(Map.of("anim_time",time,"animation_finished",finished(animation,elapsed)?1.0:0.0));
            boolean driven=playback==null?clip.loop()!=PoseAnimationLibrary.Loop.ONCE || elapsed<=clip.duration():playback.driven(elapsed);
            var sample=library.sample(clip,time,local,neutral,driven);
            float[] r=sample.pose().rotations(),p=sample.pose().translations(),s=sample.pose().scales();
            for(int bone=0;bone<bind.boneCount();bone++) {
                context.budget().step(); int offset=bone*3;
                for(int axis=0;axis<3;axis++) {
                    int i=offset+axis; double weight=animation.weight();
                    if(sample.driven()[offset]) rotation[i]=PoseAnimationLibrary.representable((clip.overridePrevious()?bindRotation[i]:rotation[i])+r[i]*weight);
                    if(sample.driven()[offset+1]) position[i]=PoseAnimationLibrary.representable((clip.overridePrevious()?bindPosition[i]:position[i])+p[i]*weight);
                    if(sample.driven()[offset+2]) {
                        // A scale factor is applied relative to one, including a zero bind scale.
                        scale[i]=PoseAnimationLibrary.representable((clip.overridePrevious()?bindScale[i]:scale[i])*(1+(s[i]-1)*weight));
                    }
                }
            }
        }
        return pose(rotation,position,scale);
    }
    @Override public BonePose blend(BonePose from,BonePose target,double progress,ScriptBudget budget) {
        float[] r=target.rotations(),p=target.translations(),s=target.scales();
        float[] ar=from.rotations(),ap=from.translations(),as=from.scales();
        for(int bone=0;bone<bind.boneCount();bone++) {
            budget.step(); int offset=bone*3;
            if (shortest==null) PoseRotation.blend(ar,r,offset,progress,r);
            else PoseRotation.axes(ar,r,offset,progress,shortest,r);
            for(int axis=0;axis<3;axis++) {
                int i=offset+axis;
                p[i]=PoseAnimationLibrary.representable(ap[i]*(1-progress)+p[i]*progress);
                s[i]=PoseAnimationLibrary.representable(as[i]*(1-progress)+s[i]*progress);
            }
        }
        return pose(r,p,s);
    }
    private BonePose pose(float[] rotation,float[] position,float[] scale) {
        return new BonePose(rotation,position,scale,bind.ownHidden(),bind.childrenHidden(),bind.glow(),bind.colorsRgba());
    }
}
