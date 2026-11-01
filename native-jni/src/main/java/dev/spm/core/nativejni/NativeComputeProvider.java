package dev.spm.core.nativejni;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.VertexBatch;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Explicitly loaded Rust computation for the pure CPU frame pipeline. The selected instruction set
 * is fixed for this instance; Java's native-free provider remains the pipeline default.
 */
public final class NativeComputeProvider implements CpuComputeProvider {
    private final Isa isa;

    private NativeComputeProvider(Isa isa) { this.isa = isa; }

    /**
     * Verifies the caller's expected library digest and JNI ABI before creating a provider.
     * AUTO follows the native conservative selection order; FMA requires an explicit request.
     * Unsupported forced instruction sets are rejected without a silent fallback.
     */
    public static NativeComputeProvider load(Path library, String sha256, Isa requested) throws IOException {
        Objects.requireNonNull(requested);
        var nativeApi = NativeTransforms.load(library, sha256);
        if (requested != Isa.AUTO) {
            if (!nativeApi.supported(requested)) throw new IllegalArgumentException("Unsupported native instruction set: " + requested);
            return new NativeComputeProvider(requested);
        }
        for (Isa candidate : new Isa[]{Isa.AVX2, Isa.AVX, Isa.SSE2, Isa.NEON, Isa.SCALAR}) {
            if (nativeApi.supported(candidate)) return new NativeComputeProvider(candidate);
        }
        throw new IllegalStateException("Native provider has no supported instruction set");
    }

    /** Actual fixed instruction set used by both bone and rigid vertex computation. */
    public Isa isa() { return isa; }

    @Override public String backend() { return "RUST_" + isa.name(); }

    @Override public BoneMatrices evaluateBones(Skeleton skeleton, BonePose pose) {
        return NativeBoneEvaluator.evaluate(Objects.requireNonNull(skeleton), Objects.requireNonNull(pose),
                new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}, isa);
    }

    @Override public VertexBatch renderVertices(VertexBatch vertices, BoneMatrices matrices) {
        return NativeRigidRenderer.transform(vertices, matrices, isa);
    }
}
