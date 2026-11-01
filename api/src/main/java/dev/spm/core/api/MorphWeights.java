package dev.spm.core.api;

import java.util.Arrays;

/**
 * Immutable interpolation weights applied to one mesh's morph targets, in target declaration order.
 *
 * <p>Weights are interpolation factors over authored deltas: a value outside {@code [0,1]} would
 * extrapolate past the source data, which no supported format authorizes, so it is rejected rather
 * than silently amplified. {@link #none(int)} disables every target for a mesh.</p>
 */
public record MorphWeights(float[] values) {
    /** Largest accepted magnitude of a single interpolation weight. */
    public static final float MAX_MAGNITUDE = 1.0f;

    public MorphWeights {
        values = values.clone();
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite morph weight");
            if (Math.abs(value) > MAX_MAGNITUDE) throw new IllegalArgumentException("Morph weight out of range");
        }
    }

    /** Every target disabled. */
    public static MorphWeights none(int targetCount) {
        if (targetCount < 0) throw new IllegalArgumentException("Negative morph target count");
        return new MorphWeights(new float[targetCount]);
    }

    public int targetCount() { return values.length; }
    public float weight(int target) {
        java.util.Objects.checkIndex(target, values.length);
        return values[target];
    }
    public boolean isIdentity() {
        for (float value : values) if (value != 0) return false;
        return true;
    }
    @Override public float[] values() { return values.clone(); }

    /** True when both weight sets carry the same target count, ignoring addressing by hand. */
    public void requireCount(int expected) {
        if (values.length != expected)
            throw new IllegalArgumentException("Morph weight count does not match the target list");
    }

    @Override public String toString() { return "MorphWeights" + Arrays.toString(values); }
}
