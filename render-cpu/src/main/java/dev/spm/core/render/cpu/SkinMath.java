package dev.spm.core.render.cpu;

/** Scalar matrix and quaternion helpers shared by the skinning paths. */
final class SkinMath {
    private SkinMath() {}

    /** Column-major 4x4 product. */
    static float[] multiply(float[] left, float[] right) {
        float[] result = new float[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                double value = 0;
                for (int k = 0; k < 4; k++) value += (double) left[k * 4 + row] * right[column * 4 + k];
                result[column * 4 + row] = finite(value);
            }
        }
        return result;
    }

    /** Inverse of a nonsingular affine column-major matrix, or null when the matrix is all zeros. */
    static float[] invertAffine(float[] m) {
        double a = m[0], b = m[4], c = m[8];
        double d = m[1], e = m[5], f = m[9];
        double g = m[2], h = m[6], i = m[10];
        double determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (determinant == 0) {
            for (float value : m) if (value != 0) throw new IllegalArgumentException("Singular bind matrix");
            return null;
        }
        if (!Double.isFinite(determinant)) throw new IllegalArgumentException("Unrepresentable bind matrix");
        double[] inverse = {e * i - f * h, c * h - b * i, b * f - c * e,
                f * g - d * i, a * i - c * g, c * d - a * f,
                d * h - e * g, b * g - a * h, a * e - b * d};
        float[] result = new float[16];
        // The cofactor list above is the adjugate in row-major order, so adj/det is the inverse in
        // row-major order; store it column-major to match BoneMatrices.
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 3; column++)
                result[column * 4 + row] = finite(inverse[row * 3 + column] / determinant);
        float tx = m[12], ty = m[13], tz = m[14];
        for (int row = 0; row < 3; row++) {
            double value = 0;
            for (int axis = 0; axis < 3; axis++) {
                double component = row == 0 ? tx : row == 1 ? ty : tz;
                value -= (double) result[axis * 4 + row] * component;
            }
            result[12 + row] = finite(value);
        }
        result[3] = 0; result[7] = 0; result[11] = 0; result[15] = 1;
        return result;
    }

    /** Row-major 3x3 inverse transpose, matching {@code BoneMatrices.normal} packing. */
    static float[] normalOf(float[] m) {
        double a = m[0], b = m[4], c = m[8];
        double d = m[1], e = m[5], f = m[9];
        double g = m[2], h = m[6], i = m[10];
        double determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (determinant == 0) throw new IllegalArgumentException("Singular bind matrix");
        double[] cofactor = {e * i - f * h, c * h - b * i, b * f - c * e,
                f * g - d * i, a * i - c * g, c * d - a * f,
                d * h - e * g, b * g - a * h, a * e - b * d};
        float[] result = new float[9];
        for (int k = 0; k < 9; k++) result[k] = finite(cofactor[k] / determinant);
        return result;
    }

    /**
     * Rotation quaternion of a rigid column-major matrix with column scales removed.
     *
     * <p>A mirrored (negative-determinant) or singular matrix is not a rotation and is refused:
     * QDEF cannot represent a reflection, so silently extracting a "rotation" from one would publish
     * a wrong pose.</p>
     */
    static double[] quaternion(float[] m) {
        double[] columns = new double[3];
        for (int column = 0; column < 3; column++) {
            double x = m[column * 4], y = m[column * 4 + 1], z = m[column * 4 + 2];
            double length = Math.sqrt(x * x + y * y + z * z);
            if (length == 0) throw new IllegalArgumentException("Singular skin transform");
            columns[column] = length;
        }
        double r00 = m[0] / columns[0], r10 = m[1] / columns[0], r20 = m[2] / columns[0];
        double r01 = m[4] / columns[1], r11 = m[5] / columns[1], r21 = m[6] / columns[1];
        double r02 = m[8] / columns[2], r12 = m[9] / columns[2], r22 = m[10] / columns[2];
        double determinant = r00 * (r11 * r22 - r12 * r21) - r01 * (r10 * r22 - r12 * r20)
                + r02 * (r10 * r21 - r11 * r20);
        if (determinant < 0) throw new IllegalArgumentException("Mirrored skin transform");
        double trace = r00 + r11 + r22;
        double w, x, y, z;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = 0.25 * s; x = (r21 - r12) / s; y = (r02 - r20) / s; z = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            double s = Math.sqrt(1 + r00 - r11 - r22) * 2;
            w = (r21 - r12) / s; x = 0.25 * s; y = (r01 + r10) / s; z = (r02 + r20) / s;
        } else if (r11 > r22) {
            double s = Math.sqrt(1 + r11 - r00 - r22) * 2;
            w = (r02 - r20) / s; x = (r01 + r10) / s; y = 0.25 * s; z = (r12 + r21) / s;
        } else {
            double s = Math.sqrt(1 + r22 - r00 - r11) * 2;
            w = (r10 - r01) / s; x = (r02 + r20) / s; y = (r12 + r21) / s; z = 0.25 * s;
        }
        double length = Math.sqrt(w * w + x * x + y * y + z * z);
        if (length == 0 || !Double.isFinite(length)) throw new IllegalArgumentException("Singular skin transform");
        return new double[] {w / length, x / length, y / length, z / length};
    }

    /** Hamilton product of two quaternions stored as (w,x,y,z). */
    static double[] multiplyQuaternion(double[] a, double[] b) {
        return new double[] {
                a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
                a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
                a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
                a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]};
    }

    /** Rotates a vector by a unit quaternion stored as (w,x,y,z). */
    static double[] rotate(double[] q, double x, double y, double z) {
        double w = q[0], qx = q[1], qy = q[2], qz = q[3];
        double tx = 2 * (qy * z - qz * y), ty = 2 * (qz * x - qx * z), tz = 2 * (qx * y - qy * x);
        return new double[] {
                x + w * tx + (qy * tz - qz * ty),
                y + w * ty + (qz * tx - qx * tz),
                z + w * tz + (qx * ty - qy * tx)};
    }

    static float finite(double value) {
        float result = (float) value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Unrepresentable skin result");
        return result;
    }
}
