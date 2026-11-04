package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.MolangContext;
import dev.spm.core.scene.MolangException;
import dev.spm.core.scene.MolangProgram;
import dev.spm.core.scene.ScriptBudget;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MolangRuntimeTest {
    private static ScriptBudget budget(long steps) {
        return new ScriptBudget(steps, 128, new CancellationToken());
    }
    private static MolangContext context(ScriptBudget budget) {
        return new MolangContext(Map.of("speed", 3.0), new HashMap<>(), Map.of(), budget);
    }
    @Test void precedenceVariablesQueriesAndDegreeMath() {
        MolangContext c = context(budget(1000));
        assertEquals(9.0, MolangProgram.compile("v.x = q.speed * 2; return v.x + math.sin(90) + 2;").evaluate(c));
        assertEquals(6.0, c.variables().get("x"));
        assertEquals(0.0, MolangProgram.compile("1 / 0").evaluate(c));
        assertEquals(14.0, MolangProgram.compile("2 + 3 * 4").evaluate(c));
    }
    @Test void shortCircuitAndBranchesDoNotExecuteSkippedAssignments() {
        MolangContext c = context(budget(1000));
        assertEquals(7, MolangProgram.compile("0 && (v.x = 1); 1 || (v.x = 2); return 1 ? 7 : (v.x = 3);").evaluate(c));
        assertFalse(c.variables().containsKey("x"));
    }
    @Test void loopsChargeEmptyBodiesAndCannotResetTheSharedBudget() {
        ScriptBudget b = budget(40);
        MolangContext c = context(b);
        MolangException e = assertThrows(MolangException.class,
                () -> MolangProgram.compile("loop(1000000000, {});").evaluate(c));
        assertEquals(MolangException.Reason.STEPS, e.reason());
        assertThrows(MolangException.class, () -> MolangProgram.compile("1").evaluate(c.child(Map.of())));
        assertEquals(0, b.remainingSteps());
        assertEquals(0, b.activeDepth());
    }
    @Test void loopControlAndReturnPropagate() {
        MolangContext c = context(budget(1000));
        assertEquals(4, MolangProgram.compile("v.x=0; loop(10, {v.x=v.x+1; v.x==2 ? continue; v.x==4 ? break;}); return v.x;").evaluate(c));
        assertEquals(5, MolangProgram.compile("loop(10, {return 5;}); return 9;").evaluate(c));
    }
    @Test void recursiveFunctionsAndChildContextsShareDepthAndSteps() {
        ScriptBudget b = new ScriptBudget(10000, 20, new CancellationToken());
        MolangProgram recursive = MolangProgram.compile("function.recur()");
        MolangContext c = new MolangContext(Map.of(), new HashMap<>(), Map.of("recur", recursive), b);
        assertEquals(MolangException.Reason.DEPTH,
                assertThrows(MolangException.class, () -> recursive.evaluate(c)).reason());
        assertEquals(0, b.activeDepth());
        ScriptBudget shared = budget(6);
        MolangContext parent = context(shared);
        MolangProgram.compile("1+2").evaluate(parent);
        assertThrows(MolangException.class, () -> MolangProgram.compile("1+2").evaluate(parent.child(Map.of())));
        assertEquals(0, shared.remainingSteps());
    }
    @Test void cancellationAndNonFiniteValuesAreExplicitFailures() {
        CancellationToken token = new CancellationToken();
        MolangContext c = context(new ScriptBudget(1000, 128, token));
        token.cancel();
        assertThrows(CancellationException.class, () -> MolangProgram.compile("1").evaluate(c));
        assertEquals(MolangException.Reason.NON_FINITE,
                assertThrows(MolangException.class, () -> MolangProgram.compile("math.sqrt(-1)").evaluate(context(budget(1000)))).reason());
    }
    @Test void unknownSyntaxIsRejectedAndCompileDepthIsBounded() {
        assertThrows(MolangException.class, () -> MolangProgram.compile("q.entity -> v.x"));
        assertThrows(MolangException.class, () -> MolangProgram.compile("v.x[0]"));
        assertThrows(MolangException.class, () -> MolangProgram.compile("math.unsupported(0,1)"));
        assertThrows(MolangException.class, () -> MolangProgram.compile("(".repeat(1000) + "1" + ")".repeat(1000)));
        assertThrows(MolangException.class, () -> MolangProgram.compile("1+".repeat(1000) + "1"));
    }
    @Test void stateAndArgumentsHaveExplicitMemoryBounds() {
        Map<String, Double> values = new HashMap<>();
        for (int i = 0; i < 4096; i++) values.put("x" + i, 0.0);
        MolangContext full = new MolangContext(Map.of(), values, Map.of(), budget(100));
        assertThrows(MolangException.class, () -> MolangProgram.compile("v.extra = 1").evaluate(full));
        values.put("extra", 1.0);
        assertThrows(IllegalArgumentException.class, () -> new MolangContext(Map.of(), values, Map.of(), budget(100)));
        assertThrows(MolangException.class, () -> MolangProgram.compile("function.many(" + "1,".repeat(64) + "1)"));
    }
    @Test void functionArgumentsUseFreshTemporariesButSharedPersistentVariables() {
        MolangProgram function = MolangProgram.compile("v.saved=c.arg0; return c.arg0 + c.arg1 + t.x;");
        MolangContext c = new MolangContext(Map.of(), Map.of(), Map.of("sum", function), budget(1000));
        assertEquals(7, MolangProgram.compile("t.x=100; return function.sum(3,4);").evaluate(c));
        assertEquals(3, c.variables().get("saved"));
        assertEquals(0, MolangProgram.compile("t.x").evaluate(c));
    }
    @Test void independentWorkersCannotMultiplySharedBudget() throws Exception {
        ScriptBudget shared = budget(5000);
        MolangProgram loop = MolangProgram.compile("loop(100000000, {});");
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> assertThrows(MolangException.class, () -> loop.evaluate(context(shared))).reason());
            var second = workers.submit(() -> assertThrows(MolangException.class, () -> loop.evaluate(context(shared))).reason());
            assertEquals(MolangException.Reason.STEPS, first.get(10, TimeUnit.SECONDS));
            assertEquals(MolangException.Reason.STEPS, second.get(10, TimeUnit.SECONDS));
            assertEquals(0, shared.remainingSteps());
            assertEquals(0, shared.activeDepth());
        } finally { workers.shutdownNow(); }
    }
    @Test void seededRandomIsLocalSharedWithChildrenAndShortCircuitSafe() {
        MolangContext a = new MolangContext(Map.of(), Map.of(), Map.of(), budget(10000), 123);
        MolangContext b = new MolangContext(Map.of(), Map.of(), Map.of(), budget(10000), 123);
        MolangProgram random = MolangProgram.compile("math.random(-3, 7)");
        MolangProgram.compile("false && math.random(0,1); true ? 1 : math.random(0,1)").evaluate(a);
        for (int i = 0; i < 100; i++) {
            double value = random.evaluate(a.child(Map.of()));
            assertEquals(value, random.evaluate(b));
            assertTrue(value >= -3 && value < 7);
            double integer = MolangProgram.compile("math.randomi(5, -2)").evaluate(a);
            assertTrue(integer >= -2 && integer < 5 && integer == Math.floor(integer));
            MolangProgram.compile("math.random_integer(5, -2)").evaluate(b);
        }
        assertEquals(4, MolangProgram.compile("math.random_integer(4,4)").evaluate(a));
        assertEquals(6, MolangProgram.compile("math.roll(3,2,2)").evaluate(a));
        assertThrows(MolangException.class, () -> MolangProgram.compile("math.die_roll(10000000,0,1)").evaluate(context(budget(100))));
    }
    @Test void commonAliasesConstantsAndBooleanNumericCoercionAreDefined() {
        MolangContext c = context(budget(1000));
        assertEquals(1, MolangProgram.compile("!0 && -2 && TRUE && !FALSE").evaluate(c));
        assertEquals(Math.E, MolangProgram.compile("MATH.E").evaluate(c));
        assertEquals(3, MolangProgram.compile("QUERY.SPEED").evaluate(c));
        assertEquals(0.5, MolangProgram.compile("math.hermite(0.5)").evaluate(c));
        assertEquals(-170, MolangProgram.compile("math.min_angle(190)").evaluate(c));
        assertEquals(180, MolangProgram.compile("math.lerprotate(170,-170,0.5)").evaluate(c));
    }
    @Test void ysmNamespaceReadsOnlyTheHostSuppliedNumericSnapshot() {
        var c=new MolangContext(Map.of("ysm.head_pitch",30.0),Map.of(),Map.of(),budget(1000));
        assertEquals(31,MolangProgram.compile("ysm.head_pitch + ysm.missing + 1").evaluate(c));
        assertThrows(MolangException.class,()->MolangProgram.compile("minecraft.head_pitch"));
    }
    @Test void nestedVariablePathsRemainOwnerConfinedNumericState() {
        var c=new MolangContext(Map.of(),new HashMap<>(Map.of("physics.x",1.0)),Map.of(),budget(1000));
        assertEquals(3,MolangProgram.compile("v.roaming.fly=2; return v.roaming.fly+v.physics.x;").evaluate(c));
        assertEquals(2,c.variables().get("roaming.fly"));
        assertEquals(1,c.variables().get("physics.x"));
    }
}
