package dev.spm.core.scene;

import java.util.Map;
import java.util.Objects;

/** Shared immutable programs and a clip; no entity or host state may be stored here. */
public record SceneDefinition(AnimationClip clip, MolangProgram update,
                              Map<String, MolangProgram> channels, Map<String, MolangProgram> functions, MolangHooks hooks) {
    public SceneDefinition(AnimationClip clip, MolangProgram update, Map<String,MolangProgram> channels, Map<String,MolangProgram> functions) {
        this(clip,update,channels,functions,MolangHooks.empty());
    }
    public SceneDefinition {
        Objects.requireNonNull(clip);
        Objects.requireNonNull(update);
        Objects.requireNonNull(hooks);
        channels = Map.copyOf(channels);
        functions = Map.copyOf(functions);
        if (channels.size() > 4096 || functions.size() > 4096) throw new IllegalArgumentException("Scene program count exceeds limit");
    }
}
