package dev.spm.core.scene;

import java.util.List;
import java.util.Map;

/** Immutable snapshot; all render passes read the same result without re-running scripts. */
public record FrameEvaluation(long instanceId, long generation, long frameId, long step,
                              double timeSeconds, double interpolation, long droppedSteps,
                              Map<String, Double> channels, Map<String, Double> variables,
                              List<EffectCommand> effects) {
    public FrameEvaluation {
        if (instanceId <= 0 || generation < 0 || frameId < 0 || step < 0 || droppedSteps < 0
                || !Double.isFinite(timeSeconds) || timeSeconds < 0
                || !Double.isFinite(interpolation) || interpolation < 0 || interpolation >= 1) {
            throw new IllegalArgumentException("Invalid frame identity/time");
        }
        channels = Map.copyOf(channels);
        channels.values().forEach(MolangContext::finite);
        variables = Map.copyOf(MolangContext.numericCopy(variables));
        effects = List.copyOf(effects);
    }
}
