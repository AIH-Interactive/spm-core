package dev.spm.core.scene;

import java.util.Map;
import java.util.Objects;

/** Shared immutable programs and a clip; no entity or host state may be stored here. */
public record SceneDefinition(AnimationClip clip, MolangProgram update,
                              Map<String, MolangProgram> channels, Map<String, MolangProgram> functions) {
    public SceneDefinition {
        Objects.requireNonNull(clip);
        Objects.requireNonNull(update);
        channels = Map.copyOf(channels);
        functions = Map.copyOf(functions);
        if (channels.size() > 4096 || functions.size() > 4096) throw new IllegalArgumentException("Scene program count exceeds limit");
    }
}
