package dev.spm.core.scene;

import java.util.Objects;

/**
 * Immutable per-frame host command for a coded controller slot in {@link CodedAnimationCollection}.
 * Allows host-side action state machines and compatibility providers to drive controller slots
 * alongside or instead of Molang controller predicates without embedding host callbacks in Core.
 */
public record CodedControllerCommand(
        CodedPlayDecision decision,
        String animation,
        CodedLoopMode loopMode,
        double transitionTicks,
        double speed,
        boolean resetPlayback,
        boolean reload) {

    public CodedControllerCommand {
        Objects.requireNonNull(decision);
        Objects.requireNonNull(animation);
        Objects.requireNonNull(loopMode);
        if (animation.length() > 1024) {
            throw new IllegalArgumentException("Animation name too long");
        }
        if (transitionTicks != -1 && (!Double.isFinite(transitionTicks) || transitionTicks < 0)) {
            throw new IllegalArgumentException("Invalid transition ticks");
        }
        if (!Double.isFinite(speed) || speed <= 0 || speed > 64) {
            throw new IllegalArgumentException("Invalid playback speed");
        }
    }

    public static CodedControllerCommand bypass() {
        return new CodedControllerCommand(CodedPlayDecision.BYPASS, "", CodedLoopMode.CLIP_DEFAULT, -1, 1.0, false, false);
    }

    public static CodedControllerCommand stop() {
        return new CodedControllerCommand(CodedPlayDecision.STOP, "", CodedLoopMode.CLIP_DEFAULT, -1, 1.0, false, false);
    }

    public static CodedControllerCommand pause() {
        return new CodedControllerCommand(CodedPlayDecision.PAUSE, "", CodedLoopMode.CLIP_DEFAULT, -1, 1.0, false, false);
    }

    public static CodedControllerCommand play(String animation, CodedLoopMode loopMode, double transitionTicks) {
        return play(animation, loopMode, transitionTicks, 1.0);
    }

    public static CodedControllerCommand play(String animation, CodedLoopMode loopMode, double transitionTicks, double speed) {
        return new CodedControllerCommand(
                CodedPlayDecision.CONTINUE,
                Objects.requireNonNull(animation),
                Objects.requireNonNull(loopMode),
                transitionTicks,
                speed,
                false,
                false);
    }
}
