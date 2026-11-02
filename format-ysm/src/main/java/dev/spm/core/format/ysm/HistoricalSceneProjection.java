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
    // Small, per-import intern tables. Parent arrays/maps still account for every reference.
    private static final int INTERN_LIMIT=4096;
    /**
     * Retained cost of one {@link JsonObject}: the 32-byte record plus its value array header.
     * Each field adds one reference in the value array, and its key reference is amortised because
     * the keys array is shared by every object of the same shape.
     */
    private static final long JSON_OBJECT_BASE=40L;
    /** Retained cost of one object field: a value-array slot plus its share of the keys array. */
    private static final long JSON_OBJECT_FIELD=8L;
    private final Map<Long,JsonNumber> numbers=new HashMap<>();
    private final Map<String,JsonString> strings=new HashMap<>();
    private record VectorKey(JsonValue x,JsonValue y,JsonValue z){}
    private record FrameKey(List<String> fields,JsonValue pre,JsonValue post,JsonValue mode){}
    private final Map<VectorKey,JsonArray> vectors=new HashMap<>();
    private final Map<FrameKey,JsonObject> frames=new HashMap<>();
    private final Map<Long,String> timestamps=new HashMap<>();
    private JsonBoolean trueValue,falseValue;
    private int nodes;
    private HistoricalSceneProjection(DecodePolicy policy,CancellationToken token,Retention retention) {
        this.policy=policy;this.token=token;this.retention=retention;
    }
    static JsonObject project(int version,String type,String key,HistoricalValue animations,HistoricalValue controllers,
                              DecodePolicy policy,CancellationToken token,Retention retention)throws IOException {
        return forVariant(shared(version,animations,controllers,policy,token,retention),type,key,policy,token,retention);
    }
    /**
     * Accumulates animation sets one clip at a time.
     *
     * <p>The historical tree spends about 97% of its node mass on animation keyframes, and a single
     * source set can hold tens of thousands of frames. Retaining them all, only to project them in a
     * second pass, is what made a real 5.6 MiB model need multiple GiB of heap. Feeding clips through
     * this collector lets the decoder project each one and drop it immediately, so the peak is one
     * clip rather than the whole file. {@link #feed} drives the same collector from an already
     * decoded tree, so the streamed and in-memory paths are the same code and cannot diverge.
     */
    static final class Collector {
        private final HistoricalSceneProjection work;
        private final int version;
        private final Map<String,JsonObject> animationSets=new LinkedHashMap<>();
        private String setKey;
        private Map<String,JsonValue> clips;
        /**
         * Credits for the projected JSON this collector accumulates.
         *
         * <p>The projection outlives the decoder that produced it: the reader's reservations are
         * released when decoding finishes, while the projected scene is retained by the import result.
         * Charging through the reader therefore made the budget report a few megabytes for a set that
         * actually holds hundreds, so the collector keeps its own running total and the lowering takes
         * it over. Routing it through the collector rather than the reader is what keeps the charge
         * attached to the thing that is actually retained.
         */
        private long projectedBytes;
        private Collector(int version,DecodePolicy policy,CancellationToken token){
            this.version=version;
            this.work=new HistoricalSceneProjection(policy,token,this::retain);
        }
        /** Records retained projection bytes. */
        private void retain(long bytes)throws IOException {
            if(bytes<0)throw new IOException("Negative historical projection charge");
            projectedBytes=Math.addExact(projectedBytes,bytes);
        }
        long projectedBytes(){return projectedBytes;}
        /** Hands the accumulated credit to a new owner and zeroes it, so it is never counted twice. */
        long takeProjectedBytes(){long taken=projectedBytes;projectedBytes=0;return taken;}
        void beginSet(String key)throws IOException {
            if(clips!=null)throw new IllegalStateException("Historical animation set is already open");
            setKey=key;clips=new LinkedHashMap<>();
        }
        void clip(HistoricalValue value)throws IOException {
            if(clips==null)throw new IllegalStateException("No historical animation set is open");
            Struct source=struct(value);String name=string(source.field("name"));
            String path="animation-set["+setKey+"]/"+name;
            if(clips.putIfAbsent(name,work.animation(source,path))!=null)
                throw new IOException("Duplicate animation name in historical source set: "+name);
        }
        void endSet()throws IOException {
            if(clips==null)throw new IllegalStateException("No historical animation set is open");
            if(animationSets.putIfAbsent(setKey,work.object(Map.of("animations",work.object(clips))))!=null)
                throw new IOException("Duplicate historical scene source-set key");
            clips=null;setKey=null;
        }
        JsonObject finish(HistoricalValue controllers)throws IOException {
            if(clips!=null)throw new IllegalStateException("Historical animation set was left open");
            Map<String,JsonObject> controllerSets=work.sets(controllers,false);
            Map<String,JsonValue> flatAnimations=work.flatten(animationSets,"animations");
            Map<String,JsonValue> flatControllers=work.flatten(controllerSets,"animation_controllers");
            var provenance=work.object(Map.of("source_profile",work.text("ysm-historical-inner-"+version),
                    "animation_sets",work.object(new LinkedHashMap<>(animationSets)),"controller_sets",work.object(new LinkedHashMap<>(controllerSets)),
                    "limitations",work.array(work.limitations)));
            return work.object(Map.of("animation_interpolation_profile",work.text("HISTORICAL_YSM"),
                    "animations",work.object(flatAnimations),"animation_controllers",work.object(flatControllers),"historical_scene",provenance));
        }
    }
    static Collector collector(int version,DecodePolicy policy,CancellationToken token)throws IOException {
        Objects.requireNonNull(policy);Objects.requireNonNull(token).check();
        return new Collector(version,policy,token);
    }
    /** Drives a collector from an in-memory tree, in the same order the streaming path would. */
    static void feed(Collector collector,HistoricalValue animations)throws IOException {
        var work=collector.work;
        HistoricalValue source=work.unwrap(animations);
        if(source==null)return;
        if(source instanceof Mapping mapping) {
            work.collection(mapping.entries().size());
            for(Entry entry:mapping.entries()) {
                work.token.check();HistoricalValue file=work.unwrap(entry.value());if(file==null)continue;
                String key=entry.key() instanceof Text text?text.value():Long.toUnsignedString(HistoricalSceneProjection.unsigned(entry.key()));
                work.feedSet(collector,key,HistoricalSceneProjection.struct(file));
            }
        } else if(source instanceof Sequence sequence) {
            work.collection(sequence.values().size());int index=0;
            for(HistoricalValue value:sequence.values()) {
                work.token.check();HistoricalValue file=work.unwrap(value);
                if(file!=null)work.feedSet(collector,Integer.toString(index),HistoricalSceneProjection.struct(file));
                index++;
            }
        } else work.feedSet(collector,"main",HistoricalSceneProjection.struct(source));
    }
    private void feedSet(Collector collector,String key,Struct source)throws IOException {
        collector.beginSet(key);
        for(HistoricalValue value:sequence(source.field("animations"))){token.check();collector.clip(value);}
        collector.endSet();
    }
    /**
     * The result is deep immutable. Charges accrue on the collector's own tally and are handed to the
     * lowering through {@link Collector#takeProjectedBytes()}, because the projection outlives the
     * decoder whose reservations are released when decoding finishes.
     */
    static JsonObject shared(int version,HistoricalValue animations,HistoricalValue controllers,
                             DecodePolicy policy,CancellationToken token,Retention retention)throws IOException {
        var collector=collector(version,policy,token);
        feed(collector,animations);
        var result=collector.finish(controllers);
        // The caller supplied the retention sink for this non-streamed path.
        long projected=collector.takeProjectedBytes();
        if(projected>0)retention.retain(projected);
        return result;
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
        var duplicateTimes=new HashSet<String>();
        for(HistoricalValue value:frames) {
            token.check();Struct frame=struct(value);String time=timestamp(frame.field("tick"),path);long easing=unsigned(frame.field("easing"));
            String mode=switch((int)easing){case 0,1->"linear";case 2->"catmullrom";default->{limitation(path+": unknown easing enum "+easing);yield "historical_easing_"+easing;}};
            var entry=new LinkedHashMap<String,JsonValue>();entry.put("pre",vector(frame.field("pre"),path));
            HistoricalValue post=unwrap(frame.field("post"));if(post!=null)entry.put("post",vector(post,path));
            entry.put("lerp_mode",text(mode));
            // Bedrock's vector shorthand has exactly the same default linear interpolation when
            // the authored pre/post agree. The exact optional fields remain in archival IR.
            JsonValue projected=mode.equals("linear")&&(post==null||entry.get("pre").equals(entry.get("post")))
                    ?entry.get("pre"):object(entry);
            JsonValue previous=result.putIfAbsent(time,projected);
            if(previous!=null) {
                var duplicates=new ArrayList<JsonValue>();
                if(duplicateTimes.contains(time))duplicates.addAll(((JsonArray)previous).values());
                else{charge(48);duplicateTimes.add(time);duplicates.add(previous);}
                duplicates.add(projected);result.put(time,array(duplicates));
                limitation(path+": duplicate keyframes at "+time+" retained as array; executable interpretation unavailable");
            }
        }
        return object(result);
    }
    /** A three-value Molang vector; collapses to {@link JsonVector3} when all three are numbers. */
    private JsonValue vector(HistoricalValue value,String path)throws IOException {
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
            result.put("blend_via_shortest_path",bool(true));
        }
        if(source.fields().containsKey("sounds"))result.put("sound_effects",strings(source.field("sounds")));
        return object(result);
    }
    private JsonValue strings(HistoricalValue source)throws IOException {
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
    private String keyNumber(double value)throws IOException {
        token.check();long bits=Double.doubleToLongBits(value);var existing=timestamps.get(bits);if(existing!=null)return existing;
        String text=BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();boolean cache=timestamps.size()<INTERN_LIMIT*4;
        charge((cache?112L:48L)+text.length()*2L);if(cache)timestamps.put(bits,text);return text;
    }
    private JsonNumber decimal(double value)throws IOException {
        token.check();long bits=Double.doubleToLongBits(value);var existing=numbers.get(bits);if(existing!=null)return existing;
        // A retained JsonNumber is one 24-byte record holding a primitive double; where the same
        // value recurs the pool hands back the existing instance and nothing more is charged.
        boolean cache=numbers.size()<INTERN_LIMIT;charge(24);
        var result=new JsonNumber(value);if(cache)numbers.put(bits,result);return result;
    }
    private JsonString text(String value)throws IOException {
        token.check();
        if((long)value.length()*2>policy.maxStringBytes()*2L)throw new IOException("Historical metadata string limit");
        var existing=strings.get(value);if(existing!=null)return existing;
        // A retained string is its String object plus the compact byte[] payload. Measured corpus
        // cardinality is ~3,026 distinct strings, comfortably inside the pool bound.
        boolean cache=value.length()<=512&&strings.size()<INTERN_LIMIT;charge(40L+value.length()*2L);
        var result=new JsonString(value);if(cache)strings.put(value,result);return result;
    }
    private JsonBoolean bool(boolean value)throws IOException {
        token.check();var existing=value?trueValue:falseValue;if(existing!=null)return existing;
        var result=new JsonBoolean(value);if(value)trueValue=result;else falseValue=result;return result;
    }
    private JsonObject object(Map<String,JsonValue> values)throws IOException {
        token.check();FrameKey frame=null;
        if(values.get("pre") instanceof JsonArray && values.get("lerp_mode") instanceof JsonString
                && values.keySet().stream().allMatch(k->k.equals("pre")||k.equals("post")||k.equals("lerp_mode"))){
            frame=new FrameKey(List.copyOf(values.keySet()),values.get("pre"),values.get("post"),values.get("lerp_mode"));
            var existing=frames.get(frame);if(existing!=null)return existing;
        }
        collection(values.size());
        // A retained object is one 32-byte record plus one shared keys array and one value slot per
        // field. Measured for the real corpus, objects and their slots are the second largest item
        // after inline vectors, at roughly 106 bytes each for the dominant three-field shape; the
        // previous 160 + 96/field was modelled on the LinkedHashMap the class no longer uses.
        boolean cache=frame!=null&&frames.size()<INTERN_LIMIT;
        charge(cache?Math.addExact(JSON_OBJECT_BASE,Math.multiplyExact((long)values.size(),JSON_OBJECT_FIELD))+128:Math.addExact(JSON_OBJECT_BASE,Math.multiplyExact((long)values.size(),JSON_OBJECT_FIELD)));
        var result=new JsonObject(values);if(cache)frames.put(frame,result);return result;
    }
    /**
     * Builds a JSON array, collapsing the ubiquitous three-number vector.
     *
     * <p>Measured on a real model: 689,563 of 690,180 distinct arrays are exactly three numbers, and
     * they are what the animation keyframe timeline is made of. As a {@code JsonArray} each one cost
     * about 152 bytes (array object + immutable list + backing {@code Object[]} + three boxed
     * numbers); {@link JsonVector3} holds the same three values in 40 bytes with no allocation beyond
     * the record itself. Strings are deliberately excluded: a Molang three-value array must stay a
     * real array so the executable decoder keeps seeing the shape it expects.
     */
    private JsonValue array(List<? extends JsonValue> values)throws IOException {
        collection(values.size());
        if(values.size()==3&&values.get(0) instanceof JsonNumber x&&values.get(1) instanceof JsonNumber y
                &&values.get(2) instanceof JsonNumber z) {
            // The wire stores these as float32, so narrowing back to float is lossless. Verified across the
            // whole corpus: all 6,250,275 components round-trip exactly. If a value ever is not float-exact
            // the array form is used instead, which keeps arbitrary precision.
            float fx=(float)x.value(),fy=(float)y.value(),fz=(float)z.value();
            if(fx==x.value()&&fy==y.value()&&fz==z.value()) {
                charge(40L);
                return new JsonVector3(fx,fy,fz);
            }
        }
        VectorKey vector=null;
        if(values.size()==3&&values.stream().allMatch(v->v instanceof JsonNumber||v instanceof JsonString)){
            vector=new VectorKey(values.get(0),values.get(1),values.get(2));var existing=vectors.get(vector);if(existing!=null)return existing;
        }
        boolean cache=vector!=null&&vectors.size()<INTERN_LIMIT;charge(96L+values.size()*16L+(cache?96:0));
        var result=new JsonArray(new ArrayList<>(values));if(cache)vectors.put(vector,result);return result;
    }
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
