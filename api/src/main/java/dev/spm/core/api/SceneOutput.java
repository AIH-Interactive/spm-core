package dev.spm.core.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of one scene evaluation. Everything a Host needs to present a frame, and nothing
 * it has to interpret: the channels are already sampled numbers, the effects already carry their
 * idempotency keys, and the clock fields describe what actually executed.
 *
 * <p>This type is a plain value. It is deliberately <em>not</em> an owner: it holds no reservations,
 * no leases and no borrowed buffers, so a Host may retain or discard it freely. Anything charged to
 * a {@link ResourceBudget} was released before this value was constructed.
 *
 * <p>{@code droppedSteps} is reported rather than hidden: when a Host falls behind by more than the
 * configured catch-up window, Core discards the excess steps and says how many, so a consumer can
 * distinguish "the animation is correct" from "the animation skipped".
 */
public record SceneOutput(long instanceId, long generation, long frameId, long totalSteps,
                          double timeSeconds, double interpolation, long droppedSteps,
                          Map<String, Double> channels, Map<String, Double> variables,
                          List<HostEffect> effects) {
    /** Upper bound on sampled channels so a malformed scene cannot produce an unbounded frame. */
    public static final int MAX_CHANNELS = 65_536;
    /** Upper bound on effects per evaluated frame. */
    public static final int MAX_EFFECTS = 4_096;

    public SceneOutput {
        Objects.requireNonNull(channels, "channels");
        Objects.requireNonNull(variables, "variables");
        Objects.requireNonNull(effects, "effects");
        if (instanceId < 0 || generation < 0 || frameId < 0 || totalSteps < 0 || droppedSteps < 0) {
            throw new IllegalArgumentException("Scene output identity and timing must be nonnegative");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0) {
            throw new IllegalArgumentException("Invalid scene time");
        }
        if (!Double.isFinite(interpolation) || interpolation < 0 || interpolation >= 1) {
            throw new IllegalArgumentException("Invalid scene interpolation");
        }
        channels = freeze(channels, "channel", MAX_CHANNELS);
        variables = freeze(variables, "variable", MAX_CHANNELS);
        if (effects.size() > MAX_EFFECTS) throw new IllegalArgumentException("Too many scene effects");
        var effectCopy = List.copyOf(effects);
        long previous = -1;
        for (HostEffect effect : effectCopy) {
            if (effect == null) throw new IllegalArgumentException("Null scene effect");
            if (effect.instanceId() != instanceId) throw new IllegalArgumentException("Effect from another scene instance");
            if (effect.generation() != generation) throw new IllegalArgumentException("Effect from another generation");
            if (effect.sequence() <= previous) throw new IllegalArgumentException("Effect sequences must increase");
            previous = effect.sequence();
        }
        effects = effectCopy;
    }

    @Override public Map<String, Double> channels() { return channels; }
    @Override public Map<String, Double> variables() { return variables; }
    @Override public List<HostEffect> effects() { return effects; }

    private static Map<String, Double> freeze(Map<String, Double> source, String label, int maximum) {
        var copy = new java.util.LinkedHashMap<String, Double>();
        for (var entry : source.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), label + " name");
            Double value = Objects.requireNonNull(entry.getValue(), label + " value");
            if (name.isBlank() || name.length() > 256) throw new IllegalArgumentException("Invalid " + label + " name");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite " + label + ": " + name);
            copy.put(name, value);
        }
        if (copy.size() > maximum) throw new IllegalArgumentException("Too many scene " + label + "s");
        return java.util.Collections.unmodifiableMap(copy);
    }
}
