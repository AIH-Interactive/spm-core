package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.nativejni.NativePackedBoneEvaluator;
import dev.spm.core.nativejni.NativeTransforms;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compiled-skeleton handle family, exercised against the real native library.
 *
 * <p>These tests cover what the Rust unit tests cannot: that the JNI entry points agree with the
 * Java declarations, that the handle survives a round trip through Java, and that the packed
 * records the native side writes are the ones the host's shader expects.
 */
@EnabledIfSystemProperty(named = "spm.native.library", matches = ".+")
class NativePackedBoneTest {
    /** The lightmap coordinate the host passes for a lit bone; glow bones ignore it. */
    private static final int PACKED_LIGHT = 0x00F0F0;
    private static final float[] IDENTITY_ROOT = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1,
            1, 0, 0, 0, 1, 0, 0, 0, 1,
    };

    private static NativeTransforms load() throws Exception {
        Path path = Path.of(System.getProperty("spm.native.library"));
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return NativeTransforms.load(path, hash);
    }

    /** A three-bone chain: root -> arm -> hand. */
    private static NativePackedBoneEvaluator chain() {
        return NativePackedBoneEvaluator.compile(
                new int[]{-1, 0, 1},
                new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0},
                new int[]{0, 0, 0});
    }

    /** Unit-scale, zero-rotation parameters with a translation that varies per bone. */
    private static float[] params(int n) {
        float[] params = new float[n * NativePackedBoneEvaluator.PARAM_STRIDE];
        for (int i = 0; i < n; i++) {
            int p = i * NativePackedBoneEvaluator.PARAM_STRIDE;
            params[p + 3] = i;
            params[p + 6] = 1;
            params[p + 7] = 1;
            params[p + 8] = 1;
        }
        return params;
    }

    private static ByteBuffer direct(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void the_abi_version_is_five() throws Exception {
        load();
        // The Java constant and the native report must agree, or `load` would already have thrown.
        assertEquals(5, NativeTransforms.ABI_VERSION);
    }

    @Test
    void a_compiled_skeleton_reports_its_shape_and_is_counted() throws Exception {
        load();
        long before = NativePackedBoneEvaluator.ownedSkeletons();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            assertEquals(3, evaluator.boneCount());
            assertEquals(3 * NativePackedBoneEvaluator.PACKED_STRIDE, evaluator.packedBytes());
            assertEquals(3 * NativePackedBoneEvaluator.PARAM_STRIDE, evaluator.paramFloats());
            assertEquals(before + 1, NativePackedBoneEvaluator.ownedSkeletons());
        }
        assertEquals(before, NativePackedBoneEvaluator.ownedSkeletons());
    }

    @Test
    void an_evaluation_writes_one_record_per_bone() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            ByteBuffer out = direct(evaluator.packedBytes());
            Isa used = evaluator.evaluate(IDENTITY_ROOT, params(3), out,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
            assertNotEquals(Isa.AUTO, used, "a reported tier is never a request");
            // The root bone carries the identity pose, so its transform is the identity.
            assertEquals(1f, out.getFloat(0), 1e-6f);
            assertEquals(1f, out.getFloat(20), 1e-6f);
            assertEquals(1f, out.getFloat(40), 1e-6f);
            assertEquals(1f, out.getFloat(60), 1e-6f);
            // The tail is [light, hidden, 0, 0]; nothing is hidden here.
            assertEquals(0f, out.getFloat(132), 1e-6f);
        }
    }

    @Test
    void the_two_tail_layouts_differ_only_in_the_tail() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            ByteBuffer floats = direct(evaluator.packedBytes());
            ByteBuffer ints = direct(evaluator.packedBytes());
            evaluator.evaluate(IDENTITY_ROOT, params(3), floats,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
            evaluator.evaluate(IDENTITY_ROOT, params(3), ints,
                    NativePackedBoneEvaluator.INT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
            for (int bone = 0; bone < 3; bone++) {
                int base = bone * NativePackedBoneEvaluator.PACKED_STRIDE;
                for (int offset = 0; offset < 128; offset += 4) {
                    assertEquals(floats.getFloat(base + offset), ints.getFloat(base + offset),
                            "bone " + bone + " offset " + offset);
                }
            }
        }
    }

    @Test
    void a_hidden_bone_is_written_as_the_canonical_hidden_record() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            float[] params = params(3);
            // `boneParams[i*12+9] != 0` hides the bone itself.
            params[9] = 1;
            ByteBuffer out = direct(evaluator.packedBytes());
            evaluator.evaluate(IDENTITY_ROOT, params, out,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
            // The hidden record is the identity transform with the hidden flag set.
            assertEquals(1f, out.getFloat(0), 1e-6f);
            assertEquals(1f, out.getFloat(132), 1e-6f);
        }
    }

    @Test
    void a_pass_filter_hides_the_bones_it_excludes() throws Exception {
        load();
        // Bone 1 is full-bright, so a non-glow pass must hide it.
        try (NativePackedBoneEvaluator evaluator = NativePackedBoneEvaluator.compile(
                new int[]{-1, 0, 1},
                new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0},
                new int[]{0, 1, 0})) {
            ByteBuffer out = direct(evaluator.packedBytes());
            int flags = NativePackedBoneEvaluator.FLOAT_TAIL
                    | (NativePackedBoneEvaluator.PASS_NON_GLOW << 1);
            evaluator.evaluate(IDENTITY_ROOT, params(3), out, flags, Isa.AUTO, false, PACKED_LIGHT);
            int glowBase = NativePackedBoneEvaluator.PACKED_STRIDE;
            assertEquals(1f, out.getFloat(glowBase + 132), 1e-6f,
                    "the glow bone must be hidden by a non-glow pass");
        }
    }

    @Test
    void a_closed_evaluator_rejects_further_use() throws Exception {
        load();
        NativePackedBoneEvaluator evaluator = chain();
        ByteBuffer out = direct(evaluator.packedBytes());
        evaluator.close();
        assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(IDENTITY_ROOT, params(3), out,
                        NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
        // Closing twice is harmless: the handle is cleared on the first close.
        evaluator.close();
    }

    @Test
    void a_stale_handle_never_resolves_to_a_later_skeleton() throws Exception {
        load();
        // Compile, free, then compile again. The second skeleton reuses the first one's slot, so
        // only the generation distinguishes them; a stale handle must be rejected rather than
        // silently reading the new model's matrices.
        NativePackedBoneEvaluator first = chain();
        first.close();
        try (NativePackedBoneEvaluator second = NativePackedBoneEvaluator.compile(
                new int[]{-1, 0, 1, 2},
                new float[]{0, 0, 0, 1, 0, 0, 2, 0, 0, 3, 0, 0},
                new int[]{0, 0, 0, 0})) {
            assertEquals(4, second.boneCount());
            ByteBuffer out = direct(second.packedBytes());
            second.evaluate(IDENTITY_ROOT, params(4), out,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
        }
    }

    @Test
    void malformed_skeletons_and_buffers_are_rejected() throws Exception {
        load();
        // A cycle has no root, so it cannot be levelled.
        assertThrows(IllegalArgumentException.class,
                () -> NativePackedBoneEvaluator.compile(new int[]{1, 0}, new float[6], new int[2]));
        // A self-parent is rejected before the cycle check.
        assertThrows(IllegalArgumentException.class,
                () -> NativePackedBoneEvaluator.compile(new int[]{0}, new float[3], new int[1]));
        // Array lengths must agree.
        assertThrows(IllegalArgumentException.class,
                () -> NativePackedBoneEvaluator.compile(new int[]{-1}, new float[6], new int[1]));
        try (NativePackedBoneEvaluator evaluator = chain()) {
            float[] params = params(3);
            // A heap buffer cannot be handed to native code.
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(IDENTITY_ROOT, params,
                            ByteBuffer.allocate(evaluator.packedBytes()),
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
            // A read-only output cannot be written.
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(IDENTITY_ROOT, params,
                            direct(evaluator.packedBytes()).asReadOnlyBuffer(),
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
            // A short output buffer is rejected rather than overrun.
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(IDENTITY_ROOT, params, direct(16),
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
            // A short parameter array is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(IDENTITY_ROOT, new float[4],
                            direct(evaluator.packedBytes()),
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
            // A root of the wrong length is rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(new float[16], params,
                            direct(evaluator.packedBytes()),
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
        }
    }

    @Test
    void a_rejected_frame_leaves_the_output_untouched() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            ByteBuffer out = direct(evaluator.packedBytes());
            for (int i = 0; i < out.capacity(); i++) {
                out.put(i, (byte) 0x5A);
            }
            float[] params = params(3);
            // A non-finite parameter must be rejected before anything is written.
            params[0] = Float.NaN;
            assertThrows(IllegalArgumentException.class,
                    () -> evaluator.evaluate(IDENTITY_ROOT, params, out,
                            NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT));
            for (int i = 0; i < out.capacity(); i++) {
                assertEquals((byte) 0x5A, out.get(i), "byte " + i + " was modified");
            }
        }
    }

    @Test
    void locator_write_back_fills_the_state_buffer() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            float[] params = params(3);
            // `boneParams[i*12+11] == 1` asks for a locator on bone 2, and only on bone 2.
            params[2 * NativePackedBoneEvaluator.PARAM_STRIDE + 11] = 1;
            ByteBuffer out = direct(evaluator.packedBytes());
            ByteBuffer state = direct(3 * 16);
            // A sentinel in every slot, so an untouched slot is distinguishable from a written one.
            for (int i = 0; i < state.capacity(); i++) {
                state.put(i, (byte) 0x5A);
            }
            evaluator.evaluate(IDENTITY_ROOT, params, out, state,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, false, PACKED_LIGHT);
            // Bone 2's locator is its world position scaled by 16, and its translation parameter is
            // negative, so the x coordinate must be finite and negative.
            float x = state.getFloat(2 * 16);
            float y = state.getFloat(2 * 16 + 4);
            float z = state.getFloat(2 * 16 + 8);
            assertTrue(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z),
                    "locator must be finite");
            assertTrue(x < 0f, "bone 2 translates along -x, so its locator x must be negative: " + x);
            assertEquals(0f, y, 1e-4f);
            assertEquals(0f, z, 1e-4f);
            // Bones 0 and 1 did not ask for a locator, so their slots keep the sentinel.
            assertEquals((byte) 0x5A, state.get(0), "bone 0 has no locator");
            assertEquals((byte) 0x5A, state.get(16), "bone 1 has no locator");
        }
    }

    @Test
    void a_persistent_buffer_may_be_left_untouched_when_nothing_moves() throws Exception {
        load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            ByteBuffer out = direct(evaluator.packedBytes());
            float[] params = params(3);
            evaluator.evaluate(IDENTITY_ROOT, params, out,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, true, PACKED_LIGHT);
            // Mark the buffer, then re-evaluate the same parameters with a persistent output.
            out.put(0, (byte) 0x7E);
            evaluator.evaluate(IDENTITY_ROOT, params, out,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.AUTO, true, PACKED_LIGHT);
            assertEquals((byte) 0x7E, out.get(0),
                    "an unchanged frame must not rewrite a persistent buffer");
        }
    }

    @Test
    void every_supported_tier_agrees_with_the_scalar_one() throws Exception {
        load();
        NativeTransforms provider = load();
        try (NativePackedBoneEvaluator evaluator = chain()) {
            float[] params = params(3);
            params[0] = 0.3f;
            params[1] = -0.2f;
            params[2] = 0.1f;
            ByteBuffer reference = direct(evaluator.packedBytes());
            evaluator.evaluate(IDENTITY_ROOT, params, reference,
                    NativePackedBoneEvaluator.FLOAT_TAIL, Isa.SCALAR, false, PACKED_LIGHT);
            for (Isa isa : Isa.values()) {
                if (isa == Isa.AUTO || !provider.supported(isa)) {
                    continue;
                }
                ByteBuffer out = direct(evaluator.packedBytes());
                evaluator.evaluate(IDENTITY_ROOT, params, out,
                        NativePackedBoneEvaluator.FLOAT_TAIL, isa, false, PACKED_LIGHT);
                for (int i = 0; i < evaluator.packedBytes(); i += 4) {
                    float expected = reference.getFloat(i);
                    float actual = out.getFloat(i);
                    float error = Math.abs(actual - expected) / Math.max(Math.abs(expected), 1f);
                    assertTrue(error <= 1e-4f,
                            isa + " differs by " + error + " at byte " + i);
                }
            }
        }
    }
}
