package dev.spm.core.verification;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TypedMolangTest {
    private static MolangContext context() {
        return new MolangContext(Map.of(),Map.of(),Map.of(),new ScriptBudget(10000,128,new CancellationToken()));
    }
    @Test void stringsRetainCaseAndTypeThroughVariablesFunctionsAndCoalescing() {
        var c=context();
        assertEquals(MolangValue.text("Bone_X"),MolangProgram.compile("v.name='Bone_X'; v.name ?? 'other'").evaluateValue(c));
        assertEquals(MolangValue.text("Bone_X"),c.typedVariables().get("name"));
        assertEquals(1,MolangProgram.compile("'0'").evaluate(c));
        assertEquals(1,MolangProgram.compile("'' ? 1 : 2").evaluate(c));
        assertEquals(1,MolangProgram.compile("'Bone_X' != 'bone_x'").evaluate(c));
        assertEquals(1,MolangProgram.compile("'a' == 1").evaluate(c));
        assertEquals(1,MolangProgram.compile("'a' == true").evaluate(c));
        assertEquals(0,MolangProgram.compile("'a' == (0 == 0)").evaluate(c));
        assertThrows(MolangException.class,()->MolangProgram.compile("'unterminated"));
    }
    @Test void typedHostQueriesAreSnapshotsAndMissingCallsAreExplicit() {
        var call=new MolangBindings.Call("query.position_delta",List.of(MolangValue.number(1)));
        var bindings=new MolangBindings(Map.of("tlm.name",MolangValue.text("Alice")),Map.of(call,MolangValue.number(2)));
        var c=new MolangContext(new SceneInputs(Map.of(),bindings),Map.of(),Map.of(),context().budget());
        assertEquals(2,MolangProgram.compile("q.position_delta(1)").evaluate(c));
        assertEquals(MolangValue.text("Alice"),MolangProgram.compile("tlm.name").evaluateValue(c));
        assertThrows(MolangException.class,()->MolangProgram.compile("q.position_delta(0)").evaluate(c));
        assertThrows(MolangException.class,()->MolangProgram.compile("ysm.unknown('id')").evaluate(c));
    }
    @Test void userFunctionAliasesPreserveTypedArguments() {
        var c=new MolangContext(Map.of(),Map.of(),Map.of("echo",MolangProgram.compile("c.arg0")),context().budget());
        assertEquals(MolangValue.text("Key"),MolangProgram.compile("fn.echo('Key')").evaluateValue(c));
        assertEquals(MolangValue.text("Key"),MolangProgram.compile("f.echo('Key')").evaluateValue(c));
    }
    @Test void referenceFunctionArgumentsMissingFunctionsAndLazyPhysicsKeysArePreserved() {
        var c=new MolangContext(Map.of(),Map.of(),Map.of("echo",MolangProgram.compile("args[0]")),context().budget());
        assertEquals(MolangValue.text("Key"),MolangProgram.compile("fn.echo('Key')").evaluateValue(c));
        assertEquals(7,MolangProgram.compile("fn.missing() ?? 7").evaluate(c));
        assertEquals(0,MolangProgram.compile("ysm.first_order('',math.sqrt(-1))").evaluate(c));
        assertEquals(0,MolangProgram.compile("ysm.second_order(1,math.sqrt(-1))").evaluate(c));
    }
    @Test void typedPropertiesHaveOneValueAcrossMemberAccessAndFunctionArguments() {
        var bindings=new MolangBindings(Map.of("query.vector",MolangValue.struct(Map.of("x",MolangValue.number(1)))),Map.of());
        var c=new MolangContext(new SceneInputs(Map.of(),bindings),Map.of(),Map.of("read",MolangProgram.compile("c.arg0.x")),context().budget());
        assertEquals(1,MolangProgram.compile("q.vector.x").evaluate(c));
        assertEquals(1,MolangProgram.compile("t.s=q.vector; t.s.x").evaluate(c));
        assertEquals(4,MolangProgram.compile("t.s=q.vector; t.s.x=4; (t.s).x").evaluate(c));
        assertEquals(2,MolangProgram.compile("v.s=q.vector; v.s.x=2; (v.s).x").evaluate(c));
        assertEquals(2,MolangProgram.compile("fn.read(v.s)").evaluate(c));
        assertEquals(7,MolangProgram.compile("v.s.x=q.absent; (v.s).x ?? 7").evaluate(c));
    }
    @Test void typedStateSurvivesFramesAndFailedFramesRollback() {
        var clip=new AnimationClip("test",1,true,Map.of(),List.of());
        var scene=new SceneInstance(new SceneDefinition(clip,MolangProgram.compile("v.name=q.clear ? 'New' : (v.name ?? 'Old')"),
                Map.of("out",MolangProgram.compile("q.fail ? math.sqrt(-1) : (v.name == 'Old')")),Map.of()),60);
        scene.evaluate(0,0,new SceneInputs(Map.of()),context().budget());
        scene.evaluate(1,16_666_667,new SceneInputs(Map.of()),context().budget());
        assertEquals(MolangValue.text("Old"),scene.typedVariables().get("name"));
        assertThrows(MolangException.class,()->scene.evaluate(2,33_333_334,new SceneInputs(Map.of("clear",1.0,"fail",1.0)),context().budget()));
        assertEquals(1,scene.evaluate(2,33_333_334,new SceneInputs(Map.of()),context().budget()).channels().get("out"));
        assertEquals(MolangValue.text("Old"),scene.typedVariables().get("name"));
    }
    @Test void physicsAdvancesOncePerTickAndFailedFramesDoNotAdvanceTheCommittedFilter() {
        var clip=new AnimationClip("test",1,true,Map.of(),List.of());
        var scene=new SceneInstance(new SceneDefinition(clip,MolangProgram.compile("v.x=ysm.first_order('K',q.input,1)"),
                Map.of("out",MolangProgram.compile("q.fail ? math.sqrt(-1) : v.x")),Map.of()),60);
        var inputs=new SceneInputs(Map.of("input",12.0));
        scene.evaluate(0,0,inputs,context().budget());
        var first=scene.evaluate(1,16_666_667,inputs,context().budget());
        assertEquals(12,first.channels().get("out"));
        assertSame(first,scene.evaluate(1,16_666_668,inputs,context().budget()));
        assertEquals(0.2,scene.evaluate(2,33_333_334,inputs,context().budget()).channels().get("out"),1e-12);
        assertThrows(MolangException.class,()->scene.evaluate(3,50_000_001,
                new SceneInputs(Map.of("input",12.0,"fail",1.0)),context().budget()));
        assertEquals(0.2+11.8/60,scene.evaluate(3,50_000_001,inputs,context().budget()).channels().get("out"),1e-12);
    }
}
