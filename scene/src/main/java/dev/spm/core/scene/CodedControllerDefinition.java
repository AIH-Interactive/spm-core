package dev.spm.core.scene;
import java.util.Objects;
/** Shared immutable coded predicate and Host-frozen fallback data, with no live callback. */
public record CodedControllerDefinition(String name,MolangProgram predicate,CodedFallbackPlan fallback,double beginningTransitionTicks) {
    public CodedControllerDefinition {
        Objects.requireNonNull(name);Objects.requireNonNull(fallback);
        if(name.isBlank()||name.length()>256||!Double.isFinite(beginningTransitionTicks)||beginningTransitionTicks<0)throw new IllegalArgumentException("Invalid coded controller definition");
    }
}
