package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import dev.spm.core.codec.legacy.LegacyCipher;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class YsmWriterEnvelopeTest {
    private static byte[] fixture(String path) throws IOException {
        try (var input = YsmWriterEnvelopeTest.class.getResourceAsStream("/ysm/" + path)) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    @Test void inverseCipherReproducesFrozenHistoricalEncryptedBodyAcrossDynamicChunks() throws Exception {
        byte[] original = fixture("legacy_v3_dynamic_vector.ysm");
        int body = 13, footer = original.length - 64;
        byte[] password = Arrays.copyOfRange(original, footer, footer + 56);
        byte[] transformed = original.clone();
        LegacyCipher.decrypt(transformed, body, footer - body, password, new CancellationToken());
        LegacyCipher.encrypt(transformed, body, footer - body, password, new CancellationToken());
        assertArrayEquals(original, transformed);
    }
    @Test void emitsRealEncryptedEnvelopeForEveryHistoricalWireVersion() throws Exception {
        var budget = new ResourceBudget(128L << 20); var policy = DecodePolicy.conservative();
        for (int version = 1; version <= 32; version++) {
            byte[] wire = fixture("historical/v" + version + ".wire");
            try (var encoded = YsmEnvelopeCodec.encode(wire, "模型 " + version, policy, budget, new CancellationToken())) {
                assertEquals(encoded.bytes().length, budget.used());
                try (var plaintext = YsmEnvelopeCodec.decode(encoded.bytes(), policy, budget, new CancellationToken())) {
                    assertArrayEquals(wire, plaintext.bytes());
                    assertEquals(version, plaintext.innerVersion());
                }
            }
            assertEquals(0, budget.used());
        }
    }
    @Test void rejectsInvalidWireSummaryLimitsAndCancellationWithoutLeakingBudget() throws Exception {
        var budget = new ResourceBudget(128L << 20); var policy = DecodePolicy.conservative();
        byte[] wire = fixture("historical/v32.wire");
        for (byte[] bad : List.of(new byte[0], new byte[] {33,0,0,0})) {
            assertThrows(IOException.class, () -> YsmEnvelopeCodec.encode(bad, "", policy, budget, new CancellationToken()));
        }
        assertThrows(IOException.class, () -> YsmEnvelopeCodec.encode(wire, "a\0b", policy, budget, new CancellationToken()));
        assertThrows(IOException.class, () -> YsmEnvelopeCodec.encode(wire, "abc", new DecodePolicy(10000,10000,1000,2,32), budget, new CancellationToken()));
        var cancelled = new CancellationToken(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> YsmEnvelopeCodec.encode(wire, "", policy, budget, cancelled));
        var tiny = new ResourceBudget(100);
        assertThrows(IOException.class, () -> YsmEnvelopeCodec.encode(wire, "", policy, tiny, new CancellationToken()));
        assertEquals(0, tiny.used()); assertEquals(0, budget.used());
    }

    @Test void handlesMultipleRawAndCompressedBlocksWithFreshPasswords() throws Exception {
        var budget = new ResourceBudget(128L << 20); var policy = DecodePolicy.conservative();
        for (boolean repetitive : new boolean[] {false, true}) {
            byte[] wire = new byte[350000];
            if (!repetitive) new Random(91371).nextBytes(wire);
            wire[0] = 1; wire[1] = 0; wire[2] = 0; wire[3] = 0;
            try (var one = YsmEnvelopeCodec.encode(wire, "blocks", policy, budget, new CancellationToken());
                 var two = YsmEnvelopeCodec.encode(wire, "blocks", policy, budget, new CancellationToken())) {
                assertFalse(Arrays.equals(one.bytes(), two.bytes()));
                assertEquals(one.bytes().length + two.bytes().length, budget.used());
                try (var decoded = YsmEnvelopeCodec.decode(one.bytes(), policy, budget, new CancellationToken())) {
                    assertArrayEquals(wire, decoded.bytes());
                }
            }
            assertEquals(0, budget.used());
        }
    }
}
