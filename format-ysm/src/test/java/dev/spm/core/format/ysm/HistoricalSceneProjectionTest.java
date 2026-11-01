package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

class HistoricalSceneProjectionTest {
    static Struct s(Object... pairs) { Map<String,HistoricalValue> fields=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) fields.put((String)pairs[i],(HistoricalValue)pairs[i+1]); return new Struct(fields); }
    static Text t(String value) { return new Text(value); }
    static Float32 f(float value) { return new Float32(Float.floatToRawIntBits(value)); }
    static Unsigned u(long value) { return new Unsigned(32,value); }
    static Sequence seq(HistoricalValue... values) { return new Sequence(List.of(values)); }
    static Mapping map(Entry... values) { return new Mapping(List.of(values)); }
    static Presence yes(HistoricalValue value) { return new Presence(true,value); }
    static Presence no() { return new Presence(false,null); }
    static Struct num(float value) { return s("type",u(1),"number",f(value)); }
    static Struct expr(String value) { return s("type",u(2),"source",t(value)); }
    static Struct key(float tick,int easing) { return s("tick",f(tick),"easing",u(easing),"pre",seq(num(16),expr("q.anim_time*16"),num(0)),"post",yes(seq(num(32),num(0),num(0)))); }
    static Struct animation(String name) { return s("name",t(name),"length",f(40),"loop",u(3),"bones",seq(s("name",t("root"),"position",seq(key(20,2)),"rotation",seq(),"scale",seq())),
            "instructions",seq(s("tick",f(10),"data",seq(t("v.a=1;"),t("v.b=2;")))),"sounds",seq(s("tick",f(20),"data",t("sound.one")))); }
    static Struct file(Struct... animations) { return s("animations",seq(animations)); }
    static JsonObject object(JsonValue value) { return (JsonObject)value; }
    static JsonObject at(JsonObject value,String field) { return object(value.values().get(field)); }
    static String text(JsonValue value) { return ((JsonString)value).value(); }
    static double number(JsonValue value) { return ((JsonNumber)value).value().doubleValue(); }
    static JsonObject project(HistoricalValue animations,HistoricalValue controllers) throws IOException {
        return HistoricalSceneProjection.project(32,"player","main",animations,controllers,DecodePolicy.localModel(),new CancellationToken(),bytes->{});
    }
    @Test void projectsTicksVectorsPrePostInstructionsSoundsAndHistoricalProfile() throws Exception {
        JsonObject metadata=project(map(new Entry(u(1),yes(file(animation("walk"))))),null);
        JsonObject clip=at(at(metadata,"animations"),"walk");
        assertEquals(2,number(clip.values().get("animation_length"))); assertEquals("hold_on_last_frame",text(clip.values().get("loop")));
        JsonObject key=at(at(at(clip,"bones"),"root"),"position");
        assertEquals(Set.of("1"),key.values().keySet()); assertEquals("catmullrom",text(at(key,"1").values().get("lerp_mode")));
        JsonArray pre=(JsonArray)at(key,"1").values().get("pre"); assertEquals(16,number(pre.values().get(0))); assertEquals("q.anim_time*16",text(pre.values().get(1)));
        assertEquals(2,((JsonArray)at(clip,"timeline").values().get("0.5")).values().size());
        assertEquals("sound.one",text(at(clip,"sound_effects").values().get("1")));
        assertEquals("HISTORICAL_YSM",text(metadata.values().get("animation_interpolation_profile")));
        assertTrue(at(at(metadata,"historical_scene"),"animation_sets").values().containsKey("1"));
    }
    @Test void retainsUnsupportedFieldsEnumsAndNonfiniteBitsWithoutSilentFallback() throws Exception {
        var fields=new LinkedHashMap<>(animation("unsupported").fields());
        fields.put("start_delay",yes(expr("q.delay"))); fields.put("loop_delay",yes(num(2))); fields.put("weight",yes(num(0.5f))); fields.put("override_previous",yes(new Bool(true)));
        fields.put("loop",u(99)); fields.put("bones",seq(s("name",t("root"),"position",seq(key(0,99)),"rotation",seq(),"scale",seq())));
        JsonObject metadata=project(file(new Struct(fields)),null); JsonObject clip=at(at(metadata,"animations"),"unsupported");
        assertEquals("q.delay",text(clip.values().get("start_delay"))); assertEquals(0.5,number(clip.values().get("blend_weight")));
        assertEquals("historical_loop_99",text(clip.values().get("loop")));
        assertEquals("historical_easing_99",text(at(at(at(at(clip,"bones"),"root"),"position"),"0").values().get("lerp_mode")));
        assertFalse(((JsonArray)at(metadata,"historical_scene").values().get("limitations")).values().isEmpty());
        fields.put("length",f(Float.POSITIVE_INFINITY)); JsonObject infinity=at(at(project(file(new Struct(fields)),null),"animations"),"unsupported");
        assertEquals("Infinity",text(infinity.values().get("animation_length")));
        fields.put("length",new Float32(0x7fc01234)); JsonValue nan=at(at(project(file(new Struct(fields)),null),"animations"),"unsupported").values().get("animation_length");
        assertEquals("7fc01234",text(at(object(nan),"historical_float32").values().get("raw_bits")));
    }
    @Test void sourceSetCollisionsArePreservedAndExcludedFromAmbiguousFlatLookup() throws Exception {
        Mapping sourceSets=map(new Entry(u(1),yes(file(animation("same")))),new Entry(u(2),yes(file(animation("same")))));
        JsonObject metadata=project(sourceSets,null);
        assertFalse(at(metadata,"animations").values().containsKey("same"));
        JsonObject sets=at(at(metadata,"historical_scene"),"animation_sets");
        assertTrue(at(at(sets,"1"),"animations").values().containsKey("same")); assertTrue(at(at(sets,"2"),"animations").values().containsKey("same"));
    }
    @Test void controllersKeepDeclarationOrderSecondsBlendAndUnsupportedCurves() throws Exception {
        Struct state=s("animations",seq(s("name",t("walk"),"condition",t(""))),"transitions",seq(s("name",t("second"),"condition",t("q.go")),s("name",t("default"),"condition",t("1"))),
                "entry",seq(t("v.enter=1;")),"exit",seq(t("v.exit=1;")),"length",yes(f(0.25f)),"blend_via_shortest_path",new Bool(true),"sounds",seq(t("state.sound")));
        Struct controllers=s("controllers",map(new Entry(t("c"),s("initial",t("default"),"states",map(new Entry(t("default"),state),new Entry(t("second"),state))))));
        JsonObject metadata=project(file(animation("walk")),map(new Entry(t("set"),s("hash",t("h"),"value",controllers))));
        JsonObject projected=at(at(at(at(metadata,"animation_controllers"),"c"),"states"),"default");
        assertEquals(0.25,number(projected.values().get("blend_transition"))); assertEquals("second",object(((JsonArray)projected.values().get("transitions")).values().get(0)).values().keySet().iterator().next());
        assertTrue(projected.values().containsKey("blend_via_shortest_path"));
    }
    @Test void loweringAttachesMetadataAndReleasesEveryProjectedAllocation() throws Exception {
        Struct geo=s("bones",seq()); Struct image=s("width",u(1),"height",u(1),"bytes",new Binary(new byte[4]));
        Struct root=s("identity",t("00000000000000000000000000000000"),"textures",map(new Entry(t("default"),yes(image))),"geos",map(new Entry(u(1),yes(geo))),"animations",map(new Entry(u(1),yes(file(animation("walk"))))));
        ResourceBudget budget=new ResourceBudget(1<<20);
        try(var historical=new YsmHistoricalModel(1,root,List.of());var result=YsmModelImporter.importPayload(historical,DecodePolicy.localModel(),budget,new CancellationToken())) {
            assertTrue(budget.used()>0); assertTrue(result.variants().get(0).document().orElseThrow().metadata().values().containsKey("animations"));
        }
        assertEquals(0,budget.used());
        ResourceBudget insufficient=new ResourceBudget(100);
        try(var historical=new YsmHistoricalModel(1,root,List.of())) {
            assertThrows(IOException.class,()->YsmModelImporter.importPayload(historical,DecodePolicy.localModel(),insufficient,new CancellationToken()));
            assertNotNull(historical.root());
        }
        assertEquals(0,insufficient.used());
        CancellationToken cancelled=new CancellationToken();cancelled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->HistoricalSceneProjection.project(1,"player","main",root.field("animations"),null,DecodePolicy.localModel(),cancelled,bytes->fail("Allocation after cancellation")));
    }
    @Test void duplicateKeysNonfiniteTimesAndSimultaneousSoundsRemainExplicit() throws Exception {
        var fields=new LinkedHashMap<>(animation("a").fields());
        fields.put("bones",seq(s("name",t("root"),"position",seq(key(20,1),key(20,2),key(Float.NaN,1)),"rotation",seq(),"scale",seq())));
        fields.put("sounds",seq(s("tick",f(0),"data",t("one")),s("tick",f(0),"data",t("two"))));
        JsonObject metadata=project(file(new Struct(fields)),null),clip=at(at(metadata,"animations"),"a");
        JsonObject track=at(at(at(clip,"bones"),"root"),"position");
        assertEquals(2,((JsonArray)track.values().get("1")).values().size());
        assertTrue(track.values().containsKey("historical_tick_7fc00000"));
        assertEquals(2,((JsonArray)at(clip,"sound_effects").values().get("0")).values().size());
    }
    @Test void controllerCurvesAndAbsentMolangVariantsNeverBecomeLinearOrZero() throws Exception {
        var clipFields=new LinkedHashMap<>(animation("a").fields());
        clipFields.put("weight",yes(s("type",u(0))));
        Struct state=s("animations",seq(),"transitions",seq(),"entry",seq(),"exit",seq(),"length",no(),
                "points",map(new Entry(f(0),f(0)),new Entry(f(0.2f),f(1))),"blend_via_shortest_path",new Bool(false));
        JsonObject metadata=project(file(new Struct(clipFields)),s("controllers",map(new Entry(t("c"),s("initial",t("default"),"states",map(new Entry(t("default"),state)))))));
        JsonValue weight=at(at(metadata,"animations"),"a").values().get("blend_weight");
        assertEquals(0,number(object(weight).values().get("historical_molang_type")));
        JsonObject curve=at(at(at(at(at(metadata,"animation_controllers"),"c"),"states"),"default"),"blend_transition");
        assertEquals(2,curve.values().size());
    }
    @Test void variantsShareImmutableSceneSubtreesAndChargeOnlyTheirOwnWrappers() throws Exception {
        Struct geo=s("bones",seq());Struct image=s("width",u(1),"height",u(1),"bytes",new Binary(new byte[4]));
        Struct root=s("identity",t("00000000000000000000000000000000"),"textures",map(new Entry(t("default"),yes(image))),
                "geos",map(new Entry(u(1),yes(geo))),"animations",map(new Entry(u(1),yes(file(animation("walk"))))));
        ResourceBudget budget=new ResourceBudget(1<<20);long singleCredits;
        try(var historical=new YsmHistoricalModel(1,root,List.of());var result=YsmModelImporter.importPayload(historical,DecodePolicy.localModel(),budget,new CancellationToken())) {
            singleCredits=budget.used();assertEquals(1,result.variants().size());
        }
        var fields=new LinkedHashMap<>(root.fields());fields.put("geos",map(new Entry(u(1),yes(geo)),new Entry(u(2),yes(geo))));
        long doubleCredits;
        try(var historical=new YsmHistoricalModel(1,new Struct(fields),List.of());var result=YsmModelImporter.importPayload(historical,DecodePolicy.localModel(),budget,new CancellationToken())) {
            JsonObject main=result.variants().get(0).document().orElseThrow().metadata(),arm=result.variants().get(1).document().orElseThrow().metadata();
            assertSame(main.values().get("animations"),arm.values().get("animations"));
            assertSame(main.values().get("animation_controllers"),arm.values().get("animation_controllers"));
            assertSame(main.values().get("historical_scene"),arm.values().get("historical_scene"));
            assertEquals("main",text(main.values().get("source_key")));assertEquals("arm",text(arm.values().get("source_key")));
            assertThrows(UnsupportedOperationException.class,()->at(main,"animations").values().clear());
            doubleCredits=budget.used();assertTrue(doubleCredits>singleCredits);assertTrue(doubleCredits-singleCredits<2000,"Only per-variant source wrapper should be charged");
        }
        assertEquals(0,budget.used());
        ResourceBudget shortBudget=new ResourceBudget(doubleCredits-1);
        try(var historical=new YsmHistoricalModel(1,new Struct(fields),List.of())) {
            assertThrows(IOException.class,()->YsmModelImporter.importPayload(historical,DecodePolicy.localModel(),shortBudget,new CancellationToken()));
            assertNotNull(historical.root());
        }
        assertEquals(0,shortBudget.used());
    }
}
