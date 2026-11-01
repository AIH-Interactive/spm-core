package dev.spm.core.runtime;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.Mesh;
import dev.spm.core.render.cpu.BindMatrices;
import dev.spm.core.render.cpu.SkinningRenderer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Weighted frame composition: rigid meshes keep the pre-existing provider path, weighted meshes are
 * skinned in the Core scalar skinner, and every mesh shares one visibility remap, UV copy and
 * triangle filter so indices, UVs and materials stay aligned.
 *
 * <p>All retained and working bytes are charged to the caller's {@link ResourceBudget} before any
 * transformation starts, and every failure path releases them, so a refused frame publishes nothing
 * and leaves {@code budget.used()} at its previous value.</p>
 */
final class WeightedFrameRenderer {
    private WeightedFrameRenderer() {}

    static CpuFrame render(ModelDocument document, BonePose pose, SkinningRig rig, SkinState state,
                           CpuComputeProvider provider, ResourceBudget budget, CancellationToken token,
                           ModelAsset.Lease lease) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(pose);
        Objects.requireNonNull(rig);
        Objects.requireNonNull(state);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(token);
        token.check();
        state.validate(rig);

        long working = workingBytes(document, rig);
        ResourceBudget.Reservation reservation = budget.reserve(working);
        try {
            BoneMatrices matrices = provider.evaluateBones(document.skeleton(), pose);
            if (matrices.boneCount() != document.skeleton().boneCount())
                throw new IllegalArgumentException("Provider bone count mismatch");
            BoneMatrices bind = null;
            boolean[] visible = matrices.visible();
            var meshes = new ArrayList<CpuFrame.Mesh>();
            for (Mesh mesh : document.meshes()) {
                token.check();
                VertexBatch original = mesh.vertices();
                int[] sourceBones = original.boneIndices();
                int[] remap = new int[sourceBones.length];
                Arrays.fill(remap, -1);
                int count = 0;
                MeshSkin skin = rig.skin(mesh.name());
                boolean weighted = skin instanceof MeshSkin.Weighted;
                for (int i = 0; i < sourceBones.length; i++) {
                    if ((i & 1023) == 0) token.check();
                    if (!weighted) {
                        if (visible[sourceBones[i]]) remap[i] = count++;
                    } else if (visibleWeight(original, (MeshSkin.Weighted) skin, visible, i)) remap[i] = count++;
                }
                VertexBatch output;
                if (weighted) {
                    // The bind pose is evaluated at most once and only when a weighted mesh exists.
                    if (bind == null) bind = BindMatrices.evaluate(document.skeleton(), document.bindPose());
                    output = SkinningRenderer.transform(original, (MeshSkin.Weighted) skin, matrices, bind,
                            state.morph(mesh.name(), ((MeshSkin.Weighted) skin).targetCount()));
                } else {
                    output = provider.renderVertices(original, matrices);
                }
                if (output.vertexCount() != count)
                    throw new IllegalArgumentException("Provider visible vertex count mismatch");
                int[] outputBones = output.boneIndices();
                float[] sourceUv = mesh.uvs(), uv = new float[Math.multiplyExact(count, 2)];
                for (int i = 0; i < remap.length; i++) if (remap[i] >= 0) {
                    int out = remap[i];
                    if (!weighted && outputBones[out] != sourceBones[i])
                        throw new IllegalArgumentException("Provider vertex order mismatch");
                    uv[out * 2] = sourceUv[i * 2];
                    uv[out * 2 + 1] = sourceUv[i * 2 + 1];
                }
                int[] sourceIndices = mesh.indices(), indices = new int[sourceIndices.length];
                int used = 0;
                for (int i = 0; i < sourceIndices.length; i += 3) {
                    if ((i & 1023) == 0) token.check();
                    int a = remap[sourceIndices[i]], b = remap[sourceIndices[i + 1]], c = remap[sourceIndices[i + 2]];
                    if (a >= 0 && b >= 0 && c >= 0) { indices[used++] = a; indices[used++] = b; indices[used++] = c; }
                }
                meshes.add(new CpuFrame.Mesh(mesh.name(), output, uv, Arrays.copyOf(indices, used), mesh.materialIndex()));
            }
            token.check();
            return new CpuFrame(document.identity(), provider.backend(), meshes, reservation, lease);
        } catch (RuntimeException | Error failure) {
            reservation.close();
            throw failure;
        }
    }

    /** A weighted vertex survives when at least one of its influencing bones is visible. */
    private static boolean visibleWeight(VertexBatch batch, MeshSkin.Weighted skin, boolean[] visible, int vertex) {
        Objects.checkIndex(vertex, batch.vertexCount());
        SkinWeights weights = skin.weights();
        for (int slot = 0, used = weights.influenceCount(vertex); slot < used; slot++)
            if (visible[weights.boneIndex(vertex, slot)] && weights.weight(vertex, slot) != 0) return true;
        return false;
    }

    /**
     * Working bytes: the pre-existing per-bone/per-vertex charge, plus the weighted extra — skin
     * transforms, morph scratch and per-influence addressing — so a weighted frame is never charged
     * only the rigid cost.
     */
    private static long workingBytes(ModelDocument document, SkinningRig rig) {
        long working = Math.multiplyExact((long) document.skeleton().boneCount(), 1024);
        for (Mesh mesh : document.meshes()) {
            long vertices = mesh.vertices().vertexCount();
            working = Math.addExact(working,
                    Math.addExact(Math.multiplyExact(vertices, 256), Math.multiplyExact((long) mesh.indices().length, 8)));
            if (rig.skin(mesh.name()) instanceof MeshSkin.Weighted weighted) {
                // Per influence slot: packed weights, the skin transform pair and the quaternion pair.
                long slots = Math.multiplyExact(vertices, SkinWeights.MAX_INFLUENCES);
                working = Math.addExact(working, Math.multiplyExact(slots, 24L));
                working = Math.addExact(working, Math.multiplyExact((long) document.skeleton().boneCount(), 200L));
                working = Math.addExact(working, Math.multiplyExact(vertices, 32L));
                working = Math.addExact(working, Math.multiplyExact((long) weighted.targetCount(), Math.multiplyExact(vertices, 24L)));
            }
        }
        return working;
    }
}
