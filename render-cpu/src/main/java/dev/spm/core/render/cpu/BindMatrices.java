package dev.spm.core.render.cpu;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;

/**
 * Bind-pose world transforms and their inverses. Skinning always composes a pose transform with the
 * <em>inverse</em> of the same bone's bind transform, so a model whose bind pose is not the identity
 * still skins from its authored rest shape.
 */
public final class BindMatrices {
    private BindMatrices() {}

    /** Bind-pose world matrices: exactly the bone evaluation of the document bind pose. */
    public static BoneMatrices evaluate(Skeleton skeleton, BonePose bindPose) {
        return BoneEvaluator.evaluate(skeleton, bindPose);
    }

    /**
     * Inverse of one bind transform, or {@code null} when that bone has no bind transform at all
     * (a bind pose that zero-scales or hides the bone's parent chain). Callers that reference such a
     * bone must refuse rather than skin against the identity.
     */
    public static float[] inverse(BoneMatrices bind, int bone) {
        float[] transform = new float[16];
        for (int element = 0; element < 16; element++) transform[element] = bind.matrix(bone, element);
        return SkinMath.invertAffine(transform);
    }
}
