package dev.spm.core.render.cpu;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.api.*;

/** Always available: complete scalar implementation of the declared rigid render contract. */
public final class JavaComputeProvider implements CpuComputeProvider {
    @Override public String backend(){return "JAVA_SCALAR";}
    @Override public BoneMatrices evaluateBones(Skeleton skeleton,BonePose pose){return BoneEvaluator.evaluate(skeleton,pose);}
    @Override public VertexBatch renderVertices(VertexBatch vertices,BoneMatrices matrices){return JavaCpuRenderer.transform(vertices,matrices);}
}
