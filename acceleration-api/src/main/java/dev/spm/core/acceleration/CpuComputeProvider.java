package dev.spm.core.acceleration;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.VertexBatch;

/** Pure synchronous batch computation. Implementations retain no caller buffers or Host objects. */
public interface CpuComputeProvider {
    String backend();
    BoneMatrices evaluateBones(Skeleton skeleton,BonePose pose);
    VertexBatch renderVertices(VertexBatch vertices,BoneMatrices matrices);
}
