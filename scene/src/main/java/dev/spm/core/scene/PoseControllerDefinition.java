package dev.spm.core.scene;

import dev.spm.core.api.ModelDocument.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bounded executable animation_controllers graph; unknown runtime fields fail explicitly. */
public final class PoseControllerDefinition {
    final ControllerDefinition graph;
    final Map<String,List<String>> sounds;
    final PoseAnimationLibrary library;
    private PoseControllerDefinition(ControllerDefinition graph,Map<String,List<String>> sounds,PoseAnimationLibrary library) {
        this.graph=graph; this.sounds=Map.copyOf(sounds); this.library=library;
    }
    public static PoseControllerDefinition decode(JsonObject source,String name,PoseAnimationLibrary library,ScriptBudget budget) {
        return decode(source,name,library,Map.of(),budget);
    }
    /** Aliases resolve local controller animation names to clips, without consulting host registries. */
    public static PoseControllerDefinition decode(JsonObject source,String name,PoseAnimationLibrary library,Map<String,String> aliases,ScriptBudget budget) {
        Objects.requireNonNull(library); Objects.requireNonNull(aliases); Objects.requireNonNull(budget).step();
        JsonObject controllers=object(source.values().get("animation_controllers"));
        if (controllers.values().size()>1024) throw fail(MolangException.Reason.CAPACITY,"At most 1024 controllers");
        JsonObject controller=object(controllers.values().get(Objects.requireNonNull(name)));
        fields(controller,Set.of("initial_state","states"));
        String initial=controller.values().containsKey("initial_state")?text(controller.values().get("initial_state")):"default";
        JsonObject declarations=object(controller.values().get("states"));
        if(declarations.values().isEmpty() || declarations.values().size()>1024) throw fail(MolangException.Reason.CAPACITY,"Controller requires 1..1024 states");
        Decoder decoder=new Decoder(budget);
        Map<String,ControllerDefinition.State> states=new LinkedHashMap<>();
        Map<String,AnimationClip> descriptors=new LinkedHashMap<>();
        Map<String,List<String>> sounds=new LinkedHashMap<>();
        for(var declaration:declarations.values().entrySet()) {
            budget.step(); validName(declaration.getKey());
            JsonObject state=object(declaration.getValue());
            fields(state,Set.of("animations","transitions","on_entry","on_exit","blend_transition","sound_effects"));
            List<ControllerDefinition.ClipBinding> clips=new ArrayList<>();
            for(JsonValue binding:array(state.values().get("animations"),64)) {
                budget.step();
                String clipName; MolangProgram weight;
                if(binding instanceof JsonString string) { clipName=string.value(); weight=decoder.program(new JsonNumber(java.math.BigDecimal.ONE)); }
                else {
                    JsonObject pair=object(binding);
                    if(pair.values().size()!=1) throw fail(MolangException.Reason.SYNTAX,"Animation binding needs one name/weight pair");
                    var entry=pair.values().entrySet().iterator().next(); clipName=entry.getKey(); weight=decoder.program(entry.getValue());
                }
                clipName=aliases.getOrDefault(clipName,clipName); validName(clipName);
                var clip=library.clip(clipName);
                descriptors.putIfAbsent(clipName,new AnimationClip(clipName,Math.max(0.000001,Math.min(1_000_000,clip.duration())),clip.loop()==PoseAnimationLibrary.Loop.LOOP,Map.of(),List.of()));
                clips.add(new ControllerDefinition.ClipBinding(clipName,weight));
            }
            List<ControllerDefinition.Transition> transitions=new ArrayList<>();
            for(JsonValue value:array(state.values().get("transitions"),128)) {
                budget.step(); JsonObject pair=object(value);
                if(pair.values().size()!=1) throw fail(MolangException.Reason.SYNTAX,"Transition needs one target/condition pair");
                var entry=pair.values().entrySet().iterator().next(); validName(entry.getKey());
                transitions.add(new ControllerDefinition.Transition(entry.getKey(),decoder.program(entry.getValue())));
            }
            List<String> stateSounds=new ArrayList<>();
            for(JsonValue value:array(state.values().get("sound_effects"),128)) {
                budget.step(); String sound;
                if(value instanceof JsonString string) sound=string.value();
                else { JsonObject effect=object(value); fields(effect,Set.of("effect")); sound=text(effect.values().get("effect")); }
                validName(sound); stateSounds.add(sound);
            }
            sounds.put(declaration.getKey(),List.copyOf(stateSounds));
            double blend=state.values().containsKey("blend_transition")?numeric(state.values().get("blend_transition")):0;
            states.put(declaration.getKey(),new ControllerDefinition.State(clips,transitions,decoder.scripts(state.values().get("on_entry")),decoder.scripts(state.values().get("on_exit")),blend));
        }
        budget.checkCancelled();
        return new PoseControllerDefinition(new ControllerDefinition(initial,descriptors,states),sounds,library);
    }
    public String initialState() { return graph.initialState(); }
    private static final class Decoder {
        final ScriptBudget budget; int characters;
        Decoder(ScriptBudget budget) { this.budget=budget; }
        List<MolangProgram> scripts(JsonValue value) {
            List<MolangProgram> result=new ArrayList<>();
            for(JsonValue instruction:array(value,128)) { budget.step(); result.add(program(instruction)); }
            return List.copyOf(result);
        }
        MolangProgram program(JsonValue value) {
            if(value instanceof JsonNumber number && number.value().precision()>65536) throw fail(MolangException.Reason.CAPACITY,"Controller numeric source capacity exceeded");
            String source=value instanceof JsonNumber number?number.value().toString():value instanceof JsonBoolean bool?(bool.value()?"1":"0"):text(value);
            if(source.length()>65536 || characters>4_194_304-source.length()) throw fail(MolangException.Reason.CAPACITY,"Controller expression capacity exceeded");
            characters+=source.length(); for(int i=0;i<source.length();i+=16) budget.step();
            return MolangProgram.compile(source);
        }
    }
    private static List<JsonValue> array(JsonValue value,int limit) {
        if(value==null) return List.of();
        if(!(value instanceof JsonArray result)) throw fail(MolangException.Reason.SYNTAX,"Expected controller array");
        if(result.values().size()>limit) throw fail(MolangException.Reason.CAPACITY,"Controller array capacity exceeded");
        return result.values();
    }
    private static JsonObject object(JsonValue value) {
        if(!(value instanceof JsonObject result)) throw fail(MolangException.Reason.SYNTAX,"Expected controller object"); return result;
    }
    private static String text(JsonValue value) {
        if(!(value instanceof JsonString result)) throw fail(MolangException.Reason.SYNTAX,"Expected controller string"); return result.value();
    }
    private static double numeric(JsonValue value) {
        if(!(value instanceof JsonNumber result)) throw fail(MolangException.Reason.UNSUPPORTED,"Only numeric linear blend_transition is supported");
        return MolangContext.finite(result.value().doubleValue());
    }
    private static void fields(JsonObject value,Set<String> allowed) {
        for(String key:value.values().keySet()) if(!allowed.contains(key)) throw fail(MolangException.Reason.UNSUPPORTED,"Unsupported executable controller field: "+key);
    }
    private static void validName(String name) {
        if(name.isBlank() || name.length()>1024) throw fail(MolangException.Reason.SYNTAX,"Invalid controller identifier");
    }
    private static MolangException fail(MolangException.Reason reason,String message) { return new MolangException(reason,message); }
}
