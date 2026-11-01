package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import static dev.spm.core.format.ysm.HistoricalValue.*;

/** Source-associated Bedrock-shaped scene metadata. Unsupported semantics remain explicit data. */
final class HistoricalSceneProjection {
    @FunctionalInterface interface Retention { void retain(long bytes) throws IOException; }
    private final DecodePolicy policy;
    private final CancellationToken token;
    private final Retention retention;
    private final List<JsonValue> limitations=new ArrayList<>();
    private int nodes;
    private HistoricalSceneProjection(DecodePolicy policy,CancellationToken token,Retention retention) {
        this.policy=policy;this.token=token;this.retention=retention;
    }
    static JsonObject project(int version,String type,String key,HistoricalValue animations,HistoricalValue controllers,
                              DecodePolicy policy,CancellationToken token,Retention retention)throws IOException {
        return forVariant(shared(version,animations,controllers,policy,token,retention),type,key,policy,token,retention);
    }
    /** The result is deep immutable and belongs to the import's shared reservation owner. */
    static JsonObject shared(int version,HistoricalValue animations,HistoricalValue controllers,
                             DecodePolicy policy,CancellationToken token,Retention retention)throws IOException {
        Objects.requireNonNull(policy);Objects.requireNonNull(token).check();Objects.requireNonNull(retention);
        var work=new HistoricalSceneProjection(policy,token,retention);
        Map<String,JsonObject> animationSets=work.sets(animations,true),controllerSets=work.sets(controllers,false);
        Map<String,JsonValue> flatAnimations=work.flatten(animationSets,"animations"),flatControllers=work.flatten(controllerSets,"animation_controllers");
        var provenance=work.object(Map.of("source_profile",work.text("ysm-historical-inner-"+version),
                "animation_sets",work.object(animationSets),"controller_sets",work.object(controllerSets),
                "limitations",work.array(work.limitations)));
        return work.object(Map.of("animation_interpolation_profile",work.text("HISTORICAL_YSM"),
                "animations",work.object(flatAnimations),"animation_controllers",work.object(flatControllers),"historical_scene",provenance));
    }
    /** Shallow variant wrapper; existing scene values are reused without reprojecting or recharging. */
    static JsonObject forVariant(JsonObject shared,String type,String key,DecodePolicy policy,CancellationToken token,Retention retention)throws IOException {
        Objects.requireNonNull(token).check();var work=new HistoricalSceneProjection(policy,token,retention);
        var fields=new LinkedHashMap<>(shared.values());fields.put("source_type",work.text(type));fields.put("source_key",work.text(key));
        return work.object(fields);
    }
    private Map<String,JsonObject> sets(HistoricalValue source,boolean animation)throws IOException {
        var result=new LinkedHashMap<String,JsonObject>();source=unwrap(source);
        if(source==null)return result;
        if(source instanceof Mapping mapping) {
            collection(mapping.entries().size());
            for(Entry entry:mapping.entries()) {
                token.check();HistoricalValue file=unwrap(entry.value());if(file==null)continue;
                String key=entry.key() instanceof Text text?text.value():Long.toUnsignedString(unsigned(entry.key()));
                putSet(result,key,struct(file),animation);
            }
        } else if(source instanceof Sequence sequence) {
            collection(sequence.values().size());int index=0;
            for(HistoricalValue value:sequence.values()) {
                token.check();HistoricalValue file=unwrap(value);if(file!=null)putSet(result,Integer.toString(index),struct(file),animation);index++;
            }
        } else putSet(result,"main",struct(source),animation);
        return result;
    }
    private void putSet(Map<String,JsonObject> result,String key,Struct source,boolean animation)throws IOException {
        String field=animation?"animations":"animation_controllers";
        JsonObject projected=animation?animations(source,key):controllers(source,key);
        if(result.putIfAbsent(key,object(Map.of(field,projected)))!=null)throw new IOException("Duplicate historical scene source-set key");
    }
    private Map<String,JsonValue> flatten(Map<String,JsonObject> sets,String field)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();var ambiguous=new HashSet<String>();
        for(var set:sets.entrySet()) for(var definition:((JsonObject)set.getValue().values().get(field)).values().entrySet()) {
            token.check();String name=definition.getKey();
            if(ambiguous.contains(name))continue;
            if(result.putIfAbsent(name,definition.getValue())!=null) {
                result.remove(name);ambiguous.add(name);
                limitation(field+" name '"+name+"' occurs in multiple source sets; excluded from flat lookup, choose an explicit historical_scene set");
            }
        }
        return result;
    }
    private JsonObject animations(Struct source,String sourceKey)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();
        for(HistoricalValue value:sequence(source.field("animations"))) {
            token.check();Struct clip=struct(value);String name=string(clip.field("name"));String path="animation-set["+sourceKey+"]/"+name;
            if(result.putIfAbsent(name,animation(clip,path))!=null)throw new IOException("Duplicate animation name in historical source set: "+name);
        }
        return object(result);
    }
    private JsonObject animation(Struct source,String path)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();
        Float32 length=floating(source.field("length"));
        result.put("animation_length",length.value()==Float.POSITIVE_INFINITY?text("Infinity"):number(length.value()/20.0,length,path+"/length"));
        long loop=unsigned(source.field("loop"));
        result.put("loop",switch((int)loop) {
            case 0,2->bool(false);
            case 1->bool(true);
            case 3->text("hold_on_last_frame");
            default->{limitation(path+": unknown loop enum "+loop);yield text("historical_loop_"+loop);}
        });
        for(String field:List.of("start_delay","loop_delay","weight","override_previous")) {
            HistoricalValue raw=unwrap(source.fields().get(field));if(raw==null)continue;
            String target=switch(field){case "weight"->"blend_weight";case "override_previous"->"override_previous_animation";default->field;};
            result.put(target,raw instanceof Bool bool?bool(bool.value()):molang(struct(raw),path+"/"+field));
            limitation(path+"/"+target+": retained; typed clip runtime does not implement this field");
        }
        var bones=new LinkedHashMap<String,JsonValue>();
        for(HistoricalValue value:sequence(source.field("bones"))) {
            token.check();Struct bone=struct(value);String name=string(bone.field("name"));var channels=new LinkedHashMap<String,JsonValue>();
            for(String channel:List.of("rotation","position","scale")) {
                List<HistoricalValue> frames=sequence(bone.field(channel));
                if(!frames.isEmpty())channels.put(channel,track(frames,path+"/"+name+"/"+channel));
            }
            if(bones.putIfAbsent(name,object(channels))!=null)throw new IOException("Duplicate historical animated bone");
        }
        result.put("bones",object(bones));
        var instructions=new LinkedHashMap<String,List<JsonValue>>();
        for(HistoricalValue value:sequence(source.field("instructions"))) {
            token.check();Struct event=struct(value);String time=timestamp(event.field("tick"),path+"/timeline");
            var lines=instructions.computeIfAbsent(time,unused->new ArrayList<>());
            for(HistoricalValue line:sequence(event.field("data"))) {token.check();lines.add(text(string(line)));}
        }
        if(!instructions.isEmpty()) {
            var timeline=new LinkedHashMap<String,JsonValue>();for(var entry:instructions.entrySet())timeline.put(entry.getKey(),array(entry.getValue()));
            result.put("timeline",object(timeline));
        }
        var sounds=new LinkedHashMap<String,List<JsonValue>>();HistoricalValue soundValue=source.fields().get("sounds");
        if(soundValue!=null)for(HistoricalValue value:sequence(soundValue)) {
            token.check();Struct event=struct(value);String time=timestamp(event.field("tick"),path+"/sound");
            sounds.computeIfAbsent(time,unused->new ArrayList<>()).add(text(string(event.field("data"))));
        }
        if(!sounds.isEmpty()) {
            var timeline=new LinkedHashMap<String,JsonValue>();
            for(var entry:sounds.entrySet()) {
                if(entry.getValue().size()>1)limitation(path+"/sound_effects/"+entry.getKey()+": simultaneous sounds retained as array; current typed runtime supports one sound per timestamp");
                timeline.put(entry.getKey(),entry.getValue().size()==1?entry.getValue().get(0):array(entry.getValue()));
            }
            result.put("sound_effects",object(timeline));
        }
        return object(result);
    }
    private JsonObject track(List<HistoricalValue> frames,String path)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();
        for(HistoricalValue value:frames) {
            token.check();Struct frame=struct(value);String time=timestamp(frame.field("tick"),path);long easing=unsigned(frame.field("easing"));
            String mode=switch((int)easing){case 0,1->"linear";case 2->"catmullrom";default->{limitation(path+": unknown easing enum "+easing);yield "historical_easing_"+easing;}};
            var entry=new LinkedHashMap<String,JsonValue>();entry.put("pre",vector(frame.field("pre"),path));
            HistoricalValue post=unwrap(frame.field("post"));if(post!=null)entry.put("post",vector(post,path));
            entry.put("lerp_mode",text(mode));
            JsonObject projected=object(entry);JsonValue previous=result.putIfAbsent(time,projected);
            if(previous!=null) {
                var duplicates=new ArrayList<JsonValue>();
                if(previous instanceof JsonArray array)duplicates.addAll(array.values());else duplicates.add(previous);
                duplicates.add(projected);result.put(time,array(duplicates));
                limitation(path+": duplicate keyframes at "+time+" retained as array; executable interpretation unavailable");
            }
        }
        return object(result);
    }
    private JsonArray vector(HistoricalValue value,String path)throws IOException {
        List<HistoricalValue> values=sequence(value);if(values.size()!=3)throw new IOException("Historical animation vector size");
        var result=new ArrayList<JsonValue>();for(HistoricalValue axis:values)result.add(molang(struct(axis),path));return array(result);
    }
    private JsonValue molang(Struct source,String path)throws IOException {
        long type=unsigned(source.field("type"));
        if(type==1){Float32 value=floating(source.field("number"));return number(value.value(),value,path);}
        if(type==2)return text(string(source.field("source")));
        limitation(path+": absent historical Molang variant retained without assuming zero");
        return object(Map.of("historical_molang_type",decimal(type)));
    }
    private JsonObject controllers(Struct source,String sourceKey)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();Mapping controllers=mapping(source.field("controllers"));collection(controllers.entries().size());
        for(Entry controller:controllers.entries()) {
            token.check();String name=string(controller.key());Struct value=struct(controller.value());var states=new LinkedHashMap<String,JsonValue>();
            Mapping declaredStates=mapping(value.field("states"));collection(declaredStates.entries().size());
            for(Entry state:declaredStates.entries()) {
                token.check();String stateName=string(state.key());String path="controller-set["+sourceKey+"]/"+name+"/"+stateName;
                if(states.putIfAbsent(stateName,state(struct(state.value()),path))!=null)throw new IOException("Duplicate historical controller state");
            }
            if(result.putIfAbsent(name,object(Map.of("initial_state",text(string(value.field("initial"))),"states",object(states))))!=null)throw new IOException("Duplicate historical controller name");
        }
        return object(result);
    }
    private JsonObject state(Struct source,String path)throws IOException {
        var result=new LinkedHashMap<String,JsonValue>();var animations=new ArrayList<JsonValue>();var transitions=new ArrayList<JsonValue>();
        for(HistoricalValue value:sequence(source.field("animations"))) {
            token.check();Struct binding=struct(value);String condition=string(binding.field("condition"));
            animations.add(object(Map.of(string(binding.field("name")),text(condition.isBlank()?"1":condition))));
        }
        for(HistoricalValue value:sequence(source.field("transitions"))) {
            token.check();Struct transition=struct(value);transitions.add(object(Map.of(string(transition.field("name")),text(string(transition.field("condition"))))));
        }
        result.put("animations",array(animations));result.put("transitions",array(transitions));
        result.put("on_entry",strings(source.field("entry")));result.put("on_exit",strings(source.field("exit")));
        HistoricalValue length=unwrap(source.fields().get("length"));
        if(length!=null) {Float32 value=floating(length);result.put("blend_transition",number(value.value(),value,path+"/blend_transition"));}
        else if(source.fields().containsKey("points")) {
            var points=new LinkedHashMap<String,JsonValue>();Mapping declaredPoints=mapping(source.field("points"));collection(declaredPoints.entries().size());
            for(Entry point:declaredPoints.entries()) {
                Float32 position=floating(point.value());points.put(floatKey(point.key(),path+"/blend_point"),number(position.value(),position,path));
            }
            result.put("blend_transition",object(points));limitation(path+": segmented blend curve retained; typed controller supports only numeric linear blend_transition");
        }
        if(source.field("blend_via_shortest_path") instanceof Bool shortest && shortest.value()) {
            result.put("blend_via_shortest_path",bool(true));limitation(path+": authored blend_via_shortest_path retained; not interpreted by typed controller decoder");
        }
        if(source.fields().containsKey("sounds"))result.put("sound_effects",strings(source.field("sounds")));
        return object(result);
    }
    private JsonArray strings(HistoricalValue source)throws IOException {
        var result=new ArrayList<JsonValue>();for(HistoricalValue value:sequence(source)){token.check();result.add(text(string(value)));}return array(result);
    }
    private JsonValue number(double value,Float32 original,String path)throws IOException {
        if(Double.isFinite(value))return decimal(value);
        limitation(path+": nonfinite float32 retained by raw bits; executable sampling unavailable");
        return object(Map.of("historical_float32",object(Map.of("raw_bits",text(String.format(Locale.ROOT,"%08x",original.rawBits()))))));
    }
    private String timestamp(HistoricalValue source,String path)throws IOException {
        Float32 value=floating(source);
        if(!Float.isFinite(value.value())) {
            limitation(path+": nonfinite timestamp retained in raw-bit key; executable sampling unavailable");
            return "historical_tick_"+String.format(Locale.ROOT,"%08x",value.rawBits());
        }
        if(value.value()<0)limitation(path+": negative timestamp retained; executable sampling unavailable");
        return keyNumber(value.value()/20.0);
    }
    private String floatKey(HistoricalValue source,String path)throws IOException {
        Float32 value=floating(source);
        if(!Float.isFinite(value.value())) {
            limitation(path+": nonfinite blend-map key retained in raw bits; executable blending unavailable");
            return "historical_float_"+String.format(Locale.ROOT,"%08x",value.rawBits());
        }
        return keyNumber(value.value());
    }
    private String keyNumber(double value)throws IOException {String text=BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();charge(48L+text.length()*2L);return text;}
    private JsonNumber decimal(double value)throws IOException {charge(96);return new JsonNumber(BigDecimal.valueOf(value));}
    private JsonString text(String value)throws IOException {
        if((long)value.length()*2>policy.maxStringBytes()*2L)throw new IOException("Historical metadata string limit");
        charge(64L+value.length()*2L);return new JsonString(value);
    }
    private JsonBoolean bool(boolean value)throws IOException {charge(48);return new JsonBoolean(value);}
    private JsonObject object(Map<String,? extends JsonValue> values)throws IOException {
        collection(values.size());long bytes=160L+values.size()*96L;
        for(String key:values.keySet()){token.check();bytes=Math.addExact(bytes,48L+key.length()*2L);}
        charge(bytes);return new JsonObject(new LinkedHashMap<>(values));
    }
    private JsonArray array(List<? extends JsonValue> values)throws IOException {collection(values.size());charge(96L+values.size()*16L);return new JsonArray(new ArrayList<>(values));}
    private void limitation(String message)throws IOException {collection(limitations.size()+1);limitations.add(text(message));}
    private void charge(long bytes)throws IOException {token.check();if(++nodes>policy.maxObjects())throw new IOException("Historical scene metadata node limit");retention.retain(bytes);}
    private void collection(int count)throws IOException {token.check();if(count>policy.maxCollectionEntries())throw new IOException("Historical scene metadata collection limit");}
    private HistoricalValue unwrap(HistoricalValue value)throws IOException {
        for(int depth=0;;depth++) {
            token.check();if(depth>policy.maxDepth())throw new IOException("Historical scene wrapper depth limit");
            if(value instanceof Presence presence){value=presence.present()?presence.value():null;continue;}
            if(value instanceof Struct struct && struct.fields().containsKey("hash") && struct.fields().containsKey("value")){value=struct.field("value");continue;}
            return value;
        }
    }
    private List<HistoricalValue> sequence(HistoricalValue value)throws IOException {if(!(value instanceof Sequence sequence))throw new IOException("Expected historical scene list");collection(sequence.values().size());return sequence.values();}
    private static Struct struct(HistoricalValue value)throws IOException {if(value instanceof Struct result)return result;throw new IOException("Expected historical scene object");}
    private static Mapping mapping(HistoricalValue value)throws IOException {if(value instanceof Mapping result)return result;throw new IOException("Expected historical scene map");}
    private static String string(HistoricalValue value)throws IOException {if(value instanceof Text result)return result.value();throw new IOException("Expected historical scene string");}
    private static long unsigned(HistoricalValue value)throws IOException {if(value instanceof Unsigned result)return result.bits();throw new IOException("Expected historical scene enum");}
    private static Float32 floating(HistoricalValue value)throws IOException {if(value instanceof Float32 result)return result;throw new IOException("Expected historical scene float");}
}
