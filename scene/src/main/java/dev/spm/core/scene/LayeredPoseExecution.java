package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sparse channel-driven TRS adapter for JSON {@link PoseControllerDefinition} slots running directly
 * inside {@link CodedAnimationCollection}. Preserves exact driven-channel masks even when evaluated
 * values equal bind pose (e.g. scale [1,1,1] unhiding a lower layer's [0,0,0] scale or [0,0,0]
 * rotation/position keyframes), and allocates zero full-skeleton arrays for empty/idle states.
 */
final class LayeredPoseExecution implements ControllerRuntime.Execution<PoseAnimationLibrary.SparseSample> {
    private final PoseAnimationLibrary library;
    private final PoseControllerDefinition definition;
    private final float[] bindRotation;
    private final Map<Integer, PoseClipPlayback> playbacks = new HashMap<>();
    private Boolean shortest;

    LayeredPoseExecution(PoseAnimationLibrary library, BonePose bind, PoseControllerDefinition definition) {
        this.library = library;
        this.definition = definition;
        this.bindRotation = bind.rotations();
    }

    private LayeredPoseExecution(LayeredPoseExecution source) {
        this.library = source.library;
        this.definition = source.definition;
        this.bindRotation = source.bindRotation;
        this.shortest = source.shortest;
        source.playbacks.forEach((slot, playback) -> this.playbacks.put(slot, playback.copy()));
    }

    @Override
    public PoseAnimationLibrary.SparseSample empty() {
        return PoseAnimationLibrary.SparseSample.EMPTY;
    }

    @Override
    public ControllerRuntime.Execution<PoseAnimationLibrary.SparseSample> copy() {
        return new LayeredPoseExecution(this);
    }

    @Override
    public void begin(List<ControllerRuntime.WeightedClip> clips, MolangContext context) {
        playbacks.clear();
        for (var animation : clips) {
            context.budget().step();
            var clip = library.clip(animation.clip().name());
            if (clip.delayed()) playbacks.put(animation.slot(), new PoseClipPlayback(clip, context));
        }
    }

    @Override
    public double blendSeconds(String source, String target, double legacy) {
        shortest = definition.outgoingRotation.get(source);
        return shortest == null ? legacy : definition.graph.states().get(source).blendSeconds();
    }

    @Override
    public void beforeExit(List<ControllerRuntime.WeightedClip> clips, double elapsed, long step,
                           MolangContext context, List<EffectTimeline.Pending> effects) {
        for (var animation : clips) {
            context.budget().step();
            var playback = playbacks.get(animation.slot());
            if (playback != null) {
                playback.advance(elapsed, step,
                        context.overlayKnown(Map.of("animation_finished", finished(animation, elapsed) ? 1.0 : 0.0)),
                        effects, animation.weight() > 0);
            }
        }
    }

    @Override
    public boolean traverseInitialEmptyStates() {
        return true;
    }

    @Override
    public boolean finished(ControllerRuntime.WeightedClip animation, double elapsed) {
        var clip = library.clip(animation.clip().name());
        var playback = playbacks.get(animation.slot());
        return playback != null ? playback.finished(elapsed)
                : clip.loop() != PoseAnimationLibrary.Loop.LOOP && elapsed >= clip.duration();
    }

    @Override
    public void emit(ControllerRuntime.WeightedClip animation, double start, double end, boolean initial,
                     long step, MolangContext context, List<EffectTimeline.Pending> effects) {
        var clip = library.clip(animation.clip().name());
        var playback = playbacks.get(animation.slot());
        var local = context.overlayKnown(Map.of("animation_finished", finished(animation, end) ? 1.0 : 0.0));
        if (playback != null) playback.advance(end, step, local, effects, animation.weight() > 0);
        else if (animation.weight() > 0) PoseClipTimeline.append(clip, start, end, initial, step, local, effects);
    }

    @Override
    public void enter(String state, long step, MolangContext context, List<EffectTimeline.Pending> effects) {
        for (String sound : definition.sounds.getOrDefault(state, List.of())) {
            context.budget().step();
            PoseClipTimeline.sound(sound, step, effects);
        }
    }

    @Override
    public PoseAnimationLibrary.SparseSample sample(List<ControllerRuntime.WeightedClip> clips,
                                                    double elapsed, MolangContext context) {
        if (clips.isEmpty()) return PoseAnimationLibrary.SparseSample.EMPTY;
        int boneCount = library.boneCount();
        PoseAnimationLibrary.SparseSample singleFast = null;
        double singleWeight = 1.0;
        int activeCount = 0;

        // First count contributing clips so the common single-clip weight==1 case allocates nothing extra.
        for (var animation : clips) {
            if (animation.weight() > 0) activeCount++;
        }
        if (activeCount == 0) return PoseAnimationLibrary.SparseSample.EMPTY;
        if (activeCount == 1) {
            for (var animation : clips) {
                if (animation.weight() <= 0) continue;
                context.budget().step();
                var clip = library.clip(animation.clip().name());
                var playback = playbacks.get(animation.slot());
                double time = playback == null ? clip.localTime(elapsed) : playback.localTime();
                var local = context.overlayKnown(Map.of("anim_time", time,
                        "animation_finished", finished(animation, elapsed) ? 1.0 : 0.0));
                boolean driven = playback == null
                        ? clip.loop() != PoseAnimationLibrary.Loop.ONCE || elapsed <= clip.duration()
                        : playback.driven(elapsed);
                singleFast = library.sampleSparse(clip, time, local, driven);
                singleWeight = animation.weight();
                break;
            }
            if (singleFast == null || singleFast.count() == 0) return PoseAnimationLibrary.SparseSample.EMPTY;
            if (singleWeight == 1.0) return singleFast;
            return scaleWeighted(singleFast, singleWeight);
        }

        int[] slotOfBone = new int[boneCount];
        Arrays.fill(slotOfBone, -1);
        int[] boneIndices = new int[Math.min(boneCount, 32)];
        byte[] channels = new byte[boneIndices.length];
        float[] rot = new float[boneIndices.length * 3];
        float[] pos = new float[boneIndices.length * 3];
        float[] scale = new float[boneIndices.length * 3];
        int used = 0;

        for (var animation : clips) {
            context.budget().step();
            double weight = animation.weight();
            if (weight <= 0) continue;
            var clip = library.clip(animation.clip().name());
            var playback = playbacks.get(animation.slot());
            double time = playback == null ? clip.localTime(elapsed) : playback.localTime();
            var local = context.overlayKnown(Map.of("anim_time", time,
                    "animation_finished", finished(animation, elapsed) ? 1.0 : 0.0));
            boolean driven = playback == null
                    ? clip.loop() != PoseAnimationLibrary.Loop.ONCE || elapsed <= clip.duration()
                    : playback.driven(elapsed);
            var sp = library.sampleSparse(clip, time, local, driven);
            int kCount = sp.count();
            if (kCount == 0) continue;
            boolean overridePrev = clip.overridePrevious();
            for (int k = 0; k < kCount; k++) {
                int bIdx = sp.boneIndices[k];
                int dst = slotOfBone[bIdx];
                if (dst < 0) {
                    if (used == boneIndices.length) {
                        int cap = Math.min(boneCount, boneIndices.length * 2);
                        boneIndices = Arrays.copyOf(boneIndices, cap);
                        channels = Arrays.copyOf(channels, cap);
                        rot = Arrays.copyOf(rot, cap * 3);
                        pos = Arrays.copyOf(pos, cap * 3);
                        scale = Arrays.copyOf(scale, cap * 3);
                    }
                    dst = used++;
                    slotOfBone[bIdx] = dst;
                    boneIndices[dst] = bIdx;
                    int dOff = dst * 3;
                    scale[dOff] = 1.0f;
                    scale[dOff + 1] = 1.0f;
                    scale[dOff + 2] = 1.0f;
                }
                int sOff = k * 3;
                int dOff = dst * 3;
                byte ch = sp.channels[k];
                if ((ch & PoseAnimationLibrary.SparseSample.HAS_ROT) != 0) {
                    if (overridePrev || (channels[dst] & PoseAnimationLibrary.SparseSample.HAS_ROT) == 0) {
                        rot[dOff]     = PoseAnimationLibrary.representable(sp.rotations[sOff]     * weight);
                        rot[dOff + 1] = PoseAnimationLibrary.representable(sp.rotations[sOff + 1] * weight);
                        rot[dOff + 2] = PoseAnimationLibrary.representable(sp.rotations[sOff + 2] * weight);
                    } else {
                        rot[dOff]     = PoseAnimationLibrary.representable(rot[dOff]     + sp.rotations[sOff]     * weight);
                        rot[dOff + 1] = PoseAnimationLibrary.representable(rot[dOff + 1] + sp.rotations[sOff + 1] * weight);
                        rot[dOff + 2] = PoseAnimationLibrary.representable(rot[dOff + 2] + sp.rotations[sOff + 2] * weight);
                    }
                    channels[dst] |= PoseAnimationLibrary.SparseSample.HAS_ROT;
                }
                if ((ch & PoseAnimationLibrary.SparseSample.HAS_POS) != 0) {
                    if (overridePrev || (channels[dst] & PoseAnimationLibrary.SparseSample.HAS_POS) == 0) {
                        pos[dOff]     = PoseAnimationLibrary.representable(sp.translations[sOff]     * weight);
                        pos[dOff + 1] = PoseAnimationLibrary.representable(sp.translations[sOff + 1] * weight);
                        pos[dOff + 2] = PoseAnimationLibrary.representable(sp.translations[sOff + 2] * weight);
                    } else {
                        pos[dOff]     = PoseAnimationLibrary.representable(pos[dOff]     + sp.translations[sOff]     * weight);
                        pos[dOff + 1] = PoseAnimationLibrary.representable(pos[dOff + 1] + sp.translations[sOff + 1] * weight);
                        pos[dOff + 2] = PoseAnimationLibrary.representable(pos[dOff + 2] + sp.translations[sOff + 2] * weight);
                    }
                    channels[dst] |= PoseAnimationLibrary.SparseSample.HAS_POS;
                }
                if ((ch & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                    double sx = 1.0 + (sp.scales[sOff]     - 1.0) * weight;
                    double sy = 1.0 + (sp.scales[sOff + 1] - 1.0) * weight;
                    double sz = 1.0 + (sp.scales[sOff + 2] - 1.0) * weight;
                    if (overridePrev || (channels[dst] & PoseAnimationLibrary.SparseSample.HAS_SCALE) == 0) {
                        scale[dOff]     = PoseAnimationLibrary.representable(sx);
                        scale[dOff + 1] = PoseAnimationLibrary.representable(sy);
                        scale[dOff + 2] = PoseAnimationLibrary.representable(sz);
                    } else {
                        scale[dOff]     = PoseAnimationLibrary.representable(scale[dOff]     * sx);
                        scale[dOff + 1] = PoseAnimationLibrary.representable(scale[dOff + 1] * sy);
                        scale[dOff + 2] = PoseAnimationLibrary.representable(scale[dOff + 2] * sz);
                    }
                    channels[dst] |= PoseAnimationLibrary.SparseSample.HAS_SCALE;
                }
            }
        }
        if (used == 0) return PoseAnimationLibrary.SparseSample.EMPTY;
        return new PoseAnimationLibrary.SparseSample(
                Arrays.copyOf(boneIndices, used),
                Arrays.copyOf(channels, used),
                Arrays.copyOf(rot, used * 3),
                Arrays.copyOf(pos, used * 3),
                Arrays.copyOf(scale, used * 3));
    }

    private static PoseAnimationLibrary.SparseSample scaleWeighted(PoseAnimationLibrary.SparseSample sp, double weight) {
        int n = sp.count();
        float[] r = new float[n * 3];
        float[] p = new float[n * 3];
        float[] s = new float[n * 3];
        for (int k = 0; k < n; k++) {
            int off = k * 3;
            byte ch = sp.channels[k];
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_ROT) != 0) {
                r[off]     = PoseAnimationLibrary.representable(sp.rotations[off]     * weight);
                r[off + 1] = PoseAnimationLibrary.representable(sp.rotations[off + 1] * weight);
                r[off + 2] = PoseAnimationLibrary.representable(sp.rotations[off + 2] * weight);
            }
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_POS) != 0) {
                p[off]     = PoseAnimationLibrary.representable(sp.translations[off]     * weight);
                p[off + 1] = PoseAnimationLibrary.representable(sp.translations[off + 1] * weight);
                p[off + 2] = PoseAnimationLibrary.representable(sp.translations[off + 2] * weight);
            }
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                s[off]     = PoseAnimationLibrary.representable(1.0 + (sp.scales[off]     - 1.0) * weight);
                s[off + 1] = PoseAnimationLibrary.representable(1.0 + (sp.scales[off + 1] - 1.0) * weight);
                s[off + 2] = PoseAnimationLibrary.representable(1.0 + (sp.scales[off + 2] - 1.0) * weight);
            } else {
                s[off] = 1.0f; s[off + 1] = 1.0f; s[off + 2] = 1.0f;
            }
        }
        return new PoseAnimationLibrary.SparseSample(sp.boneIndices, sp.channels, r, p, s);
    }

    @Override
    public PoseAnimationLibrary.SparseSample blend(PoseAnimationLibrary.SparseSample from,
                                                   PoseAnimationLibrary.SparseSample target,
                                                   double progress, ScriptBudget budget) {
        int fromCount = from.count();
        int targetCount = target.count();
        if (fromCount == 0 && targetCount == 0) return PoseAnimationLibrary.SparseSample.EMPTY;
        int boneCount = library.boneCount();
        budget.steps(fromCount + targetCount);

        int[] slotOfBone = new int[boneCount];
        Arrays.fill(slotOfBone, -1);
        int maxCount = Math.min(boneCount, fromCount + targetCount);
        int[] boneIndices = new int[maxCount];
        byte[] fromCh = new byte[maxCount];
        byte[] targetCh = new byte[maxCount];
        float[] fRot = new float[maxCount * 3];
        float[] fPos = new float[maxCount * 3];
        float[] fScale = new float[maxCount * 3];
        float[] tRot = new float[maxCount * 3];
        float[] tPos = new float[maxCount * 3];
        float[] tScale = new float[maxCount * 3];
        int used = 0;

        for (int k = 0; k < fromCount; k++) {
            int bIdx = from.boneIndices[k];
            int dst = used++;
            slotOfBone[bIdx] = dst;
            boneIndices[dst] = bIdx;
            fromCh[dst] = from.channels[k];
            int sOff = k * 3, dOff = dst * 3;
            fRot[dOff] = from.rotations[sOff]; fRot[dOff + 1] = from.rotations[sOff + 1]; fRot[dOff + 2] = from.rotations[sOff + 2];
            fPos[dOff] = from.translations[sOff]; fPos[dOff + 1] = from.translations[sOff + 1]; fPos[dOff + 2] = from.translations[sOff + 2];
            if ((from.channels[k] & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                fScale[dOff] = from.scales[sOff]; fScale[dOff + 1] = from.scales[sOff + 1]; fScale[dOff + 2] = from.scales[sOff + 2];
            } else {
                fScale[dOff] = 1.0f; fScale[dOff + 1] = 1.0f; fScale[dOff + 2] = 1.0f;
            }
            tScale[dOff] = 1.0f; tScale[dOff + 1] = 1.0f; tScale[dOff + 2] = 1.0f;
        }
        for (int k = 0; k < targetCount; k++) {
            int bIdx = target.boneIndices[k];
            int dst = slotOfBone[bIdx];
            if (dst < 0) {
                dst = used++;
                slotOfBone[bIdx] = dst;
                boneIndices[dst] = bIdx;
                int dOff = dst * 3;
                fScale[dOff] = 1.0f; fScale[dOff + 1] = 1.0f; fScale[dOff + 2] = 1.0f;
            }
            targetCh[dst] = target.channels[k];
            int sOff = k * 3, dOff = dst * 3;
            tRot[dOff] = target.rotations[sOff]; tRot[dOff + 1] = target.rotations[sOff + 1]; tRot[dOff + 2] = target.rotations[sOff + 2];
            tPos[dOff] = target.translations[sOff]; tPos[dOff + 1] = target.translations[sOff + 1]; tPos[dOff + 2] = target.translations[sOff + 2];
            if ((target.channels[k] & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                tScale[dOff] = target.scales[sOff]; tScale[dOff + 1] = target.scales[sOff + 1]; tScale[dOff + 2] = target.scales[sOff + 2];
            } else {
                tScale[dOff] = 1.0f; tScale[dOff + 1] = 1.0f; tScale[dOff + 2] = 1.0f;
            }
        }

        byte[] outCh = new byte[used];
        float[] outRot = new float[used * 3];
        float[] outPos = new float[used * 3];
        float[] outScale = new float[used * 3];
        float[] startAbs = new float[3];
        float[] endAbs = new float[3];
        float[] resAbs = new float[3];
        double oneMinus = 1.0 - progress;

        for (int k = 0; k < used; k++) {
            byte ch = (byte) (fromCh[k] | targetCh[k]);
            outCh[k] = ch;
            int off = k * 3;
            int bOff = boneIndices[k] * 3;
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_ROT) != 0) {
                for (int axis = 0; axis < 3; axis++) {
                    float br = bindRotation[bOff + axis];
                    startAbs[axis] = PoseAnimationLibrary.representable(br + fRot[off + axis]);
                    endAbs[axis] = PoseAnimationLibrary.representable(br + tRot[off + axis]);
                }
                if (shortest == null) PoseRotation.blend(startAbs, endAbs, 0, progress, resAbs);
                else PoseRotation.axes(startAbs, endAbs, 0, progress, shortest, resAbs);
                for (int axis = 0; axis < 3; axis++) {
                    outRot[off + axis] = PoseAnimationLibrary.representable(resAbs[axis] - bindRotation[bOff + axis]);
                }
            }
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_POS) != 0) {
                for (int axis = 0; axis < 3; axis++) {
                    outPos[off + axis] = PoseAnimationLibrary.representable(fPos[off + axis] * oneMinus + tPos[off + axis] * progress);
                }
            }
            if ((ch & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                for (int axis = 0; axis < 3; axis++) {
                    outScale[off + axis] = PoseAnimationLibrary.representable(fScale[off + axis] * oneMinus + tScale[off + axis] * progress);
                }
            } else {
                outScale[off] = 1.0f; outScale[off + 1] = 1.0f; outScale[off + 2] = 1.0f;
            }
        }
        return new PoseAnimationLibrary.SparseSample(
                Arrays.copyOf(boneIndices, used), outCh, outRot, outPos, outScale);
    }
}
