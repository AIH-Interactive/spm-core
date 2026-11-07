package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnimationDecodeLimitsTest {
    private static JsonObject o(Object... pairs) {
        var values = new LinkedHashMap<String,JsonValue>();
        for(int i=0;i<pairs.length;i+=2) values.put((String)pairs[i],(JsonValue)pairs[i+1]);
        return new JsonObject(values);
    }
    private static ScriptBudget budget() { return new ScriptBudget(20_000_000,128,new CancellationToken()); }
    private static ModelDocument model(String format, JsonValue channel) {
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        return new ModelDocument("limits",format,skeleton,List.of(),BonePose.identity(1),List.of(),List.of(),
                o("animations",o("a",o("bones",o("root",o("position",channel))))));
    }
    private static void rejected(Runnable action) { assertThrows(MolangException.class,action::run); }

    @Test void namedProfilesHaveExplicitCapsAndValidateCustomLimits() {
        assertEquals(new AnimationDecodeLimits(65_536,8192,65_536,128,128),AnimationDecodeLimits.genericDefaults());
        assertEquals(new AnimationDecodeLimits(131_072,8192,131_072,4096,128),AnimationDecodeLimits.historicalYsm());
        assertEquals(new AnimationDecodeLimits(1_048_576,32_768,131_072,4096,128,33_554_432),AnimationDecodeLimits.localYsm());
        assertEquals(new AnimationDecodeLimits(65_536,16_384,65_536,128,32),AnimationDecodeLimits.peer());
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(0,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,0,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,0,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,1,0,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,1,1,129));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1_048_577,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,32_769,1,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,131_073,1,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,1,4097,1));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,1,1,1,0));
        assertThrows(IllegalArgumentException.class,()->new AnimationDecodeLimits(1,1,1,1,1,33_554_433));
    }

    @Test void explicitKeyLimitsAreIndependentOfTheFormatAndDefaultStaysBounded() {
        var keys=new LinkedHashMap<String,JsonValue>();
        for(int i=0;i<131_073;i++) keys.put(Integer.toString(i),new JsonNumber(0));
        var ysm=model("ysm-raw-v3",new JsonObject(keys));
        rejected(()->PoseAnimationLibrary.fromModel(ysm,budget()));
        rejected(()->PoseAnimationLibrary.fromModel(ysm,budget(),AnimationDecodeLimits.peer()));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(ysm,budget(),AnimationDecodeLimits.localYsm()).names());
        var generic=model("bedrock",new JsonObject(keys));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(generic,budget(),AnimationDecodeLimits.localYsm()).names());
    }

    @Test void localTokenCapacityDoesNotSilentlyChangeDefaultOrPeerDepth() {
        String manyStatements="v.x=1;".repeat(2200)+"return 1;";
        var model=model("ysm-raw-v3",new JsonString(manyStatements));
        rejected(()->PoseAnimationLibrary.fromModel(model,budget()));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(model,budget(),AnimationDecodeLimits.localYsm()).names());
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(model,budget(),AnimationDecodeLimits.peer()).names());
        var deep=model("ysm-raw-v3",new JsonString("-".repeat(40)+"1"));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(deep,budget(),AnimationDecodeLimits.localYsm()).names());
        rejected(()->PoseAnimationLibrary.fromModel(deep,budget(),AnimationDecodeLimits.peer()));
    }

    @Test void explicitSourceAndEventBoundsDoNotDependOnYsmFormat() {
        var longSource=model("ysm-raw-v3",new JsonString("1"+" ".repeat(65_536)));
        rejected(()->PoseAnimationLibrary.fromModel(longSource,budget(),AnimationDecodeLimits.peer()));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(longSource,budget(),AnimationDecodeLimits.localYsm()).names());
        var source=model("ysm-raw-v3",new JsonNumber(0));
        var instructions=new ArrayList<JsonValue>();
        for(int i=0;i<129;i++) instructions.add(new JsonString("v.x=1"));
        var event=new ModelDocument(source.identity(),source.sourceFormat(),source.skeleton(),List.of(),source.bindPose(),List.of(),List.of(),
                o("animations",o("a",o("timeline",o("0",new JsonArray(instructions))))));
        rejected(()->PoseAnimationLibrary.fromModel(event,budget(),AnimationDecodeLimits.peer()));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(event,budget(),AnimationDecodeLimits.localYsm()).names());
        var malformed=model("ysm-raw-v3",new JsonString("q.foo( ;"));
        rejected(()->PoseAnimationLibrary.fromModel(malformed,budget(),AnimationDecodeLimits.localYsm()));
    }

    @Test void localProfileRetainsAggregateExpressionCap() {
        var expressions=new ArrayList<JsonValue>();
        String source="1"+" ".repeat(65_535);
        for(int i=0;i<513;i++) expressions.add(new JsonString(source));
        var base=model("ysm-raw-v3",new JsonNumber(0));
        var oversized=new ModelDocument(base.identity(),base.sourceFormat(),base.skeleton(),List.of(),base.bindPose(),List.of(),List.of(),
                o("animations",o("a",o("timeline",o("0",new JsonArray(expressions))))));
        assertEquals(MolangException.Reason.CAPACITY,assertThrows(MolangException.class,
                ()->PoseAnimationLibrary.fromModel(oversized,budget(),AnimationDecodeLimits.localYsm())).reason());
    }

    @Test void largerAggregateLocalAllowanceDoesNotRelaxPeerOrDefault() {
        var expressions=new ArrayList<JsonValue>();
        String source="1"+" ".repeat(65_535);
        for(int i=0;i<65;i++) expressions.add(new JsonString(source));
        var base=model("ysm-raw-v3",new JsonNumber(0));
        var aggregate=new ModelDocument(base.identity(),base.sourceFormat(),base.skeleton(),List.of(),base.bindPose(),List.of(),List.of(),
                o("animations",o("a",o("timeline",o("0",new JsonArray(expressions))))));
        assertEquals(Set.of("a"),PoseAnimationLibrary.fromModel(aggregate,budget(),AnimationDecodeLimits.localYsm()).names());
        assertEquals(MolangException.Reason.CAPACITY,assertThrows(MolangException.class,
                ()->PoseAnimationLibrary.fromModel(aggregate,budget(),AnimationDecodeLimits.peer())).reason());
        assertEquals(MolangException.Reason.CAPACITY,assertThrows(MolangException.class,
                ()->PoseAnimationLibrary.fromModel(aggregate,budget())).reason());
    }

    @Test void corpusFallbackClassifiesAuthoredErrorsWithoutHidingCapacityOrMissingCapabilities() {
        assertTrue(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.SYNTAX,"Expected ')' at character 5")));
        assertTrue(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.UNSUPPORTED,"Unsupported name: [ at character 0")));
        assertTrue(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.UNSUPPORTED,"Assignment requires variable or temp at character 7")));
        assertFalse(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.SYNTAX,"Token count exceeds 32768 at character 5")));
        assertFalse(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.SYNTAX,"Parser nesting exceeds 128 at character 5")));
        assertFalse(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.UNSUPPORTED,"Unsupported name: future.namespace at character 0")));
        assertFalse(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.UNKNOWN_FUNCTION,"Host snapshot has no call: query.position")));
        assertFalse(RealCorpusSkinningTest.authoredInvalid(new MolangException(MolangException.Reason.CAPACITY,"At most 1048576 keys")));
    }
}
