package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.MolangContext;
import dev.spm.core.scene.MolangException;
import dev.spm.core.scene.MolangProgram;
import dev.spm.core.scene.ScriptBudget;
import dev.spm.core.scene.AnimationClip;
import dev.spm.core.scene.SceneDefinition;
import dev.spm.core.scene.SceneInputs;
import dev.spm.core.scene.SceneInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MolangNullCoalescingTest {
    private static MolangContext context() {
        return new MolangContext(Map.of("zero", 0.0, "speed", 3.0), Map.of(), Map.of(),
                new ScriptBudget(10_000, 128, new CancellationToken()));
    }
    private static double evaluate(String source, MolangContext context) {
        return MolangProgram.compile(source).evaluate(context);
    }

    @Test void absentReadsCoalesceButTheNumericApiStillReturnsZero() {
        var context = context();
        for (String name : new String[]{"v.absent", "t.absent", "q.absent", "c.absent", "ysm.absent"}) {
            assertEquals(7, evaluate(name + " ?? 7", context), name);
            assertEquals(0, evaluate(name, context), name);
            assertEquals(2, evaluate("(" + name + " + 2) ?? 7", context), name);
        }
        assertEquals(3, evaluate("q.speed ?? 7", context));
    }

    @Test void zeroFalseAndNegativeZeroDoNotCoalesce() {
        var context = context();
        for (String source : new String[]{"0 ?? 7", "false ?? 7", "q.zero ?? 7", "v.x=0; v.x ?? 7"}) {
            assertEquals(0, evaluate(source, context), source);
        }
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(evaluate("-0 ?? 7", context)));
    }

    @Test void fallbackIsLazyAndTheLeftOperandIsEvaluatedOnce() {
        var context = context();
        assertEquals(0, evaluate("0 ?? (v.skipped=1)", context));
        assertFalse(context.variables().containsKey("skipped"));
        assertEquals(1, evaluate("(v.count=(v.count ?? 0)+1) ?? (v.skipped=1)", context));
        assertEquals(1, context.variables().get("count"));
        assertEquals(9, evaluate("v.absent ?? (v.fallback=9)", context));
        assertEquals(9, context.variables().get("fallback"));
        assertEquals(0, evaluate("0 ?? math.sqrt(-1)", context));
    }

    @Test void assignmentPreservesAbsenceWithoutLeakingNullIntoSnapshots() {
        var context = context();
        assertEquals(15, evaluate("v.a=2; v.a=(v.b=q.absent); (v.a ?? 7)+(v.b ?? 8)", context));
        assertFalse(context.variables().containsKey("a"));
        assertFalse(context.variables().containsKey("b"));
        assertEquals(4, evaluate("t.a=v.absent; t.a ?? 4", context));
        assertEquals(6, evaluate("v.physics.x=v.absent; v.physics.x ?? 6", context));
        assertDoesNotThrow(() -> Map.copyOf(context.variables()));
        assertEquals(5, evaluate("v.a=v.absent ?? 5; v.a", context));
    }

    @Test void blocksConditionalBranchesAndReturnsPreserveAbsence() {
        var context = context();
        assertEquals(7, evaluate("{v.absent;} ?? 7", context));
        assertEquals(7, evaluate("(true ? v.absent : 2) ?? 7", context));
        assertEquals(7, evaluate("(false ? 2 : v.absent) ?? 7", context));
        assertEquals(0, evaluate("(false ? v.absent : 0) ?? 7", context));
    }

    @Test void anOmittedConditionalBranchIsAbsentRatherThanNumericZero() {
        var context = context();
        assertEquals(7, evaluate("(false ? 2) ?? 7", context));
        assertEquals(0, evaluate("false ? 2", context));
        assertEquals(0, evaluate("(true ? 0) ?? 7", context));
        assertEquals(7, evaluate("(true ? v.absent) ?? 7", context));
    }

    @Test void functionArgumentsAndReturnsPreserveAbsenceAndFreshTemporaries() {
        var functions = Map.of("pass", MolangProgram.compile("return c.arg0;"),
                "absent", MolangProgram.compile("loop(1, {return v.absent;}); return 5;"),
                "temporary", MolangProgram.compile("t.caller"));
        var context = new MolangContext(Map.of(), Map.of(), functions,
                new ScriptBudget(10_000, 128, new CancellationToken()));
        assertEquals(7, evaluate("f.pass(v.absent) ?? 7", context));
        assertEquals(0, evaluate("f.pass(0) ?? 7", context));
        assertEquals(9, evaluate("f.absent() ?? 9", context));
        assertEquals(8, evaluate("t.caller=2; f.temporary() ?? 8", context));
    }

    @Test void precedenceMatchesAssignmentThenCoalescingThenConditional() {
        var context = context();
        assertEquals(0, evaluate("0 ?? 1 ? 2 : 3", context));
        assertEquals(3, evaluate("(0 ?? 1) ? 2 : 3", context));
        assertEquals(9, evaluate("v.x=v.absent ?? 0 ? 7 : 9; v.x", context));
        assertEquals(0, evaluate("v.absent ?? 0 ?? 8", context));
        assertEquals(1, evaluate("v.absent ?? 0 || 1", context));
        assertThrows(MolangException.class, () -> MolangProgram.compile("v.absent ??"));
    }

    @Test void coalescingDoesNotSwallowErrorsCancellationOrBudgetFailures() {
        var context = context();
        assertEquals(MolangException.Reason.NON_FINITE, assertThrows(MolangException.class,
                () -> evaluate("math.sqrt(-1) ?? 7", context)).reason());
        assertEquals(MolangException.Reason.UNKNOWN_FUNCTION, assertThrows(MolangException.class,
                () -> evaluate("f.unknown() ?? 7", context)).reason());
        var token = new CancellationToken();
        var budget = new ScriptBudget(10, 128, token);
        var cancelled = new MolangContext(Map.of(), Map.of(), Map.of(), budget);
        token.cancel();
        assertThrows(CancellationException.class, () -> evaluate("v.absent ?? 7", cancelled));
        var exhausted = new ScriptBudget(1, 128, new CancellationToken());
        assertEquals(MolangException.Reason.STEPS, assertThrows(MolangException.class,
                () -> evaluate("v.absent ?? 7", new MolangContext(Map.of(), Map.of(), Map.of(), exhausted))).reason());
        assertEquals(0, exhausted.activeDepth());
        assertThrows(MolangException.class, () -> MolangProgram.compile("v.absent ?? ".repeat(150) + "1"));
    }

    @Test void absentAssignmentsCommitAndRollbackWithTheOwningSceneFrame() {
        var clip = new AnimationClip("test", 1, true, Map.of(), List.of());
        var definition = new SceneDefinition(clip,
                MolangProgram.compile("v.x=q.clear ? q.absent : (q.value ?? v.x)"),
                Map.of("x", MolangProgram.compile("v.x ?? 7"),
                        "failure", MolangProgram.compile("q.fail ? math.sqrt(-1) : 0")), Map.of());
        var scene = new SceneInstance(definition, 60);
        scene.evaluate(0, 0, new SceneInputs(Map.of()), context().budget());
        var first = scene.evaluate(1, 16_666_667, new SceneInputs(Map.of("value", 3.0)), context().budget());
        assertEquals(3, first.variables().get("x"));
        assertThrows(MolangException.class, () -> scene.evaluate(2, 33_333_334,
                new SceneInputs(Map.of("clear", 1.0, "fail", 1.0)), context().budget()));
        var retry = scene.evaluate(2, 33_333_334, new SceneInputs(Map.of()), context().budget());
        assertEquals(3, retry.variables().get("x"));
        var cleared = scene.evaluate(3, 50_000_001, new SceneInputs(Map.of("clear", 1.0)), context().budget());
        assertTrue(cleared.variables().isEmpty());
        assertEquals(7, cleared.channels().get("x"));
        assertEquals(3, first.variables().get("x"));
        assertThrows(UnsupportedOperationException.class, () -> cleared.variables().put("x", 1.0));
    }
}
