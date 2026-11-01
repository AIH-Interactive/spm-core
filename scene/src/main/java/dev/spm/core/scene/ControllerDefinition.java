package dev.spm.core.scene;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable bounded Bedrock-style graph. Declaration order decides the first matching transition. */
public record ControllerDefinition(String initialState, Map<String, AnimationClip> clips, Map<String, State> states) {
    public record ClipBinding(String clip, MolangProgram weight) {
        public ClipBinding { Objects.requireNonNull(clip); Objects.requireNonNull(weight); }
    }
    public record Transition(String target, MolangProgram condition) {
        public Transition { Objects.requireNonNull(target); Objects.requireNonNull(condition); }
    }
    public record State(List<ClipBinding> animations, List<Transition> transitions,
                        List<MolangProgram> onEntry, List<MolangProgram> onExit, double blendSeconds) {
        public State {
            if (animations.size() > 64 || transitions.size() > 128 || onEntry.size() > 128 || onExit.size() > 128) {
                throw new IllegalArgumentException("State exceeds animation/transition/script bounds");
            }
            if (!Double.isFinite(blendSeconds) || blendSeconds < 0 || blendSeconds > 60) {
                throw new IllegalArgumentException("Blend duration must be 0..60 seconds");
            }
            animations = List.copyOf(animations);
            transitions = List.copyOf(transitions);
            onEntry = List.copyOf(onEntry);
            onExit = List.copyOf(onExit);
        }
    }
    public ControllerDefinition {
        Objects.requireNonNull(initialState);
        if (clips.size() > 4096 || states.isEmpty() || states.size() > 1024) {
            throw new IllegalArgumentException("Controller requires 1..1024 states and at most 4096 clips");
        }
        clips = Map.copyOf(clips);
        states = Map.copyOf(states);
        if (!states.containsKey(initialState)) throw new IllegalArgumentException("Missing initial state: " + initialState);
        int transitionCount = 0;
        for (State state : states.values()) {
            transitionCount += state.transitions.size();
            for (Transition transition : state.transitions) {
                if (!states.containsKey(transition.target)) throw new IllegalArgumentException("Missing transition state: " + transition.target);
            }
            for (ClipBinding animation : state.animations) {
                if (!clips.containsKey(animation.clip)) throw new IllegalArgumentException("Missing controller clip: " + animation.clip);
            }
        }
        if (transitionCount > 8192) throw new IllegalArgumentException("At most 8192 controller transitions");
    }
}
