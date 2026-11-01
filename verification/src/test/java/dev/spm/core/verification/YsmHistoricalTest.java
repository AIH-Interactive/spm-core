package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class YsmHistoricalTest {
    private byte[] sample(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/ysm/historical/" + name + ".wire")) {
            assertNotNull(stream); return stream.readAllBytes();
        }
    }
    @Test void readsAll32IndependentCppFixturesPreservingRootFamiliesAndIdentity() throws Exception {
        for (int version = 1; version <= 32; version++) {
            byte[] wire = sample("v" + version);
            var budget = new ResourceBudget(1 << 20);
            try (var decoded = YsmHistoricalCodec.decode(wire, DecodePolicy.conservative(), budget, new CancellationToken())) {
                assertEquals(version, decoded.innerVersion());
                var root = decoded.root();
                if (version < 19) {
                    assertEquals("000102030405060708090a0b0c0d0e0f", ((HistoricalValue.Text) root.field("identity")).value());
                    assertTrue(root.fields().containsKey("model_types"));
                    assertFalse(root.fields().containsKey("common"));
                } else {
                    var info = (HistoricalValue.Struct) root.field("info");
                    assertEquals("000102030405060708090a0b0c0d0e0f", ((HistoricalValue.Text) info.field("hash")).value());
                    assertTrue(root.fields().containsKey("common"));
                    assertFalse(root.fields().containsKey("model_types"));
                }
                assertTrue(budget.used() > 0);
                assertThrows(UnsupportedOperationException.class, () -> root.fields().clear());
            }
            assertEquals(0, budget.used());
            var shortBudget = new ResourceBudget(1 << 20);
            assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(Arrays.copyOf(wire, wire.length - 1), DecodePolicy.conservative(), shortBudget, new CancellationToken()));
            assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(Arrays.copyOf(wire, wire.length + 1), DecodePolicy.conservative(), shortBudget, new CancellationToken()));
            assertEquals(0, shortBudget.used());
        }
    }
    @Test void preservesV5QuadsAndRawFloatInfinityWithoutRuntimeProjection() throws Exception {
        var budget = new ResourceBudget(1 << 20);
        try (var decoded = YsmHistoricalCodec.decode(sample("v5-face"), DecodePolicy.conservative(), budget, new CancellationToken())) {
            var geos = (HistoricalValue.Mapping) decoded.root().field("geos");
            var geo = (HistoricalValue.Struct) ((HistoricalValue.Presence) geos.entries().get(0).value()).value();
            var bone = (HistoricalValue.Struct) ((HistoricalValue.Sequence) geo.field("bones")).values().get(0);
            assertEquals(1, ((HistoricalValue.Sequence) bone.field("quads")).values().size());
            assertEquals(1, ((HistoricalValue.Unsigned) geo.field("global_cube_count")).bits());
        }
        try (var decoded = YsmHistoricalCodec.decode(sample("v32-infinite-animation"), DecodePolicy.conservative(), budget, new CancellationToken())) {
            var player = (HistoricalValue.Struct) ((HistoricalValue.Presence) decoded.root().field("player")).value();
            var animationFile = (HistoricalValue.Struct) ((HistoricalValue.Struct) ((HistoricalValue.Mapping) player.field("animations")).entries().get(0).value()).field("value");
            var animation = (HistoricalValue.Struct) ((HistoricalValue.Sequence) animationFile.field("animations")).values().get(0);
            assertEquals(0x7f800000, ((HistoricalValue.Float32) animation.field("length")).rawBits());
            assertFalse(((HistoricalValue.Presence) animation.field("start_delay")).present());
        }
        assertEquals(0, budget.used());
    }
    @Test void rejectsMalformedVarintsUnknownVersionsDepthAndAggregateEntryLimits() throws Exception {
        var budget = new ResourceBudget(1 << 20);
        byte[] wire = sample("v32");
        for (DecodePolicy policy : List.of(new DecodePolicy(10000, 10000, 1, 1000, 100),
                new DecodePolicy(10000, 10000, 1000, 1000, 1),
                new DecodePolicy(10000, 10000, 1000, 1, 100))) {
            assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(wire, policy, budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
        for (byte[] malformed : List.of(new byte[] {33,0,0,0}, new byte[] {1,0,0,0,(byte) 0x80,0},
                new byte[] {1,0,0,0,(byte) 0xff,(byte) 0xff,(byte) 0xff,(byte) 0xff,0x10})) {
            assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(malformed, DecodePolicy.conservative(), budget, new CancellationToken()));
            assertEquals(0, budget.used());
        }
    }
}
