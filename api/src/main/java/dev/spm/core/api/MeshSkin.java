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
     */
    record Weighted(SkinWeights weights, SkinningMethod method, List<MorphTarget> morphTargets,
                    SdefParameters sdef) implements MeshSkin {
        public Weighted {
            Objects.requireNonNull(weights);
            Objects.requireNonNull(method);
            morphTargets = List.copyOf(morphTargets);
            Objects.requireNonNull(sdef);
            int vertices = weights.vertexCount();
            for (MorphTarget target : morphTargets) {
                if (target.vertexCount() != vertices)
                    throw new IllegalArgumentException("Morph target does not match the skin vertex count");
            }
            sdef.requireVertexCount(vertices);
        }
        public Weighted(SkinWeights weights, SkinningMethod method) {
            this(weights, method, List.of(), SdefParameters.none());
        }
        public Weighted(SkinWeights weights, SkinningMethod method, List<MorphTarget> morphTargets) {
            this(weights, method, morphTargets, SdefParameters.none());
        }
        public int vertexCount() { return weights.vertexCount(); }
        public int targetCount() { return morphTargets.size(); }
    }
}
