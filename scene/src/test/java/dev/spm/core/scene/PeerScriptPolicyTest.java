package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class PeerScriptPolicyTest {
    @Test void compilationUsesPeerSourceTokenAndParserNestingBoundaries() {
        assertDoesNotThrow(() -> PeerScriptPolicy.compile("1" + " ".repeat(65_535)));
        assertThrows(MolangException.class, () -> PeerScriptPolicy.compile("1" + " ".repeat(65_536)));
        // Lexer accounting includes EOF and its final require; these are the actual token boundaries.
        assertDoesNotThrow(() -> PeerScriptPolicy.compile("1;".repeat(8191)));
        assertThrows(MolangException.class, () -> PeerScriptPolicy.compile("1;".repeat(8192)));
        assertDoesNotThrow(() -> PeerScriptPolicy.compile("(".repeat(31) + "1" + ")".repeat(31)));
        assertThrows(MolangException.class, () -> PeerScriptPolicy.compile("(".repeat(32) + "1" + ")".repeat(32)));
        assertThrows(MolangException.class, () -> PeerScriptPolicy.compile("1+".repeat(32) + "1"));
        assertDoesNotThrow(() -> MolangProgram.compile("(".repeat(32) + "1" + ")".repeat(32)));
    }

    @Test void sameFrameCannotResetExhaustedBudgetAndNextFrameGetsFreshAllowance() {
        var policy = new PeerScriptPolicy();
        var token = new CancellationToken();
        var first = policy.frame(4, token);
        assertEquals(100_000, first.remainingSteps());
        for (int i = 0; i < 100_000; i++) first.step();
        assertSame(first, policy.frame(4, token));
        assertThrows(MolangException.class, () -> policy.frame(4, token).step());
        var next = policy.frame(5, token);
        assertNotSame(first, next);
        assertEquals(100_000, next.remainingSteps());
        assertEquals(0, first.remainingSteps());
        assertThrows(IllegalArgumentException.class, () -> policy.frame(4, token));
        assertThrows(IllegalArgumentException.class, () -> policy.frame(-1, token));
        assertThrows(IllegalArgumentException.class, () -> policy.frame(5, new CancellationToken()));
        assertSame(next, policy.frame(5, token));
    }

    @Test void modelsAndReentrantChildFunctionsSpendOneFrameAllowance() {
        var policy = new PeerScriptPolicy();
        var token = new CancellationToken();
        var budget = policy.frame(0, token);
        var recursive = PeerScriptPolicy.compile("function.child()");
        var first = new MolangContext(Map.of(), Map.of(), Map.of("child", recursive), budget);
        var second = new MolangContext(Map.of(), Map.of(), Map.of(), policy.frame(0, token));
        assertEquals(MolangException.Reason.DEPTH,
                assertThrows(MolangException.class, () -> recursive.evaluate(first.child(Map.of()))).reason());
        assertEquals(0, budget.activeDepth());
        long beforeSecond = budget.remainingSteps();
        PeerScriptPolicy.compile("1+2").evaluate(second);
        assertTrue(budget.remainingSteps() < beforeSecond);
        var exhausting = PeerScriptPolicy.compile("loop(1000000,{})");
        assertEquals(MolangException.Reason.STEPS,
                assertThrows(MolangException.class, () -> exhausting.evaluate(first.child(Map.of()))).reason());
        assertThrows(MolangException.class, () -> PeerScriptPolicy.compile("1").evaluate(second));
        assertEquals(0, policy.frame(0, token).remainingSteps());
    }

    @Test void concurrentWorkersReachDepthLimitOnTheSameMeter() throws Exception {
        var policy = new PeerScriptPolicy();
        var shared = policy.frame(1, new CancellationToken());
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var work = (java.util.concurrent.Callable<Void>) () -> {
                for (int i = 0; i < PeerScriptPolicy.MAX_NESTING; i++) shared.enter();
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    assertEquals(PeerScriptPolicy.MAX_NESTING, shared.activeDepth());
                    assertEquals(MolangException.Reason.DEPTH, assertThrows(MolangException.class, shared::enter).reason());
                } finally {
                    for (int i = 0; i < PeerScriptPolicy.MAX_NESTING; i++) shared.leave();
                }
                assertEquals(0, shared.activeDepth());
                return null;
            };
            var first = pool.submit(work);
            var second = pool.submit(work);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(PeerScriptPolicy.MAX_FRAME_STEPS-2L*(PeerScriptPolicy.MAX_NESTING+1),shared.remainingSteps());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test void cancellationCannotBeDetachedByRequestingTheSameFrame() {
        var policy = new PeerScriptPolicy();
        var token = new CancellationToken();
        var budget = policy.frame(0, token);
        token.cancel();
        assertSame(budget, policy.frame(0, token));
        assertThrows(java.util.concurrent.CancellationException.class, budget::step);
        assertThrows(IllegalArgumentException.class, () -> policy.frame(0, new CancellationToken()));
    }
}
