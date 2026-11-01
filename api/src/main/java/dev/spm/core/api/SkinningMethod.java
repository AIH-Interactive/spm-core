package dev.spm.core.api;

/**
 * Host-neutral weighted skinning method. The choice is model rigging data, not a host GPU state.
 *
 * <p>{@link #BDEF} is linear blend skinning; it is defined for every nonsingular skin transform.
 * {@link #QDEF} blends dual quaternions and preserves volume under rotation, but it can represent
 * only orientation-preserving rigid transforms with a uniform scale, and a blend of opposing
 * rotations can cancel the quaternion sum. {@link #SDEF} needs per-vertex spherical parameters that
 * no supported source format stores.</p>
 */
public enum SkinningMethod { BDEF, SDEF, QDEF }
