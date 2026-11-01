package dev.spm.core.verification;

import dev.spm.core.api.CacheWire;
import dev.spm.core.api.DecodeProfile;
import dev.spm.core.api.DecodePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T04 named this test and it did not exist. It pins the <em>version negotiation</em> contract
 * for derived caches: three independent layers (wire, schema, profile) plus a content digest.
 *
 * <p>The point is not that a codec exists — it does not, and {@code cache-wire-spec.md} says so.
 * The point is that a Host can now classify a cached entry as reusable, rebuildable or
 * profile-incompatible instead of guessing from a file extension or a library version, and that a
 * JNI/ABI or product-version bump cannot silently invalidate a user's model cache.
 */
final class CacheWireCompatibilityTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String WIRE = "ysm-archive";

    @Test void identicalLayersAreReusable() {
        var entry = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 1024);
        assertEquals(CacheWire.Verdict.REUSE, entry.classify(WIRE, 1, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A));
        assertDoesNotThrow(() -> entry.requireReusable(WIRE, 1, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A));
    }

    @Test void wireVersionSchemaVersionAndDigestEachForceARebuild() {
        var entry = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 1024);
        assertEquals(CacheWire.Verdict.REBUILD,
                entry.classify("other-wire", 1, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A),
                "a different wire id must rebuild");
        assertEquals(CacheWire.Verdict.REBUILD,
                entry.classify(WIRE, 2, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A),
                "a different wire version must rebuild");
        assertEquals(CacheWire.Verdict.REBUILD,
                entry.classify(WIRE, 1, 2, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A),
                "a different schema version must rebuild");
        assertEquals(CacheWire.Verdict.REBUILD,
                entry.classify(WIRE, 1, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_B),
                "changed bytes must rebuild even when the digest field itself is well formed");
    }

    @Test void aDifferentDecodeProfileIsReportedSeparatelyFromARebuild() {
        var entry = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 1024);
        assertEquals(CacheWire.Verdict.REPROFILE,
                entry.classify(WIRE, 1, 1, DecodeProfile.CONSERVATIVE.fingerprint(), SHA_A),
                "same wire and schema but a different decode profile is a separate entry, not a corruption");
        assertThrows(IllegalStateException.class,
                () -> entry.requireReusable(WIRE, 1, 1, DecodeProfile.CONSERVATIVE.fingerprint(), SHA_A));
    }

    @Test void jniAndLibraryVersionsDoNotAppearInTheCacheIdentity() {
        // The cache identity is built from wire/schema/profile/digest only. Nothing in this type
        // accepts a JNI ABI number or a product version, so bumping either cannot invalidate a cache.
        var first = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 10);
        var second = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 10);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        for (var component : CacheWire.class.getRecordComponents()) {
            String name = component.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("abi"), "cache identity must not carry an ABI version: " + name);
            assertFalse(name.contains("library"), "cache identity must not carry a library version: " + name);
        }
    }

    @Test void constructionRejectsMalformedDescriptors() {
        assertThrows(IllegalArgumentException.class, () -> new CacheWire("", 1, 1, "p", SHA_A, 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheWire(WIRE, 0, 1, "p", SHA_A, 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheWire(WIRE, 1, 0, "p", SHA_A, 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheWire(WIRE, 1, 1, "", SHA_A, 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheWire(WIRE, 1, 1, "p", "nothex", 1));
        assertThrows(IllegalArgumentException.class, () -> new CacheWire(WIRE, 1, 1, "p", SHA_A, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheWire(WIRE, 1, 1, "p", SHA_A, CacheWire.MAX_PAYLOAD_BYTES + 1));
    }

    @Test void digestIsNormalizedToLowerCaseSoCaseNeverChangesTheVerdict() {
        var entry = CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A.toUpperCase(java.util.Locale.ROOT), 1);
        assertEquals(SHA_A, entry.contentSha256());
        assertEquals(CacheWire.Verdict.REUSE,
                entry.classify(WIRE, 1, 1, DecodeProfile.LOCAL_MODEL.fingerprint(), SHA_A.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test void profileFingerprintTracksEveryLimitAndTheProfileIdentity() {
        var local = DecodeProfile.LOCAL_MODEL;
        var conservative = DecodeProfile.CONSERVATIVE;
        assertNotEquals(local.fingerprint(), conservative.fingerprint());
        assertThrows(IllegalArgumentException.class, () -> local.requireCompatible(conservative));

        // Same numeric limits but a different id must still be incompatible: a name is a promise.
        var renamed = new DecodeProfile("spm.renamed", 1, local.policy());
        assertThrows(IllegalArgumentException.class, () -> local.requireCompatible(renamed));
        assertNotEquals(local.fingerprint(), renamed.fingerprint());

        // Same id and limits but a different revision must also be incompatible.
        var revised = new DecodeProfile(local.id(), 2, local.policy());
        assertThrows(IllegalArgumentException.class, () -> local.requireCompatible(revised));

        // Changing one numeric limit changes the fingerprint even under the same id/revision.
        var stricter = new DecodePolicy(local.policy().maxInputBytes() - 1, local.policy().maxExpandedBytes(),
                local.policy().maxEntries(), local.policy().maxStringBytes(), local.policy().maxDepth(),
                local.policy().maxObjects(), local.policy().maxCollectionEntries());
        var changed = new DecodeProfile(local.id(), local.revision(), stricter);
        assertNotEquals(local.fingerprint(), changed.fingerprint());
    }

    @Test void builtInProfilesAreConsistentWithTheirPolicies() {
        assertEquals(DecodePolicy.localModel(), DecodeProfile.LOCAL_MODEL.policy());
        assertEquals(DecodePolicy.conservative(), DecodeProfile.CONSERVATIVE.policy());
        assertEquals(1, DecodeProfile.LOCAL_MODEL.revision());
        assertEquals(CacheWire.CURRENT_SCHEMA_VERSION, CacheWire.of(WIRE, 1, DecodeProfile.LOCAL_MODEL, SHA_A, 1).schemaVersion());
    }
}
