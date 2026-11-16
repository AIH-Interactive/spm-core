package dev.spm.core.lua;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.MolangException;
import dev.spm.core.scene.ScriptBudget;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared execution budget for bounded Lua execution: delegates instruction steps and cancellation to
 * {@link ScriptBudget} while bounding call stack depth and logical heap allocations.
 */
public final class LuaBudget {
    private final ScriptBudget scriptBudget;
    private final int maxCallDepth;
    private final long maxMemoryBytes;
    private final AtomicLong allocatedBytes = new AtomicLong();
    private final ThreadLocal<int[]> callDepth = ThreadLocal.withInitial(() -> new int[1]);

    public LuaBudget(long maxSteps, int maxCallDepth, long maxMemoryBytes, CancellationToken cancellation) {
        this(new ScriptBudget(maxSteps, Math.min(256, Math.max(1, maxCallDepth)), Objects.requireNonNull(cancellation)),
                maxCallDepth, maxMemoryBytes);
    }

    public LuaBudget(ScriptBudget scriptBudget, int maxCallDepth, long maxMemoryBytes) {
        if (maxCallDepth < 1 || maxCallDepth > 512) {
            throw new IllegalArgumentException("Call depth must be 1..512");
        }
        if (maxMemoryBytes < 1024) {
            throw new IllegalArgumentException("Memory budget must be >= 1024 bytes");
        }
        this.scriptBudget = Objects.requireNonNull(scriptBudget);
        this.maxCallDepth = maxCallDepth;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    public static LuaBudget avatarDefault(CancellationToken cancellation) {
        return new LuaBudget(250_000L, 64, 4L << 20, cancellation);
    }

    public void step() {
        try {
            scriptBudget.step();
        } catch (MolangException budgetExhausted) {
            throw new LuaException(LuaException.Reason.BUDGET, budgetExhausted.getMessage(), budgetExhausted);
        }
    }

    public void checkCancelled() {
        scriptBudget.checkCancelled();
    }

    public void chargeMemory(long bytes) {
        if (bytes <= 0) return;
        while (true) {
            long current = allocatedBytes.get();
            long next = current + bytes;
            if (next < 0 || next > maxMemoryBytes) {
                throw new LuaException(LuaException.Reason.MEMORY,
                        "Lua memory budget exceeded (" + maxMemoryBytes + " bytes)");
            }
            if (allocatedBytes.compareAndSet(current, next)) return;
        }
    }

    public void enterCall() {
        step();
        int[] depth = callDepth.get();
        if (depth[0] >= maxCallDepth) {
            throw new LuaException(LuaException.Reason.BUDGET,
                    "Lua call stack depth limit exceeded (" + maxCallDepth + ")");
        }
        depth[0]++;
    }

    public void leaveCall() {
        int[] depth = callDepth.get();
        if (depth[0] > 0) depth[0]--;
    }

    public long remainingSteps() { return scriptBudget.remainingSteps(); }
    public long allocatedBytes() { return allocatedBytes.get(); }
    public long maxMemoryBytes() { return maxMemoryBytes; }
}
