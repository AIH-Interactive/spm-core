package dev.spm.core.scene;

import java.util.List;
import java.util.Map;

/** Shared typed-clip instructions and effects; scripts execute only in the frame's candidate context. */
final class PoseClipTimeline {
    private PoseClipTimeline() { }
    static void range(PoseAnimationLibrary.Clip clip,double start,double end,boolean initial,long step,
                      MolangContext context,List<EffectTimeline.Pending> effects) {
        int dispatched=0;
        for (var event:clip.events()) {
            context.budget().step();
            double epsilon=4*Math.max(Math.ulp(event.time()),Math.max(Math.ulp(start),Math.ulp(end)));
            if (!(initial?event.time()==0:event.time()>start+epsilon && event.time()<=end+epsilon)) continue;
            if (++dispatched>4096) throw new MolangException(MolangException.Reason.CAPACITY,"Too many animation events in one step");
            MolangContext local=context.overlay(Map.of("anim_time",event.time(),"life_time",step/60.0,"delta_time",1.0/60));
            for (var instruction:event.instructions()) { context.budget().step(); instruction.evaluate(local); }
            if (event.sound()!=null) sound(event.sound(),step,effects);
        }
    }
    static void append(PoseAnimationLibrary.Clip clip,double start,double end,boolean initial,long step,
                       MolangContext context,List<EffectTimeline.Pending> effects) {
        if(clip==null || clip.events().isEmpty()) return;
        boolean looping=clip.loop()==PoseAnimationLibrary.Loop.LOOP && Double.isFinite(clip.duration());
        long first=looping?Math.max(0,(long)Math.floor(start/clip.duration())-1):0;
        long last=looping?Math.addExact((long)Math.floor(end/clip.duration()),1):0;
        int dispatched=0;
        for(long cycle=first;cycle<=last;cycle++) {
            context.budget().step();
            for(var event:clip.events()) {
                context.budget().step();
                double occurrence=(looping?cycle*clip.duration():0)+event.time();
                double epsilon=4*Math.max(Math.ulp(occurrence),Math.max(Math.ulp(start),Math.ulp(end)));
                if(!(initial?occurrence==0:occurrence>start+epsilon && occurrence<=end+epsilon)) continue;
                if(++dispatched>4096) throw new MolangException(MolangException.Reason.CAPACITY,"Too many animation events in one step");
                MolangContext local=context.overlay(Map.of("anim_time",event.time(),"life_time",step/60.0,"delta_time",1.0/60));
                for(var instruction:event.instructions()) { context.budget().step(); instruction.evaluate(local); }
                if(event.sound()!=null) sound(event.sound(),step,effects);
            }
        }
    }
    static void sound(String resource,long step,List<EffectTimeline.Pending> effects) {
        if(effects.size()>=4096) throw new MolangException(MolangException.Reason.CAPACITY,"Too many effects in one frame");
        effects.add(new EffectTimeline.Pending(step,new AnimationClip.Cue(0,"sound",resource,Map.of())));
    }
}
