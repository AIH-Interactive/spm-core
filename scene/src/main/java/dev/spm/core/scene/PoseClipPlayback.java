package dev.spm.core.scene;

import java.util.List;
import java.util.Map;

/** Candidate-owned declared delay schedule. No Host state or effects escape a failed frame. */
final class PoseClipPlayback {
    private final PoseAnimationLibrary.Clip clip;
    private final double initialDelay;
    private double cycleStart;
    private double previous;
    private double localTime;
    private boolean needsStart=true;
    private boolean active;

    PoseClipPlayback(PoseAnimationLibrary.Clip clip,MolangContext context) {
        this.clip=clip;
        initialDelay=delay(clip.startDelay(),context.overlay(Map.of("anim_time",0.0)));
        cycleStart=initialDelay;
    }
    private PoseClipPlayback(PoseClipPlayback source) {
        clip=source.clip; initialDelay=source.initialDelay; cycleStart=source.cycleStart;
        previous=source.previous; localTime=source.localTime; needsStart=source.needsStart; active=source.active;
    }
    PoseClipPlayback copy() { return new PoseClipPlayback(this); }
    boolean started() { return active; }
    double localTime() { return localTime; }
    boolean finished(double elapsed) { return clip.loop()!=PoseAnimationLibrary.Loop.LOOP && reached(elapsed,initialDelay+clip.duration()); }
    boolean driven(double elapsed) {
        return active && (clip.loop()!=PoseAnimationLibrary.Loop.ONCE || reached(initialDelay+clip.duration(),elapsed));
    }
    void advance(double elapsed,long step,MolangContext context,List<EffectTimeline.Pending> effects,boolean emit) {
        if (elapsed<previous) throw new IllegalArgumentException("Animation schedule cannot regress");
        double lower=previous;
        int cycles=0;
        while (true) {
            context.budget().step();
            if (needsStart) {
                if (!reached(elapsed,cycleStart)) break;
                active=true; needsStart=false;
                if (emit) PoseClipTimeline.range(clip,0,0,true,step,context,effects);
            }
            double start=Math.max(0,lower-cycleStart);
            double end=Math.min(clip.duration(),Math.max(0,elapsed-cycleStart));
            double cycleEnd=cycleStart+clip.duration();
            if (reached(elapsed,cycleEnd)) end=clip.duration();
            if (emit) PoseClipTimeline.range(clip,start,end,false,step,context,effects);
            localTime=end;
            if (clip.loop()!=PoseAnimationLibrary.Loop.LOOP || !Double.isFinite(clip.duration()) || !reached(elapsed,cycleEnd)) break;
            if (++cycles>4096) throw new MolangException(MolangException.Reason.CAPACITY,"At most 4096 animation loops per step");
            double pause=delay(clip.loopDelay(),context.overlay(Map.of("anim_time",clip.duration())));
            cycleStart=MolangContext.finite(cycleEnd+pause); lower=cycleStart; needsStart=true;
        }
        previous=elapsed;
    }
    private static double delay(PoseAnimationLibrary.Scalar expression,MolangContext context) {
        if (expression==null) return 0;
        double result=expression.value(context);
        if (result<0 || result>1_000_000) throw new MolangException(MolangException.Reason.CAPACITY,"Animation delay must be 0..1000000 seconds");
        return result;
    }
    private static boolean reached(double now,double boundary) {
        return now>=boundary || Double.isFinite(boundary) && boundary-now<=4*Math.max(Math.ulp(now),Math.ulp(boundary));
    }
}
