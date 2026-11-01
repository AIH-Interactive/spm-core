package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;

/** TRS adapter for the shared scalar/typed controller scheduler, using the player's track sampler. */
final class PoseClipExecution implements ControllerRuntime.Execution<BonePose> {
    private final PoseAnimationLibrary library;
    private final BonePose bind;
    private final BonePose neutral;
    private final PoseControllerDefinition definition;
    PoseClipExecution(PoseAnimationLibrary library,BonePose bind,PoseControllerDefinition definition) {
        this.library=library; this.bind=bind; this.definition=definition; neutral=BonePose.identity(bind.boneCount());
    }
    @Override public BonePose empty() { return bind; }
    @Override public boolean traverseInitialEmptyStates() { return true; }
    @Override public boolean finished(AnimationClip descriptor,double elapsed) {
        var clip=library.clip(descriptor.name());
        return clip.loop()!=PoseAnimationLibrary.Loop.LOOP && elapsed>=clip.duration();
    }
    @Override public void emit(AnimationClip descriptor,double start,double end,boolean initial,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
        var clip=library.clip(descriptor.name());
        PoseClipTimeline.append(clip,start,end,initial,step,context.overlay(Map.of("animation_finished",finished(descriptor,end)?1.0:0.0)),effects);
    }
    @Override public void enter(String state,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
        for(String sound:definition.sounds.getOrDefault(state,List.of())) { context.budget().step(); PoseClipTimeline.sound(sound,step,effects); }
    }
    @Override public BonePose sample(List<ControllerRuntime.WeightedClip> clips,double elapsed,MolangContext context) {
        float[] rotation=bind.rotations(),position=bind.translations(),scale=bind.scales();
        for(var animation:clips) {
            context.budget().step(); if(animation.weight()<=0) continue;
            var clip=library.clip(animation.clip().name());
            var local=context.overlay(Map.of("anim_time",clip.localTime(elapsed),"animation_finished",finished(animation.clip(),elapsed)?1.0:0.0));
            var sample=library.sample(clip,clip.localTime(elapsed),local,neutral,clip.loop()!=PoseAnimationLibrary.Loop.ONCE || elapsed<=clip.duration());
            float[] r=sample.pose().rotations(),p=sample.pose().translations(),s=sample.pose().scales();
            for(int bone=0;bone<bind.boneCount();bone++) {
                context.budget().step(); int offset=bone*3;
                for(int axis=0;axis<3;axis++) {
                    int i=offset+axis; double weight=animation.weight();
                    if(sample.driven()[offset]) rotation[i]=PoseAnimationLibrary.representable(rotation[i]+r[i]*weight);
                    if(sample.driven()[offset+1]) position[i]=PoseAnimationLibrary.representable(position[i]+p[i]*weight);
                    if(sample.driven()[offset+2]) {
                        // A scale factor is applied relative to one, including a zero bind scale.
                        scale[i]=PoseAnimationLibrary.representable(scale[i]*(1+(s[i]-1)*weight));
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
            budget.step(); int offset=bone*3; PoseRotation.blend(ar,r,offset,progress,r);
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
