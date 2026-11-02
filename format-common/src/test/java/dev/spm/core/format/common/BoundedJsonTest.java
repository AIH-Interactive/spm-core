package dev.spm.core.format.common;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.ResourceBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BoundedJsonTest {
    private static final DecodePolicy POLICY = DecodePolicy.conservative();

    @Test void escapedDuplicateNamesRejectWithoutRetainingBudget() {
        var budget = new ResourceBudget(8192);
        try (var quota = new BoundedJson.Quota(POLICY, budget, new CancellationToken())) {
            assertThrows(IOException.class, () -> BoundedJson.read(
                    "{\"name\":1,\"n\\u0061me\":2}".getBytes(StandardCharsets.UTF_8), quota));
            assertTrue(budget.used() > 0);
        }
        assertEquals(0, budget.used());
    }

    @Test void cancellationReleasesExistingConstructionReservations() throws Exception {
        var budget = new ResourceBudget(8192);
        var token = new CancellationToken();
        try (var quota = new BoundedJson.Quota(POLICY, budget, token)) {
            quota.charge(256);
            token.cancel();
            assertThrows(CancellationException.class, () -> BoundedJson.read(
                    "{}".getBytes(StandardCharsets.UTF_8), quota));
            assertEquals(256, budget.used());
        }
        assertEquals(0, budget.used());
    }

    @Test void transferKeepsReservationsLiveAfterQuotaCloseAndOnlyOwnerReleasesThem() throws Exception {
        var budget = new ResourceBudget(320);
        var quota = new BoundedJson.Quota(POLICY, budget, new CancellationToken());
        quota.charge(128);
        quota.charge(192);
        var reservations = quota.transfer();
        try {
            quota.close();
            assertEquals(320, budget.used());
            assertThrows(IllegalStateException.class, () -> budget.reserve(1));
            assertThrows(IllegalStateException.class, quota::transfer);
            assertThrows(UnsupportedOperationException.class, reservations::clear);
            // The quota's two charges transfer as one ticket for the whole amount, so that a retained
            // import holds one accounting object rather than one per parsed node.
            assertEquals(1, reservations.size(), "a quota transfers a single consolidated ticket");
            assertEquals(320, reservations.get(0).bytes());
            reservations.get(0).close();
            reservations.get(0).close();
            assertEquals(0, budget.used());
            try (var replacement = budget.reserve(128)) {
                assertEquals(128, budget.used());
                assertEquals(128, replacement.bytes());
            }
            assertEquals(0, budget.used());
        } finally {
            reservations.forEach(ResourceBudget.Reservation::close);
            quota.close();
        }
        assertEquals(0, budget.used());
    }
}
