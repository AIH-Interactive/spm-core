package dev.spm.core.format.gltf;

import java.io.IOException;

/** Pure glTF transform utilities matching {@code BoneEvaluator}'s ZYX Euler convention. */
final class GltfTransforms {
    private GltfTransforms() {}

    record Trs(float[] translation, float[] eulerZyx, float[] scale) {}

    /** Converts a glTF quaternion {@code (x, y, z, w)} into ZYX Euler angles (radians). */
    static float[] quaternionToEulerZyx(double x, double y, double z, double w) throws IOException {
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        if (!(norm > 0) || !Double.isFinite(norm)) throw new IOException("Zero or non-finite glTF quaternion");
        x /= norm; y /= norm; z /= norm; w /= norm;
        double m00 = 1 - 2 * (y * y + z * z);
        double m10 = 2 * (x * y + z * w);
        double m20 = 2 * (x * z - y * w);
        double m01 = 2 * (x * y - z * w);
        double m11 = 1 - 2 * (x * x + z * z);
        double m21 = 2 * (y * z + x * w);
        double m22 = 1 - 2 * (x * x + y * y);
        return matrix3ToEulerZyx(m00, m10, m20, m01, m11, m21, m22);
    }

    /** Decomposes a column-major 4x4 affine matrix into {@code T * Rz * Ry * Rx * S}. */
    static Trs decomposeMatrix(float[] m) throws IOException {
        if (m.length != 16) throw new IOException("Invalid glTF matrix length");
        for (float v : m) if (!Float.isFinite(v)) throw new IOException("Non-finite glTF node matrix");
        if (m[3] != 0 || m[7] != 0 || m[11] != 0 || m[15] != 1) {
            throw new IOException("Non-affine glTF node matrix");
        }
        double sx = Math.hypot(Math.hypot(m[0], m[1]), m[2]);
        double sy = Math.hypot(Math.hypot(m[4], m[5]), m[6]);
        double sz = Math.hypot(Math.hypot(m[8], m[9]), m[10]);
        if (sx == 0 || sy == 0 || sz == 0) {
            // Degenerate zero-scale axis: preserve zero scale and identity rotation.
            return new Trs(new float[] {m[12], m[13], m[14]}, new float[3],
                    new float[] {(float) sx, (float) sy, (float) sz});
        }
        double det = m[0] * ((double) m[5] * m[10] - (double) m[9] * m[6])
                - m[4] * ((double) m[1] * m[10] - (double) m[9] * m[2])
                + m[8] * ((double) m[1] * m[6] - (double) m[5] * m[2]);
        if (det < 0) sx = -sx;
        double r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        double r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        double r22 = m[10] / sz;
        float[] euler = matrix3ToEulerZyx(r00, r10, r20, r01, r11, r21, r22);
        return new Trs(new float[] {m[12], m[13], m[14]}, euler,
                new float[] {(float) sx, (float) sy, (float) sz});
    }

    private static float[] matrix3ToEulerZyx(double m00, double m10, double m20,
                                             double m01, double m11, double m21, double m22) {
        double sy = Math.max(-1.0, Math.min(1.0, -m20));
        double ry = Math.asin(sy);
        double rx, rz;
        if (Math.abs(sy) < 1.0 - 1e-6) {
            rx = Math.atan2(m21, m22);
            rz = Math.atan2(m10, m00);
        } else {
            rz = 0.0;
            rx = sy > 0 ? Math.atan2(m01, m11) : Math.atan2(-m01, m11);
        }
        return new float[] {(float) rx, (float) ry, (float) rz};
    }
}
