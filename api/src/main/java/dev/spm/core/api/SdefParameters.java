package dev.spm.core.api;

import java.util.Objects;

/**
 * Immutable spherical-blend (SDEF) parameters for one mesh: one joint sphere per vertex, plus
 * optional two-anchor {@code R0}/{@code R1} reference vectors and archival scalar {@code radii}.
 *
 * <p>SDEF skins a vertex around a sphere instead of around a single transformed point. The sphere
 * centre is carried by the influence transforms (so it follows the joint), while the offset from the
 * centre to the vertex is only rotated (so the sphere keeps its radius under any amount of twist,
 * where linear blend skinning would collapse it).</p>
 *
 * <p>When 3D {@code anchorsR0} and {@code anchorsR1} are supplied (matching the PMD/MMD {@code C/R0/R1}
 * specification), the carried sphere centre evaluates {@code sum(w_i * (M_i * (C + rw_i)))} where
 * {@code d = R0 - R1}, {@code rw_0 = (1 - u_0) * d} and {@code rw_{i>0} = -u_0 * d}, preserving the
 * identity {@code sum(u_i * rw_i) = 0} so rigid agreement {@code M_i = M} always reproduces {@code M * C}.</p>
 *
 * <p>When only scalar {@link #radii()} are supplied without 3D anchor vectors, {@link #radii()} is
 * validated, retained and re-exposed as archival input without fabricating an axis convention.</p>
 */
public record SdefParameters(float[] centres, float[] radii, float[] anchorsR0, float[] anchorsR1) {
    /** No authored data at all; {@link SkinningRig} refuses an SDEF binding built from this value. */
    public static SdefParameters none() {
        return new SdefParameters(new float[0], new float[0], new float[0], new float[0]);
    }

    public SdefParameters(float[] centres, float[] radii) {
        this(centres, radii, new float[0], new float[0]);
    }

    public SdefParameters {
        centres = Objects.requireNonNull(centres).clone();
        radii = Objects.requireNonNull(radii).clone();
        anchorsR0 = Objects.requireNonNull(anchorsR0).clone();
        anchorsR1 = Objects.requireNonNull(anchorsR1).clone();
        if (centres.length % 3 != 0) throw new IllegalArgumentException("SDEF centre sizes disagree");
        if (radii.length != 0 && radii.length % 2 != 0)
            throw new IllegalArgumentException("SDEF radius sizes disagree");
        if (anchorsR0.length != anchorsR1.length || anchorsR0.length % 3 != 0)
            throw new IllegalArgumentException("SDEF anchor sizes disagree");
        for (float value : centres) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite SDEF centre");
        }
        for (float value : radii) {
            if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid SDEF radius");
        }
        for (float value : anchorsR0) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite SDEF anchor");
        }
        for (float value : anchorsR1) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite SDEF anchor");
        }
    }

    /** True when an authored sphere centre exists, which an SDEF binding requires. */
    public boolean hasCentres() { return centres.length != 0; }

    /** True when explicit 3D {@code R0}/{@code R1} reference anchors exist. */
    public boolean hasAnchors() { return anchorsR0.length != 0; }

    /** Centres are mandatory for a real SDEF binding; radii and anchors, when present, must match too. */
    public void requireVertexCount(int vertexCount) {
        if (centres.length != 0 && centres.length != Math.multiplyExact(vertexCount, 3))
            throw new IllegalArgumentException("SDEF centres do not match the vertex count");
        if (radii.length != 0 && radii.length != Math.multiplyExact(vertexCount, 2))
            throw new IllegalArgumentException("SDEF radii do not match the vertex count");
        if (anchorsR0.length != 0 && anchorsR0.length != Math.multiplyExact(vertexCount, 3))
            throw new IllegalArgumentException("SDEF anchors do not match the vertex count");
    }

    /** Zero-filled centres of the shape an SDEF binding requires; the caller fills in the spheres. */
    public static SdefParameters zeros(int vertexCount) {
        return new SdefParameters(new float[Math.multiplyExact(vertexCount, 3)], new float[0]);
    }

    public float centre(int vertex, int axis) {
        Objects.checkIndex(axis, 3);
        return centres[vertex * 3 + axis];
    }

    public float radius(int vertex, int axis) {
        Objects.checkIndex(axis, 2);
        return radii[vertex * 2 + axis];
    }

    public float anchorR0(int vertex, int axis) {
        Objects.checkIndex(axis, 3);
        return anchorsR0[vertex * 3 + axis];
    }

    public float anchorR1(int vertex, int axis) {
        Objects.checkIndex(axis, 3);
        return anchorsR1[vertex * 3 + axis];
    }

    @Override public float[] centres() { return centres.clone(); }
    @Override public float[] radii() { return radii.clone(); }
    @Override public float[] anchorsR0() { return anchorsR0.clone(); }
    @Override public float[] anchorsR1() { return anchorsR1.clone(); }
}
