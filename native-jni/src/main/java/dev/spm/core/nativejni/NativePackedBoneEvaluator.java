package dev.spm.core.nativejni;

import dev.spm.core.acceleration.Isa;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Zero-copy packed bone evaluation against a compiled skeleton.
 *
 * <p>This is the real-time path. {@link NativeBoneEvaluator} extracts a logical
 * {@code BoneMatrices} object per frame, which means allocating and filling four Java arrays and
 * then reading them back; that is the right shape for a one-off query, and the wrong shape for a
 * render loop. This class instead compiles the skeleton once, then writes the host's packed
 * 144-byte-per-bone records straight into a caller-owned direct buffer, so a frame allocates
 * nothing and copies nothing.
 *
 * <p>Instances hold a native skeleton handle and must be closed. The handle is generation-encoded,
 * so a handle from a closed instance can never resolve to a later skeleton that reused its slot —
 * using one reports {@code InvalidHandle} rather than silently reading another model's matrices.
 *
 * <p>The record layout is {@link #PACKED_STRIDE} bytes per bone: a column-major {@code mat4}
 * transform, a column-major {@code mat4} normal matrix whose fourth row and column are
 * {@code (0,0,0,1)}, and one {@code vec4} tail. The tail is either four {@code float}s or four
 * {@code int}s, selected by {@link #FLOAT_TAIL}; the host's shader decides which it wants.
 */
public final class NativePackedBoneEvaluator implements AutoCloseable {
    /** Bytes per packed bone record: nine {@code vec4} slots. */
    public static final int PACKED_STRIDE = 144;
    /** Floats per bone in the caller's {@code boneParams} array. */
    public static final int PARAM_STRIDE = 12;
    /** Floats of root data: a 4x4 pose followed by a 3x3 normal matrix. */
    public static final int ROOT_FLOATS = 25;

    /** Tail is four {@code float}s: {@code [light, hidden, 0, 0]}. */
    public static final int FLOAT_TAIL = 1;
    /** Tail is four {@code int}s: {@code [light, hidden, 0, 0]}. */
    public static final int INT_TAIL = 2;

    /** Every bone is written with its own visibility. */
    public static final int PASS_ALL = 0;
    /** Only non-glow bones stay visible; glow bones are written hidden. */
    public static final int PASS_NON_GLOW = 1;
    /** Only glow bones stay visible; non-glow bones are written hidden. */
    public static final int PASS_GLOW = 2;

    private long handle;
    private final int boneCount;

    private NativePackedBoneEvaluator(long handle, int boneCount) {
        this.handle = handle;
        this.boneCount = boneCount;
    }

    /**
     * Compiles {@code parents}, {@code pivots} and {@code glow} into a native skeleton.
     *
     * @param parents one entry per bone: the parent index, or {@code -1} for a root
     * @param pivots three floats per bone
     * @param glow one entry per bone: {@code 0} normal, {@code 1} full-bright
     * @throws IllegalArgumentException if the arrays disagree in length or the topology is invalid
     */
    public static NativePackedBoneEvaluator compile(int[] parents, float[] pivots, int[] glow) {
        NativeTransforms.requireReady();
        Objects.requireNonNull(parents);
        Objects.requireNonNull(pivots);
        Objects.requireNonNull(glow);
        int n = parents.length;
        if (pivots.length != Math.multiplyExact(n, 3) || glow.length != n) {
            throw new IllegalArgumentException("Skeleton array length mismatch");
        }
        long handle = nCompileSkeleton(parents, pivots, glow);
        if (handle < 0) {
            throw new IllegalArgumentException("Native skeleton rejected, status=" + handle);
        }
        return new NativePackedBoneEvaluator(handle, n);
    }

    /** Bones in this skeleton. */
    public int boneCount() {
        return boneCount;
    }

    /** Bytes a packed evaluation writes: {@code boneCount * PACKED_STRIDE}. */
    public int packedBytes() {
        return Math.multiplyExact(boneCount, PACKED_STRIDE);
    }

    /** Floats a packed evaluation reads from {@code boneParams}. */
    public int paramFloats() {
        return Math.multiplyExact(boneCount, PARAM_STRIDE);
    }

    /**
     * Evaluates one frame into {@code out}.
     *
     * <p>Every buffer is validated before the native call: a wrong capacity, a heap buffer, a
     * read-only output or a non-little-endian view is rejected here rather than producing garbage
     * records. Nothing is written until the whole frame has been evaluated and checked finite, so a
     * rejected frame leaves {@code out} untouched.
     *
     * @param root 25 floats: a 4x4 pose followed by a 3x3 normal matrix
     * @param params {@code boneCount * PARAM_STRIDE} floats, the host's existing per-bone layout
     * @param out {@code boneCount * PACKED_STRIDE} writable bytes
     * @param state optional {@code boneCount * 4} floats for locator write-back, or {@code null}
     * @param flags tail encoding and pass filter, combined as {@code tail | (pass << 1)}
     * @param requested the instruction-set tier to ask for
     * @param persistentOutput true when {@code out} still holds the previous frame's records
     * @param packedLight the packed lightmap coordinate written into every non-glow record; glow
     *     bones always get the full-bright value
     * @return the tier whose instructions actually ran
     */
    public Isa evaluate(float[] root, float[] params, ByteBuffer out, ByteBuffer state,
                        int flags, Isa requested, boolean persistentOutput, int packedLight) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(params);
        Objects.requireNonNull(out);
        Objects.requireNonNull(requested);
        requireOpen();
        if (root.length != ROOT_FLOATS) {
            throw new IllegalArgumentException("Root must be " + ROOT_FLOATS + " floats");
        }
        if (params.length < paramFloats()) {
            throw new IllegalArgumentException("boneParams must hold at least " + paramFloats() + " floats");
        }
        if (!out.isDirect() || out.isReadOnly() || out.order() != ByteOrder.LITTLE_ENDIAN
                || out.capacity() < packedBytes()) {
            throw new IllegalArgumentException("Invalid packed output buffer");
        }
        if (state != null && (!state.isDirect() || state.isReadOnly()
                || state.order() != ByteOrder.LITTLE_ENDIAN
                || state.capacity() < Math.multiplyExact(boneCount, 16))) {
            throw new IllegalArgumentException("Invalid locator state buffer");
        }
        long outcome = nEvaluatePacked(handle, root, params, out, state, flags, requested.code(),
                persistentOutput, packedLight);
        if (outcome < 0) {
            throw new IllegalArgumentException("Native packed evaluation rejected, status=" + outcome);
        }
        if ((int) outcome != boneCount) {
            throw new IllegalStateException("Incomplete native packed result");
        }
        return Isa.fromCode((int) (outcome >>> 32));
    }

    /**
     * Evaluates one frame into {@code out}, allocating the root array's buffer from a float array.
     *
     * <p>Convenience overload for callers that keep the root pose in a Java array; it copies the
     * 25 floats into a small direct buffer, which is negligible next to the per-bone work.
     */
    public Isa evaluate(float[] root, float[] params, ByteBuffer out, int flags, Isa requested,
                        boolean persistentOutput, int packedLight) {
        return evaluate(root, params, out, null, flags, requested, persistentOutput, packedLight);
    }

    /** Live native skeletons, so a leak is observable without waiting for a GC. */
    public static long ownedSkeletons() {
        NativeTransforms.requireReady();
        return nOwnedSkeletons();
    }

    @Override
    public void close() {
        if (handle != 0) {
            long owned = handle;
            handle = 0;
            nFreeSkeleton(owned);
        }
    }

    private void requireOpen() {
        if (handle == 0) {
            throw new IllegalStateException("Packed bone evaluator is closed");
        }
    }

    private static native long nCompileSkeleton(int[] parents, float[] pivots, int[] glow);
    private static native int nFreeSkeleton(long handle);
    private static native long nOwnedSkeletons();
    private static native long nEvaluatePacked(long handle, float[] root, float[] params,
                                               ByteBuffer out, ByteBuffer state, int flags,
                                               int isa, boolean persistentOutput, int packedLight);
}
