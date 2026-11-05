package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MolangPhysicsTest {
    private ScriptBudget budget() { return new ScriptBudget(100_000, 64, new CancellationToken()); }

    @Test void zeroClockOnlySeedsTheNextPositiveFrame() {
        var p = new MolangPhysics(); var b = budget();
        p.advance(0, b); p.first("s", 4, 1, b);
        p.advance(1, b);
        assertEquals(0, p.first("s", 4, 1, b));
        p.advance(2, b);
        assertEquals(4, p.first("s", 4, 1, b));
    }

    @Test void firstCallAndClockUsePriorArguments() {
        var p = new MolangPhysics(); var b = budget();
        p.advance(1, b);
        assertEquals(10, p.first("spring", 10, 2, b));
        assertEquals(0, p.first("spring", 4, 2, b));
        p.advance(2, b);
        assertEquals(2, p.first("spring", 8, 2, b), 1e-12);
        p.advance(2, b);
        assertEquals(2, p.first("spring", 8, 2, b), 1e-12);
        p.advance(3, b);
        assertEquals(5, p.first("spring", 8, 2, b), 1e-12);
        assertThrows(MolangException.class, () -> p.advance(1, b));
    }

    @Test void sharedKeyKeepsOriginalTypeAndCopyIsDeep() {
        var p = new MolangPhysics(); var b = budget();
        p.advance(1, b); p.first("shared", 6, 2, b);
        assertEquals(0, p.second("shared", 8, 4, .5, 1, b));
        var snapshot = p.copy();
        p.advance(2, b); snapshot.advance(2, b);
        assertEquals(2, p.first("shared", 20, 1, b), 1e-12);
        assertEquals(2, snapshot.first("shared", 4, 1, b), 1e-12);
        p.advance(3, b); snapshot.advance(3, b);
        assertEquals(20, p.first("shared", 0, 1, b));
        assertEquals(4, snapshot.first("shared", 0, 1, b));
    }

    @Test void secondOrderHasZeroInitialVelocityAndUsesSubsteps() {
        var p = new MolangPhysics(); var b = budget(); p.advance(1, b);
        assertEquals(3, p.second("s", 3, 1, 1, 0, b));
        p.advance(1.01, b);
        assertEquals(0, p.second("s", 3, 1, 1, 0, b));
        p.advance(1.02, b);
        assertEquals(3 * 4 * Math.PI * Math.PI * .0001,
                p.second("s", 3, 1, 1, 0, b), 1e-12);
        long before = b.remainingSteps(); p.advance(2, b);
        assertTrue(before - b.remainingSteps() > 2);
    }

    @Test void failedAdvanceDoesNotCommitEarlierFiltersOrClock() {
        var p = new MolangPhysics(); var b = budget(); p.advance(1, b);
        p.first("a", 2, 1, b); p.first("b", 4, 1, b);
        assertEquals(MolangException.Reason.STEPS, assertThrows(MolangException.class,
                () -> p.advance(2, new ScriptBudget(3, 64, new CancellationToken()))).reason());
        assertEquals(0, p.first("a", 2, 1, b));
        p.advance(2, b);
        assertEquals(2, p.first("a", 2, 1, b));
        assertEquals(4, p.first("b", 4, 1, b));
    }

    @Test void invalidInputsAndResourceBoundsAreExplicit() {
        var p = new MolangPhysics(); var b = budget();
        assertEquals(0, p.first("", 1, 1, b));
        assertEquals(MolangException.Reason.NON_FINITE, assertThrows(MolangException.class,
                () -> p.first("bad", Double.NaN, 1, b)).reason());
        assertThrows(MolangException.class, () -> p.first("zero", 1, 0, b));
        assertThrows(MolangException.class, () -> p.second("bad-frequency", 1, Double.NaN, 1, 1, b));
        assertThrows(MolangException.class, () -> p.advance(Double.POSITIVE_INFINITY, b));
        assertEquals(MolangException.Reason.CAPACITY, assertThrows(MolangException.class,
                () -> p.first("x".repeat(65_537), 1, 1, b)).reason());
        for (int i = 0; i < 4096; i++) p.first("key" + i, 1, 1, b);
        assertEquals(MolangException.Reason.CAPACITY, assertThrows(MolangException.class,
                () -> p.first("overflow", 1, 1, b)).reason());
        p.advance(1, b);
        assertEquals(MolangException.Reason.STEPS, assertThrows(MolangException.class,
                () -> p.advance(2, new ScriptBudget(1, 64, new CancellationToken()))).reason());
    }
    @Test void zeroAndNegativeSecondOrderFrequencyFreezeTheExistingSimulation() {
        var p=new MolangPhysics(); var b=budget();
        p.advance(1,b);
        assertEquals(5,p.second("spring",5,0,1,1,b));
        p.advance(2,b);
        assertEquals(0,p.second("spring",5,1,1,1,b));
        p.advance(2.1,b);
        double value=p.second("spring",5,0,1,1,b);
        assertTrue(Double.isFinite(value));
        p.advance(2.2,b);
        assertEquals(value,p.second("spring",5,-1,1,1,b));
        p.advance(2.3,b);
        assertEquals(value,p.second("spring",5,1,1,1,b));
    }
}
