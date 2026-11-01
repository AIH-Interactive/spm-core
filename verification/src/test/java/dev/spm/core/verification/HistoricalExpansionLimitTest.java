package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F02 regression: {@code maxExpandedBytes} bounds the decoded historical tree per import, not only the
 * wire length. A decode that fits the shared budget must still respect a stricter expansion limit.
 */
final class HistoricalExpansionLimitTest {
    private static byte[] sample(String name) throws IOException {
        try (var input = HistoricalExpansionLimitTest.class.getResourceAsStream("/ysm/historical/" + name + ".wire")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    @Test void strictExpansionLimitRejectsATreeThatWouldOtherwiseFitTheSharedBudget() throws Exception {
        byte[] wire = sample("v1");
        var generous = new ResourceBudget(1 << 22);
        long retained;
        var measured = YsmHistoricalCodec.decode(wire, DecodePolicy.conservative(), generous, new CancellationToken());
        try { retained = generous.used(); } finally { measured.close(); }
        assertEquals(0, generous.used());
        assertTrue(retained > wire.length, "the decoded tree is larger than its wire, so the limit must apply to the tree");

        // Exactly at the retained charge is accepted.
        var exactBudget = new ResourceBudget(1 << 22);
        var exact = new DecodePolicy(wire.length, retained, 16384, 1 << 20, 32);
        var decoded = YsmHistoricalCodec.decode(wire, exact, exactBudget, new CancellationToken());
        try { assertNotNull(decoded.root()); } finally { decoded.close(); }
        assertEquals(0, exactBudget.used());

        // One byte less is rejected and leaks nothing.
        var tightBudget = new ResourceBudget(1 << 22);
        var tight = new DecodePolicy(wire.length, retained - 1, 16384, 1 << 20, 32);
        assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(wire, tight, tightBudget, new CancellationToken()));
        assertEquals(0, tightBudget.used());

        // The reported defect: wire length equalled maxExpandedBytes while the tree was ~12x larger.
        var defectBudget = new ResourceBudget(1 << 22);
        var asReported = new DecodePolicy(wire.length, wire.length, 16384, 1 << 20, 32);
        assertThrows(IOException.class, () -> YsmHistoricalCodec.decode(wire, asReported, defectBudget, new CancellationToken()));
        assertEquals(0, defectBudget.used());
    }

    @Test void everyFixtureHonoursItsOwnTreeLimit() throws Exception {
        for (int version = 1; version <= 32; version++) {
            byte[] wire = sample("v" + version);
            var sizing = new ResourceBudget(1 << 22);
            long retained;
            var probe = YsmHistoricalCodec.decode(wire, DecodePolicy.conservative(), sizing, new CancellationToken());
            try { retained = sizing.used(); } finally { probe.close(); }
            assertEquals(0, sizing.used());
            var budget = new ResourceBudget(1 << 22);
            var oneUnder = new DecodePolicy(wire.length, retained - 1, 16384, 1 << 20, 32);
            assertThrows(IOException.class,
                    () -> YsmHistoricalCodec.decode(wire, oneUnder, budget, new CancellationToken()),
                    "v" + version + " must reject a tree limit one byte below its retained charge");
            assertEquals(0, budget.used(), "v" + version + " must not leak on rejection");
        }
    }
}
