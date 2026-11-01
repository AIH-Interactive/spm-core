package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SceneRuntimeTest {
    private static ScriptBudget budget() { return new ScriptBudget(10000, 128, new CancellationToken()); }
    private static SceneDefinition definition() {
        KeyframeCurve curve = new KeyframeCurve(List.of(new KeyframeCurve.Key(0, 0), new KeyframeCurve.Key(1, 10)), KeyframeCurve.Interpolation.LINEAR);
        AnimationClip clip = new AnimationClip("walk", 1, true, Map.of("bone.x", curve),
                List.of(new AnimationClip.Cue(0, "sound", "step", Map.of("volume", 1.0)),
                        new AnimationClip.Cue(0.5, "particle", "dust", Map.of())));
        return new SceneDefinition(clip, MolangProgram.compile("v.count=v.count+1;"),
                Map.of("speed", MolangProgram.compile("q.speed+v.count")), Map.of());
    }
    @Test void rational60HzClockDoesNotRegressAndBoundsCatchUp() {
        FixedStepClock clock = new FixedStepClock(4);
        assertEquals(0, clock.advance(0).executedSteps());
        assertEquals(1, clock.advance(16_666_667).executedSteps());
        FixedStepClock.Advance a = clock.advance(1_000_000_000);
        assertEquals(4, a.executedSteps());
        assertEquals(55, a.droppedSteps());
        assertEquals(5, a.totalSteps());
        assertThrows(IllegalArgumentException.class, () -> clock.advance(0));
        FixedStepClock exact = new FixedStepClock(60);
        exact.advance(0);
        assertEquals(60, exact.advance(1_000_000_000).totalSteps());
    }
    @Test void stateIsPerInstanceAndMultipassReusesOneImmutableEvaluation() {
        SceneInstance first = new SceneInstance(definition(), 60);
        SceneInstance second = new SceneInstance(definition(), 60);
        SceneInputs input = new SceneInputs(Map.of("speed", 2.0));
        FrameEvaluation f0 = first.evaluate(0, 0, input, budget());
        FrameEvaluation f1 = first.evaluate(1, 500_000_000, input, budget());
        assertEquals(30, f1.variables().get("count"));
        assertEquals(5, f1.channels().get("bone.x"));
        assertEquals(32, f1.channels().get("speed"));
        assertSame(f1, first.evaluate(1, 900_000_000, input, budget()));
        assertTrue(second.evaluate(0, 0, input, budget()).variables().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> f1.variables().put("count", 2.0));
        EffectCursor delivery = new EffectCursor();
        assertEquals(1, delivery.claim(f0).size());
        assertEquals(1, delivery.claim(f1).size());
        assertTrue(delivery.claim(f1).isEmpty());
        assertTrue(delivery.claim(f0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> first.evaluate(0, 500_000_000, input, budget()));
    }
    @Test void failedFrameDoesNotPublishTimeVariablesOrEffects() {
        SceneInstance instance = new SceneInstance(definition(), 60);
        SceneInputs input = new SceneInputs(Map.of("speed", 2.0));
        instance.evaluate(0, 0, input, budget());
        assertThrows(MolangException.class, () -> instance.evaluate(1, 500_000_000, input,
                new ScriptBudget(5, 128, new CancellationToken())));
        FrameEvaluation retry = instance.evaluate(1, 500_000_000, input, budget());
        assertEquals(30, retry.variables().get("count"));
        assertEquals(1, retry.effects().size());
        assertEquals(30, retry.step());
    }
    @Test void loopingEffectBoundariesAndStepCurveAreDeterministic() {
        SceneInstance instance = new SceneInstance(definition(), 120);
        SceneInputs input = new SceneInputs(Map.of());
        FrameEvaluation initial = instance.evaluate(0, 0, input, budget());
        assertEquals(1, initial.effects().size());
        FrameEvaluation twoLoops = instance.evaluate(1, 2_000_000_000L, input, budget());
        assertEquals(4, twoLoops.effects().size());
        assertEquals(0, twoLoops.channels().get("bone.x"));
        assertEquals(4, twoLoops.effects().stream().map(EffectCommand::sequence).distinct().count());
        KeyframeCurve step = new KeyframeCurve(List.of(new KeyframeCurve.Key(0, 2), new KeyframeCurve.Key(1, 4)), KeyframeCurve.Interpolation.STEP);
        assertEquals(2, step.sample(0.5));
        assertEquals(4, step.sample(1));
    }
    @Test void namedChannelsKeepCaseAndCuesAreDeliveredInTimeOrder() {
        AnimationClip clip = new AnimationClip("test", 1, false,
                Map.of("Bone.X", new KeyframeCurve(List.of(new KeyframeCurve.Key(0, 5)), KeyframeCurve.Interpolation.STEP)),
                List.of(new AnimationClip.Cue(0.007, "sound", "late", Map.of()),
                        new AnimationClip.Cue(0.003, "sound", "early", Map.of())));
        SceneInstance instance = new SceneInstance(new SceneDefinition(clip, MolangProgram.compile("0"), Map.of(), Map.of()), 60);
        SceneInputs input = new SceneInputs(Map.of());
        instance.evaluate(0, 0, input, budget());
        FrameEvaluation frame = instance.evaluate(1, 1_000_000_000, input, budget());
        assertEquals(5, frame.channels().get("Bone.X"));
        assertEquals(List.of("early", "late"), frame.effects().stream().map(EffectCommand::resource).toList());
        EffectCursor cursor = new EffectCursor();
        cursor.claim(frame);
        FrameEvaluation other = new SceneInstance(definition(), 60).evaluate(0, 0, input, budget());
        assertThrows(IllegalArgumentException.class, () -> cursor.claim(other));
    }
    @Test void manyTinyLoopsRejectEffectOverflowWithoutPublishingAnything() {
        AnimationClip clip = new AnimationClip("fast", 0.000001, true, Map.of(),
                List.of(new AnimationClip.Cue(0, "sound", "tick", Map.of())));
        SceneInstance instance = new SceneInstance(new SceneDefinition(clip, MolangProgram.compile("v.x=v.x+1"), Map.of(), Map.of()), 60);
        SceneInputs inputs = new SceneInputs(Map.of());
        FrameEvaluation initial = instance.evaluate(0, 0, inputs, budget());
        assertThrows(IllegalStateException.class, () -> instance.evaluate(1, 16_666_667, inputs,
                new ScriptBudget(100000, 128, new CancellationToken())));
        assertSame(initial, instance.evaluate(0, 0, inputs, budget()));
    }
}
