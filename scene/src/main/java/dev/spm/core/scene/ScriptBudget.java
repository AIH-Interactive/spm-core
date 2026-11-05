package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A non-resettable request budget shared across child evaluations and workers.
 *
 * <p>Steps are one aggregate counter, so independent workers cannot multiply the allowance.
 * Nesting depth is <em>per thread</em>: it describes a single evaluation stack and must not be shared,
 * or two workers each ten frames deep would falsely exhaust a depth of twenty. Both counters are
 * latch-free on the hot path so the interpreter does not serialize on a monitor once per AST node.
 *
 * <p>{@link #activeDepth()} therefore reports the calling thread's depth only.
 */
public final class ScriptBudget {
    private final AtomicLong remaining;
    private final int maximumDepth;
    private final int maximumCallDepth;
    private final CancellationToken cancellation;
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[67]);

    public ScriptBudget(long maximumSteps, int maximumDepth, CancellationToken cancellation) {
        this(maximumSteps,maximumDepth,Math.min(64,maximumDepth),cancellation);
    }
    ScriptBudget(long maximumSteps,int maximumDepth,int maximumCallDepth,CancellationToken cancellation) {
        if (maximumSteps < 0 || maximumDepth < 1 || maximumDepth > 256) {
            throw new IllegalArgumentException("Steps must be nonnegative; depth must be 1..256");
        }
        this.remaining = new AtomicLong(maximumSteps);
        this.maximumDepth = maximumDepth;
        if(maximumCallDepth<1 || maximumCallDepth>64) throw new IllegalArgumentException("Call depth must be 1..64");
        this.maximumCallDepth=maximumCallDepth;
        this.cancellation = Objects.requireNonNull(cancellation);
    }

    /** Charges one step against the shared allowance; it is never restored. */
    public void step() {
        cancellation.check();
        while (true) {
            long before = remaining.get();
            if (before == 0L) throw new MolangException(MolangException.Reason.STEPS, "Script step budget exhausted");
            if (remaining.compareAndSet(before, before - 1)) return;
        }
    }

    void enter() {
        step();
        int[] current = depth.get();
        if (current[0] >= maximumDepth || current[2]>=512) throw new MolangException(MolangException.Reason.DEPTH, "Script AST depth budget exhausted");
        current[0]++; current[2]++;
    }

    void leave() { int[] current=depth.get(); current[0]--; current[2]--; }
    void enterFunction() {
        step(); int[] current=depth.get();
        if(current[1]>=maximumCallDepth) throw new MolangException(MolangException.Reason.DEPTH,"Script function depth budget exhausted");
        current[3+current[1]]=current[0]; current[1]++; current[0]=0;
    }
    void leaveFunction() { int[] current=depth.get(); current[1]--; current[0]=current[3+current[1]]; }

    public long remainingSteps() { return remaining.get(); }

    /** Current thread's evaluation depth; concurrent workers keep independent counts. */
    public int activeDepth() { return depth.get()[0]; }

    public void checkCancelled() { cancellation.check(); }
}
