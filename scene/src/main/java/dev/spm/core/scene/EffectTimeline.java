package dev.spm.core.scene;

import java.util.List;

/** Shared scalar clip instruction traversal, separate from host dispatch. */
final class EffectTimeline {
    private static final int MAX_EFFECTS_PER_FRAME = 4096;
    record Pending(long step, AnimationClip.Cue cue) { }
    private EffectTimeline() { }
    static void append(AnimationClip clip, double start, double end, boolean initial, long step,
                       List<Pending> effects, ScriptBudget budget) {
        if (clip.cues().isEmpty()) return;
        long firstCycle = clip.looping() ? (long) Math.floor(start / clip.durationSeconds()) : 0;
        long lastCycle = clip.looping() ? (long) Math.floor(end / clip.durationSeconds()) : 0;
        for (long cycle = firstCycle; cycle <= lastCycle; cycle++) {
            budget.step();
            for (AnimationClip.Cue cue : clip.cues()) {
                budget.step();
                double occurrence = cycle * clip.durationSeconds() + cue.timeSeconds();
                if ((initial && occurrence == 0) || (!initial && occurrence > start && occurrence <= end)) {
                    if (effects.size() == MAX_EFFECTS_PER_FRAME) throw new IllegalStateException("Frame effect capacity exceeded");
                    effects.add(new Pending(step, cue));
                }
            }
        }
    }
}
