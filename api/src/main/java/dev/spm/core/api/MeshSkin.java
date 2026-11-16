package dev.spm.core.api;

import java.util.List;
import java.util.Objects;

/**
 * One document mesh's skinning data. A mesh is either explicitly rigid — keeping the byte-identical
 * rigid provider path — or weighted, in which case weights and a method are mandatory.
 *
 * <p>Morph targets are additive positional (and optional normal) deltas applied before skinning.</p>
 */
public sealed interface MeshSkin {
    /** Explicitly unskinned: {@code ModelFrameRenderer} keeps the pre-existing rigid path. */
    record Rigid() implements MeshSkin {
        public static final Rigid INSTANCE = new Rigid();
    }

    /**
     * Weighted skinning. {@code weights} must address this mesh's vertices; the binding requires the
     * vertex count to be the document mesh's count, checked by {@link SkinningRig}.
     *
     * <p>When {@code inverseBindMatrices} is non-empty it holds {@code boneCount * 16} finite floats
     * in column-major order, overriding {@code inverse(bindPose)} for formats such as glTF that author
     * explicit inverse bind matrices.</p>
     */
    record Weighted(SkinWeights weights, SkinningMethod method, List<MorphTarget> morphTargets,
                    SdefParameters sdef, float[] inverseBindMatrices) implements MeshSkin {
        public Weighted {
            Objects.requireNonNull(weights);
            Objects.requireNonNull(method);
            morphTargets = List.copyOf(morphTargets);
            Objects.requireNonNull(sdef);
            inverseBindMatrices = Objects.requireNonNull(inverseBindMatrices).clone();
            int vertices = weights.vertexCount();
            for (MorphTarget target : morphTargets) {
                if (target.vertexCount() != vertices)
                    throw new IllegalArgumentException("Morph target does not match the skin vertex count");
            }
            sdef.requireVertexCount(vertices);
            if (inverseBindMatrices.length != 0
                    && inverseBindMatrices.length != Math.multiplyExact(weights.boneCount(), 16)) {
                throw new IllegalArgumentException("Inverse bind matrices do not match skeleton bone count");
            }
            for (float v : inverseBindMatrices) {
                if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite inverse bind matrix");
            }
        }
        public Weighted(SkinWeights weights, SkinningMethod method) {
            this(weights, method, List.of(), SdefParameters.none(), new float[0]);
        }
        public Weighted(SkinWeights weights, SkinningMethod method, List<MorphTarget> morphTargets) {
            this(weights, method, morphTargets, SdefParameters.none(), new float[0]);
        }
        public Weighted(SkinWeights weights, SkinningMethod method, List<MorphTarget> morphTargets,
                        SdefParameters sdef) {
            this(weights, method, morphTargets, sdef, new float[0]);
        }
        public boolean hasInverseBindMatrices() { return inverseBindMatrices.length != 0; }
        @Override public float[] inverseBindMatrices() { return inverseBindMatrices.clone(); }
        public int vertexCount() { return weights.vertexCount(); }
        public int targetCount() { return morphTargets.size(); }
    }
}
