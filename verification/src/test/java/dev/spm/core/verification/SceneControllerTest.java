package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SceneControllerTest {
    private static MolangProgram p(String source) { return MolangProgram.compile(source); }
    private static ScriptBudget budget() { return new ScriptBudget(200000, 128, new CancellationToken()); }
    private static AnimationClip clip(String name, double value) {
        return new AnimationClip(name, 1, true,
                Map.of("x", new KeyframeCurve(List.of(new KeyframeCurve.Key(0, value)), KeyframeCurve.Interpolation.LINEAR)),
                List.of(new AnimationClip.Cue(0, "sound", name, Map.of())));
    }
    private static SceneDefinition base() {
        return new SceneDefinition(clip("base", 999), p("v.updates=v.updates+1"), Map.of(), Map.of());
    }
    private static ControllerDefinition.State state(String clip, List<ControllerDefinition.Transition> transitions,
                                                     String entry, String exit, double blend) {
        return new ControllerDefinition.State(clip.isEmpty() ? List.of() : List.of(new ControllerDefinition.ClipBinding(clip, p("1"))),
                transitions, List.of(p(entry)), List.of(p(exit)), blend);
    }
    @Test void orderedTransitionsEntryExitAndTimeArePerInstanceAndOncePerTick() {
        var idle = state("idle", List.of(new ControllerDefinition.Transition("walk", p("q.move")),
                new ControllerDefinition.Transition("wrong", p("1"))), "v.enter=v.enter+1", "v.exit=v.exit+1", 0);
        var walk = state("walk", List.of(), "v.enter=v.enter+10", "0", 0);
        var wrong = state("wrong", List.of(), "v.enter=999", "0", 0);
        var controller = new ControllerDefinition("idle", Map.of("idle", clip("idle", 2), "walk", clip("walk", 8), "wrong", clip("wrong", 100)),
                Map.of("idle", idle, "walk", walk, "wrong", wrong));
        SceneInstance a = new SceneInstance(base(), controller, 60);
        SceneInstance b = new SceneInstance(base(), controller, 60);
        var input = new SceneInputs(Map.of("move", 1.0));
        var initial = a.evaluate(0, 0, input, budget());
        assertEquals("idle", a.controllerSnapshot().state());
        assertEquals(1, initial.variables().get("enter"));
        assertEquals(List.of("idle"), initial.effects().stream().map(EffectCommand::resource).toList());
        var frame = a.evaluate(1, 16_666_667, input, budget());
        assertEquals("walk", a.controllerSnapshot().state());
        assertEquals(0, a.controllerSnapshot().stateTimeSeconds());
        assertEquals(11, frame.variables().get("enter"));
        assertEquals(1, frame.variables().get("exit"));
        assertEquals(8, frame.channels().get("x"));
        assertSame(frame, a.evaluate(1, 50_000_000, input, budget()));
        assertEquals(11, a.evaluate(1, 0, input, budget()).variables().get("enter"));
        b.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        assertEquals("idle", b.controllerSnapshot().state());
    }
    @Test void weightedClipsAndCrossfadeReachExpectedIntermediateAndFinalValues() {
        var a = state("a", List.of(new ControllerDefinition.Transition("b", p("q.move"))), "0", "0", 0);
        var b = new ControllerDefinition.State(List.of(new ControllerDefinition.ClipBinding("b", p("0.25")),
                new ControllerDefinition.ClipBinding("c", p("0.75"))), List.of(), List.of(), List.of(), 1);
        var definition = new ControllerDefinition("a", Map.of("a", clip("a", 2), "b", clip("b", 10), "c", clip("c", 6)), Map.of("a", a, "b", b));
        SceneInstance instance = new SceneInstance(base(), definition, 120);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        var start = instance.evaluate(1, 16_666_667, new SceneInputs(Map.of("move", 1.0)), budget());
        assertEquals(2, start.channels().get("x"));
        var middle = instance.evaluate(2, 516_666_667, new SceneInputs(Map.of()), budget());
        assertEquals(4.5, middle.channels().get("x"), 1e-12);
        assertEquals(0.5, instance.controllerSnapshot().blendProgress(), 1e-12);
        var end = instance.evaluate(3, 1_016_666_667, new SceneInputs(Map.of()), budget());
        assertEquals(7, end.channels().get("x"));
        assertEquals(1, instance.controllerSnapshot().stateTimeSeconds());
    }
    @Test void emptyStateChainsAreBoundedAndFailureRollsBackWholeGraph() {
        var a = state("", List.of(new ControllerDefinition.Transition("b", p("1"))), "v.entry=v.entry+1", "0", 0);
        var b = state("", List.of(new ControllerDefinition.Transition("a", p("1"))), "v.entry=v.entry+1", "0", 0);
        var def = new ControllerDefinition("a", Map.of(), Map.of("a", a, "b", b));
        SceneInstance instance = new SceneInstance(base(), def, 60);
        var initial = instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        assertThrows(MolangException.class, () -> instance.evaluate(1, 16_666_667, new SceneInputs(Map.of()), budget()));
        assertEquals("a", instance.controllerSnapshot().state());
        assertSame(initial, instance.evaluate(0, 0, new SceneInputs(Map.of()), budget()));
        assertEquals(1, initial.variables().get("entry"));
    }
    @Test void entryFailureDoesNotCommitTransitionTimeVariablesOrEffects() {
        var a = state("a", List.of(new ControllerDefinition.Transition("b", p("q.move"))), "0", "v.exit=v.exit+1", 0);
        var b = state("b", List.of(), "loop(100, {}); v.enter=1", "0", 0);
        var def = new ControllerDefinition("a", Map.of("a", clip("a", 1), "b", clip("b", 2)), Map.of("a", a, "b", b));
        SceneInstance instance = new SceneInstance(base(), def, 60);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        var input = new SceneInputs(Map.of("move", 1.0));
        assertThrows(MolangException.class, () -> instance.evaluate(1, 16_666_667, input,
                new ScriptBudget(30, 128, new CancellationToken())));
        assertEquals("a", instance.controllerSnapshot().state());
        var retry = instance.evaluate(1, 16_666_667, input, budget());
        assertEquals(1, retry.variables().get("exit"));
        assertEquals(1, retry.variables().get("enter"));
        assertEquals(List.of("b"), retry.effects().stream().map(EffectCommand::resource).toList());
    }
    @Test void missingStateAndClipReferencesAreRejectedBeforeEvaluation() {
        assertThrows(IllegalArgumentException.class, () -> new ControllerDefinition("missing", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ControllerDefinition("a", Map.of(),
                Map.of("a", state("unknown", List.of(), "0", "0", 0))));
    }
    @Test void selfTransitionDoesNotRestartAndEmptyChainReachesAnimatedStateOnce() {
        var empty = state("", List.of(new ControllerDefinition.Transition("active", p("1"))), "v.entry=v.entry+1", "0", 0);
        var active = state("active", List.of(new ControllerDefinition.Transition("active", p("1"))), "v.entry=v.entry+10", "0", 0);
        SceneInstance instance = new SceneInstance(base(), new ControllerDefinition("empty", Map.of("active", clip("active", 7)),
                Map.of("empty", empty, "active", active)), 120);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        var frame = instance.evaluate(1, 1_000_000_000, new SceneInputs(Map.of()), budget());
        assertEquals("active", instance.controllerSnapshot().state());
        assertEquals(11, frame.variables().get("entry"));
        assertEquals(59 / 60.0, instance.controllerSnapshot().stateTimeSeconds());
        assertEquals(1, frame.effects().size());
    }
    @Test void finishQueriesUseStateTimeAndNonLoopingDuration() {
        AnimationClip once = new AnimationClip("once", 0.5, false, clip("dummy", 2).curves(), List.of());
        var playing = state("once", List.of(new ControllerDefinition.Transition("done", p("q.all_animations_finished && q.any_animation_finished && q.state_time>=0.5"))), "0", "0", 0);
        var done = state("done", List.of(), "v.finished=1", "0", 0);
        SceneInstance instance = new SceneInstance(base(), new ControllerDefinition("playing",
                Map.of("once", once, "done", clip("done", 9)), Map.of("playing", playing, "done", done)), 60);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        var before = instance.evaluate(1, 483_333_334, new SceneInputs(Map.of()), budget());
        assertFalse(before.variables().containsKey("finished"));
        assertEquals("playing", instance.controllerState());
        var frame = instance.evaluate(2, 500_000_000, new SceneInputs(Map.of()), budget());
        assertEquals("done", instance.controllerSnapshot().state());
        assertEquals(1, frame.variables().get("finished"));
    }
    @Test void interruptedBlendStartsFromCurrentBlendedPoseWithoutNestedHistory() {
        var a = state("a", List.of(new ControllerDefinition.Transition("b", p("q.mode==1"))), "0", "0", 0);
        var b = state("b", List.of(new ControllerDefinition.Transition("c", p("q.mode==2"))), "0", "0", 1);
        var c = state("c", List.of(), "0", "0", 1);
        var def = new ControllerDefinition("a", Map.of("a", clip("a", 2), "b", clip("b", 8), "c", clip("c", 20)),
                Map.of("a", a, "b", b, "c", c));
        SceneInstance instance = new SceneInstance(base(), def, 120);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        instance.evaluate(1, 16_666_667, new SceneInputs(Map.of("mode", 1.0)), budget());
        var halfway = instance.evaluate(2, 516_666_667, new SceneInputs(Map.of()), budget());
        assertEquals(5, halfway.channels().get("x"));
        var interrupted = instance.evaluate(3, 533_333_334, new SceneInputs(Map.of("mode", 2.0)), budget());
        assertEquals(5, interrupted.channels().get("x"));
        var after = instance.evaluate(4, 1_033_333_334, new SceneInputs(Map.of()), budget());
        assertEquals(12.5, after.channels().get("x"));
    }
    @Test void failingFrameRestoresRandomSequenceAsWellAsControllerState() {
        var a = state("a", List.of(new ControllerDefinition.Transition("b", p("q.move"))), "0", "0", 0);
        var b = state("b", List.of(), "v.random=math.random(0,1); loop(100, {});", "0", 0);
        var def = new ControllerDefinition("a", Map.of("a", clip("a", 1), "b", clip("b", 2)), Map.of("a", a, "b", b));
        SceneInstance retry = new SceneInstance(base(), def, 60, 1234);
        SceneInstance clean = new SceneInstance(base(), def, 60, 1234);
        var stopped = new SceneInputs(Map.of());
        var moving = new SceneInputs(Map.of("move", 1.0));
        retry.evaluate(0, 0, stopped, budget());
        clean.evaluate(0, 0, stopped, budget());
        assertThrows(MolangException.class, () -> retry.evaluate(1, 16_666_667, moving,
                new ScriptBudget(80, 128, new CancellationToken())));
        var retried = retry.evaluate(1, 16_666_667, moving, budget());
        var expected = clean.evaluate(1, 16_666_667, moving, budget());
        assertEquals(expected.variables().get("random"), retried.variables().get("random"));
        assertEquals(expected.channels(), retried.channels());
    }
    @Test void graphAndMolangShareOneNonResettableFrameBudget() {
        var a = state("a", List.of(new ControllerDefinition.Transition("b", p("0"))), "0", "0", 0);
        var b = state("b", List.of(), "0", "0", 0);
        var def = new ControllerDefinition("a", Map.of("a", clip("a", 1), "b", clip("b", 2)), Map.of("a", a, "b", b));
        SceneInstance instance = new SceneInstance(base(), def, 60);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        ScriptBudget shared = new ScriptBudget(30, 128, new CancellationToken());
        assertThrows(MolangException.class, () -> instance.evaluate(1, 1_000_000_000, new SceneInputs(Map.of()), shared));
        assertEquals(0, shared.remainingSteps());
        assertEquals(0, shared.activeDepth());
        assertEquals(0, instance.controllerSnapshot().stateTimeSeconds());
    }
    @Test void finiteEmptyBridgeMayReturnToAnimatedStateAndRestartIt() {
        var a = state("a", List.of(new ControllerDefinition.Transition("bridge", p("q.restart"))), "v.entries=v.entries+1", "0", 0);
        var bridge = state("", List.of(new ControllerDefinition.Transition("a", p("1"))), "0", "0", 0);
        var def = new ControllerDefinition("a", Map.of("a", clip("a", 2)), Map.of("a", a, "bridge", bridge));
        SceneInstance instance = new SceneInstance(base(), def, 60);
        instance.evaluate(0, 0, new SceneInputs(Map.of()), budget());
        var frame = instance.evaluate(1, 16_666_667, new SceneInputs(Map.of("restart", 1.0)), budget());
        assertEquals("a", instance.controllerState());
        assertEquals(0, instance.controllerStateTimeSeconds());
        assertEquals(2, frame.variables().get("entries"));
        assertEquals(1, frame.effects().size());
    }
}
