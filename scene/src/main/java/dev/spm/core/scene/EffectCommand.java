package dev.spm.core.scene;

import java.util.Map;
import java.util.Objects;

/** Pure data. (instanceId, generation, sequence) is the host's idempotency key. */
public record EffectCommand(long instanceId, long generation, long sequence, long step,
                            String kind, String resource, Map<String, Double> parameters) {
    public EffectCommand {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resource, "resource");
        if (kind.isBlank() || kind.length() > 64) throw new IllegalArgumentException("Invalid effect kind");
        if (resource.isBlank() || resource.length() > 1024) throw new IllegalArgumentException("Invalid effect resource");
        if (instanceId < 0 || generation < 0 || sequence < 0 || step < 0) {
            throw new IllegalArgumentException("Effect identity and timing must be nonnegative");
        }
        parameters = Map.copyOf(MolangContext.numericCopy(parameters));
    }
}
