package dev.spm.core.api;

import java.util.Objects;

/**
 * Named, versioned decode profile. A policy is a set of numeric limits; a profile additionally
 * names <em>which</em> environment those limits were chosen for, so a Host can record that choice
 * and a cache can refuse to mix work produced under different trust assumptions.
 *
 * <p>The profile is pure Core data: it carries no authentication result, no Peer identity and no
 * permission. A Host that needs a stricter remote policy constructs one explicitly; selecting
 * {@link #LOCAL_MODEL} never implies that remote input is trusted.
 *
 * <p>Two profiles are equal only when the id, revision and every {@link DecodePolicy} field match,
 * so a changed limit cannot silently keep an old name.
 */
public record DecodeProfile(String id, int revision, DecodePolicy policy) {
    public static final String LOCAL_ID = "spm.local-model";
    public static final String CONSERVATIVE_ID = "spm.conservative";
    /**
     * Explicit local-file workload. This mirrors {@link DecodePolicy#localModel()} and is not a
     * statement about the trustworthiness of the bytes being decoded.
     */
    public static final DecodeProfile LOCAL_MODEL = new DecodeProfile(LOCAL_ID, 1, DecodePolicy.localModel());
    /** Smallest supported limits; appropriate for input whose origin is not established. */
    public static final DecodeProfile CONSERVATIVE = new DecodeProfile(CONSERVATIVE_ID, 1, DecodePolicy.conservative());

    public DecodeProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        if (id.isBlank() || id.length() > 128) throw new IllegalArgumentException("Invalid decode profile id");
        if (revision < 1) throw new IllegalArgumentException("Decode profile revision must be positive");
    }

    /** Rejects a different id or revision even when the numeric limits happen to be equal. */
    public void requireCompatible(DecodeProfile other) {
        Objects.requireNonNull(other, "other");
        if (!equals(other)) {
            throw new IllegalArgumentException("Incompatible decode profile: " + id + "/" + revision);
        }
    }

    /** Stable textual identity for cache keys and diagnostics; never a security token. */
    public String fingerprint() {
        return id + "@" + revision + "[in=" + policy.maxInputBytes()
                + ",exp=" + policy.maxExpandedBytes()
                + ",ent=" + policy.maxEntries()
                + ",str=" + policy.maxStringBytes()
                + ",depth=" + policy.maxDepth()
                + ",obj=" + policy.maxObjects()
                + ",coll=" + policy.maxCollectionEntries() + "]";
    }
}
