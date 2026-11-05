package dev.spm.core.scene;

import java.util.List;

/** One cursor per scene instance. Claiming reserves delivery once, before executing external effects. */
public final class EffectCursor {
    private long instance;
    private long generation = -1;
    private long frame = -1;
    private long sequence=-1;
    public synchronized List<EffectCommand> claim(FrameEvaluation evaluation) {
        return claim(evaluation.instanceId(),evaluation.generation(),evaluation.frameId(),evaluation.step(),evaluation.effects());
    }
    public synchronized List<EffectCommand> claim(PoseFrame evaluation) {
        return claim(evaluation.instanceId(),evaluation.generation(),evaluation.frameId(),evaluation.step(),evaluation.effects());
    }
    public synchronized List<EffectCommand> claim(FrameEvaluation evaluation,long currentGeneration) {
        if(currentGeneration<0) throw new IllegalArgumentException("Invalid current generation");
        return evaluation.generation()==currentGeneration?claim(evaluation):List.of();
    }
    public synchronized List<EffectCommand> claim(PoseFrame evaluation,long currentGeneration) {
        if(currentGeneration<0) throw new IllegalArgumentException("Invalid current generation");
        return evaluation.generation()==currentGeneration?claim(evaluation):List.of();
    }
    private List<EffectCommand> claim(long owner,long candidateGeneration,long candidateFrame,long step,List<EffectCommand> effects) {
        if (instance != 0 && instance != owner) throw new IllegalArgumentException("Effect cursor belongs to another scene instance");
        if (candidateGeneration < generation || candidateGeneration == generation && candidateFrame <= frame) return List.of();
        long nextSequence=candidateGeneration==generation?sequence:-1;
        for(EffectCommand effect:effects) {
            if(effect.instanceId()!=owner || effect.generation()!=candidateGeneration || effect.step()>step || effect.sequence()<=nextSequence)
                throw new IllegalArgumentException("Effect batch identity, order or timing does not match its frame");
            nextSequence=effect.sequence();
        }
        instance=owner; sequence=nextSequence;
        generation = candidateGeneration;
        frame = candidateFrame;
        return effects;
    }
}
