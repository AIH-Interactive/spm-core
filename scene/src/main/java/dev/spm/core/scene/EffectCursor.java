package dev.spm.core.scene;

import java.util.List;

/** One cursor per scene instance. Claiming reserves delivery once, before executing external effects. */
public final class EffectCursor {
    private long instance;
    private long generation = -1;
    private long frame = -1;
    public synchronized List<EffectCommand> claim(FrameEvaluation evaluation) {
        return claim(evaluation.instanceId(),evaluation.generation(),evaluation.frameId(),evaluation.effects());
    }
    public synchronized List<EffectCommand> claim(PoseFrame evaluation) {
        return claim(evaluation.instanceId(),evaluation.generation(),evaluation.frameId(),evaluation.effects());
    }
    private List<EffectCommand> claim(long owner,long candidateGeneration,long candidateFrame,List<EffectCommand> effects) {
        if (instance == 0) instance = owner;
        if (instance != owner) throw new IllegalArgumentException("Effect cursor belongs to another scene instance");
        if (candidateGeneration < generation || candidateGeneration == generation && candidateFrame <= frame) return List.of();
        generation = candidateGeneration;
        frame = candidateFrame;
        return effects;
    }
}
