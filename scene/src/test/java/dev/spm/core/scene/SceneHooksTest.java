package dev.spm.core.scene;
import dev.spm.core.api.CancellationToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SceneHooksTest {
 private static List<MolangProgram> scripts(String... s) { return java.util.Arrays.stream(s).map(MolangProgram::compile).toList(); }
 private static ScriptBudget budget(){return new ScriptBudget(100000,128,new CancellationToken());}
 private static SceneInstance scene(MolangHooks h){return new SceneInstance(new SceneDefinition(new AnimationClip("test",1,true,Map.of(),List.of()),MolangProgram.compile("v.order=v.order*10+3"),Map.of(),Map.of(),h),60);}
 @Test void lifecycleIsTransactionalAndInitializationIsOncePerGeneration(){
 var h=new MolangHooks(scripts("v.order=1"),scripts("v.order=v.order*10+2"),scripts("v.order=v.order*10+4"),scripts("v.order=v.order*10+5; q.fail ? math.sqrt(-1) : 0"),List.of(),List.of());
 var s=scene(h);assertThrows(MolangException.class,()->s.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),budget()));assertTrue(s.typedVariables().isEmpty());
 var first=s.evaluate(0,0,new SceneInputs(Map.of()),budget());assertEquals(15,first.variables().get("order"));assertSame(first,s.evaluate(0,99,new SceneInputs(Map.of()),budget()));
 assertThrows(MolangException.class,()->s.evaluate(1,16_666_667,new SceneInputs(Map.of("fail",1.0)),budget()));assertEquals(MolangValue.number(15),s.typedVariables().get("order"));
 assertEquals(152345,s.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).variables().get("order"));s.reset();assertEquals(15,s.evaluate(0,0,new SceneInputs(Map.of()),budget()).variables().get("order"));}
 @Test void deferFlushUsesInitialLifoBatchAndRollsBackLateFailure(){
 var h=new MolangHooks(scripts("v.order=0; ysm.defer('a',1); ysm.defer('b',2)"),List.of(),List.of(),List.of(),scripts("v.order=v.order*10+args[0]; ysm.defer('nested',9); q.fail ? math.sqrt(-1) : 0"),List.of());
 var s=scene(h);assertThrows(MolangException.class,()->s.evaluate(0,0,new SceneInputs(Map.of("fail",1.0)),budget()));assertTrue(s.typedVariables().isEmpty());var f=s.evaluate(0,0,new SceneInputs(Map.of()),budget());assertEquals(21,f.variables().get("order"));assertTrue(f.effects().isEmpty());assertEquals(213,s.evaluate(1,16_666_667,new SceneInputs(Map.of()),budget()).variables().get("order"));}
 @Test void syncUsesFiniteFloat32AndRecursiveHandlersShareDepthBudget(){
 var h=new MolangHooks(scripts("ysm.sync(16777217,true,'text')"),List.of(),List.of(),List.of(),List.of(),scripts("v.value=args[0]; v.bool=args[1]; v.text=args[2]"));
 var local=scene(h).evaluate(0,0,new SceneInputs(Map.of(),MolangBindings.empty(),true,SceneInputs.SyncMode.LOCAL),budget());
 assertEquals(16777216,local.variables().get("value"));assertEquals(1,local.variables().get("bool"));assertEquals(1,local.variables().get("text"));
 var host=scene(h).evaluate(0,0,new SceneInputs(Map.of()),budget());assertEquals(MolangValue.number(16777216),host.effects().get(0).arguments().get(0));
 var overflow=new MolangHooks(scripts("ysm.sync(1e100)"),List.of(),List.of(),List.of(),List.of(),List.of());
 assertThrows(MolangException.class,()->scene(overflow).evaluate(0,0,new SceneInputs(Map.of()),budget()));
 var recursive=new MolangHooks(scripts("ysm.sync(1)"),List.of(),List.of(),List.of(),List.of(),scripts("ysm.sync(args[0])"));
 var owner=scene(recursive);var failure=assertThrows(MolangException.class,()->owner.evaluate(0,0,new SceneInputs(Map.of(),MolangBindings.empty(),true,SceneInputs.SyncMode.LOCAL),new ScriptBudget(10000,8,new CancellationToken())));
 assertEquals(MolangException.Reason.DEPTH,failure.reason());assertTrue(owner.typedVariables().isEmpty());
 }
}
