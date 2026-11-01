package dev.spm.core.scene;

/** 60Hz rational clock; excess whole steps are dropped, fractional phase is retained. */
public final class FixedStepClock {
    public record Advance(int executedSteps, long droppedSteps, long totalSteps, double interpolation) { }
    private final int maximumCatchUp;
    private boolean started;
    private long lastNanos;
    private long phase;
    private long totalSteps;

    public FixedStepClock(int maximumCatchUp) {
        if (maximumCatchUp < 1 || maximumCatchUp > 600) throw new IllegalArgumentException("Catch-up must be 1..600");
        this.maximumCatchUp = maximumCatchUp;
    }
    private FixedStepClock(FixedStepClock source) {
        maximumCatchUp = source.maximumCatchUp;
        started = source.started;
        lastNanos = source.lastNanos;
        phase = source.phase;
        totalSteps = source.totalSteps;
    }
    synchronized FixedStepClock copy() { return new FixedStepClock(this); }
    public synchronized Advance advance(long nowNanos) {
        if (!started) {
            started = true;
            lastNanos = nowNanos;
            return new Advance(0, 0, 0, 0);
        }
        if (nowNanos < lastNanos) throw new IllegalArgumentException("Clock cannot regress");
        long elapsed = Math.subtractExact(nowNanos, lastNanos);
        long scaledRemainder = (elapsed % 1_000_000_000L) * 60 + phase;
        long due = (elapsed / 1_000_000_000L) * 60 + scaledRemainder / 1_000_000_000L;
        int executed = (int) Math.min(due, maximumCatchUp);
        long candidateTotal = Math.addExact(totalSteps, executed);
        lastNanos = nowNanos;
        phase = scaledRemainder % 1_000_000_000L;
        totalSteps = candidateTotal;
        return new Advance(executed, due - executed, totalSteps, phase / 1_000_000_000.0);
    }
}
