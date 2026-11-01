package dev.spm.core.api;

/**
 * Immutable spherical-blend (SDEF) parameters for one mesh: one joint sphere per vertex.
 *
 * <p>SDEF skins a vertex around a sphere instead of around a single transformed point. The sphere
 * centre is carried by the influence transforms (so it follows the joint), while the offset from the
 * centre to the vertex is only rotated (so the sphere keeps its radius under any amount of twist,
 * where linear blend skinning would collapse it).</p>
 *
 * <p><b>Deliberate limitation.</b> A historically authored SDEF vertex also carries two ellipsoid
 * radii {@code R0}/{@code R1} measured along two source axes. No source format handled by this
 * project exposes those radii together with the axis convention they are measured in, so
 * {@link #radii()} is validated, retained and re-exposed as archival input but is <em>not</em>
 * applied: rescaling a vertex along an axis this code cannot identify would fabricate deformation
 * instead of reproducing it. A caller that knows the convention must apply the scaling itself before
 * handing vertices in, or accept that only the sphere-carrying half of SDEF is reproduced.</p>
 *
 * <p>Centres are the sphere position in model-space Core units, one three-component point per vertex,
 * expressed independently of any bone pivot — they are the vertex's own sphere, not an offset from a
 * joint. {@link #zeros(int)} therefore places every sphere at the model origin, which is only a
 * sensible default for geometry that actually sits there; a real rig supplies authored centres.</p>
 */
public record SdefParameters(float[] centres, float[] radii) {
    /** No authored data at all; {@link SkinningRig} refuses an SDEF binding built from this value. */
    public static SdefParameters none() { return new SdefParameters(new float[0], new float[0]); }

    public SdefParameters {
        centres = centres.clone();
        radii = radii.clone();
        if (centres.length % 3 != 0) throw new IllegalArgumentException("SDEF centre sizes disagree");
        if (radii.length != 0 && radii.length % 2 != 0)
            throw new IllegalArgumentException("SDEF radius sizes disagree");
        for (float value : centres) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite SDEF centre");
        }
        for (float value : radii) {
            if (!Float.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid SDEF radius");
        }
    }

    /** True when an authored sphere centre exists, which an SDEF binding requires. */
    public boolean hasCentres() { return centres.length != 0; }

    /** Centres are mandatory for a real SDEF binding; radii, when present, must match too. */
    public void requireVertexCount(int vertexCount) {
        if (centres.length != 0 && centres.length != Math.multiplyExact(vertexCount, 3))
            throw new IllegalArgumentException("SDEF centres do not match the vertex count");
        if (radii.length != 0 && radii.length != Math.multiplyExact(vertexCount, 2))
            throw new IllegalArgumentException("SDEF radii do not match the vertex count");
    }

    /** Zero-filled centres of the shape an SDEF binding requires; the caller fills in the spheres. */
    public static SdefParameters zeros(int vertexCount) {
        return new SdefParameters(new float[Math.multiplyExact(vertexCount, 3)], new float[0]);
    }

    public float centre(int vertex, int axis) {
        java.util.Objects.checkIndex(axis, 3);
        return centres[vertex * 3 + axis];
    }

    public float radius(int vertex, int axis) {
        java.util.Objects.checkIndex(axis, 2);
        return radii[vertex * 2 + axis];
    }

    @Override public float[] centres() { return centres.clone(); }
    @Override public float[] radii() { return radii.clone(); }
}
