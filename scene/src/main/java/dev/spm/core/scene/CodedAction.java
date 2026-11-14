package dev.spm.core.scene;
import java.util.Objects;
/** Immutable actions targeting only the currently active coded member. */
public sealed interface CodedAction {
    record SelectAnimation(String name,CodedLoopMode mode) implements CodedAction {
        public SelectAnimation { Objects.requireNonNull(name);Objects.requireNonNull(mode);if(name.length()>1024)throw new IllegalArgumentException("Animation name too long"); }
    }
    record BeginningTransition(double ticks) implements CodedAction {
        public BeginningTransition { if(!Double.isFinite(ticks)||ticks<0)throw new IllegalArgumentException("Invalid transition ticks"); }
    }
    record Reset() implements CodedAction { }
    record Reload() implements CodedAction { }
}
