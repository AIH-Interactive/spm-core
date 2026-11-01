package dev.spm.core.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Host-neutral, exactly-once effect request produced by a scene evaluation.
 *
 * <p>Core computes effects; it never plays a sound, spawns a particle, touches the network or
 * submits anything to a GPU. The Host receives these records and decides what they mean.
 *
 * <p>{@code (instanceId, generation, sequence)} is the Host's idempotency key: a Host that replays,
 * retries or double-buffers a frame must drop a repeated key instead of firing the effect twice.
 * {@code sequence} is strictly increasing per {@code (instanceId, generation)}. {@code step} is the
 * fixed-clock step that produced it, which lets a Host order effects that share a frame.
 *
 * <p>A retired generation is not reusable: a Host that replaces a scene must raise
 * {@code generation} so a late command from the previous scene cannot debit the replacement.
 */
public record HostEffect(long instanceId, long generation, long sequence, long step,
                         String kind, String resource, Map<String, Double> parameters) {
    /** Longest accepted kind label; longer values are a producer bug, not user data. */
    public static final int MAX_KIND_LENGTH = 64;
    /** Longest accepted resource key; matches the Host-visible resource namespace budget. */
    public static final int MAX_RESOURCE_LENGTH = 1024;
    /** Upper bound on parameters so one effect cannot smuggle an unbounded payload. */
    public static final int MAX_PARAMETERS = 256;

    public HostEffect {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(parameters, "parameters");
        if (instanceId < 0 || generation < 0 || sequence < 0 || step < 0) {
            throw new IllegalArgumentException("Effect identity and timing must be nonnegative");
        }
        if (kind.isBlank() || kind.length() > MAX_KIND_LENGTH) {
            throw new IllegalArgumentException("Invalid effect kind");
        }
        if (resource.isBlank() || resource.length() > MAX_RESOURCE_LENGTH) {
            throw new IllegalArgumentException("Invalid effect resource");
        }
        var copy = new LinkedHashMap<String, Double>();
        for (var entry : parameters.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey(), "parameter name");
            Double value = Objects.requireNonNull(entry.getValue(), "parameter value");
            if (name.isBlank() || name.length() > 256) throw new IllegalArgumentException("Invalid effect parameter name");
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite effect parameter: " + name);
            copy.put(name, value);
        }
        if (copy.size() > MAX_PARAMETERS) throw new IllegalArgumentException("Too many effect parameters");
        parameters = java.util.Collections.unmodifiableMap(copy);
    }

    @Override public Map<String, Double> parameters() { return parameters; }

    /** True when both records denote the same Host effect occurrence. */
    public boolean sameKey(HostEffect other) {
        return other != null && instanceId == other.instanceId
                && generation == other.generation && sequence == other.sequence;
    }
}
