package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.ResourceBudget;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T07 named this test and it did not exist. When two clients or two evaluators share a script
 * budget, the <em>aggregate</em> allowance must be the same whether the work ran in one worker or
 * many. Otherwise a Peer could multiply its allowance simply by sharding.
 *
 * <p>This complements {@code SharedBudgetPropagationTest} (which covers {@link ResourceBudget}) by
 * covering the script-step allowance, which is the one a Molang author can actually exhaust.
 */
final class PeerScriptBudgetParityTest {

    /** Compiles a program that spends exactly {@code steps} interpreter steps per evaluation. */
    private static MolangProgram spending(int steps) {
        StringBuilder expression = new StringBuilder("0");
        for (int i = 0; i < steps; i++) expression.append("+1");
        return MolangProgram.compile(expression.toString());
    }

    private static long stepsConsumedBy(Runnable work, long allowance) {
        var budget = new ScriptBudget(allowance, 256, new CancellationToken());
        work.run();
        return allowance - budget.remainingSteps();
    }

    @Test void oneWorkerAndFourWorkersConsumeTheSameTotalForTheSameWork() throws Exception {
        var program = spending(50);
        // Both arms perform exactly 8 evaluations, so only the sharding differs.
        var singleBudget = new ScriptBudget(1_000_000, 256, new CancellationToken());
        for (int i = 0; i < 8; i++) {
            program.evaluate(new MolangContext(Map.of(), Map.of(), Map.of(), singleBudget, 0));
        }
        long single = 1_000_000 - singleBudget.remainingSteps();
        assertTrue(single > 0, "the single-worker run must actually spend steps");

        // Four workers each doing a quarter of the evaluations against ONE shared budget.
        var shared = new ScriptBudget(1_000_000, 256, new CancellationToken());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2; i++) {
                        program.evaluate(new MolangContext(Map.of(), Map.of(), Map.of(), shared, 0));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        long multi = 1_000_000 - shared.remainingSteps();
        assertEquals(single, multi, "sharding the same work across workers must not change total steps");
    }

    @Test void workersCannotEachSpendTheWholeSharedAllowance() throws Exception {
        var program = spending(100);
        long allowance = 250;
        var shared = new ScriptBudget(allowance, 256, new CancellationToken());
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        var exhausted = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 5; i++) {
                        try {
                            program.evaluate(new MolangContext(Map.of(), Map.of(), Map.of(), shared, 0));
                        } catch (MolangException expected) {
                            exhausted.incrementAndGet();
                            return null;
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(0, shared.remainingSteps(), "the aggregate allowance must be fully spent, not kept per worker");
        assertTrue(exhausted.get() > 0, "at least one worker must observe exhaustion");
        // 4 workers x 5 evaluations x 100 steps = 2000 desired steps against a 250 allowance.
        assertTrue(exhausted.get() >= 3, "the allowance is shared, so most workers must be refused");
    }

    @Test void depthIsPerEvaluationStackNotSharedBetweenPeers() throws Exception {
        // Two workers each nesting to exactly the limit must both succeed: depth describes one
        // stack, so sharing it would falsely exhaust a peer.
        int maximumDepth = 20;
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var results = new java.util.concurrent.atomic.AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 2; worker++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var budget = new ScriptBudget(1_000_000, maximumDepth, new CancellationToken());
                    for (int i = 0; i < maximumDepth; i++) budget.enter();
                    assertEquals(maximumDepth, budget.activeDepth(), "each thread sees only its own depth");
                    for (int i = 0; i < maximumDepth; i++) budget.leave();
                    assertEquals(0, budget.activeDepth());
                    results.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(2, results.get(), "both peers must nest to the limit without interfering");
    }

    @Test void aChildEvaluationCannotResetTheParentsAllowance() {
        var budget = new ScriptBudget(1000, 256, new CancellationToken());
        var program = spending(10);
        var context = new MolangContext(Map.of(), Map.of(), Map.of(), budget, 0);
        program.evaluate(context);
        long afterFirst = budget.remainingSteps();
        assertTrue(afterFirst < 1000);
        // Reusing the same context must continue spending, never restart the counter.
        program.evaluate(context);
        assertTrue(budget.remainingSteps() < afterFirst, "a second evaluation must keep spending");
    }

    @Test void cancellationIsVisibleToEveryPeerImmediately() {
        var token = new CancellationToken();
        var budget = new ScriptBudget(1_000_000, 256, token);
        token.cancel();
        // A cancelled credit check must fail regardless of how many steps remain.
        assertThrows(java.util.concurrent.CancellationException.class, budget::step);
        assertThrows(java.util.concurrent.CancellationException.class, budget::checkCancelled);
        assertEquals(1_000_000, budget.remainingSteps(), "cancellation must not consume the allowance");
    }

    @Test void theAllowanceIsNeverRestoredByLeavingAScope() {
        var budget = new ScriptBudget(10, 256, new CancellationToken());
        budget.enter();
        budget.leave();
        budget.enter();
        budget.leave();
        // Two complete enter/leave cycles still cost two steps.
        assertEquals(8, budget.remainingSteps(), "leaving a scope must not refund the step it cost");
    }

    @Test void aPeerWithItsOwnBudgetIsIndependentButCannotExceedItsOwnLimit() {
        // Two independent budgets model two unrelated models. They must not share, but each is capped.
        var first = new ScriptBudget(100, 256, new CancellationToken());
        var second = new ScriptBudget(100, 256, new CancellationToken());
        var program = spending(10);
        program.evaluate(new MolangContext(Map.of(), Map.of(), Map.of(), first, 0));
        assertEquals(100, second.remainingSteps(), "an unrelated peer's budget is untouched");
        for (int i = 0; i < 20; i++) {
            try {
                program.evaluate(new MolangContext(Map.of(), Map.of(), Map.of(), second, 0));
            } catch (MolangException exhausted) {
                break;
            }
        }
        assertTrue(second.remainingSteps() < 100, "the second peer still spends its own allowance");
    }
}
