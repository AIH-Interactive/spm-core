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
    private final CancellationToken cancellation;
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    public ScriptBudget(long maximumSteps, int maximumDepth, CancellationToken cancellation) {
        if (maximumSteps < 0 || maximumDepth < 1 || maximumDepth > 256) {
            throw new IllegalArgumentException("Steps must be nonnegative; depth must be 1..256");
        }
        this.remaining = new AtomicLong(maximumSteps);
        this.maximumDepth = maximumDepth;
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
        if (current[0] >= maximumDepth) throw new MolangException(MolangException.Reason.DEPTH, "Script depth budget exhausted");
        current[0]++;
    }

    void leave() { depth.get()[0]--; }

    public long remainingSteps() { return remaining.get(); }

    /** Current thread's evaluation depth; concurrent workers keep independent counts. */
    public int activeDepth() { return depth.get()[0]; }

    public void checkCancelled() { cancellation.check(); }
}
