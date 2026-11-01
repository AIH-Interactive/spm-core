package dev.spm.core.render.cpu;

import dev.spm.core.api.MorphTarget;
import dev.spm.core.api.MorphWeights;
import java.util.List;
import java.util.Objects;

/**
 * Accumulates additive morph deltas. Positional deltas are summed before any skinning transform is
 * applied, so a morphed model skins its displaced vertices rather than displacing skinned ones.
 *
 * <p>Every target list is validated against its weight vector before a single byte of scratch is
 * allocated: a mismatched count, an out-of-range weight or a target built for a different vertex
 * count refuses the whole call.</p>
 */
public final class MorphApplicator {
    private MorphApplicator() {}

    /** Accumulated positional deltas, three entries per vertex; zero-filled when nothing is active. */
    public static float[] positions(List<MorphTarget> targets, MorphWeights weights, int vertexCount) {
        validate(targets, weights, vertexCount);
        float[] deltas = new float[Math.multiplyExact(vertexCount, 3)];
        int components = deltas.length;
        for (int target = 0; target < targets.size(); target++) {
            float weight = weights.weight(target);
            if (weight == 0) continue;
            float[] source = targets.get(target).positionDeltas();
            for (int k = 0; k < components; k++) deltas[k] += weight * source[k];
        }
        for (float value : deltas)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Unrepresentable morph delta");
        return deltas;
    }

    /**
     * Accumulated normal deltas, or {@code null} when no active target morphs normals. Returning null
     * lets the skinning path keep the untouched bind normals without a zero-filled allocation.
     */
    public static float[] normals(List<MorphTarget> targets, MorphWeights weights, int vertexCount) {
        validate(targets, weights, vertexCount);
        boolean any = false;
        for (int target = 0; target < targets.size(); target++) {
            if (weights.weight(target) != 0 && targets.get(target).morphsNormals()) { any = true; break; }
        }
        if (!any) return null;
        float[] deltas = new float[Math.multiplyExact(vertexCount, 3)];
        int components = deltas.length;
        for (int target = 0; target < targets.size(); target++) {
            float weight = weights.weight(target);
            if (weight == 0 || !targets.get(target).morphsNormals()) continue;
            float[] source = targets.get(target).normalDeltas();
            for (int k = 0; k < components; k++) deltas[k] += weight * source[k];
        }
        for (float value : deltas)
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Unrepresentable morph normal delta");
        return deltas;
    }

    private static void validate(List<MorphTarget> targets, MorphWeights weights, int vertexCount) {
        Objects.requireNonNull(targets);
        Objects.requireNonNull(weights);
        if (vertexCount < 0) throw new IllegalArgumentException("Negative vertex count");
        if (targets.size() != weights.targetCount())
            throw new IllegalArgumentException("Morph weights do not address every target");
        for (MorphTarget target : targets) {
            Objects.requireNonNull(target);
            if (target.vertexCount() != vertexCount)
                throw new IllegalArgumentException("Morph target does not match the vertex count");
        }
    }
}
