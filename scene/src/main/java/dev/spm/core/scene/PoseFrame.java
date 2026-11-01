package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable logical animation result, directly consumable by Java or Rust bone computation. */
public record PoseFrame(long instanceId,long generation,long frameId,long step,double timeSeconds,
                        double interpolation,long droppedSteps,String animation,double animationTimeSeconds,
                        boolean finished,BonePose pose,Map<String,Double> variables,List<EffectCommand> effects) {
    public PoseFrame {
        if (instanceId<=0 || generation<0 || frameId<0 || step<0 || !Double.isFinite(timeSeconds) || timeSeconds<0
                || !Double.isFinite(animationTimeSeconds) || animationTimeSeconds<0 || !Double.isFinite(interpolation)
                || interpolation<0 || interpolation>=1 || droppedSteps<0) throw new IllegalArgumentException("Invalid pose frame identity/time");
        Objects.requireNonNull(animation); Objects.requireNonNull(pose);
        variables=Map.copyOf(MolangContext.numericCopy(variables));
        effects=List.copyOf(effects);
    }
}
