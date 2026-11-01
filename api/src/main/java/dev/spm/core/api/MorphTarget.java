package dev.spm.core.api;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable additive morph target: per-vertex position (and optional normal) deltas in Core units,
 * relative to the mesh bind vertices. Deltas are applied before skinning, never after.
 *
 * <p>Vertices without a delta carry zeros. {@code normalDeltas} is either empty (the target only
 * moves positions) or exactly one delta per vertex.</p>
 */
public record MorphTarget(String name, float[] positionDeltas, float[] normalDeltas) {
    public MorphTarget {
        if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Blank morph target name");
        positionDeltas = Objects.requireNonNull(positionDeltas).clone();
        normalDeltas = Objects.requireNonNull(normalDeltas).clone();
        if (positionDeltas.length % 3 != 0) throw new IllegalArgumentException("Morph position sizes disagree");
        if (normalDeltas.length != 0 && normalDeltas.length != positionDeltas.length)
            throw new IllegalArgumentException("Morph normal sizes disagree");
        for (float value : positionDeltas)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite morph position delta");
        for (float value : normalDeltas)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite morph normal delta");
    }

    /** Positional-only target. */
    public MorphTarget(String name, float[] positionDeltas) {
        this(name, positionDeltas, new float[0]);
    }

    public int vertexCount() { return positionDeltas.length / 3; }
    public boolean morphsNormals() { return normalDeltas.length != 0; }

    @Override public float[] positionDeltas() { return positionDeltas.clone(); }
    @Override public float[] normalDeltas() { return normalDeltas.clone(); }

    /** Zero-filled target for a mesh without authored deltas. */
    public static MorphTarget zeros(String name, int vertexCount, boolean normals) {
        if (vertexCount < 0) throw new IllegalArgumentException("Negative vertex count");
        int components = Math.multiplyExact(vertexCount, 3);
        return new MorphTarget(name, new float[components], normals ? new float[components] : new float[0]);
    }
}
