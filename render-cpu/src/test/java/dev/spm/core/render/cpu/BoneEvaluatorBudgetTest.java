package dev.spm.core.render.cpu;

import dev.spm.core.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the budgeted bone-evaluation entry point. {@code ModelFrameRenderer} reserves a whole-frame
 * working set before it calls any provider, so this overload exists for callers that use
 * {@link BoneEvaluator} directly and would otherwise allocate with no accounting at all.
 */
final class BoneEvaluatorBudgetTest {

    private static Skeleton skeleton() {
        return new Skeleton(List.of("root", "child"), new int[] {-1, 0}, new float[6]);
    }

    private static BonePose pose() {
        return BonePose.identity(2);
    }

    @Test void theWorkingChargeIsDocumentedAndMatchesTheModel() {
        assertTrue(BoneEvaluator.WORKING_BYTES_PER_BONE > 0);
        // 16 matrix + 9 normal + 3 locator floats is 112 bytes of result arrays per bone; the
        // modelled charge must exceed that so a rejection precedes the allocation.
        assertTrue(BoneEvaluator.WORKING_BYTES_PER_BONE > 112,
                "the working charge must exceed the result arrays it protects");
    }

    @Test void aSufficientBudgetProducesTheSameResultAsTheUnbudgetedPath() {
        var budget = new ResourceBudget(1L << 20);
        var token = new CancellationToken();
        var budgeted = BoneEvaluator.evaluate(skeleton(), pose(), new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, budget, token);
        var plain = BoneEvaluator.evaluate(skeleton(), pose());
        assertArrayEquals(plain.transforms(), budgeted.transforms(), 0f);
        assertArrayEquals(plain.normals(), budgeted.normals(), 0f);
        assertArrayEquals(plain.locatorPositions(), budgeted.locatorPositions(), 0f);
        assertArrayEquals(plain.visible(), budgeted.visible());
        assertEquals(0, budget.used(), "every credit must be released on the success path");
    }

    @Test void aTightBudgetIsRejectedBeforeAllocatingAndReleasesNothing() {
        long required = 2L * BoneEvaluator.WORKING_BYTES_PER_BONE;
        var budget = new ResourceBudget(required - 1);
        var token = new CancellationToken();
        assertThrows(ResourceBudgetExceededException.class,
                () -> BoneEvaluator.evaluate(skeleton(), pose(), new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, budget, token));
        assertEquals(0, budget.used(), "a rejection must not consume credits");
    }

    @Test void anExactBudgetSucceedsAndFullyReleases() {
        long exact = 2L * BoneEvaluator.WORKING_BYTES_PER_BONE;
        var budget = new ResourceBudget(exact);
        var token = new CancellationToken();
        BoneEvaluator.evaluate(skeleton(), pose(), new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, budget, token);
        assertEquals(0, budget.used());
    }

    @Test void aCancelledTokenIsRejectedAndConsumesNoBudget() {
        var budget = new ResourceBudget(1L << 20);
        var token = new CancellationToken();
        token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> BoneEvaluator.evaluate(skeleton(), pose(), new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, budget, token));
        assertEquals(0, budget.used(), "cancellation before the reservation must not charge anything");
    }

    @Test void aFailureInsideEvaluationStillReleasesTheReservation() {
        var budget = new ResourceBudget(1L << 20);
        var token = new CancellationToken();
        // A mismatched pose fails deep inside extraction, after the reservation is taken.
        var wrong = BonePose.identity(3);
        assertThrows(IllegalArgumentException.class,
                () -> BoneEvaluator.evaluate(skeleton(), wrong, new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}, budget, token));
        assertEquals(0, budget.used(), "a mid-evaluation failure must still release its credits");
    }

    @Test void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class,
                () -> BoneEvaluator.evaluate(skeleton(), pose(), new float[16], null, new CancellationToken()));
        assertThrows(NullPointerException.class,
                () -> BoneEvaluator.evaluate(skeleton(), pose(), new float[16], new ResourceBudget(1024), null));
    }
}
