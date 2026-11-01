package dev.spm.core.render.cpu;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.MeshSkin;
import dev.spm.core.api.MorphWeights;
import dev.spm.core.api.SdefParameters;
import dev.spm.core.api.SkinWeights;
import dev.spm.core.api.SkinningMethod;
import dev.spm.core.api.VertexBatch;
import java.util.Objects;

/**
 * Scalar weighted skinning: linear blend (BDEF), spherical blend (SDEF) and dual-quaternion blend
 * (QDEF), with additive morph deltas applied before any skin transform.
 *
 * <p>Every skin transform is {@code pose * inverseBind}, so a document whose bind pose is not the
 * identity skins from its authored rest shape rather than from world space. Normals use the exact
 * inverse transpose of that same skin transform.</p>
 *
 * <p>Visibility is a per-part cull: a weighted vertex is emitted when at least one of its influencing
 * bones is visible, which reduces exactly to the pre-existing rigid rule for single-influence
 * vertices. Invisible influences are dropped and the remaining weights are renormalized, so a hidden
 * bone cannot keep dragging the vertices it partially owns.</p>
 */
public final class SkinningRenderer {
    /** Below this length a blended quaternion has no reliable rotation left to extract. */
    static final double MIN_QUATERNION_LENGTH = 1.0e-6;

    private SkinningRenderer() {}

    /** Skins one mesh. Every weight, index and method precondition is checked before any output. */
    public static VertexBatch transform(VertexBatch batch, MeshSkin.Weighted skin, BoneMatrices pose,
                                        BoneMatrices bind, MorphWeights morphWeights) {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(skin);
        Objects.requireNonNull(pose);
        Objects.requireNonNull(bind);
        Objects.requireNonNull(morphWeights);
        SkinWeights weights = skin.weights();
        int vertexCount = batch.vertexCount();
        int boneCount = pose.boneCount();
        if (weights.vertexCount() != vertexCount)
            throw new IllegalArgumentException("Skin weights do not match the mesh vertex count");
        if (weights.boneCount() != boneCount || bind.boneCount() != boneCount)
            throw new IllegalArgumentException("Skin weights do not match the skeleton");
        morphWeights.requireCount(skin.morphTargets().size());
        skin.sdef().requireVertexCount(vertexCount);
        if (skin.method() == SkinningMethod.SDEF && !skin.sdef().hasCentres())
            throw new IllegalArgumentException("SDEF requires authored sphere centres");

        // Resolve and validate every referenced bone before allocating output, so a failure here
        // cannot leave a partially written batch behind.
        SkinTransforms transforms = new SkinTransforms(pose, bind, weights, skin.method());
        for (int bone : batch.boneIndices())
            if (bone < 0 || bone >= boneCount) throw new IllegalArgumentException("Vertex bone index out of range");

        float[] morphPositions = MorphApplicator.positions(skin.morphTargets(), morphWeights, vertexCount);
        float[] morphNormals = MorphApplicator.normals(skin.morphTargets(), morphWeights, vertexCount);
        float[] bindPositions = batch.positions(), bindNormals = batch.normals();
        boolean[] visible = pose.visible();
        int[] boneGlow = pose.glow(), boneColors = pose.colorsRgba(), vertexColors = batch.colorsRgba();

        int emitted = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++)
            if (visibleInfluence(weights, visible, vertex)) emitted++;
        float[] positions = new float[Math.multiplyExact(emitted, 3)];
        float[] normals = new float[positions.length];
        int[] bones = new int[emitted], glow = new int[emitted], colors = new int[emitted];

        int output = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            if (!visibleInfluence(weights, visible, vertex)) continue;
            double sum = 0;
            for (int slot = 0, used = weights.influenceCount(vertex); slot < used; slot++)
                if (visible[weights.boneIndex(vertex, slot)]) sum += weights.weight(vertex, slot);
            double[] position = {bindPositions[vertex * 3] + morphPositions[vertex * 3],
                    bindPositions[vertex * 3 + 1] + morphPositions[vertex * 3 + 1],
                    bindPositions[vertex * 3 + 2] + morphPositions[vertex * 3 + 2]};
            double[] normal = {bindNormals[vertex * 3], bindNormals[vertex * 3 + 1], bindNormals[vertex * 3 + 2]};
            if (morphNormals != null) {
                normal[0] += morphNormals[vertex * 3];
                normal[1] += morphNormals[vertex * 3 + 1];
                normal[2] += morphNormals[vertex * 3 + 2];
            }
            double[] skinned;
            double[] direction;
            switch (skin.method()) {
                case BDEF -> {
                    skinned = linearBlend(transforms, weights, visible, vertex, position, sum);
                    direction = linearBlendNormal(transforms, weights, visible, vertex, normal, sum);
                }
                case QDEF -> {
                    DualQuaternion blend = dualQuaternion(transforms, weights, visible, vertex, sum);
                    skinned = blend.apply(position);
                    direction = SkinMath.rotate(blend.rotation, normal[0], normal[1], normal[2]);
                }
                case SDEF -> {
                    skinned = sphericalBlend(transforms, weights, visible, vertex, position, sum, skin.sdef());
                    direction = SkinMath.rotate(blendedRotation(transforms, weights, visible, vertex, sum),
                            normal[0], normal[1], normal[2]);
                }
                default -> throw new IllegalStateException("Unsupported skinning method: " + skin.method());
            }
            int dst = output * 3;
            for (int axis = 0; axis < 3; axis++) positions[dst + axis] = SkinMath.finite(skinned[axis]);
            double length = Math.hypot(Math.hypot(direction[0], direction[1]), direction[2]);
            if (!Double.isFinite(length)) throw new IllegalArgumentException("Unrepresentable skinned normal");
            if (length != 0)
                for (int axis = 0; axis < 3; axis++) normals[dst + axis] = SkinMath.finite(direction[axis] / length);
            int dominant = dominantVisible(weights, visible, vertex);
            bones[output] = dominant;
            glow[output] = boneGlow[dominant];
            colors[output] = tint(vertexColors[vertex], boneColors[dominant]);
            output++;
        }
        return new VertexBatch(positions, normals, bones, glow, colors);
    }

    /** Linear blend skinning: {@code sum(w * (pose * inverseBind) * position)}. */
    private static double[] linearBlend(SkinTransforms t, SkinWeights w, boolean[] visible, int vertex,
                                        double[] position, double sum) {
        double[] result = new double[3];
        for (int slot = 0, used = w.influenceCount(vertex); slot < used; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            double weight = w.weight(vertex, slot) / sum;
            for (int row = 0; row < 3; row++) {
                double value = t.matrix(bone, 12 + row);
                for (int axis = 0; axis < 3; axis++) value += (double) t.matrix(bone, axis * 4 + row) * position[axis];
                result[row] += weight * value;
            }
        }
        return result;
    }

    /** Inverse transpose of each skin transform, blended and then normalized by the caller. */
    private static double[] linearBlendNormal(SkinTransforms t, SkinWeights w, boolean[] visible, int vertex,
                                              double[] normal, double sum) {
        double[] result = new double[3];
        for (int slot = 0, used = w.influenceCount(vertex); slot < used; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            double weight = w.weight(vertex, slot) / sum;
            for (int row = 0; row < 3; row++) {
                double value = 0;
                for (int axis = 0; axis < 3; axis++) value += (double) t.normal(bone, axis * 3 + row) * normal[axis];
                result[row] += weight * value;
            }
        }
        return result;
    }

    /**
     * Dual-quaternion linear blend with shortest-path sign correction against the first visible
     * influence. The real and dual parts are scaled by the same normalization factor, and the
     * translation is recovered as {@code 2 * vector(d * conj(q))}, which is exact for a single
     * influence and the standard chordal approximation for a blend.
     *
     * <p>The blend carries rotation and translation only. Scale in a bone's skin transform is not
     * representable by a unit dual quaternion and is therefore ignored, which is the documented
     * difference from BDEF. A blend whose quaternion sum cancels — two equal influences a half turn
     * apart, for example — has no rotation left to extract and is refused explicitly rather than
     * publishing an arbitrary orientation.</p>
     */
    private static DualQuaternion dualQuaternion(SkinTransforms t, SkinWeights w, boolean[] visible, int vertex,
                                                 double sum) {
        double[] reference = null;
        for (int slot = 0, used = w.influenceCount(vertex); slot < used && reference == null; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (visible[bone] && w.weight(vertex, slot) != 0) reference = t.quaternion(bone);
        }
        if (reference == null) throw new IllegalStateException("Visible skin influence without a rotation");
        double[] rotation = new double[4];
        double[] dual = new double[4];
        for (int slot = 0, used = w.influenceCount(vertex); slot < used; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            double weight = w.weight(vertex, slot) / sum;
            double[] quaternion = t.quaternion(bone);
            double sign = dot(quaternion, reference) < 0 ? -1 : 1;
            double[] boneDual = t.dual(bone);
            for (int k = 0; k < 4; k++) {
                rotation[k] += sign * weight * quaternion[k];
                dual[k] += sign * weight * boneDual[k];
            }
        }
        double length = Math.sqrt(dot(rotation, rotation));
        if (!(length > MIN_QUATERNION_LENGTH) || !Double.isFinite(length))
            throw new IllegalArgumentException("Degenerate dual quaternion blend");
        for (int k = 0; k < 4; k++) { rotation[k] /= length; dual[k] /= length; }
        double[] conjugate = {rotation[0], -rotation[1], -rotation[2], -rotation[3]};
        double[] product = SkinMath.multiplyQuaternion(dual, conjugate);
        double[] translation = {2 * product[1], 2 * product[2], 2 * product[3]};
        for (double value : translation)
            if (!Double.isFinite(value))
                throw new IllegalArgumentException("Unrepresentable dual quaternion translation");
        return new DualQuaternion(rotation, translation);
    }

    /**
     * Spherical blend skinning: the sphere centre is carried by the full affine skin transforms while
     * the offset from the centre to the vertex is only rotated, so the sphere keeps its radius under
     * twisting where BDEF would collapse it.
     *
     * <p>A centre on the vertex makes the offset vanish, so a zero offset reduces SDEF to a pure
     * rotational blend around that vertex; the authored centres are what buy the volume behaviour.
     * The authored radii are never applied — see {@link SdefParameters} for why.</p>
     */
    private static double[] sphericalBlend(SkinTransforms t, SkinWeights w, boolean[] visible, int vertex,
                                           double[] position, double sum, SdefParameters sdef) {
        double[] centre = {sdef.centre(vertex, 0), sdef.centre(vertex, 1), sdef.centre(vertex, 2)};
        double[] carried = new double[3];
        for (int slot = 0, used = w.influenceCount(vertex); slot < used; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            double weight = w.weight(vertex, slot) / sum;
            for (int row = 0; row < 3; row++) {
                double value = t.matrix(bone, 12 + row);
                for (int axis = 0; axis < 3; axis++) value += (double) t.matrix(bone, axis * 4 + row) * centre[axis];
                carried[row] += weight * value;
            }
        }
        double[] offset = SkinMath.rotate(blendedRotation(t, w, visible, vertex, sum),
                position[0] - centre[0], position[1] - centre[1], position[2] - centre[2]);
        return new double[] {carried[0] + offset[0], carried[1] + offset[1], carried[2] + offset[2]};
    }

    /** Normalized blended rotation with shortest-path sign correction against the first influence. */
    private static double[] blendedRotation(SkinTransforms t, SkinWeights w, boolean[] visible, int vertex,
                                            double sum) {
        double[] reference = null;
        for (int slot = 0, used = w.influenceCount(vertex); slot < used && reference == null; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (visible[bone] && w.weight(vertex, slot) != 0) reference = t.quaternion(bone);
        }
        if (reference == null) throw new IllegalStateException("Visible skin influence without a rotation");
        double[] rotation = new double[4];
        for (int slot = 0, used = w.influenceCount(vertex); slot < used; slot++) {
            int bone = w.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            double weight = w.weight(vertex, slot) / sum;
            double[] quaternion = t.quaternion(bone);
            double sign = dot(quaternion, reference) < 0 ? -1 : 1;
            for (int k = 0; k < 4; k++) rotation[k] += sign * weight * quaternion[k];
        }
        double length = Math.sqrt(dot(rotation, rotation));
        if (!(length > MIN_QUATERNION_LENGTH) || !Double.isFinite(length))
            throw new IllegalArgumentException("Degenerate blended rotation");
        for (int k = 0; k < 4; k++) rotation[k] /= length;
        return rotation;
    }

    private static double dot(double[] a, double[] b) {
        double value = 0;
        for (int k = 0; k < 4; k++) value += a[k] * b[k];
        return value;
    }

    private static boolean visibleInfluence(SkinWeights weights, boolean[] visible, int vertex) {
        for (int slot = 0, used = weights.influenceCount(vertex); slot < used; slot++)
            if (visible[weights.boneIndex(vertex, slot)] && weights.weight(vertex, slot) != 0) return true;
        return false;
    }

    private static int dominantVisible(SkinWeights weights, boolean[] visible, int vertex) {
        int best = -1;
        float bestWeight = -1;
        for (int slot = 0, used = weights.influenceCount(vertex); slot < used; slot++) {
            int bone = weights.boneIndex(vertex, slot);
            if (!visible[bone]) continue;
            float weight = weights.weight(vertex, slot);
            if (weight > bestWeight) { bestWeight = weight; best = bone; }
        }
        if (best < 0) throw new IllegalStateException("Visible skin vertex without a visible influence");
        return best;
    }

    /** Logical normalized RGBA multiplication, rounded to the nearest 8-bit channel. */
    private static int tint(int vertex, int bone) {
        if (vertex == 0xffffffff) return bone;
        if (bone == 0xffffffff) return vertex;
        int color = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int value = (((vertex >>> shift) & 255) * ((bone >>> shift) & 255) + 127) / 255;
            color |= value << shift;
        }
        return color;
    }

    /** Rigid part of a blended dual quaternion: rotate then translate. */
    private record DualQuaternion(double[] rotation, double[] translation) {
        double[] apply(double[] point) {
            double[] rotated = SkinMath.rotate(rotation, point[0], point[1], point[2]);
            return new double[] {rotated[0] + translation[0], rotated[1] + translation[1],
                    rotated[2] + translation[2]};
        }
    }

    /**
     * Per-bone {@code pose * inverseBind} skin transforms, their inverse transposes, and — only for the
     * methods that need it — the rigid rotation/dual-quaternion decomposition, addressed by bone index.
     *
     * <p>The quaternion decomposition is skipped for BDEF on purpose: linear blend skinning is
     * defined for mirrored and non-uniformly scaled transforms, and decomposing those into a rotation
     * would refuse inputs BDEF must accept.</p>
     *
     * <p>A bone the bind pose never emitted has no usable transform and is refused exactly when a
     * vertex references it.</p>
     */
    private static final class SkinTransforms {
        private final float[] skin;
        private final float[] skinNormal;
        private final boolean[] usable;
        private final double[][] quaternion;
        private final double[][] dual;

        SkinTransforms(BoneMatrices pose, BoneMatrices bind, SkinWeights weights, SkinningMethod method) {
            int boneCount = pose.boneCount();
            boolean rigid = method != SkinningMethod.BDEF;
            skin = new float[Math.multiplyExact(boneCount, 16)];
            skinNormal = new float[Math.multiplyExact(boneCount, 9)];
            usable = new boolean[boneCount];
            quaternion = rigid ? new double[boneCount][] : null;
            dual = rigid ? new double[boneCount][] : null;
            boolean[] needed = new boolean[boneCount];
            for (int vertex = 0; vertex < weights.vertexCount(); vertex++)
                for (int slot = 0, used = weights.influenceCount(vertex); slot < used; slot++)
                    needed[weights.boneIndex(vertex, slot)] = true;
            for (int bone = 0; bone < boneCount; bone++) {
                if (!needed[bone]) continue;
                float[] bindTransform = new float[16];
                for (int element = 0; element < 16; element++) bindTransform[element] = bind.matrix(bone, element);
                float[] inverse = SkinMath.invertAffine(bindTransform);
                if (inverse == null) continue;
                float[] poseTransform = new float[16];
                for (int element = 0; element < 16; element++) poseTransform[element] = pose.matrix(bone, element);
                float[] product = SkinMath.multiply(poseTransform, inverse);
                System.arraycopy(product, 0, skin, bone * 16, 16);
                System.arraycopy(SkinMath.normalOf(product), 0, skinNormal, bone * 9, 9);
                if (rigid) {
                    double[] rotation = SkinMath.quaternion(product);
                    quaternion[bone] = rotation;
                    double[] vector = {0, product[12], product[13], product[14]};
                    double[] product2 = SkinMath.multiplyQuaternion(vector, rotation);
                    dual[bone] = new double[] {0.5 * product2[0], 0.5 * product2[1],
                            0.5 * product2[2], 0.5 * product2[3]};
                }
                usable[bone] = true;
            }
        }

        float matrix(int bone, int element) {
            requireUsable(bone);
            return skin[bone * 16 + element];
        }

        float normal(int bone, int element) {
            requireUsable(bone);
            return skinNormal[bone * 9 + element];
        }

        double[] quaternion(int bone) {
            requireUsable(bone);
            return quaternion[bone];
        }

        double[] dual(int bone) {
            requireUsable(bone);
            return dual[bone];
        }

        private void requireUsable(int bone) {
            if (!usable[bone]) throw new IllegalArgumentException("Skin bone has no bind transform");
        }
    }
}
