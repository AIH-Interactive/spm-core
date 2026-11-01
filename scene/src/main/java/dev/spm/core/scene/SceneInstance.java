package dev.spm.core.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Serial per-owner runtime. A failed or cancelled frame publishes no time, variable or effect changes. */
public final class SceneInstance {
    private final long instanceId = SceneIdentity.next();
    private final SceneDefinition definition;
    private FixedStepClock clock;
    private Map<String, Double> variables = Map.of();
    private FrameEvaluation lastFrame;
    private long effectSequence;
    private long randomState;
    private ControllerRuntime<Map<String,Double>> controller;

    public SceneInstance(SceneDefinition definition, int maximumCatchUpSteps) {
        this(definition, null, maximumCatchUpSteps, 0);
    }
    public SceneInstance(SceneDefinition definition, int maximumCatchUpSteps, long randomSeed) {
        this(definition, null, maximumCatchUpSteps, randomSeed);
    }
    public SceneInstance(SceneDefinition definition, ControllerDefinition controller, int maximumCatchUpSteps) {
        this(definition, controller, maximumCatchUpSteps, 0);
    }
    public SceneInstance(SceneDefinition definition, ControllerDefinition controller, int maximumCatchUpSteps, long randomSeed) {
        this.definition = Objects.requireNonNull(definition);
        clock = new FixedStepClock(maximumCatchUpSteps);
        this.controller = controller == null ? null : new ControllerRuntime<>(controller,ControllerRuntime.scalarExecution());
        randomState = randomSeed;
    }
    public synchronized ControllerSnapshot controllerSnapshot() {
        if (controller == null) throw new IllegalStateException("Scene has no controller");
        return controller.snapshot();
    }
    public synchronized String controllerState() { return controllerSnapshot().state(); }
    public synchronized double controllerStateTimeSeconds() { return controllerSnapshot().stateTimeSeconds(); }
    public synchronized FrameEvaluation evaluate(long frameId, long nowNanos, SceneInputs inputs, ScriptBudget budget) {
        if (frameId < 0 || (lastFrame != null && frameId < lastFrame.frameId())) throw new IllegalArgumentException("Frame id must increase");
        if (lastFrame != null && frameId == lastFrame.frameId()) return lastFrame;
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(budget).checkCancelled();
        FixedStepClock candidateClock = clock.copy();
        FixedStepClock.Advance advance = candidateClock.advance(nowNanos);
        long firstStep = advance.totalSteps() - advance.executedSteps();
        MolangContext context = new MolangContext(inputs.queries(), variables, definition.functions(), budget, randomState);
        ControllerRuntime<Map<String,Double>> candidateController = controller == null ? null : controller.copy();
        List<EffectTimeline.Pending> pending = new ArrayList<>();
        if (lastFrame == null) {
            if (candidateController == null) EffectTimeline.append(definition.clip(), 0, 0, true, 0, pending, budget);
            else candidateController.initialize(sceneContext(context, 0), pending);
        }
        for (int offset = 1; offset <= advance.executedSteps(); offset++) {
            budget.step();
            long step = firstStep + offset;
            MolangContext tickContext = sceneContext(context, step);
            definition.update().evaluate(candidateController == null ? tickContext : candidateController.contextAt(tickContext, step));
            if (candidateController == null) {
                EffectTimeline.append(definition.clip(), (step - 1) / 60.0, step / 60.0, false, step, pending, budget);
            } else candidateController.tick(step, tickContext, pending);
        }
        double time = advance.totalSteps() / 60.0;
        Map<String, Double> channels = new TreeMap<>();
        if (candidateController != null) channels.putAll(candidateController.pose());
        else {
            for (Map.Entry<String, KeyframeCurve> entry : new TreeMap<>(definition.clip().curves()).entrySet()) {
                budget.step();
                channels.put(entry.getKey(), entry.getValue().sample(definition.clip().localTime(time)));
            }
        }
        MolangContext sample = sceneContext(context, advance.totalSteps());
        if (candidateController != null) sample = candidateController.contextAt(sample, advance.totalSteps());
        for (Map.Entry<String, MolangProgram> entry : new TreeMap<>(definition.channels()).entrySet()) {
            budget.step();
            channels.put(entry.getKey(), entry.getValue().evaluate(sample));
        }
        List<EffectCommand> effects = new ArrayList<>(pending.size());
        for (EffectTimeline.Pending effect : pending) {
            budget.step();
            AnimationClip.Cue cue = effect.cue();
            effects.add(new EffectCommand(instanceId, 0, Math.addExact(effectSequence, effects.size()),
                    effect.step(), cue.kind(), cue.resource(), cue.parameters()));
        }
        budget.checkCancelled();
        FrameEvaluation candidate = new FrameEvaluation(instanceId, 0, frameId, advance.totalSteps(), time,
                advance.interpolation(), advance.droppedSteps(), channels, context.variables(), effects);
        long candidateSequence = Math.addExact(effectSequence, effects.size());
        clock = candidateClock;
        variables = candidate.variables();
        effectSequence = candidateSequence;
        controller = candidateController;
        randomState = context.randomState();
        lastFrame = candidate;
        return candidate;
    }
    private MolangContext sceneContext(MolangContext context, long step) {
        Map<String, Double> builtins = new HashMap<>();
        builtins.put("anim_time", definition.clip().localTime(step / 60.0));
        builtins.put("delta_time", 1.0 / 60.0);
        builtins.put("life_time", step / 60.0);
        return context.overlay(builtins);
    }
}
