package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.render.cpu.BoneEvaluator;
import dev.spm.core.scene.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AnimationPoseTest {
    private static JsonNumber n(double v) { return new JsonNumber(BigDecimal.valueOf(v)); }
    private static JsonString s(String v) { return new JsonString(v); }
    private static JsonArray v(JsonValue... values) { return new JsonArray(List.of(values)); }
    private static JsonObject o(Object... pairs) {
        Map<String, JsonValue> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], (JsonValue) pairs[i + 1]);
        return new JsonObject(values);
    }
    private static ScriptBudget budget() { return new ScriptBudget(500000, 128, new CancellationToken()); }
    private static Skeleton skeleton() { return new Skeleton(List.of("root"), new int[] {-1}, new float[3]); }
    private static BonePose bind() {
        return new BonePose(new float[] {0.1f, 0.2f, 0.3f}, new float[] {2, 3, 4}, new float[] {1, 2, 3},
                new boolean[] {false}, new boolean[] {true}, new int[] {7}, new int[] {0x80402010});
    }
    private static MolangContext context() { return new MolangContext(Map.of("offset", 16.0), Map.of(), Map.of(), budget()); }
    @Test void constantAndMolangTracksProduceFinalPoseAndPreserveMaterialMetadata() {
        var json = o("animations", o("a", o("loop", new JsonBoolean(true), "animation_length", n(1), "bones", o("root",
                o("position", v(n(16), s("q.offset"), n(0)), "rotation", v(n(90), n(0), n(45)), "scale", n(2))))));
        var library = PoseAnimationLibrary.decode(json, skeleton(), budget());
        BonePose pose = library.sample("a", 0.5, context(), bind());
        assertArrayEquals(new float[] {1, 4, 4}, pose.translations(), 1e-6f);
        assertArrayEquals(new float[] {0.1f-(float)(Math.PI/2), 0.2f, 0.3f+(float)(Math.PI/4)}, pose.rotations(), 1e-6f);
        assertArrayEquals(new float[] {2,4,6}, pose.scales(), 1e-6f);
        assertArrayEquals(bind().childrenHidden(), pose.childrenHidden());
        assertArrayEquals(bind().glow(), pose.glow());
        assertArrayEquals(bind().colorsRgba(), pose.colorsRgba());
        assertArrayEquals(pose.rotations(), library.sample("a", 0.5, context(), bind()).rotations());
        assertTrue(BoneEvaluator.evaluate(skeleton(), pose).visible()[0]);
    }
    @Test void timestampPrePostDiscontinuityLinearStepAndCatmullRomAreEvaluated() {
        var linear = o("0", v(n(0),n(0),n(0)), "1", o("pre", v(n(16),n(0),n(0)), "post", v(n(32),n(0),n(0))),
                "2", v(n(48),n(0),n(0)));
        var step = o("0", o("post", n(0), "lerp_mode", s("step")), "1", n(16));
        var catmull = o("0", n(0), "1", o("post", n(16), "lerp_mode", s("catmull_rom")),
                "2", o("post", n(0), "lerp_mode", s("catmull_rom")), "3", n(0));
        var library = PoseAnimationLibrary.decode(o("animations", o("linear", o("animation_length",n(3),"bones",o("root",o("position",linear))),
                "step",o("animation_length",n(2),"bones",o("root",o("position",step))),
                "cat",o("animation_length",n(3),"bones",o("root",o("position",catmull))))), skeleton(), budget());
        BonePose neutral = BonePose.identity(1);
        assertEquals(-0.5f, library.sample("linear",0.5,context(),neutral).translations()[0], 1e-6);
        assertEquals(-2, library.sample("linear",1,context(),neutral).translations()[0]);
        assertEquals(-2.5, library.sample("linear",1.5,context(),neutral).translations()[0]);
        assertEquals(0, library.sample("step",0.5,context(),neutral).translations()[0]);
        assertEquals(-1, library.sample("step",1,context(),neutral).translations()[0]);
        assertEquals(-0.5625, library.sample("cat",1.5,context(),neutral).translations()[0], 1e-6);
    }
    @Test void loopHoldAndOnceHaveDistinctEndBehavior() {
        var track=o("0",n(0),"1",n(16));
        var bone=o("root",o("position",track));
        var library=PoseAnimationLibrary.decode(o("animations",o("loop",o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",bone),
                "hold",o("loop",s("hold_on_last_frame"),"animation_length",n(1),"bones",bone),
                "once",o("animation_length",n(1),"bones",bone))),skeleton(),budget());
        assertEquals(0, library.sample("loop",2,context(),BonePose.identity(1)).translations()[0]);
        assertEquals(-1, library.sample("hold",2,context(),BonePose.identity(1)).translations()[0]);
        assertEquals(0, library.sample("once",2,context(),BonePose.identity(1)).translations()[0]);
        assertEquals(-1, library.sample("once",1,context(),BonePose.identity(1)).translations()[0]);
    }
    @Test void playerSnapshotsEffectsAndTimelineAreNotRepeatedAcrossPassesOrSamePlaySelection() {
        var clip=o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",o("root",o("position",s("q.anim_time*16"))),
                "sound_effects",o("0",o("effect",s("step")),"0.5",s("half")),"timeline",o("0",s("v.entries=v.entries+1")));
        var library=PoseAnimationLibrary.decode(o("animations",o("a",clip)),skeleton(),budget());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0.5);
        player.play("a");
        var first=player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        player.play("a");
        var second=player.evaluate(1,500_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(-0.5,second.pose().translations()[0]);
        assertEquals(1,second.variables().get("entries"));
        assertSame(second,player.evaluate(1,900_000_000,new SceneInputs(Map.of()),budget()));
        var cursor=new EffectCursor();
        assertEquals(1,cursor.claim(first).size());
        assertEquals(1,cursor.claim(second).size());
        assertTrue(cursor.claim(second).isEmpty());
        var looped=player.evaluate(2,1_000_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(2,looped.variables().get("entries"));
        assertEquals(0,looped.pose().translations()[0]);
    }
    @Test void channelsStopDrivingAndResetToBindWhileNewChannelsRemainDriven() {
        var library=PoseAnimationLibrary.decode(o("animations",o("a",o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",o("root",o("position",v(n(16),n(0),n(0)),"rotation",v(n(0),n(0),n(170))))),
                "b",o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",o("root",o("scale",n(2)))))),skeleton(),budget());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,1);
        player.play("a"); player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        player.play("b");
        player.evaluate(1,0,new SceneInputs(Map.of()),budget());
        var halfway=player.evaluate(2,500_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(-0.5,halfway.pose().translations()[0],1e-6);
        assertEquals(Math.toRadians(85),halfway.pose().rotations()[2],1e-5);
        assertArrayEquals(new float[]{2,2,2},halfway.pose().scales());
        var reset=player.evaluate(3,1_000_000_000,new SceneInputs(Map.of()),budget());
        assertArrayEquals(new float[3],reset.pose().translations());
        assertArrayEquals(new float[3],reset.pose().rotations());
    }
    @Test void quaternionBlendTakesShortArcAndBudgetFailureDoesNotConsumePlayCommand() {
        var library=PoseAnimationLibrary.decode(o("animations",o("a",o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",o("root",o("rotation",v(n(0),n(0),n(170))))),
                "b",o("loop",new JsonBoolean(true),"animation_length",n(1),"bones",o("root",o("rotation",v(n(0),n(0),n(-170))))))),skeleton(),budget());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0);
        player.play("a"); player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        player.play("b",1);
        assertThrows(MolangException.class,()->player.evaluate(1,0,new SceneInputs(Map.of()),new ScriptBudget(1,128,new CancellationToken())));
        player.evaluate(1,0,new SceneInputs(Map.of()),budget());
        var halfway=player.evaluate(2,500_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(Math.PI,Math.abs(halfway.pose().rotations()[2]),1e-5);
    }
    @Test void unsupportedDataUnknownBonesDuplicateTimesAndCancelledDecodeReject() {
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(o("animations",o("bad",o("bones",o("unknown",o("rotation",n(0)))))),skeleton(),budget()));
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(o("animations",o("bad",o("bones",o("root",o("rotation",o("0",n(0),"0.0",n(1))))))),skeleton(),budget()));
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(o("animations",o("bad",o("particle_effects",o()))),skeleton(),budget()));
        CancellationToken token=new CancellationToken(); token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->PoseAnimationLibrary.decode(o("animations",o()),skeleton(),new ScriptBudget(100,128,token)));
    }
    @Test void decimalLoopBoundariesAreDeliveredOnceAndStaleFramesCannotReplay() {
        var definition=o("loop",new JsonBoolean(true),"animation_length",n(0.1),
                "bones",o("root",o("position",n(0))),"sound_effects",o("0",s("tick")));
        var library=PoseAnimationLibrary.decode(o("animations",o("a",definition)),skeleton(),budget());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,0);
        player.play("a");
        var initial=player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var interval=player.evaluate(1,300_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(3,interval.effects().size());
        var next=player.evaluate(2,400_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(1,next.effects().size());
        EffectCursor cursor=new EffectCursor();
        cursor.claim(interval); cursor.claim(next);
        assertTrue(cursor.claim(initial).isEmpty());
        assertEquals(4,next.effects().get(0).sequence());
        assertThrows(IllegalArgumentException.class,()->player.evaluate(1,400_000_000,new SceneInputs(Map.of()),budget()));
    }
    @Test void oncePreservesItsEndpointBeforeResetAndStopResetsWithoutRestartingEffects() {
        var library=PoseAnimationLibrary.decode(o("animations",o("a",o("animation_length",n(0.5),
                "bones",o("root",o("position",v(n(16),n(0),n(0))))))),skeleton(),budget());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),120,1);
        player.play("a"); player.evaluate(0,0,new SceneInputs(Map.of()),budget());
        var endpoint=player.evaluate(1,500_000_000,new SceneInputs(Map.of()),budget());
        assertTrue(endpoint.finished()); assertEquals(-1,endpoint.pose().translations()[0]);
        var fading=player.evaluate(2,750_000_000,new SceneInputs(Map.of()),budget());
        assertEquals(-0.75,fading.pose().translations()[0],1e-6);
        player.play("a");
        assertEquals(-0.5,player.evaluate(3,1_000_000_000,new SceneInputs(Map.of()),budget()).pose().translations()[0],1e-6);
        player.restart("a",0);
        assertEquals(-1,player.evaluate(4,1_000_000_000,new SceneInputs(Map.of()),budget()).pose().translations()[0]);
        player.stop();
        var stopped=player.evaluate(5,1_500_000_000,new SceneInputs(Map.of()),budget());
        assertEquals("",stopped.animation()); assertEquals(-0.5,stopped.pose().translations()[0],1e-6);
    }
    @Test void tinyLoopDurationsAndOversizedTimestampTextRejectBeforeRuntime() {
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(o("animations",o("a",o("loop",new JsonBoolean(true),
                "animation_length",n(1e-50),"bones",o("root",o("position",n(0)))))),skeleton(),budget()));
        assertThrows(MolangException.class,()->PoseAnimationLibrary.decode(o("animations",o("a",o("bones",o("root",
                o("position",o("0."+"0".repeat(10000),n(1))))))),skeleton(),budget()));
    }
    @Test void emptySkeletonSamplingStillChecksCancellation() {
        Skeleton empty=new Skeleton(List.of(),new int[0],new float[0]);
        var library=PoseAnimationLibrary.decode(o("animations",o("a",o("animation_length",n(1)))),empty,budget());
        CancellationToken token=new CancellationToken(); token.cancel();
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),new ScriptBudget(100,128,token));
        assertThrows(java.util.concurrent.CancellationException.class,()->library.sample("a",0,context,BonePose.identity(0)));
    }
}
