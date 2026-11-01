package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.Skeleton;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class CoreContractsTest {
    @Test void skeletonOwnsArraysAndSortsParentsBeforeChildren() {
        int[] parents = {1, -1};
        float[] pivots = {0, 0, 0, 1, 2, 3};
        Skeleton skeleton = new Skeleton(List.of("child", "root"), parents, pivots);
        parents[0] = -1;
        pivots[3] = 99;
        assertEquals(1, skeleton.parent(0));
        assertEquals(1, skeleton.pivot(1, 0));
        int[] order = skeleton.evaluationOrder();
        assertArrayEquals(new int[]{1, 0}, order);
        order[0] = 0;
        assertArrayEquals(new int[]{1, 0}, skeleton.evaluationOrder());
    }
    @Test void skeletonRejectsCyclesInvalidIndicesAndNonFinitePivots() {
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of("a", "b"), new int[]{1, 0}, new float[6]));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of("a"), new int[]{4}, new float[3]));
        assertThrows(IllegalArgumentException.class, () -> new Skeleton(List.of("a"), new int[]{-1}, new float[]{Float.NaN,0,0}));
    }
    @Test void sharedBudgetIsAtomicAndReservationsCloseExactlyOnce() throws Exception {
        ResourceBudget budget = new ResourceBudget(100);
        ResourceBudget.Reservation first = budget.reserve(60);
        var pool = Executors.newSingleThreadExecutor();
        try {
            assertTrue(pool.submit(() -> {
                try { budget.reserve(50); return false; }
                catch (IllegalStateException expected) { return true; }
            }).get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(60, budget.used());
        first.close(); first.close();
        assertEquals(0, budget.used());
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        try (var reservation = budget.reserve(100)) { assertEquals(100, budget.used()); assertNotNull(reservation); }
        assertEquals(0, budget.used());
    }
    @Test void cancellationIsExplicitAndVisibleToWorkers() {
        var token = new CancellationToken();
        token.check(); token.cancel();
        assertThrows(CancellationException.class, token::check);
    }
}
