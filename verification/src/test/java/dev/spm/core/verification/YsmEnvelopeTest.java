package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import dev.spm.core.codec.legacy.LegacyHash;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class YsmEnvelopeTest {
    private byte[] vector() throws IOException {
        try (var input = getClass().getResourceAsStream("/ysm/legacy_v3_dynamic_vector.ysm")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }

    @Test void decodesApprovedNativeWriterVectorAcrossDynamicCipherBoundary() throws Exception {
        byte[] source = vector();
        assertEquals("548e5610a57e561f84878882f14dd3557aac1c73c073accc5ee970312d8ac2f3", sha(source));
        var budget = new ResourceBudget(64L << 20);
        var payload = YsmEnvelopeCodec.decode(source, DecodePolicy.conservative(), budget, new CancellationToken());
        assertEquals(1, payload.innerVersion());
        assertEquals(12004, payload.bytes().length);
        assertEquals("3e41fd30736a8b89eb043e8eeefc7cc328491580e88a395ab2c4062df5ad0ea6", sha(payload.bytes()));
        byte[] altered = payload.bytes(); altered[0] = 99;
        assertEquals(1, payload.bytes()[0]);
        assertEquals(12004, budget.used());
        payload.close(); payload.close();
        assertEquals(0, budget.used());
        assertThrows(IllegalStateException.class, payload::bytes);
    }

    @Test void rejectsTamperingEveryTruncatedPrefixAndOutputLimitWithoutLeaks() throws Exception {
        byte[] source = vector();
        var failures = new ArrayList<byte[]>();
        for (int cut : new int[] {0, 7, 12, 64, 1000, source.length - 1}) failures.add(Arrays.copyOf(source, cut));
        byte[] corrupt = source.clone(); corrupt[100] ^= 1; failures.add(corrupt);
        for (byte[] malformed : failures) {
            var budget = new ResourceBudget(1 << 20);
            assertThrows(IOException.class, () -> YsmEnvelopeCodec.decode(malformed, DecodePolicy.conservative(), budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
        var budget = new ResourceBudget(1 << 20);
        var tiny = new DecodePolicy(20000, 12003, 32, 1024, 8);
        assertThrows(IOException.class, () -> YsmEnvelopeCodec.decode(source, tiny, budget, new CancellationToken()));
        assertEquals(0, budget.used());
        var cancelled = new CancellationToken(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> YsmEnvelopeCodec.decode(source, DecodePolicy.conservative(), budget, cancelled));
        assertEquals(0, budget.used());
    }

    @Test void rejectsForgedChecksumsThatReachVersionCipherAndSaltValidation() throws Exception {
        byte[] valid = vector();
        for (int index : new int[] {9, 13, valid.length - 65, valid.length - 64}) {
            byte[] forged = valid.clone(); forged[index] ^= 0x5a;
            long checksum = LegacyHash.seeded(forged, 0, forged.length - 8, 0x9e5599db80c67c29L);
            for (int i = 0; i < 8; i++) forged[forged.length - 8 + i] = (byte) (checksum >>> (i * 8));
            var budget = new ResourceBudget(1 << 20);
            assertThrows(IOException.class, () -> YsmEnvelopeCodec.decode(forged, DecodePolicy.conservative(), budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
    }

    @Test void accountsForDecompressorWorkspaceBeforeReadingCompressedData() throws Exception {
        byte[] source = vector();
        var budget = new ResourceBudget(30000); // Fits source + plaintext, excludes decoder workspace.
        assertThrows(IOException.class, () -> YsmEnvelopeCodec.decode(source, DecodePolicy.conservative(), budget, new CancellationToken()));
        assertEquals(0, budget.used());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
