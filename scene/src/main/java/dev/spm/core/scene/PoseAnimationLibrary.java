package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.api.Skeleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Executable bounded Bedrock TRS tracks. Source pixels/degrees are normalized exactly once at sampling. */
public final class PoseAnimationLibrary {
    /** Interpolation only: the historical profile changes curve tangents, not the declared Core scheduler contract. */
    public enum InterpolationProfile { CORE_NON_UNIFORM, HISTORICAL_YSM }
    enum Loop { ONCE, LOOP, HOLD }
    enum Interpolation { LINEAR, STEP, CATMULL_ROM }
    record Scalar(double constant, MolangProgram expression) {
        double value(MolangContext context) {
            context.budget().step();
            return expression == null ? constant : expression.evaluate(context);
        }
    }
    record Vector(Scalar x, Scalar y, Scalar z) {
        double[] value(MolangContext context) { return new double[] {x.value(context), y.value(context), z.value(context)}; }
    }
    record Key(double time, Vector pre, Vector post, Interpolation mode) { }
    record Track(List<Key> keys,InterpolationProfile profile) {
        double endTime() { return keys.get(keys.size()-1).time; }
        double[] value(double time, MolangContext context) {
            context.budget().step();
            Key first = keys.get(0);
            if (time < first.time) return first.pre.value(context);
            int low=0, high=keys.size();
            while (low<high) {
                context.budget().step();
                int middle=(low+high)>>>1;
                if (keys.get(middle).time<=time) low=middle+1; else high=middle;
            }
            int leftIndex=low-1;
            Key left=keys.get(leftIndex);
            if (left.time==time || low==keys.size()) return left.post.value(context);
            Key right=keys.get(low);
            Interpolation mode=right.mode==Interpolation.CATMULL_ROM ? right.mode : left.mode;
            double[] a=left.post.value(context);
            if (mode==Interpolation.STEP) return a;
            double[] b=right.pre.value(context);
            double t=(time-left.time)/(right.time-left.time);
            double[] result=new double[3];
            if (mode==Interpolation.CATMULL_ROM) {
                if(profile==InterpolationProfile.HISTORICAL_YSM) {
                    // d253 EasingType.buildCatmullRomKeyFrame and MathUtil.catmullRom: time
                    // normalizes this segment only, and neighboring values use uniform tangents.
                    double[] p0=leftIndex==0?a:keys.get(leftIndex-1).post.value(context);
                    double[] p3=low+1==keys.size()?b:keys.get(low+1).pre.value(context);
                    for(int axis=0;axis<3;axis++) {
                        double m0=(b[axis]-p0[axis])/2,m1=(p3[axis]-a[axis])/2;
                        result[axis]=MolangContext.finite((2*a[axis]-2*b[axis]+m0+m1)*t*t*t+(-3*a[axis]+3*b[axis]-2*m0-m1)*t*t+m0*t+a[axis]);
                    }
                    return result;
                }
                // Non-uniform Catmull-Rom (Barry-Goldman). Tangents are scaled by the real key
                // intervals, so unevenly spaced Bedrock keys do not overshoot; a clamped endpoint
                // reuses its own key so the outer tangent degenerates to the inner secant.
                int beforeIndex=Math.max(0,leftIndex-1), afterIndex=Math.min(keys.size()-1,low+1);
                double[] p0=beforeIndex==leftIndex?a:keys.get(beforeIndex).post.value(context);
                double[] p3=afterIndex==low?b:keys.get(afterIndex).pre.value(context);
                double t1=left.time, t2=right.time, h12=t2-t1;
                double t0=beforeIndex==leftIndex?t1-h12:keys.get(beforeIndex).time;
                double t3=afterIndex==low?t2+h12:keys.get(afterIndex).time;
                for (int axis=0;axis<3;axis++) {
                    double a1=((t1-time)*p0[axis]+(time-t0)*a[axis])/(t1-t0);
                    double a2=((t2-time)*a[axis]+(time-t1)*b[axis])/h12;
                    double a3=((t3-time)*b[axis]+(time-t2)*p3[axis])/(t3-t2);
                    double b1=((t2-time)*a1+(time-t0)*a2)/(t2-t0);
                    double b2=((t3-time)*a2+(time-t1)*a3)/(t3-t1);
                    result[axis]=MolangContext.finite(((t2-time)*b1+(time-t1)*b2)/h12);
                }
            } else for (int axis=0;axis<3;axis++) result[axis]=MolangContext.finite(a[axis]*(1-t)+b[axis]*t);
            return result;
        }
    }
    record BoneTracks(int index, Track rotation, Track position, Track scale) { }
    record Event(double time, String sound, List<MolangProgram> instructions) { }
    record Clip(String name, double duration, Loop loop, Scalar blendWeight, Scalar startDelay, Scalar loopDelay,
                boolean overridePrevious, List<BoneTracks> bones, List<Event> events) {
        double localTime(double elapsed) { return loop==Loop.LOOP ? elapsed%duration : Math.min(elapsed,duration); }
        boolean delayed() { return startDelay!=null || loopDelay!=null; }
    }
    record Sample(BonePose pose, boolean[] driven) { }
    private final Skeleton skeleton;
    private final Map<String,Clip> clips;
    private PoseAnimationLibrary(Skeleton skeleton, Map<String,Clip> clips) {
        this.skeleton=skeleton;
        this.clips=Collections.unmodifiableMap(new LinkedHashMap<>(clips));
    }
    public static PoseAnimationLibrary fromModel(ModelDocument model, ScriptBudget budget) {
        Objects.requireNonNull(model); Objects.requireNonNull(budget).step();
        if (model.skeleton().boneCount()>65536) throw limit("At most 65536 animation skeleton bones");
        boolean ysm=model.sourceFormat().startsWith("ysm-");
        return new Decoder(model.skeleton(),budget,
                ysm?InterpolationProfile.HISTORICAL_YSM:InterpolationProfile.CORE_NON_UNIFORM,ysm)
                .decode(model.metadata());
    }
    public static PoseAnimationLibrary decode(JsonObject source, Skeleton skeleton, ScriptBudget budget) {
        return decode(source,skeleton,budget,InterpolationProfile.CORE_NON_UNIFORM);
    }
    public static PoseAnimationLibrary decode(JsonObject source,Skeleton skeleton,ScriptBudget budget,InterpolationProfile profile) {
        Objects.requireNonNull(source); Objects.requireNonNull(skeleton); Objects.requireNonNull(budget).step();
        if (skeleton.boneCount()>65536) throw limit("At most 65536 animation skeleton bones");
        return new Decoder(skeleton,budget,Objects.requireNonNull(profile),false).decode(source);
    }
    public Set<String> names() { return clips.keySet(); }
    public int boneCount() { return skeleton.boneCount(); }
    Clip clip(String name) {
        Clip result=clips.get(name);
        if (result==null) throw new IllegalArgumentException("Unknown animation: "+name);
        return result;
    }
    /**
     * Stateless sample: ONCE is un-driven after its exact final key; HOLD keeps it, LOOP wraps it.
     * Declared delay expressions reconstruct a fresh schedule for this call; timeline commands are never run.
     * Use a player/controller when delay expressions must retain exactly-once playback state across frames.
     */
    public BonePose sample(String name,double elapsedSeconds,MolangContext context,BonePose bindPose) {
        if (!Double.isFinite(elapsedSeconds) || elapsedSeconds<0) throw new IllegalArgumentException("Invalid animation time");
        Clip clip=clip(name);
        if (clip.delayed()) {
            MolangContext local=context.sampling(Map.of("life_time",elapsedSeconds));
            PoseClipPlayback playback=new PoseClipPlayback(clip,local);
            playback.advance(elapsedSeconds,0,local,new ArrayList<>(),false);
            return sample(clip,playback.localTime(),local.overlay(Map.of("anim_time",playback.localTime())),bindPose,playback.driven(elapsedSeconds)).pose;
        }
        boolean drive=clip.loop!=Loop.ONCE || elapsedSeconds<=clip.duration;
        MolangContext local=context.sampling(Map.of("anim_time",clip.localTime(elapsedSeconds),"life_time",elapsedSeconds));
        return sample(clip,clip.localTime(elapsedSeconds),local,bindPose,drive).pose;
    }
    Sample sample(Clip clip,double localTime,MolangContext context,BonePose bindPose,boolean drive) {
        context.budget().step();
        if (bindPose.boneCount()!=boneCount()) throw new IllegalArgumentException("Animation bind pose does not match skeleton");
        for (int bone=0;bone<boneCount();bone++) context.budget().step();
        float[] rotations=bindPose.rotations(), positions=bindPose.translations(), scales=bindPose.scales();
        boolean[] driven=new boolean[Math.multiplyExact(boneCount(),3)];
        double weight=drive && clip!=null && clip.blendWeight!=null ? Math.max(0,clip.blendWeight.value(context)) : 1;
        if (drive && clip!=null) for (BoneTracks bone:clip.bones) {
            context.budget().step();
            int offset=bone.index*3;
            if (bone.rotation!=null) {
                double[] delta=bone.rotation.value(localTime,context);
                for (int axis=0;axis<3;axis++) rotations[offset+axis]=representable(rotations[offset+axis]+delta[axis]*weight*(axis==2?1:-1)*Math.PI/180);
                driven[offset]=true;
            }
            if (bone.position!=null) {
                double[] delta=bone.position.value(localTime,context);
                for (int axis=0;axis<3;axis++) positions[offset+axis]=representable(positions[offset+axis]+delta[axis]*weight*(axis==0?-1:1)/16);
                driven[offset+1]=true;
            }
            if (bone.scale!=null) {
                double[] factor=bone.scale.value(localTime,context);
                for (int axis=0;axis<3;axis++) scales[offset+axis]=representable(scales[offset+axis]*(1+(factor[axis]-1)*weight));
                driven[offset+2]=true;
            }
        }
        return new Sample(new BonePose(rotations,positions,scales,bindPose.ownHidden(),bindPose.childrenHidden(),bindPose.glow(),bindPose.colorsRgba()),driven);
    }
    static float representable(double value) {
        float result=(float)value;
        if (!Float.isFinite(result)) throw new MolangException(MolangException.Reason.NON_FINITE,"Animation channel does not fit finite float32");
        return result;
    }
    private static MolangException invalid(String message) { return new MolangException(MolangException.Reason.SYNTAX,message); }
    private static MolangException limit(String message) { return new MolangException(MolangException.Reason.CAPACITY,message); }
    private static JsonObject object(JsonValue value,String label) {
        if (!(value instanceof JsonObject result)) throw invalid("Expected object: "+label);
        return result;
    }
    private static double number(JsonValue value,String label) {
        if (!(value instanceof JsonNumber result)) throw invalid("Expected number: "+label);
        double numeric=result.value();
        if (!Double.isFinite(numeric)) throw invalid("Non-finite "+label);
        return numeric;
    }
    private static String string(JsonValue value,String label) {
        if (!(value instanceof JsonString result)) throw invalid("Expected string: "+label);
        return result.value();
    }
    private static void fields(JsonObject value,Set<String> allowed) {
        for (String key:value.values().keySet()) if (!allowed.contains(key)) {
            throw new MolangException(MolangException.Reason.UNSUPPORTED,"Unsupported executable animation field: "+key);
        }
    }
    private static final class Decoder {
        final Skeleton skeleton;
        final ScriptBudget budget;
        final InterpolationProfile profile;
        final boolean historicalYsm;
        final Map<String,Integer> boneIndices=new LinkedHashMap<>();
        int totalKeys;
        int totalTracks;
        int totalEvents;
        int expressionChars;
        Decoder(Skeleton skeleton,ScriptBudget budget,InterpolationProfile profile,boolean historicalYsm) {
            this.skeleton=skeleton; this.budget=budget; this.profile=profile;
            this.historicalYsm=historicalYsm;
            for (int i=0;i<skeleton.boneCount();i++) { budget.step(); boneIndices.put(skeleton.names().get(i),i); }
        }
        PoseAnimationLibrary decode(JsonObject source) {
            JsonValue animations=source.values().get("animations");
            if (animations==null) return new PoseAnimationLibrary(skeleton,Map.of());
            JsonObject definitions=object(animations,"animations");
            if (definitions.values().size()>1024) throw limit("At most 1024 animations");
            Map<String,Clip> result=new LinkedHashMap<>();
            for (Map.Entry<String,JsonValue> entry:definitions.values().entrySet()) {
                budget.step();
                if (entry.getKey().isBlank() || entry.getKey().length()>1024) throw invalid("Invalid animation name");
                result.put(entry.getKey(),animation(entry.getKey(),object(entry.getValue(),"animation")));
            }
            return new PoseAnimationLibrary(skeleton,result);
        }
        Clip animation(String name,JsonObject value) {
            fields(value,Set.of("animation_length","loop","bones","sound_effects","timeline","blend_weight","start_delay","loop_delay","override_previous_animation"));
            List<BoneTracks> bones=new ArrayList<>();
            double inferred=0;
            JsonValue boneValue=value.values().get("bones");
            if (boneValue!=null) for (Map.Entry<String,JsonValue> entry:object(boneValue,"bones").values().entrySet()) {
                budget.step();
                if (++totalTracks>65536) throw limit("At most 65536 animated bone declarations");
                Integer index=boneIndices.get(entry.getKey());
                if (index==null) {
                    if (historicalYsm) continue;
                    throw invalid("Animation references unknown bone: "+entry.getKey());
                }
                JsonObject channels=object(entry.getValue(),"bone channels");
                fields(channels,Set.of("rotation","position","scale"));
                Track rotation=track(channels.values().get("rotation"));
                Track position=track(channels.values().get("position"));
                Track scale=track(channels.values().get("scale"));
                for (Track track:new Track[]{rotation,position,scale}) if (track!=null) inferred=Math.max(inferred,track.endTime());
                bones.add(new BoneTracks(index,rotation,position,scale));
            }
            JsonValue authoredLength=value.values().get("animation_length");
            boolean historicalInfinity=profile==InterpolationProfile.HISTORICAL_YSM && authoredLength instanceof JsonString text && text.value().equals("Infinity");
            double duration=historicalInfinity?Double.POSITIVE_INFINITY:value.values().containsKey("animation_length") ? number(authoredLength,"animation_length")
                    : inferred==0 ? Double.POSITIVE_INFINITY : inferred;
            if (duration<0 || (Double.isFinite(duration) && duration>1_000_000)) throw invalid("Animation length must be 0..1000000 seconds");
            Loop loop=loop(value.values().get("loop"));
            if (loop==Loop.LOOP && duration<0.000001) throw invalid("Looping animation duration must be at least one microsecond");
            List<Event> events=new ArrayList<>();
            JsonValue sounds=value.values().get("sound_effects");
            if (sounds!=null) for (Map.Entry<String,JsonValue> entry:object(sounds,"sound_effects").values().entrySet()) {
                budget.step(); countEvent();
                JsonValue sound=entry.getValue();
                String resource;
                if (sound instanceof JsonString text) resource=text.value();
                else {
                    JsonObject definition=object(sound,"sound effect"); fields(definition,Set.of("effect"));
                    resource=string(definition.values().get("effect"),"effect");
                }
                if (resource.isBlank() || resource.length()>1024) throw invalid("Invalid sound selector");
                events.add(new Event(time(entry.getKey()),resource,List.of()));
            }
            JsonValue timeline=value.values().get("timeline");
            if (timeline!=null) for (Map.Entry<String,JsonValue> entry:object(timeline,"timeline").values().entrySet()) {
                budget.step(); countEvent();
                List<MolangProgram> instructions=new ArrayList<>();
                if (entry.getValue() instanceof JsonArray array) {
                    int instructionLimit=historicalYsm?4096:128;
                    if (array.values().size()>instructionLimit)
                        throw limit("At most "+instructionLimit+" instructions per timeline event");
                    for (JsonValue instruction:array.values()) instructions.add(program(string(instruction,"timeline instruction")));
                } else instructions.add(program(string(entry.getValue(),"timeline instruction")));
                events.add(new Event(time(entry.getKey()),null,List.copyOf(instructions)));
            }
            if (events.size()>4096) throw limit("At most 4096 events per animation");
            if (!historicalYsm) {
                for (Event event:events) if (event.time>duration) throw invalid("Event after animation duration");
                if (inferred>duration) throw invalid("Bone keyframe after animation duration");
            }
            events.sort(Comparator.comparingDouble(Event::time));
            Scalar weight=value.values().containsKey("blend_weight")?scalar(value.values().get("blend_weight")):null;
            Scalar startDelay=value.values().containsKey("start_delay")?scalar(value.values().get("start_delay")):null;
            Scalar loopDelay=value.values().containsKey("loop_delay")?scalar(value.values().get("loop_delay")):null;
            JsonValue override=value.values().get("override_previous_animation");
            if (override!=null && !(override instanceof JsonBoolean)) throw invalid("override_previous_animation must be boolean");
            return new Clip(name,duration,loop,weight,startDelay,loopDelay,override instanceof JsonBoolean bool && bool.value(),List.copyOf(bones),List.copyOf(events));
        }
        void countEvent() { if (++totalEvents>65536) throw limit("At most 65536 animation events"); }
        Loop loop(JsonValue value) {
            if (value==null) return Loop.ONCE;
            if (value instanceof JsonBoolean bool) return bool.value()?Loop.LOOP:Loop.ONCE;
            return switch(string(value,"loop").toLowerCase(Locale.ROOT)) {
                case "true","loop" -> Loop.LOOP;
                case "false","play_once" -> Loop.ONCE;
                case "hold_on_last_frame" -> Loop.HOLD;
                default -> throw invalid("Unsupported loop value");
            };
        }
        Track track(JsonValue value) {
            if (value==null) return null;
            // The columnar form must be recognised before the shorthand branch below, which treats any
            // non-object as one keyframed constant at t=0. Letting a timeline reach it would produce a
            // plausible but wrong animation with no error anywhere, so the order of these branches is
            // load-bearing; ColumnarTimelineSamplingTest pins that a timeline never decodes as one key.
            if (value instanceof JsonTimeline timeline) return timeline(timeline);
            List<Key> keys=new ArrayList<>();
            if (!(value instanceof JsonObject timestamps)) keys.add(key(0,value));
            else {
                if (timestamps.values().isEmpty()) throw invalid("Empty animation track");
                TreeMap<Double,Key> sorted=new TreeMap<>();
                for (Map.Entry<String,JsonValue> entry:timestamps.values().entrySet()) {
                    budget.step(); double time=time(entry.getKey());
                    if (sorted.putIfAbsent(time,key(time,entry.getValue()))!=null) throw invalid("Duplicate numeric keyframe time");
                }
                keys.addAll(sorted.values());
            }
            return new Track(List.copyOf(keys),profile);
        }
        /**
         * Decodes the columnar form through the same per-key routine the object form uses.
         *
         * <p>Each key is rebuilt as the {@link JsonVector3} the object form would have held and handed
         * to {@link #key(double,JsonValue)}, so the key cap, the budget steps, the interpolation
         * mapping and the vector decoding are the same code and cannot drift from the object path.
         * The rebuilt vectors are transient: only the compressed node is retained.
         *
         * <p>Ticks are wired in {@code float32}, and the object form prints the same tick divided by
         * twenty as decimal text. Dividing the float here reproduces that double exactly, because
         * {@code BigDecimal.valueOf(double)} round-trips through {@code toString()} losslessly.
         */
        Track timeline(JsonTimeline timeline) {
            int count=timeline.keyCount();
            TreeMap<Double,Key> sorted=new TreeMap<>();
            // The node records which keys differ from plain linearity; everything else is the compact
            // shorthand, whose post equals its pre and whose mode is linear. Reading the sparse table
            // by cursor keeps this linear rather than a lookup per key.
            int entry=0;
            for (int index=0;index<count;index++) {
                boolean sparse=entry<timeline.sparseCount() && timeline.sparseKey(entry)==index;
                budget.step();
                double time=(double)timeline.tick(index)/20.0;
                // The object form spends one step parsing its decimal timestamp text; spend the same
                // one here so the same music track costs the same allowance in either shape.
                budget.step();
                if (!Double.isFinite(time) || time<0 || time>1_000_000) throw invalid("Invalid animation timestamp");
                JsonValue raw;
                if (!sparse) raw=new JsonVector3(timeline.pre(index,0),timeline.pre(index,1),timeline.pre(index,2));
                else {
                    var object=new LinkedHashMap<String,JsonValue>();
                    object.put("pre",new JsonVector3(timeline.pre(index,0),timeline.pre(index,1),timeline.pre(index,2)));
                    object.put("post",new JsonVector3(timeline.sparsePost(entry,0),timeline.sparsePost(entry,1),timeline.sparsePost(entry,2)));
                    object.put("lerp_mode",new JsonString(interpolation(timeline.sparseMode(entry))));
                    raw=new JsonObject(object);
                }
                if (sorted.putIfAbsent(time,key(time,raw))!=null) throw invalid("Duplicate numeric keyframe time");
                if (sparse) entry++;
            }
            return new Track(List.copyOf(sorted.values()),profile);
        }
        /** The {@code lerp_mode} text the object form carries for each columnar interpolation code. */
        private static String interpolation(byte mode) {
            return switch(mode) {
                case JsonTimeline.LINEAR -> "linear";
                case JsonTimeline.STEP -> "step";
                case JsonTimeline.CATMULL_ROM -> "catmullrom";
                default -> throw invalid("Unsupported columnar interpolation code");
            };
        }
        Key key(double time,JsonValue value) {
            budget.step();
            int keyLimit=historicalYsm?131072:65536;
            if (++totalKeys>keyLimit) throw limit("At most "+keyLimit+" animation keys");
            if (!(value instanceof JsonObject object)) { Vector vector=vector(value); return new Key(time,vector,vector,Interpolation.LINEAR); }
            fields(object,Set.of("pre","post","vector","lerp_mode","easing"));
            JsonValue raw=object.values().get("vector");
            Vector pre,post;
            if (raw!=null) {
                if (object.values().containsKey("pre") || object.values().containsKey("post")) throw invalid("vector conflicts with pre/post");
                pre=vector(raw); post=pre;
            } else {
                JsonValue preValue=object.values().get("pre"),postValue=object.values().get("post");
                if (preValue==null && postValue==null) throw invalid("Keyframe needs vector/pre/post");
                pre=vector(preValue==null?postValue:preValue);
                post=postValue==null || preValue==null ? pre : vector(postValue);
            }
            JsonValue mode=object.values().getOrDefault("lerp_mode",object.values().get("easing"));
            Interpolation interpolation=mode==null?Interpolation.LINEAR:switch(string(mode,"interpolation").toLowerCase(Locale.ROOT)) {
                case "linear" -> Interpolation.LINEAR;
                case "step" -> Interpolation.STEP;
                case "catmull_rom","catmullrom" -> Interpolation.CATMULL_ROM;
                default -> throw new MolangException(MolangException.Reason.UNSUPPORTED,"Unsupported animation interpolation");
            };
            return new Key(time,pre,post,interpolation);
        }
        Vector vector(JsonValue value) {
            budget.step();
            // A three-number vector is stored inline as JsonVector3, so both forms must be accepted.
            if (value instanceof JsonVector3 inline) {
                return new Vector(simple(inline.x()),simple(inline.y()),simple(inline.z()));
            }
            if (value instanceof JsonArray array) {
                if (array.values().size()==1) { Scalar scalar=scalar(array.values().get(0)); return new Vector(scalar,scalar,scalar); }
                if (array.values().size()!=3) throw invalid("Animation vector needs one or three elements");
                return new Vector(scalar(array.values().get(0)),scalar(array.values().get(1)),scalar(array.values().get(2)));
            }
            Scalar scalar=scalar(value); return new Vector(scalar,scalar,scalar);
        }
        /** A literal number needs no Molang program; the inline vector has already been validated. */
        Scalar simple(double value) { budget.step(); return new Scalar(value,null); }
        Scalar scalar(JsonValue value) {
            budget.step();
            if (value instanceof JsonNumber) return new Scalar(number(value,"animation scalar"),null);
            return new Scalar(0,program(string(value,"Molang scalar")));
        }
        MolangProgram program(String source) {
            int singleExpressionLimit=historicalYsm?131072:65536;
            if (source.length()>singleExpressionLimit || expressionChars>4_194_304-source.length())
                throw limit("Animation expression source limit exceeded");
            expressionChars+=source.length();
            for (int i=0;i<source.length();i+=16) budget.step();
            return historicalYsm
                    ?MolangProgram.compile(source,131072,8192)
                    :MolangProgram.compile(source);
        }
        double time(String text) {
            budget.step();
            if (text.length()>64) throw limit("Animation timestamp text exceeds 64 characters");
            try {
                double time=new BigDecimal(text).doubleValue();
                if (!Double.isFinite(time) || time<0 || time>1_000_000) throw invalid("Invalid animation timestamp");
                return time==0?0:time;
            } catch (NumberFormatException ex) { throw invalid("Invalid animation timestamp: "+text); }
        }
    }
}
