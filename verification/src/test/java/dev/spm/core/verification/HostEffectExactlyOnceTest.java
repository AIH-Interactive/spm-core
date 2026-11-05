package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T07 named this test and it did not exist. Core computes effects; the Host plays them. This
 * class pins the exactly-once delivery contract on the Core side: the same effect occurrence must
 * never be handed to the Host twice, and a cursor must not cross into another scene instance or a
 * stale generation.
 */
final class HostEffectExactlyOnceTest {

    /** A 2s looping clip with one cue at t=0.5s, so a 60 Hz clock crosses it exactly once per cycle. */
    private static SceneDefinition cuedScene() {
        var clip = new AnimationClip("clip", 2.0, true,
                Map.of("value", new KeyframeCurve(
                        List.of(new KeyframeCurve.Key(0, 0), new KeyframeCurve.Key(2, 1)),
                        KeyframeCurve.Interpolation.LINEAR)),
                List.of(new AnimationClip.Cue(0.5, "sound", "spm:test.ogg", Map.of("gain", 1.0))));
        MolangProgram zero = MolangProgram.compile("0");
        return new SceneDefinition(clip, zero, Map.of(), Map.of());
    }

    private static SceneInstance instance() {
        return new SceneInstance(cuedScene(), 600, 7L);
    }

    private static ScriptBudget budget() {
        return new ScriptBudget(1_000_000, 256, new CancellationToken());
    }

    /** 60 Hz, so step N is N/60 s. Step 30 is exactly t=0.5s. */
    private static long nanosAtStep(long step) {
        return step * 1_000_000_000L / 60L;
    }

    /**
     * The first {@code evaluate} only establishes the clock baseline: {@link FixedStepClock}
     * reports zero executed steps on its first advance, so no timeline interval is traversed. Every
     * test below primes with step 0 first and starts from frame id 1. This is a real property of the
     * clock, not a test workaround, so it is asserted in
     * {@link #theFirstEvaluationEstablishesTheClockWithoutExecutingOrEmittingAnything()}.
     */
    private static SceneInstance primed() {
        var scene = instance();
        scene.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        return scene;
    }

    @Test void theFirstEvaluationEstablishesTheClockWithoutExecutingOrEmittingAnything() {
        var scene = instance();
        var cursor = new EffectCursor();
        var bootstrap = scene.evaluate(0, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        assertEquals(0, bootstrap.step(), "the first advance must not execute steps");
        assertEquals(0.0, bootstrap.timeSeconds());
        assertTrue(bootstrap.effects().isEmpty());
        assertTrue(cursor.claim(bootstrap).isEmpty());
    }

    @Test void aSingleCueIsClaimedExactlyOnceEvenWhenTheFrameIsReevaluated() {
        var scene = primed();
        var cursor = new EffectCursor();
        var first = scene.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        List<EffectCommand> claimed = cursor.claim(first);

        assertEquals(1, claimed.size(), "the 0.5s cue must fire once when step 30 is crossed");
        var effect = claimed.get(0);
        assertEquals("sound", effect.kind());
        assertEquals("spm:test.ogg", effect.resource());

        // Re-claiming the same frame must not re-deliver: this is the idempotency guarantee.
        assertTrue(cursor.claim(first).isEmpty(), "the same frame must not be delivered twice");

        assertTrue(cursor.claim(first).isEmpty(), "repeated claims stay empty");
        assertEquals(1, claimed.size());
    }

    @Test void advancingPastTheCueDoesNotReplayItAndTheNextCycleProducesANewSequence() {
        var scene = primed();
        var cursor = new EffectCursor();
        var atCrossing = scene.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        assertEquals(1, cursor.claim(atCrossing).size());
        long firstSequence = atCrossing.effects().get(0).sequence();

        // Steps 31..119 must not contain the cue again.
        for (long step = 31; step <= 119; step++) {
            var frame = scene.evaluate(step, nanosAtStep(step), new SceneInputs(Map.of()), budget());
            assertTrue(cursor.claim(frame).isEmpty(), "cue replayed at step " + step);
        }

        // Step 120 is the start of the second cycle; the cue lands at 2.5s = step 150.
        var second = scene.evaluate(150, nanosAtStep(150), new SceneInputs(Map.of()), budget());
        List<EffectCommand> again = cursor.claim(second);
        assertEquals(1, again.size(), "the looped clip's cue fires once in the second cycle");
        long secondSequence = again.get(0).sequence();
        assertTrue(secondSequence > firstSequence, "sequence must increase across cycles");
    }

    @Test void everyDeliveredEffectCarriesAnIncreasingSequence() {
        var scene = primed();
        var cursor = new EffectCursor();
        long previous = -1;
        int delivered = 0;
        for (long step = 1; step <= 400; step++) {
            var frame = scene.evaluate(step, nanosAtStep(step), new SceneInputs(Map.of()), budget());
            for (EffectCommand effect : cursor.claim(frame)) {
                assertTrue(effect.sequence() > previous,
                        "sequence must strictly increase: " + effect.sequence() + " after " + previous);
                previous = effect.sequence();
                assertEquals(frame.instanceId(), effect.instanceId());
                assertEquals(frame.generation(), effect.generation());
                delivered++;
            }
        }
        // Steps 30/150/270/390 fall at 0.5s in cycles 0..3.
        assertEquals(4, delivered, "four cycles crossed in 400 steps => four deliveries");
    }

    @Test void aCursorRefusesToServeAnotherSceneInstance() {
        var cursor = new EffectCursor();
        var first = primed();
        var other = primed(); // a fresh instance id, same definition
        var frame = first.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        cursor.claim(frame);
        var otherFrame = other.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        assertThrows(IllegalArgumentException.class, () -> cursor.claim(otherFrame));
    }

    @Test void aStaleGenerationOrFrameIsDroppedInsteadOfReplayed() {
        var cursor = new EffectCursor();
        var scene = primed();
        var frame = scene.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        assertEquals(1, cursor.claim(frame).size());

        // Same generation, older frame => dropped.
        var olderShell = new FrameEvaluation(frame.instanceId(), frame.generation(), 0, frame.step(),
                frame.timeSeconds(), frame.interpolation(), frame.droppedSteps(),
                frame.channels(), frame.variables(), frame.effects());
        assertTrue(cursor.claim(olderShell).isEmpty(), "an older frame must not be replayed");

        // A later generation is accepted and advances the cursor.
        var newerEffects=frame.effects().stream().map(effect -> new EffectCommand(effect.instanceId(),frame.generation()+5,
                effect.sequence(),effect.step(),effect.kind(),effect.resource(),effect.parameters(),effect.arguments())).toList();
        var newer = new FrameEvaluation(frame.instanceId(), frame.generation() + 5, 1, frame.step(),
                frame.timeSeconds(), frame.interpolation(), frame.droppedSteps(),
                frame.channels(), frame.variables(), newerEffects);
        assertEquals(1, cursor.claim(newer).size(), "a newer generation is delivered");

        // Now the original (lower) generation is retired and must not be served again.
        assertTrue(cursor.claim(frame).isEmpty(), "a retired generation must not be served");
    }

    @Test void aFailedEvaluationPublishesNoEffectsAtAll() {
        var scene = primed();
        var cursor = new EffectCursor();
        var cancelled = new CancellationToken();
        cancelled.cancel();
        var exhausted = new ScriptBudget(0, 256, cancelled);

        assertThrows(RuntimeException.class,
                () -> scene.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), exhausted));

        // The failed frame must not have advanced the scene: re-evaluating the same id with a real
        // budget still produces the cue, and it is still delivered exactly once.
        var frame = scene.evaluate(1, nanosAtStep(30), new SceneInputs(Map.of()), budget());
        assertEquals(1, cursor.claim(frame).size());
    }
}
