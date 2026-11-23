package dev.spm.core.integration;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.Skeleton;
import dev.spm.core.format.ysm.YsmArchiveCodec;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import dev.spm.core.format.ysm.YsmHistoricalCodec;
import dev.spm.core.format.ysm.YsmImportResult;
import dev.spm.core.format.ysm.YsmModelImporter;
import dev.spm.core.format.ysm.YsmWirePayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Measures the host's Java bone-evaluation algorithm on the real models, to find out whether the
 * Rust fast path actually beats it.
 *
 * <p>This is the smoke test that decides whether the 1.1.0 SIMD work has any value. The plan
 * assumed the optimized Java path costs 38.3 µs for a 500-bone model; that number was never
 * measured on a real rig, and the real rigs turned out to be 577–773 bones at 25–26 levels deep.
 *
 * <p># Why the algorithm is ported rather than called
 *
 * <p>{@code SpmCoreBoneMatrixWriter} lives in the host repository and takes a {@code BuiltModel},
 * which pulls in Minecraft types. Neither belongs on spm-core's test classpath. The arithmetic
 * below is therefore a line-for-line transcription of {@code SpmCoreBoneMatrixWriter}:
 *
 * <ul>
 *   <li>{@code evaluateInPlace} — the hierarchy walk, the zero-rotation/unit-scale fast path, the
 *       uniform-scale normal shortcut and the cofactor inverse-transpose.
 *   <li>{@code writeEvaluated} — the 144-byte record assembly, including the root pose/normal
 *       multiply and the hidden-record canonical form.
 * </ul>
 *
 * <p>The only changes are mechanical: {@code Matrix4f}/{@code Matrix3f} accessors become array
 * reads, and {@code BuiltModel.ComputeCache} becomes a local record with the same three fields.
 * No arithmetic order, branching or rounding was altered.
 *
 * <p># How the port is validated
 *
 * <p>{@link #javaTranscriptionMatchesTheRustReference()} compares this transcription against
 * spm-core's own Rust scalar reference ({@code spm-render-simd}), which implements the same
 * specification. If the two agree within {@code 1e-4} on every bone of every real model, the
 * transcription is faithful enough to time.
 */
public final class BoneEvaluationSmokeTest {
    private static final Path DEFAULT_DIR = Path.of("D:\\SparkleMorpher\\TestModel");

    /** The three fields {@code BuiltModel.ComputeCache} exposes to the writer. */
    record Cache(int[] evaluationOrder, int[] parents, float[] pivots, int boneCount) {
    }

    /** Per-call scratch, sized once, exactly like {@code SpmCoreBoneMatrixWriter.Scratch}. */
    static final class Scratch {
        float[] transforms = new float[0];
        float[] normals = new float[0];
        boolean[] visible = new boolean[0];
        boolean[] descendants = new boolean[0];
        double[] uniformScale = new double[0];

        void ensureCapacity(int boneCount, boolean needNormals) {
            if (visible.length < boneCount) {
                transforms = new float[boneCount * 16];
                visible = new boolean[boneCount];
                descendants = new boolean[boneCount];
                uniformScale = new double[boneCount];
            }
            if (needNormals && normals.length < boneCount * 9) {
                normals = new float[boneCount * 9];
            }
        }
    }

    @Test
    void reportJavaPathCostOnRealModels() throws IOException {
        List<Path> models = models();
        if (models.isEmpty()) {
            System.out.println("[smoke] skipped: no .ysm models found");
            return;
        }
        System.out.println();
        System.out.println("=== Java bone evaluation (SpmCoreBoneMatrixWriter transcription) ===");
        System.out.printf(Locale.ROOT, "%-34s %6s %6s %12s %12s%n",
                "model", "bones", "depth", "eval µs", "144B write µs");
        List<double[]> results = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Path model : models) {
            Skeleton skeleton = largestSkeleton(model);
            if (skeleton == null) {
                continue;
            }
            Cache cache = cacheOf(skeleton);
            float[] params = params(skeleton.boneCount(), true);
            Scratch scratch = new Scratch();
            float[] rootPose = {
                    2f, 0f, 0f, 0f,
                    0f, 3f, 0f, 0f,
                    0f, 0f, 4f, 0f,
                    5f, 6f, 7f, 1f,
            };
            float[] rootNormal = {0.5f, 0f, 0f, 0f, 1f / 3f, 0f, 0f, 0f, 0.25f};
            byte[] out = new byte[skeleton.boneCount() * 144];

            // JIT warm-up: a cold measurement would be measuring the interpreter.
            for (int i = 0; i < 3_000; i++) {
                evaluateInPlace(cache, params, null, scratch, true);
                writeEvaluated(cache, rootPose, rootNormal, params, null, 0x00F000F0, out, true, scratch);
            }
            int iterations = Math.min(20_000, Math.max(500, 2_000_000 / Math.max(1, skeleton.boneCount())));

            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                evaluateInPlace(cache, params, null, scratch, true);
            }
            double evalUs = (System.nanoTime() - start) / 1000.0 / iterations;

            start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                writeEvaluated(cache, rootPose, rootNormal, params, null, 0x00F000F0, out, true, scratch);
            }
            double writeUs = (System.nanoTime() - start) / 1000.0 / iterations;

            System.out.printf(Locale.ROOT, "%-34s %6d %6d %12.2f %12.2f%n",
                    model.getFileName().toString(), skeleton.boneCount(), depthOf(skeleton),
                    evalUs, writeUs);
            results.add(new double[]{skeleton.boneCount(), evalUs, writeUs});
            names.add(model.getFileName().toString());
        }
        System.out.println();
        System.out.println("eval = hierarchy walk + local matrices + normals (what the Rust kernel replaces)");
        System.out.println("write = 144B/bone record assembly including the root multiply");
        System.out.println("full frame = eval + write");
        for (int i = 0; i < results.size(); i++) {
            double[] row = results.get(i);
            if (row[0] < 500) {
                continue;
            }
            System.out.printf(Locale.ROOT,
                    "%s: %.0f bones — Java full frame %.2f µs (eval %.2f + write %.2f)%n",
                    names.get(i), row[0], row[1] + row[2], row[1], row[2]);
        }
    }

    /**
     * Cross-checks the transcription against the Rust scalar reference.
     *
     * <p>Both implement the same specification, so agreement within {@code 1e-4} is what makes the
     * timing defensible: it shows the ported code is the same arithmetic and not a faster
     * approximation of it.
     *
     * <p>The comparison runs through the {@code spm-render-simd} test binary, which owns the Rust
     * reference; this method records the Java side's output so that binary can compare against it.
     */
    @Test
    void javaTranscriptionMatchesTheRustReference() throws IOException {
        Path crossCheck = Path.of(System.getProperty("spm.javaCrossCheckOut", "build/java-crosscheck.txt"));
        List<Path> models = models();
        if (models.isEmpty()) {
            System.out.println("[smoke] skipped: no .ysm models found");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Path model : models) {
            Skeleton skeleton = largestSkeleton(model);
            if (skeleton == null) {
                continue;
            }
            Cache cache = cacheOf(skeleton);
            float[] params = params(skeleton.boneCount(), true);
            Scratch scratch = new Scratch();
            evaluateInPlace(cache, params, null, scratch, true);
            text.append("model ").append(model.getFileName()).append(' ')
                    .append(skeleton.boneCount()).append('\n');
            for (int bone = 0; bone < skeleton.boneCount(); bone++) {
                text.append(bone);
                for (int k = 0; k < 16; k++) {
                    text.append(' ').append(scratch.transforms[bone * 16 + k]);
                }
                for (int k = 0; k < 9; k++) {
                    text.append(' ').append(scratch.normals[bone * 9 + k]);
                }
                text.append(' ').append(scratch.visible[bone] ? 1 : 0);
                text.append('\n');
            }
        }
        Files.createDirectories(crossCheck.toAbsolutePath().getParent());
        Files.writeString(crossCheck, text.toString());
        System.out.println("[smoke] wrote Java reference to " + crossCheck.toAbsolutePath());
    }

    // ---------------------------------------------------------------------------------------
    // Transcription of SpmCoreBoneMatrixWriter.evaluateInPlace
    // ---------------------------------------------------------------------------------------

    static boolean evaluateInPlace(Cache cache, float[] boneParams, float[] stateBuffer,
                                   Scratch scratch, boolean computeNormals) {
        int[] order = cache.evaluationOrder();
        int[] parents = cache.parents();
        float[] pivots = cache.pivots();
        int n = parents.length;
        scratch.ensureCapacity(n, computeNormals);

        float[] transforms = scratch.transforms;
        float[] normals = scratch.normals;
        boolean[] visible = scratch.visible;
        boolean[] descendants = scratch.descendants;
        double[] uniformScale = scratch.uniformScale;

        for (int idx = 0; idx < n; idx++) {
            int bone = order[idx];
            visible[bone] = false;
            descendants[bone] = false;
            uniformScale[bone] = 0.0;
            int parent = parents[bone];
            if (parent >= 0 && !descendants[parent]) {
                continue;
            }
            int p = bone * 12;
            float sxScale = boneParams[p + 6];
            float syScale = boneParams[p + 7];
            float szScale = boneParams[p + 8];
            if (sxScale == 0.0f || syScale == 0.0f || szScale == 0.0f) {
                continue;
            }
            float rx = boneParams[p];
            float ry = boneParams[p + 1];
            float rz = boneParams[p + 2];
            double dx = -boneParams[p + 3] * 0.0625;
            double dy = boneParams[p + 4] * 0.0625;
            double dz = boneParams[p + 5] * 0.0625;

            boolean uniformLocal = sxScale == syScale && syScale == szScale;
            boolean unitScale = uniformLocal && sxScale == 1.0f;

            int mOff = bone * 16;
            float w00, w10, w20, w01, w11, w21, w02, w12, w22, w03, w13, w23;
            if (rx == 0.0f && ry == 0.0f && rz == 0.0f && unitScale) {
                if (parent < 0) {
                    w00 = 1.0f; w10 = 0.0f; w20 = 0.0f;
                    w01 = 0.0f; w11 = 1.0f; w21 = 0.0f;
                    w02 = 0.0f; w12 = 0.0f; w22 = 1.0f;
                    w03 = (float) dx; w13 = (float) dy; w23 = (float) dz;
                } else {
                    int pOff = parent * 16;
                    w00 = transforms[pOff];     w10 = transforms[pOff + 1];  w20 = transforms[pOff + 2];
                    w01 = transforms[pOff + 4]; w11 = transforms[pOff + 5];  w21 = transforms[pOff + 6];
                    w02 = transforms[pOff + 8]; w12 = transforms[pOff + 9];  w22 = transforms[pOff + 10];
                    if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
                        w03 = transforms[pOff + 12];
                        w13 = transforms[pOff + 13];
                        w23 = transforms[pOff + 14];
                    } else {
                        w03 = (float) ((double) w00 * dx + (double) w01 * dy + (double) w02 * dz + transforms[pOff + 12]);
                        w13 = (float) ((double) w10 * dx + (double) w11 * dy + (double) w12 * dz + transforms[pOff + 13]);
                        w23 = (float) ((double) w20 * dx + (double) w21 * dy + (double) w22 * dz + transforms[pOff + 14]);
                    }
                }
            } else {
                double cx = rx == 0.0f ? 1.0 : Math.cos(rx);
                double sx = rx == 0.0f ? 0.0 : Math.sin(rx);
                double cy = ry == 0.0f ? 1.0 : Math.cos(ry);
                double sy = ry == 0.0f ? 0.0 : Math.sin(ry);
                double cz = rz == 0.0f ? 1.0 : Math.cos(rz);
                double sz = rz == 0.0f ? 0.0 : Math.sin(rz);

                float l00 = (float) ((cz * cy) * sxScale);
                float l10 = (float) ((sz * cy) * sxScale);
                float l20 = (float) ((-sy) * sxScale);
                float l01 = (float) ((cz * sy * sx - sz * cx) * syScale);
                float l11 = (float) ((sz * sy * sx + cz * cx) * syScale);
                float l21 = (float) ((cy * sx) * syScale);
                float l02 = (float) ((cz * sy * cx + sz * sx) * szScale);
                float l12 = (float) ((sz * sy * cx - cz * sx) * szScale);
                float l22 = (float) ((cy * cx) * szScale);

                int pv = bone * 3;
                double px = pivots[pv];
                double py = pivots[pv + 1];
                double pz = pivots[pv + 2];

                float l03 = (float) (px + dx - ((double) l00 * px + (double) l01 * py + (double) l02 * pz));
                float l13 = (float) (py + dy - ((double) l10 * px + (double) l11 * py + (double) l12 * pz));
                float l23 = (float) (pz + dz - ((double) l20 * px + (double) l21 * py + (double) l22 * pz));

                if (parent < 0) {
                    w00 = l00; w10 = l10; w20 = l20;
                    w01 = l01; w11 = l11; w21 = l21;
                    w02 = l02; w12 = l12; w22 = l22;
                    w03 = l03; w13 = l13; w23 = l23;
                } else {
                    int pOff = parent * 16;
                    double p00 = transforms[pOff],     p10 = transforms[pOff + 1],  p20 = transforms[pOff + 2];
                    double p01 = transforms[pOff + 4], p11 = transforms[pOff + 5],  p21 = transforms[pOff + 6];
                    double p02 = transforms[pOff + 8], p12 = transforms[pOff + 9],  p22 = transforms[pOff + 10];
                    double p03 = transforms[pOff + 12], p13 = transforms[pOff + 13], p23 = transforms[pOff + 14];

                    w00 = (float) (p00 * l00 + p01 * l10 + p02 * l20);
                    w10 = (float) (p10 * l00 + p11 * l10 + p12 * l20);
                    w20 = (float) (p20 * l00 + p21 * l10 + p22 * l20);

                    w01 = (float) (p00 * l01 + p01 * l11 + p02 * l21);
                    w11 = (float) (p10 * l01 + p11 * l11 + p12 * l21);
                    w21 = (float) (p20 * l01 + p21 * l11 + p22 * l21);

                    w02 = (float) (p00 * l02 + p01 * l12 + p02 * l22);
                    w12 = (float) (p10 * l02 + p11 * l12 + p12 * l22);
                    w22 = (float) (p20 * l02 + p21 * l12 + p22 * l22);

                    w03 = (float) (p00 * l03 + p01 * l13 + p02 * l23 + p03);
                    w13 = (float) (p10 * l03 + p11 * l13 + p12 * l23 + p13);
                    w23 = (float) (p20 * l03 + p21 * l13 + p22 * l23 + p23);
                }
            }

            if (!Float.isFinite(w00) || !Float.isFinite(w10) || !Float.isFinite(w20)
                    || !Float.isFinite(w01) || !Float.isFinite(w11) || !Float.isFinite(w21)
                    || !Float.isFinite(w02) || !Float.isFinite(w12) || !Float.isFinite(w22)
                    || !Float.isFinite(w03) || !Float.isFinite(w13) || !Float.isFinite(w23)) {
                return false;
            }

            transforms[mOff] = w00;      transforms[mOff + 1] = w10;  transforms[mOff + 2] = w20;  transforms[mOff + 3] = 0.0f;
            transforms[mOff + 4] = w01;  transforms[mOff + 5] = w11;  transforms[mOff + 6] = w21;  transforms[mOff + 7] = 0.0f;
            transforms[mOff + 8] = w02;  transforms[mOff + 9] = w12;  transforms[mOff + 10] = w22; transforms[mOff + 11] = 0.0f;
            transforms[mOff + 12] = w03; transforms[mOff + 13] = w13; transforms[mOff + 14] = w23; transforms[mOff + 15] = 1.0f;

            double worldUniformScale = 0.0;
            if (uniformLocal) {
                double baseScale = parent < 0 ? 1.0 : uniformScale[parent];
                if (baseScale != 0.0) {
                    double candidate = baseScale * sxScale;
                    double abs = Math.abs(candidate);
                    if (Double.isFinite(candidate) && abs >= 1e-6 && abs <= 1e6) {
                        worldUniformScale = candidate;
                    }
                }
            }
            uniformScale[bone] = worldUniformScale;

            if (computeNormals) {
                int nOff = bone * 9;
                if (worldUniformScale == 1.0 || worldUniformScale == -1.0) {
                    normals[nOff] = w00;     normals[nOff + 1] = w10; normals[nOff + 2] = w20;
                    normals[nOff + 3] = w01; normals[nOff + 4] = w11; normals[nOff + 5] = w21;
                    normals[nOff + 6] = w02; normals[nOff + 7] = w12; normals[nOff + 8] = w22;
                } else if (worldUniformScale != 0.0) {
                    double invS2 = 1.0 / (worldUniformScale * worldUniformScale);
                    normals[nOff] = (float) (w00 * invS2);     normals[nOff + 1] = (float) (w10 * invS2); normals[nOff + 2] = (float) (w20 * invS2);
                    normals[nOff + 3] = (float) (w01 * invS2); normals[nOff + 4] = (float) (w11 * invS2); normals[nOff + 5] = (float) (w21 * invS2);
                    normals[nOff + 6] = (float) (w02 * invS2); normals[nOff + 7] = (float) (w12 * invS2); normals[nOff + 8] = (float) (w22 * invS2);
                } else {
                    double a = w00, b = w01, c = w02;
                    double d = w10, e = w11, f = w12;
                    double g = w20, h = w21, ii = w22;
                    double det = a * (e * ii - f * h) - b * (d * ii - f * g) + c * (d * h - e * g);
                    if (det == 0.0 || !Double.isFinite(det)) {
                        return false;
                    }
                    double invDet = 1.0 / det;
                    float n0 = (float) ((e * ii - f * h) * invDet);
                    float n1 = (float) ((c * h - b * ii) * invDet);
                    float n2 = (float) ((b * f - c * e) * invDet);
                    float n3 = (float) ((f * g - d * ii) * invDet);
                    float n4 = (float) ((a * ii - c * g) * invDet);
                    float n5 = (float) ((c * d - a * f) * invDet);
                    float n6 = (float) ((d * h - e * g) * invDet);
                    float n7 = (float) ((b * g - a * h) * invDet);
                    float n8 = (float) ((a * e - b * d) * invDet);
                    normals[nOff] = n0;     normals[nOff + 1] = n1; normals[nOff + 2] = n2;
                    normals[nOff + 3] = n3; normals[nOff + 4] = n4; normals[nOff + 5] = n5;
                    normals[nOff + 6] = n6; normals[nOff + 7] = n7; normals[nOff + 8] = n8;
                }
            }

            boolean ownVisible = boneParams[p + 9] == 0.0f;
            visible[bone] = ownVisible;
            descendants[bone] = boneParams[p + 10] == 0.0f;

            if (stateBuffer != null && ownVisible && boneParams[p + 11] == 1.0f) {
                int sOff = bone * 4;
                if (sOff + 2 < stateBuffer.length) {
                    int pv = bone * 3;
                    double px = pivots[pv];
                    double py = pivots[pv + 1];
                    double pz = pivots[pv + 2];
                    float lx = (float) (w03 + (double) w00 * px + (double) w01 * py + (double) w02 * pz);
                    float ly = (float) (w13 + (double) w10 * px + (double) w11 * py + (double) w12 * pz);
                    float lz = (float) (w23 + (double) w20 * px + (double) w21 * py + (double) w22 * pz);
                    if (!Float.isFinite(lx) || !Float.isFinite(ly) || !Float.isFinite(lz)) {
                        return false;
                    }
                    stateBuffer[sOff] = -lx * 16.0f;
                    stateBuffer[sOff + 1] = ly * 16.0f;
                    stateBuffer[sOff + 2] = lz * 16.0f;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Transcription of SpmCoreBoneMatrixWriter.writeEvaluated
    // ---------------------------------------------------------------------------------------

    static boolean writeEvaluated(Cache cache, float[] rootPose, float[] rootNormal,
                                  float[] boneParams, float[] stateBuffer, int packedLight,
                                  byte[] out, boolean floatTail, Scratch scratch) {
        int boneCount = cache.parents().length;
        if (boneCount <= 0 || boneParams.length < boneCount * 12 || out.length < boneCount * 144) {
            return false;
        }
        if (!evaluateInPlace(cache, boneParams, stateBuffer, scratch, true)) {
            return false;
        }
        float[] transforms = scratch.transforms;
        float[] normals = scratch.normals;
        boolean[] visible = scratch.visible;
        float[] bakedGlow = cacheGlow(boneCount);

        // rootPose / rootNormal as column-major arrays: index = column * 4 + row.
        float r00 = rootPose[0], r01 = rootPose[4], r02 = rootPose[8], r03 = rootPose[12];
        float r10 = rootPose[1], r11 = rootPose[5], r12 = rootPose[9], r13 = rootPose[13];
        float r20 = rootPose[2], r21 = rootPose[6], r22 = rootPose[10], r23 = rootPose[14];
        float r30 = rootPose[3], r31 = rootPose[7], r32 = rootPose[11], r33 = rootPose[15];
        boolean affineRoot = r03 == 0.0f && r13 == 0.0f && r23 == 0.0f && r33 == 1.0f;

        float rn00 = rootNormal[0], rn01 = rootNormal[3], rn02 = rootNormal[6];
        float rn10 = rootNormal[1], rn11 = rootNormal[4], rn12 = rootNormal[7];
        float rn20 = rootNormal[2], rn21 = rootNormal[5], rn22 = rootNormal[8];

        int cursor = 0;
        for (int i = 0; i < boneCount; i++) {
            if (!visible[i]) {
                cursor = writeHiddenRecord(out, cursor, floatTail);
                continue;
            }
            int mOff = i * 16;
            float l00 = transforms[mOff],     l01 = transforms[mOff + 1],  l02 = transforms[mOff + 2];
            float l10 = transforms[mOff + 4], l11 = transforms[mOff + 5],  l12 = transforms[mOff + 6];
            float l20 = transforms[mOff + 8], l21 = transforms[mOff + 9],  l22 = transforms[mOff + 10];
            float l30 = transforms[mOff + 12], l31 = transforms[mOff + 13], l32 = transforms[mOff + 14];

            if (affineRoot) {
                cursor = putFloat(out, cursor, r00 * l00 + r10 * l01 + r20 * l02);
                cursor = putFloat(out, cursor, r01 * l00 + r11 * l01 + r21 * l02);
                cursor = putFloat(out, cursor, r02 * l00 + r12 * l01 + r22 * l02);
                cursor = putFloat(out, cursor, 0.0f);

                cursor = putFloat(out, cursor, r00 * l10 + r10 * l11 + r20 * l12);
                cursor = putFloat(out, cursor, r01 * l10 + r11 * l11 + r21 * l12);
                cursor = putFloat(out, cursor, r02 * l10 + r12 * l11 + r22 * l12);
                cursor = putFloat(out, cursor, 0.0f);

                cursor = putFloat(out, cursor, r00 * l20 + r10 * l21 + r20 * l22);
                cursor = putFloat(out, cursor, r01 * l20 + r11 * l21 + r21 * l22);
                cursor = putFloat(out, cursor, r02 * l20 + r12 * l21 + r22 * l22);
                cursor = putFloat(out, cursor, 0.0f);

                cursor = putFloat(out, cursor, r00 * l30 + r10 * l31 + r20 * l32 + r30);
                cursor = putFloat(out, cursor, r01 * l30 + r11 * l31 + r21 * l32 + r31);
                cursor = putFloat(out, cursor, r02 * l30 + r12 * l31 + r22 * l32 + r32);
                cursor = putFloat(out, cursor, 1.0f);
            } else {
                cursor = putFloat(out, cursor, r00 * l00 + r10 * l01 + r20 * l02);
                cursor = putFloat(out, cursor, r01 * l00 + r11 * l01 + r21 * l02);
                cursor = putFloat(out, cursor, r02 * l00 + r12 * l01 + r22 * l02);
                cursor = putFloat(out, cursor, r03 * l00 + r13 * l01 + r23 * l02);

                cursor = putFloat(out, cursor, r00 * l10 + r10 * l11 + r20 * l12);
                cursor = putFloat(out, cursor, r01 * l10 + r11 * l11 + r21 * l12);
                cursor = putFloat(out, cursor, r02 * l10 + r12 * l11 + r22 * l12);
                cursor = putFloat(out, cursor, r03 * l10 + r13 * l11 + r23 * l12);

                cursor = putFloat(out, cursor, r00 * l20 + r10 * l21 + r20 * l22);
                cursor = putFloat(out, cursor, r01 * l20 + r11 * l21 + r21 * l22);
                cursor = putFloat(out, cursor, r02 * l20 + r12 * l21 + r22 * l22);
                cursor = putFloat(out, cursor, r03 * l20 + r13 * l21 + r23 * l22);

                cursor = putFloat(out, cursor, r00 * l30 + r10 * l31 + r20 * l32 + r30);
                cursor = putFloat(out, cursor, r01 * l30 + r11 * l31 + r21 * l32 + r31);
                cursor = putFloat(out, cursor, r02 * l30 + r12 * l31 + r22 * l32 + r32);
                cursor = putFloat(out, cursor, r03 * l30 + r13 * l31 + r23 * l32 + r33);
            }

            int nOff = i * 9;
            float ln00 = normals[nOff],     ln01 = normals[nOff + 1], ln02 = normals[nOff + 2];
            float ln10 = normals[nOff + 3], ln11 = normals[nOff + 4], ln12 = normals[nOff + 5];
            float ln20 = normals[nOff + 6], ln21 = normals[nOff + 7], ln22 = normals[nOff + 8];

            cursor = putFloat(out, cursor, rn00 * ln00 + rn10 * ln01 + rn20 * ln02);
            cursor = putFloat(out, cursor, rn01 * ln00 + rn11 * ln01 + rn21 * ln02);
            cursor = putFloat(out, cursor, rn02 * ln00 + rn12 * ln01 + rn22 * ln02);
            cursor = putFloat(out, cursor, 0.0f);

            cursor = putFloat(out, cursor, rn00 * ln10 + rn10 * ln11 + rn20 * ln12);
            cursor = putFloat(out, cursor, rn01 * ln10 + rn11 * ln11 + rn21 * ln12);
            cursor = putFloat(out, cursor, rn02 * ln10 + rn12 * ln11 + rn22 * ln12);
            cursor = putFloat(out, cursor, 0.0f);

            cursor = putFloat(out, cursor, rn00 * ln20 + rn10 * ln21 + rn20 * ln22);
            cursor = putFloat(out, cursor, rn01 * ln20 + rn11 * ln21 + rn21 * ln22);
            cursor = putFloat(out, cursor, rn02 * ln20 + rn12 * ln21 + rn22 * ln22);
            cursor = putFloat(out, cursor, 0.0f);

            cursor = putFloat(out, cursor, 0.0f);
            cursor = putFloat(out, cursor, 0.0f);
            cursor = putFloat(out, cursor, 0.0f);
            cursor = putFloat(out, cursor, 1.0f);

            int light = bakedGlow[i] != 0 ? 0x00F000F0 : packedLight;
            cursor = writeTail(out, cursor, light, false, floatTail);
        }
        return true;
    }

    /** Glow comes from the baked bones in production; here it is uniform so both paths compare. */
    private static float[] cacheGlow(int boneCount) {
        return new float[boneCount];
    }

    private static int writeHiddenRecord(byte[] out, int cursor, boolean floatTail) {
        float[] identity = {
                1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
        };
        for (int pass = 0; pass < 2; pass++) {
            for (float value : identity) {
                cursor = putFloat(out, cursor, value);
            }
        }
        return writeTail(out, cursor, 0, true, floatTail);
    }

    /** Writes the four-word tail: `[light, hidden, 0, 0]`. */
    private static int writeTail(byte[] out, int cursor, int light, boolean hidden, boolean floatTail) {
        if (floatTail) {
            cursor = putFloat(out, cursor, light);
            cursor = putFloat(out, cursor, hidden ? 1.0f : 0.0f);
            cursor = putFloat(out, cursor, 0.0f);
            return putFloat(out, cursor, 0.0f);
        }
        cursor = putInt(out, cursor, light);
        cursor = putInt(out, cursor, hidden ? 1 : 0);
        cursor = putInt(out, cursor, 0);
        return putInt(out, cursor, 0);
    }

    private static int putFloat(byte[] out, int cursor, float value) {
        int bits = Float.floatToRawIntBits(value);
        out[cursor] = (byte) bits;
        out[cursor + 1] = (byte) (bits >>> 8);
        out[cursor + 2] = (byte) (bits >>> 16);
        out[cursor + 3] = (byte) (bits >>> 24);
        return cursor + 4;
    }

    private static int putInt(byte[] out, int cursor, int value) {
        out[cursor] = (byte) value;
        out[cursor + 1] = (byte) (value >>> 8);
        out[cursor + 2] = (byte) (value >>> 16);
        out[cursor + 3] = (byte) (value >>> 24);
        return cursor + 4;
    }

    // ---------------------------------------------------------------------------------------
    // Model loading and shared fixtures
    // ---------------------------------------------------------------------------------------

    private static List<Path> models() throws IOException {
        Path dir = Path.of(System.getProperty("spm.testModelDir", DEFAULT_DIR.toString()));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .sorted()
                    .toList();
        }
    }

    static Cache cacheOf(Skeleton skeleton) {
        return new Cache(skeleton.evaluationOrder(), skeleton.parents(), skeleton.pivots(),
                skeleton.boneCount());
    }

    /** Per-bone parameters matching the Rust benchmark fixture exactly, so the two are comparable. */
    static float[] params(int n, boolean rotating) {
        float[] params = new float[n * 12];
        for (int i = 0; i < n; i++) {
            int p = i * 12;
            float step = i;
            if (rotating) {
                params[p] = step * 0.05f;
                params[p + 1] = step * 0.031f;
                params[p + 2] = -step * 0.017f;
            }
            params[p + 3] = step * 0.1f - 1f;
            params[p + 4] = 0.5f;
            params[p + 5] = -step * 0.05f;
            params[p + 6] = 1f;
            params[p + 7] = 1f;
            params[p + 8] = 1f;
            params[p + 11] = i % 4 == 0 ? 1f : 0f;
        }
        return params;
    }

    private static Skeleton largestSkeleton(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        DecodePolicy policy = DecodePolicy.localModel();
        ResourceBudget budget = new ResourceBudget(policy.maxExpandedBytes());
        YsmImportResult imported;
        try {
            YsmWirePayload wire = YsmEnvelopeCodec.decode(bytes, policy, budget, new CancellationToken());
            try (wire) {
                imported = YsmModelImporter.importPayload(wire, policy, budget, new CancellationToken());
            }
        } catch (IOException | RuntimeException notEnvelope) {
            var archive = YsmArchiveCodec.decode(bytes, policy, budget, new CancellationToken());
            try (archive) {
                var historical = YsmHistoricalCodec.decodeForImport(
                        archive.read(archive.entries().iterator().next()),
                        policy, budget, new CancellationToken());
                imported = YsmModelImporter.importPayload(historical, policy, budget, new CancellationToken());
            }
        }
        Skeleton best = null;
        final YsmImportResult owned = imported;
        try (owned) {
            for (YsmImportResult.Variant variant : owned.variants()) {
                if (variant.document().isEmpty()) {
                    continue;
                }
                ModelDocument document = variant.document().get();
                if (best == null || document.skeleton().boneCount() > best.boneCount()) {
                    best = document.skeleton();
                }
            }
        }
        return best;
    }

    private static int depthOf(Skeleton skeleton) {
        int[] parents = skeleton.parents();
        int[] depth = new int[skeleton.boneCount()];
        int max = 0;
        for (int bone : skeleton.evaluationOrder()) {
            depth[bone] = parents[bone] < 0 ? 0 : depth[parents[bone]] + 1;
            max = Math.max(max, depth[bone]);
        }
        return max + 1;
    }
}
