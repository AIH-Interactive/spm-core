package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.scene.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the shared-budget concurrency contract and for non-uniform Catmull-Rom tracks.
 *
 * <p>Depth belongs to one evaluation stack, so two workers nested to the same level must not exhaust
 * each other's allowance; steps stay one aggregate counter, so adding workers cannot multiply the
 * allowance.
 */
final class ScriptBudgetConcurrencyTest {
    private static JsonNumber n(double v) { return new JsonNumber(BigDecimal.valueOf(v)); }
    private static JsonString s(String v) { return new JsonString(v); }
    private static JsonObject o(Object... pairs) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], (JsonValue) pairs[i + 1]);
        return new JsonObject(values);
    }
    private static ScriptBudget budget() { return new ScriptBudget(500000, 128, new CancellationToken()); }
    private static Skeleton skeleton() { return new Skeleton(List.of("root"), new int[] {-1}, new float[3]); }

    @Test void twoWorkersNestedToTheLimitDoNotReportDepthForEachOther() throws Exception {
        ScriptBudget shared = new ScriptBudget(4000, 20, new CancellationToken());
        MolangProgram nested = MolangProgram.compile(nested(10));
        var workers = Executors.newFixedThreadPool(2);
        try {
            Callable<String> task = () -> {
                try {
                    nested.evaluate(new MolangContext(Map.of(), new LinkedHashMap<>(), Map.of(), shared));
                    return "OK";
                } catch (MolangException failure) { return failure.reason().name(); }
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertEquals("OK", first.get(20, TimeUnit.SECONDS));
            assertEquals("OK", second.get(20, TimeUnit.SECONDS));
            assertEquals(0, shared.activeDepth(), "worker depth must not leak to the caller");
            assertTrue(shared.remainingSteps() > 0, "steps remain one shared allowance");
        } finally { workers.shutdownNow(); }
    }

    @Test void genuineRecursionStillReportsDepthAndRestoresTheCount() {
        ScriptBudget budget = new ScriptBudget(1_000_000, 20, new CancellationToken());
        MolangProgram recursive = MolangProgram.compile("function.recur()");
        MolangContext context = new MolangContext(Map.of(), new LinkedHashMap<>(),
                Map.of("recur", recursive), budget);
        assertEquals(MolangException.Reason.DEPTH,
                assertThrows(MolangException.class, () -> recursive.evaluate(context)).reason());
        assertEquals(0, budget.activeDepth());
    }

    @Test void catmullRomUsesRealKeyIntervalsInsteadOfAssumingUniformSpacing() {
        // 0, 16, 0, 0 pixels at t = 0, 1, 2, 3 (uniform) and at t = 0, 4, 8, 12 (stretched) must
        // sample identically; a rule that ignores intervals separates them. The third track keeps
        // the same values but stretches only its last interval, which must change the result.
        var uniform = o("0", n(0), "1", o("post", n(16), "lerp_mode", s("catmull_rom")),
                "2", o("post", n(0), "lerp_mode", s("catmull_rom")), "3", n(0));
        var stretched = o("0", n(0), "4", o("post", n(16), "lerp_mode", s("catmull_rom")),
                "8", o("post", n(0), "lerp_mode", s("catmull_rom")), "12", n(0));
        var skewed = o("0", n(0), "1", o("post", n(16), "lerp_mode", s("catmull_rom")),
                "2", o("post", n(0), "lerp_mode", s("catmull_rom")), "6", n(0));
        var library = PoseAnimationLibrary.decode(o("animations", o(
                "uniform", o("animation_length", n(3), "bones", o("root", o("position", uniform))),
                "stretched", o("animation_length", n(12), "bones", o("root", o("position", stretched))),
                "skewed", o("animation_length", n(6), "bones", o("root", o("position", skewed))))),
                skeleton(), budget());
        BonePose neutral = BonePose.identity(1);
        var context = new MolangContext(Map.of(), new LinkedHashMap<>(), Map.of(), budget());

        float uniformMiddle = library.sample("uniform", 1.5, context, neutral).translations()[0];
        float stretchedMiddle = library.sample("stretched", 6.0, context, neutral).translations()[0];
        float skewedMiddle = library.sample("skewed", 1.5, context, neutral).translations()[0];
        // 8.6 source pixels at the middle of [1,2], negated and scaled to block units: -8.6/16.
        assertEquals(-0.5625f, uniformMiddle, 1e-5f);
        assertEquals(uniformMiddle, stretchedMiddle, 1e-6f, "uniformly stretched keys sample identically");
        assertEquals(-0.6f, skewedMiddle, 1e-5f, "a stretched neighbour interval changes the tangent");
    }

    private static String nested(int depth) {
        String source = "1";
        for (int i = 0; i < depth; i++) source = "(" + source + "+1)";
        return source;
    }
}
