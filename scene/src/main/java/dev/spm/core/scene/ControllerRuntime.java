package dev.spm.core.scene;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Private candidate state, copied before each scene frame and committed by its owner. */
final class ControllerRuntime<P> {
    record WeightedClip(AnimationClip clip, double weight) { }
    /** One graph scheduler; scalar and typed pose implementations supply only clip execution. */
    interface Execution<P> {
        P empty();
        P sample(List<WeightedClip> clips,double elapsed,MolangContext context);
        P blend(P from,P target,double progress,ScriptBudget budget);
        default boolean finished(AnimationClip clip,double elapsed) { return !clip.looping() && elapsed>=clip.durationSeconds(); }
        default void emit(AnimationClip clip,double start,double end,boolean initial,long step,MolangContext context,List<EffectTimeline.Pending> effects) {
            EffectTimeline.append(clip,start,end,initial,step,effects,context.budget());
        }
        default void enter(String state,long step,MolangContext context,List<EffectTimeline.Pending> effects) { }
        default boolean traverseInitialEmptyStates() { return false; }
    }
    private final ControllerDefinition definition;
    private final Execution<P> execution;
    private String state;
    private long enteredStep;
    private long currentStep;
    private List<WeightedClip> active = List.of();
    private P pose;
    private P blendFrom;
    private double blendSeconds;
    private double blendProgress = 1;

    ControllerRuntime(ControllerDefinition definition,Execution<P> execution) {
        this.definition=definition; this.execution=execution; pose=execution.empty(); blendFrom=pose;
    }
    private ControllerRuntime(ControllerRuntime<P> other) {
        definition = other.definition;
        execution = other.execution;
        state = other.state;
        enteredStep = other.enteredStep;
        currentStep = other.currentStep;
        active = other.active;
        pose = other.pose;
        blendFrom = other.blendFrom;
        blendSeconds = other.blendSeconds;
        blendProgress = other.blendProgress;
    }
    ControllerRuntime<P> copy() { return new ControllerRuntime<>(this); }
    ControllerSnapshot snapshot() {
        return new ControllerSnapshot(state == null ? definition.initialState() : state, timeAt(currentStep), blendProgress);
    }
    double timeAt(long step) { return (step - enteredStep) / 60.0; }
    P pose() { return pose; }
    MolangContext contextAt(MolangContext context, long step) {
        double elapsed = timeAt(step);
        boolean any = false;
        boolean all = true;
        boolean applied = false;
        for (WeightedClip animation : active) {
            if (animation.weight <= 0) continue;
            applied = true;
            boolean finished = execution.finished(animation.clip,elapsed);
            any |= finished;
            all &= finished;
        }
        return context.overlay(Map.of("state_time", elapsed, "anim_time", elapsed,
                "any_animation_finished", any || !applied ? 1.0 : 0.0,
                "all_animations_finished", all ? 1.0 : 0.0));
    }
    void initialize(MolangContext context, List<EffectTimeline.Pending> effects) {
        state = definition.initialState();
        run(definition.states().get(state).onEntry(), contextAt(context, 0));
        execution.enter(state,0,contextAt(context,0),effects);
        weights(contextAt(context, 0));
        initialEffects(0, effects, contextAt(context,0));
        sample(0, context);
        if(execution.traverseInitialEmptyStates() && definition.states().get(state).animations().isEmpty()) tick(0,context,effects);
    }
    void tick(long step, MolangContext context, List<EffectTimeline.Pending> effects) {
        currentStep = step;
        ScriptBudget budget = context.budget();
        Set<String> visited = new HashSet<>();
        visited.add(state);
        boolean transitioned = false;
        while (true) {
            budget.step();
            ControllerDefinition.State source = definition.states().get(state);
            MolangContext sourceContext = contextAt(context, step);
            ControllerDefinition.Transition selected = null;
            for (ControllerDefinition.Transition transition : source.transitions()) {
                budget.step();
                if (transition.condition().evaluate(sourceContext) != 0) { selected = transition; break; }
            }
            if (selected == null || selected.target().equals(state)) break;
            ControllerDefinition.State target = definition.states().get(selected.target());
            if (target.animations().isEmpty() && !visited.add(selected.target())) {
                throw new MolangException(MolangException.Reason.CONTROLLER_CYCLE, "Empty controller state cycle at " + selected.target());
            }
            run(source.onExit(), sourceContext);
            state = selected.target();
            enteredStep = step;
            blendFrom = pose;
            blendSeconds = target.blendSeconds();
            run(target.onEntry(), contextAt(context, step));
            execution.enter(state,step,contextAt(context,step),effects);
            weights(contextAt(context, step));
            initialEffects(step, effects, contextAt(context,step));
            transitioned = true;
            if (!target.animations().isEmpty()) break;
        }
        if (!transitioned) {
            weights(contextAt(context, step));
            for (WeightedClip animation : active) {
                budget.step();
                if (animation.weight > 0) {
                    execution.emit(animation.clip,Math.max(0,timeAt(step-1)),timeAt(step),false,step,contextAt(context,step),effects);
                }
            }
        }
        sample(step, context);
    }
    private static void run(List<MolangProgram> scripts, MolangContext context) {
        for (MolangProgram script : scripts) { context.budget().step(); script.evaluate(context); }
    }
    private void weights(MolangContext context) {
        List<WeightedClip> result = new ArrayList<>();
        for (ControllerDefinition.ClipBinding binding : definition.states().get(state).animations()) {
            context.budget().step();
            double weight = Math.max(0, binding.weight().evaluate(context));
            result.add(new WeightedClip(definition.clips().get(binding.clip()), weight));
        }
        active = List.copyOf(result);
    }
    private void initialEffects(long step, List<EffectTimeline.Pending> effects, MolangContext currentContext) {
        ScriptBudget budget=currentContext.budget();
        for (WeightedClip animation : active) {
            budget.step();
            if (animation.weight > 0) execution.emit(animation.clip,0,0,true,step,currentContext,effects);
        }
    }
    private void sample(long step,MolangContext context) {
        P target=execution.sample(active,timeAt(step),contextAt(context,step));
        blendProgress=blendSeconds==0?1:Math.min(1,timeAt(step)/blendSeconds);
        pose=blendProgress==1?target:execution.blend(blendFrom,target,blendProgress,context.budget());
        if (blendProgress==1) blendFrom=execution.empty();
    }
    static Execution<Map<String,Double>> scalarExecution() { return new ScalarExecution(); }
    private static final class ScalarExecution implements Execution<Map<String,Double>> {
    @Override public Map<String,Double> empty() { return Map.of(); }
    @Override public Map<String,Double> sample(List<WeightedClip> active,double elapsed,MolangContext context) {
        ScriptBudget budget=context.budget();
        Map<String, Double> target = new TreeMap<>();
        for (WeightedClip animation : active) {
            budget.step();
            if (animation.weight <= 0) continue;
            for (Map.Entry<String, KeyframeCurve> curve : new TreeMap<>(animation.clip.curves()).entrySet()) {
                budget.step();
                double value = MolangContext.finite(curve.getValue().sample(animation.clip.localTime(elapsed)) * animation.weight);
                target.merge(curve.getKey(), value, (a, b) -> MolangContext.finite(a + b));
                if (target.size() > 4096) throw new MolangException(MolangException.Reason.CAPACITY, "Controller output channel limit exceeded");
            }
        }
        return Map.copyOf(target);
    }
    @Override public Map<String,Double> blend(Map<String,Double> blendFrom,Map<String,Double> target,double blendProgress,ScriptBudget budget) {
        Set<String> names = new HashSet<>(blendFrom.keySet());
        names.addAll(target.keySet());
        if (names.size() > 4096) throw new MolangException(MolangException.Reason.CAPACITY, "Controller blended channel limit exceeded");
        Map<String, Double> blended = new TreeMap<>();
        for (String name : names) {
            budget.step();
            double value = blendFrom.getOrDefault(name, 0.0) * (1 - blendProgress) + target.getOrDefault(name, 0.0) * blendProgress;
            blended.put(name, MolangContext.finite(value));
        }
        return Map.copyOf(blended);
    }
    }
}
