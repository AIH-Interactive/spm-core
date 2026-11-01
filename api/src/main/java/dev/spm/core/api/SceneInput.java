package dev.spm.core.api;

import java.util.Objects;

/**
 * Host-sampled numeric input for one frame. Deliberately a <em>snapshot</em>: the values are copied
 * on construction so a scene evaluation cannot observe a concurrently mutated Host map.
 *
 * <p>Core never reads the game, the entity, the client tick or any Loader type. A Host that wants a
 * query called {@code query.foo} supplies {@code "foo"} here; the {@code query}/{@code q} namespace
 * is applied by the expression layer, not by this record.
 *
 * <p>Only finite values are accepted. A non-finite query is a Host bug, and silently admitting NaN
 * would poison every downstream evaluation with no diagnostic.
 */
public record SceneInput(long frameId, long nowNanos, java.util.Map<String, Double> queries) {
    public SceneInput {
        Objects.requireNonNull(queries, "queries");
        if (frameId < 0) throw new IllegalArgumentException("Scene input frame id must be nonnegative");
        if (nowNanos < 0) throw new IllegalArgumentException("Scene input time must be nonnegative");
        var copy = new java.util.LinkedHashMap<String, Double>();
        for (var entry : queries.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "query name");
            Double value = Objects.requireNonNull(entry.getValue(), "query value");
            if (name.isBlank() || name.length() > 256) throw new IllegalArgumentException("Invalid query name");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite query value: " + name);
            copy.put(name, value);
        }
        if (copy.size() > 4096) throw new IllegalArgumentException("At most 4096 query inputs");
        queries = java.util.Collections.unmodifiableMap(copy);
    }

    /** Empty input for a frame that only needs clock progression. */
    public static SceneInput empty(long frameId, long nowNanos) {
        return new SceneInput(frameId, nowNanos, java.util.Map.of());
    }

    @Override public java.util.Map<String, Double> queries() { return queries; }
}
