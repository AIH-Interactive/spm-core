package dev.spm.core.scene;


import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T07 named this test and it did not exist in Core (the class of that name lives in the old
 * SPM implementation, which must not be used as an oracle). This pins the Core-side 60 Hz rational
 * clock on its own terms.
 *
 * <p>The clock's contract: one fixed rate of 60 steps per second; sub-step time accumulates as an
 * integer phase so drift cannot build up over long runs; excess whole steps beyond the catch-up
 * window are <em>dropped and reported</em>, never silently absorbed; time may not run backwards.
 */
final class SimulationClockTest {
    private static final long NANOS_PER_STEP = 1_000_000_000L / 60L; // 16_666_666

    @Test void firstAdvanceEstablishesTheBaselineWithoutExecutingSteps() {
        var clock = new FixedStepClock(600);
        var advance = clock.advance(123_456_789L);
        assertEquals(0, advance.executedSteps());
        assertEquals(0, advance.droppedSteps());
        assertEquals(0, advance.totalSteps());
        assertEquals(0.0, advance.interpolation());
    }

    @Test void aWholeSecondIsExactlySixtySteps() {
        var clock = new FixedStepClock(600);
        clock.advance(0);
        var advance = clock.advance(1_000_000_000L);
        assertEquals(60, advance.executedSteps());
        assertEquals(0, advance.droppedSteps());
        assertEquals(60, advance.totalSteps());
        assertEquals(0.0, advance.interpolation(), 0.0);
    }

    @Test void subStepRemaindersAccumulateInsteadOfBeingLost() {
        var clock = new FixedStepClock(600);
        clock.advance(0);
        int executed = 0;
        // 59 advances of 1ms each = 59ms; that alone is 3 whole steps (3*16.67=50ms) with 9ms left.
        for (int i = 1; i <= 59; i++) {
            executed += clock.advance(i * 1_000_000L).executedSteps();
        }
        assertEquals(3, executed, "59ms at 60Hz is 3 executed steps");
        // The remaining ~9ms must not be discarded: 8 more ms tips it to 4 steps total.
        for (int i = 60; i <= 67; i++) {
            executed += clock.advance(i * 1_000_000L).executedSteps();
        }
        assertEquals(4, executed, "accumulated phase must survive across calls");
    }

    @Test void nanosecondAlignmentDoesNotDriftAcrossManySteps() {
        var clock = new FixedStepClock(600);
        clock.advance(0);
        // Drive the clock with an exact 60Hz nanosecond timeline; the total must stay exact.
        long nanos = 0;
        long steps = 0;
        for (int i = 0; i < 600; i++) {
            nanos += 1_000_000_000L / 60L;
            steps += clock.advance(nanos).executedSteps();
        }
        // 600 * 16_666_666ns = 9.999_999_600s, i.e. 400ns short of the 10s that would be 600 steps.
        assertEquals(599, steps, "truncating nanosecond input yields 599 steps, never 600");
        // The retained 400ns remainder is not lost: adding it crosses the 600th boundary.
        assertEquals(1, clock.advance(nanos + 400).executedSteps(),
                "the sub-step remainder accumulated across all 600 advances must still count");
    }

    @Test void excessStepsBeyondTheCatchUpWindowAreDroppedAndReported() {
        var clock = new FixedStepClock(5);
        clock.advance(0);
        // 10 seconds = 600 due steps, window is 5.
        var advance = clock.advance(10_000_000_000L);
        assertEquals(5, advance.executedSteps(), "executed steps are capped by the window");
        assertEquals(595, advance.droppedSteps(), "the excess must be reported, not hidden");
        assertEquals(5, advance.totalSteps(), "dropped steps do not advance logical time");
    }

    @Test
    void theCatchUpWindowCapsEverySingleAdvanceNotOnlyTheFirstOne() {
        // The window is a per-advance cap, not a startup allowance. This is what keeps one very
        // late frame from burning a huge number of script steps in a single tick.
        var narrow = new FixedStepClock(5);
        narrow.advance(0);
        var second = narrow.advance(1_000_000_000L);
        assertEquals(5, second.executedSteps(), "an ordinary 60-step second is still capped at 5");
        assertEquals(55, second.droppedSteps());
        assertEquals(5, second.totalSteps());

        // The same gap executes in full under a window large enough to hold it.
        var wide = new FixedStepClock(600);
        wide.advance(0);
        var full = wide.advance(1_000_000_000L);
        assertEquals(60, full.executedSteps());
        assertEquals(0, full.droppedSteps());
        assertEquals(60, full.totalSteps());
    }

    @Test void timeMayNotRunBackwards() {
        var clock = new FixedStepClock(600);
        clock.advance(5_000_000_000L);
        assertThrows(IllegalArgumentException.class, () -> clock.advance(4_999_999_999L));
    }

    @Test void interpolationStaysInTheHalfOpenUnitInterval() {
        var clock = new FixedStepClock(600);
        clock.advance(0);
        for (long nanos = 1; nanos < 200_000_000L; nanos += 1_234_567L) {
            double interpolation = clock.advance(nanos).interpolation();
            assertTrue(interpolation >= 0 && interpolation < 1,
                    "interpolation must be in [0,1): " + interpolation + " at " + nanos);
        }
    }

    @Test void catchUpWindowMustBeWithinTheSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> new FixedStepClock(0));
        assertThrows(IllegalArgumentException.class, () -> new FixedStepClock(601));
        assertDoesNotThrow(() -> new FixedStepClock(1));
        assertDoesNotThrow(() -> new FixedStepClock(600));
    }

    @Test void aCopyAdvancesIndependentlyOfItsSource() {
        var clock = new FixedStepClock(600);
        clock.advance(0);
        clock.advance(NANOS_PER_STEP * 30);
        var copy = clock.copy();
        assertEquals(30, copy.advance(NANOS_PER_STEP * 60).executedSteps());
        // The original is unaffected by the copy's advance.
        assertEquals(30, clock.advance(NANOS_PER_STEP * 60).executedSteps());
    }
}
