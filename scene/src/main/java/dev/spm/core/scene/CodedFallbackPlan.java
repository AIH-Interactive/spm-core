package dev.spm.core.scene;
import java.util.List;
import java.util.Objects;
/** Ordered bounded Core-evaluated branches; null condition denotes the mandatory terminal branch. */
public record CodedFallbackPlan(List<Branch> branches) {
    public record Branch(MolangProgram condition,List<CodedAction> actions,CodedPlayDecision decision) {
        public Branch { actions=List.copyOf(actions);Objects.requireNonNull(decision);if(actions.size()>4096||decision==CodedPlayDecision.BYPASS)throw new IllegalArgumentException("Invalid fallback branch"); }
    }
    public CodedFallbackPlan {
        branches=List.copyOf(branches);
        if(branches.isEmpty()||branches.size()>4096||branches.get(branches.size()-1).condition()!=null)throw new IllegalArgumentException("A bounded fallback needs a terminal branch");
        long actions=0;for(int i=0;i<branches.size();i++){if(i<branches.size()-1&&branches.get(i).condition()==null)throw new IllegalArgumentException("Unreachable fallback branch");actions+=branches.get(i).actions().size();}
        if(actions>4096)throw new IllegalArgumentException("Too many fallback actions");
    }
}
