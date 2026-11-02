package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.scene.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins that the columnar track shape samples <em>bit-identically</em> to the object shape.
 *
 * <p>The two shapes are decoded by the same per-key routine, so this compares the raw float bits of
 * the resulting pose rather than a tolerance: a tolerance would hide exactly the drift the columnar
 * form is at risk of, such as a tick divided twice or a mode mapped to the wrong enum constant.
 *
 * <p>The second purpose is a regression lock. {@code PoseAnimationLibrary.track} treats any value that
 * is not a {@code JsonObject} as <em>one</em> keyframe at t=0, which is correct for the Bedrock
 * shorthand and catastrophic for a timeline: the animation would decode without any error and simply
 * be wrong. Every test here therefore also proves the columnar track was not collapsed to one key.
 */
class ColumnarTimelineSamplingTest {
    private static JsonNumber n(double value) { return new JsonNumber(value); }
    private static JsonString s(String value) { return new JsonString(value); }
    private static JsonArray vector(double x,double y,double z) { return new JsonArray(List.of(n(x),n(y),n(z))); }
    private static JsonObject o(Object... pairs) {
        Map<String,JsonValue> values=new LinkedHashMap<>();
        for(int index=0;index<pairs.length;index+=2) values.put((String)pairs[index],(JsonValue)pairs[index+1]);
        return new JsonObject(values);
    }
    private static ScriptBudget budget() { return new ScriptBudget(5_000_000,128,new CancellationToken()); }
    private static Skeleton skeleton() { return new Skeleton(List.of("root"),new int[] {-1},new float[3]); }
    private static MolangContext context() { return new MolangContext(Map.of(),Map.of(),Map.of(),budget()); }
    /** Wraps a bone position track into a decodable document; {@code length} is in seconds. */
    private static JsonObject document(JsonValue track,double length) {
        return o("animations",o("a",o("loop",new JsonBoolean(true),"animation_length",n(length),
                "bones",o("root",o("position",track)))));
    }
    /** Samples both shapes at {@code samples} evenly spaced times and compares raw float bits. */
    private static void assertSameBits(JsonValue objectTrack,JsonValue columnarTrack,double length,int samples) {
        var object=PoseAnimationLibrary.decode(document(objectTrack,length),skeleton(),budget());
        var columnar=PoseAnimationLibrary.decode(document(columnarTrack,length),skeleton(),budget());
        var bind=BonePose.identity(1);
        for(int index=0;index<=samples;index++) {
            double time=length*index/samples;
            float[] left=object.sample("a",time,context(),bind).translations();
            float[] right=columnar.sample("a",time,context(),bind).translations();
            for(int axis=0;axis<3;axis++) {
                assertEquals(Float.floatToRawIntBits(left[axis]),Float.floatToRawIntBits(right[axis]),
                        "axis "+axis+" at t="+time+" must match bit for bit");
            }
        }
    }
    /** The same keys as an object-form timestamp map, which is what the columnar node replaces. */
    private static JsonValue objectForm(float[] ticks,float[][] pre,float[] modes) {
        var values=new LinkedHashMap<String,JsonValue>();
        for(int index=0;index<ticks.length;index++) {
            String time=BigDecimalText(ticks[index]/20.0);
            JsonValue value=new JsonVector3(pre[index][0],pre[index][1],pre[index][2]);
            if(modes!=null && modes[index]!=0) value=o("pre",value,"post",value,"lerp_mode",s(mode(modes[index])));
            values.put(time,value);
        }
        return new JsonObject(values);
    }
    /** The decimal text the projection would emit for a tick, without an exponent or trailing zeros. */
    private static String BigDecimalText(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    private static String mode(float code) {
        return switch((int)code) {
            case 1 -> "step";
            case 2 -> "catmullrom";
            default -> "linear";
        };
    }

    @Test void linearMultiKeyColumnarTrackSamplesBitIdenticallyToItsObjectForm() {
        float[] ticks={0,7,20,53,120};
        float[][] pre={{0,0,0},{4,8,0},{16,0,32},{-8,3,1},{9,9,9}};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[0],new float[0],new byte[0]);
        assertSameBits(objectForm(ticks,pre,null),columnar,6,200);
        // The regression lock: a timeline decoded as one t=0 constant would repeat its first vector at
        // every time, so three differently spaced samples must differ from each other and from the first.
        var library=PoseAnimationLibrary.decode(document(columnar,6),skeleton(),budget());
        float first=library.sample("a",0,context(),BonePose.identity(1)).translations()[0];
        float middle=library.sample("a",3.5,context(),BonePose.identity(1)).translations()[0];
        float last=library.sample("a",5.9,context(),BonePose.identity(1)).translations()[0];
        assertNotEquals(first,middle,"a timeline must not decode as one constant keyframe");
        assertNotEquals(middle,last,"a timeline must not decode as one constant keyframe");
    }

    @Test void stepAndCatmullRomSparseKeysSampleBitIdenticallyToTheirObjectForm() {
        float[] ticks={0,20,40,60};
        float[][] pre={{0,0,0},{16,0,0},{0,0,0},{32,4,0}};
        // Middle two keys are curved; the first and last stay plain linear shorthand.
        float[] modes={0,2,2,0};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[]{1,2},flatten(new float[][] {pre[1],pre[2]}),
                new byte[]{JsonTimeline.CATMULL_ROM,JsonTimeline.CATMULL_ROM});
        assertSameBits(objectForm(ticks,pre,modes),columnar,5,200);
    }

    @Test void stepModeSparseKeySamplesBitIdenticallyToItsObjectForm() {
        float[] ticks={0,20,40};
        float[][] pre={{0,0,0},{16,0,0},{0,0,0}};
        float[] modes={0,1,0};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[]{1},flatten(new float[][] {pre[1]}),
                new byte[]{JsonTimeline.STEP});
        assertSameBits(objectForm(ticks,pre,modes),columnar,5,120);
    }

    @Test void distinctPostVectorSamplesBitIdenticallyToThePrePostObjectForm() {
        float[] ticks={0,20,40};
        float[][] pre={{0,0,0},{0,0,0},{0,0,0}};
        float[][] post={{0f,0f,0f},{16f,32f,-8f},{0f,0f,0f}};
        // The object form authors pre and post explicitly on the middle key, which is the discontinuity
        // case: pre is reached from the left segment, post from the right one.
        var object=o("0",vector(0,0,0),
                BigDecimalText(1.0),o("pre",vector(0,0,0),"post",vector(16,32,-8),"lerp_mode",s("linear")),
                BigDecimalText(2.0),vector(0,0,0));
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[]{1},flatten(new float[][] {post[1]}),new byte[]{JsonTimeline.LINEAR});
        assertSameBits(object,columnar,4,200);
    }

    @Test void exactKeyTimesAndOutOfRangeTimesAreIncludedInTheComparison() {
        float[] ticks={0,20,40};
        float[][] pre={{0,0,0},{16,8,4},{32,0,0}};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[0],new float[0],new byte[0]);
        var objectTrack=objectForm(ticks,pre,null);
        var object=PoseAnimationLibrary.decode(document(objectTrack,2),skeleton(),budget());
        var decoded=PoseAnimationLibrary.decode(document(columnar,2),skeleton(),budget());
        var bind=BonePose.identity(1);
        for(double time:new double[] {0,1,2,0.05,0.999,1.001,1.999,2.5,10,-0}) {
            float[] left=object.sample("a",Math.max(time,0),context(),bind).translations();
            float[] right=decoded.sample("a",Math.max(time,0),context(),bind).translations();
            for(int axis=0;axis<3;axis++) {
                assertEquals(Float.floatToRawIntBits(left[axis]),Float.floatToRawIntBits(right[axis]),
                        "axis "+axis+" at t="+time);
            }
        }
    }

    @Test void columnarTrackIsNeverCollapsedToOneKeyframeAtTimeZero() {
        float[] ticks={0,20,40,80};
        float[][] pre={{0,0,0},{100,0,0},{200,0,0},{400,0,0}};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[0],new float[0],new byte[0]);
        var library=PoseAnimationLibrary.decode(document(columnar,4),skeleton(),budget());
        var bind=BonePose.identity(1);
        // A single t=0 constant key would make every sample identical; distinct authored keys cannot.
        float atZero=library.sample("a",0,context(),bind).translations()[0];
        float atTwo=library.sample("a",2,context(),bind).translations()[0];
        float atFour=library.sample("a",3.9,context(),bind).translations()[0];
        assertNotEquals(atZero,atTwo,"a timeline must not decode as one constant keyframe");
        assertNotEquals(atTwo,atFour,"a timeline must not decode as one constant keyframe");
        // And the values must be the ones the object form produces, not merely distinct.
        var object=PoseAnimationLibrary.decode(document(objectForm(ticks,pre,null),4),skeleton(),budget());
        assertEquals(Float.floatToRawIntBits(object.sample("a",3.9,context(),bind).translations()[0]),
                Float.floatToRawIntBits(atFour));
    }

    @Test void repeatedTicksInAColumnarNodeAreRejectedLikeTheObjectForm() {
        // The projection never emits a repeated tick, and a hand-built node that holds one must be
        // refused exactly as the object form refuses it, rather than silently keeping one keyframe.
        var repeated=o("0",vector(0,0,0),"0.0",vector(16,0,0));
        assertThrows(MolangException.class,
                ()->PoseAnimationLibrary.decode(document(repeated,1),skeleton(),budget()));
        // The node itself refuses the state the decoder would have to reject, so a timeline can never
        // carry two keyframes at one time and quietly lose one.
        assertThrows(IllegalArgumentException.class,
                ()->new JsonTimeline(new float[] {20,20},new float[] {0,0,0,1,1,1},new int[0],new float[0],new byte[0]));
        // Wire order is not sorted order, so distinctness must not be enforced by monotonicity alone.
        assertThrows(IllegalArgumentException.class,
                ()->new JsonTimeline(new float[] {40,0,40},new float[] {0,0,0,1,1,1,2,2,2},new int[0],new float[0],
                        new byte[0]));
        // Non-adjacent duplicates are distinct from the unsorted case above and must also be refused.
        assertThrows(IllegalArgumentException.class,
                ()->new JsonTimeline(new float[] {40,0,20,40},new float[] {0,0,0,1,1,1,2,2,2,3,3,3},new int[0],
                        new float[0],new byte[0]));
        // The same ticks in a different order are legal: distinctness is not monotonicity.
        assertEquals(3,new JsonTimeline(new float[] {40,0,20},new float[] {0,0,0,1,1,1,2,2,2},new int[0],new float[0],
                new byte[0]).keyCount());
    }

    @Test void keyCapAppliesToTheColumnarFormExactlyAsToTheObjectForm() {
        int count=70_000;
        float[] ticks=new float[count];
        float[] components=new float[3*count];
        for(int index=0;index<count;index++) ticks[index]=index;
        var oversized=new JsonTimeline(ticks,components,new int[0],new float[0],new byte[0]);
        assertThrows(MolangException.class,
                ()->PoseAnimationLibrary.decode(document(oversized,4000),skeleton(),budget()),
                "the 65536 key cap must not be bypassed by the compact shape");
    }

    @Test void columnarDecodeSpendsTheSameBudgetStepsAsTheObjectForm() {
        float[] ticks={0,20,40};
        float[][] pre={{0,0,0},{16,8,4},{32,0,0}};
        var columnar=new JsonTimeline(ticks,flatten(pre),new int[0],new float[0],new byte[0]);
        var objectTrack=objectForm(ticks,pre,null);
        long objectUsed=steps(objectTrack),columnarUsed=steps(columnar);
        assertEquals(objectUsed,columnarUsed,"the compact shape must not become a budget shortcut");
    }
    private static long steps(JsonValue track) {
        var budget=new ScriptBudget(1_000_000,128,new CancellationToken());
        PoseAnimationLibrary.decode(document(track,2),skeleton(),budget);
        return 1_000_000-budget.remainingSteps();
    }

    private static float[] flatten(float[][] vectors) {
        var result=new float[vectors.length*3];
        for(int index=0;index<vectors.length;index++) {
            result[index*3]=vectors[index][0];result[index*3+1]=vectors[index][1];result[index*3+2]=vectors[index][2];
        }
        return result;
    }
}
