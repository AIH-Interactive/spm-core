package dev.spm.core.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Versioned descriptor for a derived cache entry. This is a <em>contract value</em>, not a codec:
 * it says precisely which three version layers a cached payload was produced under, so a Host can
 * decide to reuse or rebuild it without guessing from a file extension or a library version.
 *
 * <p>The three layers are deliberately independent and must never be derived from one another:
 * <ol>
 *   <li><b>wire</b> — {@code wireId}/{@code wireVersion}: the on-disk or on-network encoding
 *       (for example the YSM archive or a V3D logical package).</li>
 *   <li><b>schema</b> — {@code schemaVersion}: the logical IR schema the payload was lowered to.</li>
 *   <li><b>profile</b> — {@code profileFingerprint}: the {@link DecodeProfile} limits and identity
 *       that were in force when the payload was produced.</li>
 * </ol>
 *
 * <p>Changing the JNI or C ABI, or bumping a product library version, must <em>not</em> invalidate a
 * model cache: none of those layers appear here. Conversely a payload produced under a local-model
 * profile must not be reused for untrusted input, which is why the profile fingerprint is part of
 * the identity rather than an afterthought.
 *
 * <p>A payload digest is carried so a Host can verify content independently of the path it found it
 * at. An incompatible entry is <b>rejected or rebuilt</b>; Core never deletes a user's original
 * model to reclaim derived data.
 */
public record CacheWire(String wireId, int wireVersion, int schemaVersion,
                        String profileFingerprint, String contentSha256, long payloadBytes) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** Bumped when the logical IR schema changes shape in a way older readers cannot represent. */
    public static final int CURRENT_SCHEMA_VERSION = 1;
    /** Upper bound for one cached payload; larger entries are refused before allocation. */
    public static final long MAX_PAYLOAD_BYTES = 256L << 20;

    public CacheWire {
        Objects.requireNonNull(wireId, "wireId");
        Objects.requireNonNull(profileFingerprint, "profileFingerprint");
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (wireId.isBlank() || wireId.length() > 128) throw new IllegalArgumentException("Invalid cache wire id");
        if (wireVersion < 1) throw new IllegalArgumentException("Cache wire version must be positive");
        if (schemaVersion < 1) throw new IllegalArgumentException("Cache schema version must be positive");
        if (profileFingerprint.isBlank() || profileFingerprint.length() > 512) {
            throw new IllegalArgumentException("Invalid cache profile fingerprint");
        }
        String lower = contentSha256.toLowerCase(java.util.Locale.ROOT);
        if (!SHA256.matcher(lower).matches()) throw new IllegalArgumentException("Invalid cache content SHA-256");
        contentSha256 = lower;
        if (payloadBytes < 0 || payloadBytes > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Cache payload size out of range");
        }
    }

    /** Builds a descriptor from a profile so the fingerprint can never be hand-written wrong. */
    public static CacheWire of(String wireId, int wireVersion, DecodeProfile profile,
                               String contentSha256, long payloadBytes) {
        Objects.requireNonNull(profile, "profile");
        return new CacheWire(wireId, wireVersion, CURRENT_SCHEMA_VERSION, profile.fingerprint(),
                contentSha256, payloadBytes);
    }

    /** What a Host should do with an entry it found. */
    public enum Verdict {
        /** Reuse the payload unchanged. */
        REUSE,
        /** The wire or schema layer moved; the payload must be produced again. */
        REBUILD,
        /** Same wire and schema but a different decode profile; produce a separate entry. */
        REPROFILE
    }

    /**
     * Classifies this entry against what the Host can currently consume. A different content digest
     * is not a version problem and is reported as {@link Verdict#REBUILD}: the bytes on disk no
     * longer match what the descriptor claims.
     */
    public Verdict classify(String expectedWireId, int expectedWireVersion, int expectedSchemaVersion,
                            String expectedProfileFingerprint, String expectedContentSha256) {
        Objects.requireNonNull(expectedWireId, "expectedWireId");
        Objects.requireNonNull(expectedProfileFingerprint, "expectedProfileFingerprint");
        Objects.requireNonNull(expectedContentSha256, "expectedContentSha256");
        if (!wireId.equals(expectedWireId)
                || wireVersion != expectedWireVersion
                || schemaVersion != expectedSchemaVersion) {
            return Verdict.REBUILD;
        }
        if (!contentSha256.equalsIgnoreCase(expectedContentSha256)) return Verdict.REBUILD;
        if (!profileFingerprint.equals(expectedProfileFingerprint)) return Verdict.REPROFILE;
        return Verdict.REUSE;
    }

    /** Throws unless this entry is exactly reusable; convenient for strict load paths. */
    public void requireReusable(String expectedWireId, int expectedWireVersion, int expectedSchemaVersion,
                                String expectedProfileFingerprint, String expectedContentSha256) {
        Verdict verdict = classify(expectedWireId, expectedWireVersion, expectedSchemaVersion,
                expectedProfileFingerprint, expectedContentSha256);
        if (verdict != Verdict.REUSE) {
            throw new IllegalStateException("Derived cache is not reusable (" + verdict + "): " + wireId);
        }
    }
}
