package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins which tracks become columnar and which must stay in the object form.
 *
 * <p>Every fallback case here asserts the <em>legacy shape</em>, not merely "not columnar", because the
 * point of the fallback is that the previous code path runs unchanged: the same object, the same
 * array of duplicate keyframes, the same {@code historical_*} placeholder key and the same recorded
 * limitation. A test that only checked {@code !(value instanceof JsonTimeline)} would pass even if
 * the fallback silently produced a different shape.
 */
class ColumnarTimelineProjectionTest {
    /** A numeric Molang axis, the only shape a columnar component can hold. */
    static Sequence vector(float x,float y,float z) { return seq(molang(x),molang(y),molang(z)); }
    static Struct molang(float value) { return frame("type",u(1),"number",f(value)); }
    /** A Molang expression axis, which no float32 column can hold. */
    static Struct expression(String source) { return frame("type",u(2),"source",str(source)); }
    static Struct frame(Object... pairs) { return new Struct(pairs(pairs)); }
    static Map<String,HistoricalValue> pairs(Object... pairs) {
        var fields=new LinkedHashMap<String,HistoricalValue>();
        for(int index=0;index<pairs.length;index+=2) fields.put((String)pairs[index],(HistoricalValue)pairs[index+1]);
        return fields;
    }
    static HistoricalValue u(long value) { return new Unsigned(32,value); }
    static HistoricalValue f(float value) { return new Float32(Float.floatToRawIntBits(value)); }
    static HistoricalValue str(String value) { return new Text(value); }
    static Sequence seq(HistoricalValue... values) { return new Sequence(List.of(values)); }
    static Presence yes(HistoricalValue value) { return new Presence(true,value); }
    static Presence no() { return new Presence(false,null); }
    /** One raw keyframe with numeric axes and an explicit tick, easing and optional post. */
    static Struct key(float tick,int easing,HistoricalValue pre,HistoricalValue post) {
        return frame("tick",f(tick),"easing",u(easing),"pre",pre,"post",post);
    }
    static Struct numericKey(float tick,int easing) {
        return key(tick,easing,vector(tick,0,0),no());
    }
    /** Wraps frames into the animation set the projection consumes. */
    static JsonObject project(HistoricalValue... frames) throws IOException {
        Struct clip=frame("name",str("a"),"length",f(1000),"loop",u(0),
                "bones",seq(frame("name",str("root"),"position",new Sequence(List.of(frames)),
                        "rotation",seq(),"scale",seq())),"instructions",seq(),"sounds",seq());
        HistoricalValue files=frame("animations",seq(clip));
        return HistoricalSceneProjection.project(32,"player","main",files,null,
                DecodePolicy.localModel(),new CancellationToken(),bytes->{});
    }
    static JsonValue track(JsonObject metadata) {
        return ((JsonObject)((JsonObject)((JsonObject)((JsonObject)metadata.values().get("animations"))
                .values().get("a")).values().get("bones")).values().get("root")).values().get("position");
    }
    static List<JsonValue> limitations(JsonObject metadata) {
        return ((JsonArray)((JsonObject)metadata.values().get("historical_scene")).values().get("limitations")).values();
    }

    @Test void linearNumericTrackBecomesColumnarWithExactlyItsKeys() throws Exception {
        JsonObject metadata=project(numericKey(0,0),numericKey(20,1),numericKey(40,0));
        var value=assertInstanceOf(JsonTimeline.class,track(metadata),"a numeric linear track must be columnar");
        assertEquals(3,value.keyCount());assertEquals(0,value.sparseCount(),"every key is the plain shorthand");
        assertEquals(0f,value.tick(0));assertEquals(20f,value.tick(1));assertEquals(40f,value.tick(2));
        assertEquals(20f,value.pre(1,0));assertEquals(0f,value.pre(1,1));assertEquals(0f,value.pre(1,2));
        assertTrue(limitations(metadata).isEmpty(),"a fully columnar track records no limitation");
    }

    @Test void catmullRomKeyStaysColumnarAndBecomesOneSparseEntry() throws Exception {
        JsonObject metadata=project(numericKey(0,0),numericKey(20,2),numericKey(40,0));
        var value=assertInstanceOf(JsonTimeline.class,track(metadata),"one curved key must not force the whole track back");
        assertEquals(3,value.keyCount());assertEquals(1,value.sparseCount(),"only the curved key pays for an entry");
        assertEquals(1,value.sparseKey(0));assertEquals(JsonTimeline.CATMULL_ROM,value.sparseMode(0));
        // The sparse post equals the pre, because this fixture authored no separate post.
        assertEquals(20f,value.sparsePost(0,0));
    }

    @Test void authoredPostDifferentFromPreBecomesASparseEntryWithItsOwnVector() throws Exception {
        JsonObject metadata=project(numericKey(0,0),key(20,0,vector(16,0,0),yes(vector(32,8,0))),numericKey(40,0));
        var value=assertInstanceOf(JsonTimeline.class,track(metadata));
        assertEquals(1,value.sparseCount());
        assertEquals(1,value.sparseKey(0));assertEquals(JsonTimeline.LINEAR,value.sparseMode(0));
        assertEquals(16f,value.pre(1,0));assertEquals(32f,value.sparsePost(0,0));
        assertEquals(8f,value.sparsePost(0,1),"a distinct post vector must be retained exactly");
    }

    @Test void molangExpressionFallsBackToTheWholeTrackObjectForm() throws Exception {
        JsonObject metadata=project(numericKey(0,0),key(20,0,seq(molang(16),expression("q.anim_time*16"),molang(0)),no()),
                numericKey(40,0));
        assertEquals(JsonObject.class,track(metadata).getClass(),"one expression makes the whole track unexpressible");
        JsonObject track=(JsonObject)track(metadata);
        assertEquals(Set.of("0","1","2"),track.values().keySet(),"every key must still be present");
        var mixed=(JsonArray)track.values().get("1");
        assertEquals("q.anim_time*16",((JsonString)mixed.values().get(1)).value());
        // The numeric keys keep the compact inline form, so nothing about them changed either.
        assertInstanceOf(JsonVector3.class,track.values().get("0"));
    }

    @Test void nonFiniteComponentFallsBackAndKeepsTheRawBitsForm() throws Exception {
        Struct poisoned=key(20,0,vector(16,0,0),yes(seq(molang(16),molang(Float.NaN),molang(0))));
        JsonObject metadata=project(numericKey(0,0),poisoned,numericKey(40,0));
        var track=(JsonObject)track(metadata);
        var post=(JsonArray)((JsonObject)track.values().get("1")).values().get("post");
        var raw=(JsonObject)post.values().get(1);
        assertEquals("7fc00000",((JsonString)((JsonObject)raw.values().get("historical_float32")).values().get("raw_bits")).value());
        assertFalse(limitations(metadata).isEmpty());
    }

    @Test void nonFiniteTickFallsBackToTheRawBitKey() throws Exception {
        JsonObject metadata=project(numericKey(0,0),key(Float.NaN,0,vector(16,0,0),no()),numericKey(40,0));
        var track=(JsonObject)track(metadata);
        assertTrue(track.values().containsKey("historical_tick_7fc00000"),
                "a NaN tick has no float32 column slot and must keep the placeholder key");
        assertFalse(limitations(metadata).isEmpty());
    }

    @Test void duplicateTimestampsFallBackToTheArrayOfBothKeyframes() throws Exception {
        JsonObject metadata=project(numericKey(0,0),numericKey(20,0),numericKey(20,1),numericKey(40,0));
        var track=(JsonObject)track(metadata);
        var duplicates=assertInstanceOf(JsonArray.class,track.values().get("1"));
        assertEquals(2,duplicates.values().size(),"both keyframes at the shared tick must survive");
        assertFalse(limitations(metadata).isEmpty());
    }

    @Test void unknownEasingEnumFallsBackToThePlaceholderMode() throws Exception {
        JsonObject metadata=project(numericKey(0,0),numericKey(20,99),numericKey(40,0));
        var track=(JsonObject)track(metadata);
        var entry=(JsonObject)track.values().get("1");
        assertEquals("historical_easing_99",((JsonString)entry.values().get("lerp_mode")).value());
        assertFalse(limitations(metadata).isEmpty());
    }

    @Test void negativeTickFallsBackAndRecordsTheLimitation() throws Exception {
        JsonObject metadata=project(numericKey(0,0),numericKey(-20,0),numericKey(40,0));
        var track=(JsonObject)track(metadata);
        assertTrue(track.values().containsKey("-1"),"the object form keeps the negative timestamp it always did");
        assertFalse(limitations(metadata).isEmpty());
    }

    @Test void singleKeyTrackStaysObjectFormBecauseTheNodeWouldCostMore() throws Exception {
        JsonObject metadata=project(numericKey(20,1));
        var track=(JsonObject)track(metadata);
        assertEquals(Set.of("1"),track.values().keySet(),"a single key keeps the timestamp map");
        assertInstanceOf(JsonVector3.class,track.values().get("1"),"and the key keeps its compact form");
    }

    @Test void columnarTrackChargesFewerBytesThanTheObjectFormForTheSameKeys() throws Exception {
        long columnar=retained(120);
        // Force the same 120 keys through the object form by giving one key a Molang expression.
        long object=retained(120,true);
        assertTrue(columnar<object,"the columnar node must actually be cheaper: columnar="+columnar+" object="+object);
        assertTrue(columnar*2<object,"the saving must be the array-shape one, not noise");
    }

    /** Retained bytes the projection reports for a track of {@code keys} numeric keyframes. */
    static long retained(int keys) throws IOException { return retained(keys,false); }
    static long retained(int keys,boolean poison) throws IOException {
        var frames=new ArrayList<HistoricalValue>();
        for(int index=0;index<keys;index++) {
            if(poison&&index==0)frames.add(key(0,0,seq(molang(1),expression("q.anim_time"),molang(0)),no()));
            else frames.add(numericKey(index,index%2));
        }
        Struct clip=frame("name",str("a"),"length",f(1000),"loop",u(0),
                "bones",seq(frame("name",str("root"),"position",new Sequence(List.copyOf(frames)),
                        "rotation",seq(),"scale",seq())),"instructions",seq(),"sounds",seq());
        long[] total={0};
        HistoricalSceneProjection.project(32,"player","main",frame("animations",seq(clip)),null,
                DecodePolicy.localModel(),new CancellationToken(),bytes->total[0]+=bytes);
        return total[0];
    }
}
