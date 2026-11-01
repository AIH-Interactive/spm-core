package dev.spm.core.format.v3d;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module-local coverage for the V3D logical-package codec. The end-to-end behaviour tests live in
 * {@code verification}; this pins the module's own capture/parse contract, its ownership and
 * idempotency, and its rejection of tampered metadata so the module is no longer zero-test.
 */
final class V3dCodecTest {
    private static final DecodePolicy POLICY = DecodePolicy.conservative();

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** A small synthetic payload shaped like a compiled historical wire. */
    private static byte[] payload() {
        byte[] bytes = new byte[96];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, 1); // inner version
        for (int i = 4; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    private static byte[] envelope() throws IOException {
        var budget = new ResourceBudget(32L << 20);
        try (var source = YsmEnvelopeCodec.encode(payload(), "archive", POLICY, budget, new CancellationToken())) {
            return source.bytes();
        }
    }

    @Test void captureProducesTheFourFixedEntriesAndRestoresTheOriginalByteForByte() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        try (var archive = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            assertEquals(Set.of(V3dManifest.SOURCE, V3dManifest.WIRE, V3dManifest.MANIFEST, V3dManifest.INTEGRITY),
                    archive.entries(), "the package must contain exactly the four logical entries");
            assertArrayEquals(original, archive.restoreOriginal(),
                    "restoring the source must reproduce the exact input bytes");
            assertEquals(sha(original), archive.manifest().source().sha256());
            assertEquals(V3dManifest.FORMAT, archive.manifest().format());
            assertEquals(V3dManifest.VERSION, archive.manifest().formatVersion());
        }
        assertEquals(0, budget.used(), "every credit must be released when the package closes");
    }

    @Test void theGenerationNameBindsTheSourceDigestAndDecoderProfile() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        try (var archive = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            String name = archive.generationName();
            assertTrue(name.startsWith(sha(original)),
                    "the generation name must start with the exact source digest so two different "
                            + "sources can never share a cache directory");
            assertTrue(name.endsWith(".v3d"));
            assertTrue(name.contains("p" + archive.manifest().decoderProfile()));
        }
    }

    @Test void aParsedPackageRoundTripsThroughCaptureAndExposesTheSameEntries() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        Map<ResourceKey, byte[]> files;
        try (var captured = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            files = new LinkedHashMap<>();
            for (ResourceKey key : captured.entries()) files.put(key, captured.read(key));
        }
        try (var parsed = V3dCodec.parse(files, V3dCodec.Verification.FULL, POLICY, budget, new CancellationToken())) {
            assertEquals(files.keySet(), parsed.entries());
            assertArrayEquals(payload(), parsed.read(V3dManifest.WIRE));
            assertArrayEquals(original, parsed.restoreOriginal());
        }
        assertEquals(0, budget.used());
    }

    @Test void tamperedWireBytesAreRejected() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        Map<ResourceKey, byte[]> files = new LinkedHashMap<>();
        try (var captured = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            for (ResourceKey key : captured.entries()) files.put(key, captured.read(key));
        }
        byte[] wire = files.get(V3dManifest.WIRE);
        wire[wire.length - 1] ^= 0x7f;
        files.put(V3dManifest.WIRE, wire);
        assertThrows(IOException.class,
                () -> V3dCodec.parse(files, V3dCodec.Verification.FULL, POLICY, budget, new CancellationToken()),
                "a modified payload must not be accepted under an exact-verification policy");
        assertEquals(0, budget.used());
    }

    @Test void aMissingRequiredEntryIsRejected() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        Map<ResourceKey, byte[]> files = new LinkedHashMap<>();
        try (var captured = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            for (ResourceKey key : captured.entries()) files.put(key, captured.read(key));
        }
        files.remove(V3dManifest.INTEGRITY);
        assertThrows(IOException.class,
                () -> V3dCodec.parse(files, V3dCodec.Verification.FULL, POLICY, budget, new CancellationToken()));
        assertEquals(0, budget.used());
    }

    @Test void anUnexpectedExtraEntryIsRejected() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        Map<ResourceKey, byte[]> files = new LinkedHashMap<>();
        try (var captured = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            for (ResourceKey key : captured.entries()) files.put(key, captured.read(key));
        }
        files.put(new ResourceKey("extra/unexpected.bin"), new byte[4]);
        assertThrows(IOException.class,
                () -> V3dCodec.parse(files, V3dCodec.Verification.FULL, POLICY, budget, new CancellationToken()));
    }

    @Test void aTightBudgetIsRejectedAndLeaksNothing() throws Exception {
        byte[] original = envelope();
        var tight = new ResourceBudget(64);
        assertThrows(IOException.class,
                () -> V3dCodec.capture(original, POLICY, tight, new CancellationToken()));
        assertEquals(0, tight.used());
    }

    @Test void anAlreadyCancelledTokenIsRejectedAndLeaksNothing() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        var token = new CancellationToken();
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> V3dCodec.capture(original, POLICY, budget, token));
        assertEquals(0, budget.used());
    }

    @Test void closingTwiceIsSafeAndExposesNothingAfterwards() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        var archive = V3dCodec.capture(original, POLICY, budget, new CancellationToken());
        assertFalse(archive.entries().isEmpty());
        archive.close();
        archive.close();
        assertThrows(IllegalStateException.class, archive::entries,
                "a closed package must expose nothing");
        assertEquals(0, budget.used());
    }

    @Test void ownedBytesAreDefensiveCopiesNotAliasesOfThePackage() throws Exception {
        byte[] original = envelope();
        var budget = new ResourceBudget(128L << 20);
        try (var archive = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
            byte[] wire = archive.read(V3dManifest.WIRE);
            wire[0] ^= 0x55;
            assertFalse(java.util.Arrays.equals(wire, archive.read(V3dManifest.WIRE)),
                    "mutating a returned array must not change the package's own storage");
        }
    }
}
