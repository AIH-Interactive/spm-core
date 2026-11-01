package dev.spm.core.scene;

import java.util.List;
import java.util.Objects;

/** Scalar seconds-based curve with strict increasing keys and clamped endpoints. */
public record KeyframeCurve(List<Key> keys, Interpolation interpolation) {
    public enum Interpolation { LINEAR, STEP }
    public record Key(double timeSeconds, double value) {
        public Key {
            if (!Double.isFinite(timeSeconds) || timeSeconds < 0 || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Finite nonnegative key time and finite value required");
            }
        }
    }
    public KeyframeCurve {
        keys = List.copyOf(keys);
        Objects.requireNonNull(interpolation);
        if (keys.isEmpty() || keys.size() > 65_536) throw new IllegalArgumentException("Curve needs 1..65536 keys");
        for (int i = 1; i < keys.size(); i++) {
            if (keys.get(i).timeSeconds <= keys.get(i - 1).timeSeconds) throw new IllegalArgumentException("Key times must increase strictly");
        }
    }
    public double sample(double timeSeconds) {
        if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("Finite sample time required");
        if (timeSeconds <= keys.get(0).timeSeconds) return keys.get(0).value;
        if (timeSeconds >= keys.get(keys.size() - 1).timeSeconds) return keys.get(keys.size() - 1).value;
        int low = 0;
        int high = keys.size() - 1;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (keys.get(middle).timeSeconds <= timeSeconds) low = middle;
            else high = middle;
        }
        Key a = keys.get(low);
        Key b = keys.get(high);
        if (interpolation == Interpolation.STEP) return a.value;
        double blend = (timeSeconds - a.timeSeconds) / (b.timeSeconds - a.timeSeconds);
        return MolangContext.finite(a.value * (1 - blend) + b.value * blend);
    }
}
