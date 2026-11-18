package dev.spm.core.scene;
import java.util.List;
import java.util.Objects;
/** Shared immutable coded predicate and Host-frozen fallback data, with no live callback. */
public record CodedControllerDefinition(String name,MolangProgram predicate,CodedFallbackPlan fallback,double beginningTransitionTicks,boolean additive) {
    public CodedControllerDefinition(String name,MolangProgram predicate,CodedFallbackPlan fallback,double beginningTransitionTicks) {
        this(name,predicate,fallback,beginningTransitionTicks,false);
    }
    public CodedControllerDefinition {
        Objects.requireNonNull(name);Objects.requireNonNull(fallback);
        if(name.isBlank()||name.length()>256||!Double.isFinite(beginningTransitionTicks)||beginningTransitionTicks<0)throw new IllegalArgumentException("Invalid coded controller definition");
    }
    public static CodedControllerDefinition hostDriven(String name,double beginningTransitionTicks) {
        return hostDriven(name,beginningTransitionTicks,false);
    }
    public static CodedControllerDefinition hostDriven(String name,double beginningTransitionTicks,boolean additive) {
        var terminal=new CodedFallbackPlan(List.of(new CodedFallbackPlan.Branch(null,List.of(),CodedPlayDecision.STOP)));
        return new CodedControllerDefinition(name,null,terminal,beginningTransitionTicks,additive);
    }
}
