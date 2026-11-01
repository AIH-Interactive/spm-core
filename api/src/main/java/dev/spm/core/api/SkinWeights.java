package dev.spm.core.api;

import java.util.Objects;

/**
 * Immutable packed linear-blend skin weights: at most {@link #MAX_INFLUENCES} bone influences per
 * vertex, padded to the fixed stride recorded by {@link LayoutDescriptor#skinWeightsV1()}.
 *
 * <p>Packing is canonical: the influences that are used occupy the leading slots of each vertex and
 * every later slot holds weight {@code 0} and bone index {@code 0}. Weights are finite,
 * non-negative and normalized to 1 within {@link #NORMALIZATION_TOLERANCE}. A vertex without any
 * influence is rejected instead of being rendered at its unskinned bind position, and a repeated
 * bone index inside one vertex is rejected because it would count the same transform twice.</p>
 */
public final class SkinWeights {
    /** Fixed influences per vertex; matches the packed skin-weight wire layout. */
    public static final int MAX_INFLUENCES = 4;
    /** Absolute tolerance accepted on the per-vertex weight sum. */
    public static final double NORMALIZATION_TOLERANCE = 1.0e-4;

    private final int boneCount;
    private final int[] boneIndices;
    private final float[] weights;

    /** Canonical padded form: both arrays hold {@code vertexCount * MAX_INFLUENCES} entries. */
    public SkinWeights(int boneCount, int[] boneIndices, float[] weights) {
        this.boneCount = boneCount;
        this.boneIndices = Objects.requireNonNull(boneIndices).clone();
        this.weights = Objects.requireNonNull(weights).clone();
        if (boneCount < 0) throw new IllegalArgumentException("Negative bone count");
        if (this.boneIndices.length != this.weights.length
                || this.boneIndices.length % MAX_INFLUENCES != 0)
            throw new IllegalArgumentException("Skin weight sizes disagree");
        int vertices = this.boneIndices.length / MAX_INFLUENCES;
        if (vertices > 0 && boneCount < 1) throw new IllegalArgumentException("Missing skin skeleton");
        for (int vertex = 0; vertex < vertices; vertex++) {
            int base = vertex * MAX_INFLUENCES;
            double sum = 0;
            int used = 0;
            boolean padding = false;
            for (int slot = 0; slot < MAX_INFLUENCES; slot++) {
                int bone = this.boneIndices[base + slot];
                float weight = this.weights[base + slot];
                if (bone < 0 || bone >= boneCount) throw new IllegalArgumentException("Skin bone index out of range");
                if (!Float.isFinite(weight) || weight < 0 || weight > 1)
                    throw new IllegalArgumentException("Invalid skin weight");
                if (weight == 0) {
                    if (bone != 0) throw new IllegalArgumentException("Unused skin influence slot");
                    padding = true;
                    continue;
                }
                if (padding) throw new IllegalArgumentException("Unused skin influence slot precedes a used one");
                for (int previous = 0; previous < used; previous++)
                    if (this.boneIndices[base + previous] == bone)
                        throw new IllegalArgumentException("Duplicate skin bone");
                used++;
                sum += weight;
            }
            if (used == 0) throw new IllegalArgumentException("Zero skin weight");
            if (Math.abs(sum - 1.0) > NORMALIZATION_TOLERANCE)
                throw new IllegalArgumentException("Unnormalized skin weight");
        }
    }

    /**
     * Builds canonical packed form from per-vertex influence lists. This is the boundary where the
     * influence-count cap is enforced: more than {@link #MAX_INFLUENCES} influences for one vertex is
     * refused instead of being truncated to the largest few.
     */
    public static SkinWeights of(int boneCount, int[][] boneIndices, float[][] weights) {
        Objects.requireNonNull(boneIndices);
        Objects.requireNonNull(weights);
        if (boneIndices.length != weights.length) throw new IllegalArgumentException("Skin weight sizes disagree");
        int vertices = boneIndices.length;
        int[] packedBones = new int[Math.multiplyExact(vertices, MAX_INFLUENCES)];
        float[] packedWeights = new float[packedBones.length];
        for (int vertex = 0; vertex < vertices; vertex++) {
            int[] bones = Objects.requireNonNull(boneIndices[vertex]);
            float[] values = Objects.requireNonNull(weights[vertex]);
            if (bones.length != values.length) throw new IllegalArgumentException("Skin weight sizes disagree");
            if (bones.length < 1) throw new IllegalArgumentException("Zero skin weight");
            if (bones.length > MAX_INFLUENCES) throw new IllegalArgumentException("Too many skin influences");
            for (int slot = 0; slot < bones.length; slot++) {
                packedBones[vertex * MAX_INFLUENCES + slot] = bones[slot];
                packedWeights[vertex * MAX_INFLUENCES + slot] = values[slot];
            }
        }
        return new SkinWeights(boneCount, packedBones, packedWeights);
    }

    /** One full-weight influence per vertex; the rigid case expressed as skin weights. */
    public static SkinWeights rigid(int boneCount, int[] boneIndices) {
        int[] bones = Objects.requireNonNull(boneIndices).clone();
        float[] values = new float[Math.multiplyExact(bones.length, MAX_INFLUENCES)];
        int[] packed = new int[values.length];
        for (int vertex = 0; vertex < bones.length; vertex++) {
            packed[vertex * MAX_INFLUENCES] = bones[vertex];
            values[vertex * MAX_INFLUENCES] = 1;
        }
        return new SkinWeights(boneCount, packed, values);
    }

    public int vertexCount() { return boneIndices.length / MAX_INFLUENCES; }
    public int boneCount() { return boneCount; }

    /** Number of used influences of one vertex, between 1 and {@link #MAX_INFLUENCES}. */
    public int influenceCount(int vertex) {
        Objects.checkIndex(vertex, vertexCount());
        int base = vertex * MAX_INFLUENCES;
        int count = 0;
        while (count < MAX_INFLUENCES && weights[base + count] != 0) count++;
        return count;
    }

    public int boneIndex(int vertex, int slot) {
        Objects.checkIndex(vertex, vertexCount());
        Objects.checkIndex(slot, MAX_INFLUENCES);
        return boneIndices[vertex * MAX_INFLUENCES + slot];
    }

    public float weight(int vertex, int slot) {
        Objects.checkIndex(vertex, vertexCount());
        Objects.checkIndex(slot, MAX_INFLUENCES);
        return weights[vertex * MAX_INFLUENCES + slot];
    }

    /** Highest-weight influence of one vertex; equal weights keep the earliest slot. */
    public int dominantBone(int vertex) {
        int count = influenceCount(vertex);
        int best = boneIndex(vertex, 0);
        float bestWeight = weight(vertex, 0);
        for (int slot = 1; slot < count; slot++) {
            float candidate = weight(vertex, slot);
            if (candidate > bestWeight) { bestWeight = candidate; best = boneIndex(vertex, slot); }
        }
        return best;
    }

    public int[] boneIndices() { return boneIndices.clone(); }
    public float[] weights() { return weights.clone(); }
}
