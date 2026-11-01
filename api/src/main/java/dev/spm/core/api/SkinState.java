package dev.spm.core.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable animated inputs for one weighted frame: the per-mesh morph interpolation weights.
 *
 * <p>A mesh bound with morph targets must appear here with one weight per target; a mesh without
 * targets must not appear at all, which keeps the caller's assumption explicit instead of silently
 * ignored.</p>
 */
public final class SkinState {
    private final Map<String, MorphWeights> morph;

    private SkinState(Map<String, MorphWeights> morph) { this.morph = morph; }

    /** No morph influence anywhere; valid only for rigs whose meshes carry no targets. */
    public static SkinState rest() { return new SkinState(Map.of()); }

    public static SkinState of(Map<String, MorphWeights> morph) {
        Objects.requireNonNull(morph);
        var copy = new LinkedHashMap<String, MorphWeights>();
        morph.forEach((name, weights) -> copy.put(Objects.requireNonNull(name), Objects.requireNonNull(weights)));
        return new SkinState(Map.copyOf(copy));
    }

    /** Morph weights of one mesh; every target disabled when the state does not mention it. */
    public MorphWeights morph(String meshName, int targetCount) {
        MorphWeights weights = morph.get(Objects.requireNonNull(meshName));
        if (weights == null) return MorphWeights.none(targetCount);
        weights.requireCount(targetCount);
        return weights;
    }

    /** Rejects a state that names a mesh without targets, or omits a weight list a rig needs. */
    public void validate(SkinningRig rig) {
        Objects.requireNonNull(rig);
        for (String name : morph.keySet()) {
            if (!(rig.skin(name) instanceof MeshSkin.Weighted weighted) || weighted.morphTargets().isEmpty())
                throw new IllegalArgumentException("Morph weights name a mesh without morph targets: " + name);
        }
        for (String name : rig.weightedMeshes()) {
            if (!(rig.skin(name) instanceof MeshSkin.Weighted weighted)) continue;
            if (weighted.morphTargets().isEmpty()) continue;
            MorphWeights weights = morph.get(name);
            if (weights == null) throw new IllegalArgumentException("Missing morph weights for mesh: " + name);
            weights.requireCount(weighted.morphTargets().size());
        }
    }
}
