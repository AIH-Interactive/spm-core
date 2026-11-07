package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.scene.SceneInputs.SyncMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.spm.core.scene.PoseControllerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MolangHooksTest {
    private static List<MolangProgram> scripts(String... sources) {
        return java.util.Arrays.stream(sources).map(MolangProgram::compile).toList();
    }
    private static MolangHooks hooks(List<MolangProgram> init,List<MolangProgram> before,List<MolangProgram> after,
                                      List<MolangProgram> frame,List<MolangProgram> defer,List<MolangProgram> sync) {
        return new MolangHooks(init,before,after,frame,defer,sync);
    }
    private static PoseAnimationPlayer player(MolangHooks hooks) {
        return new PoseAnimationPlayer(library(obj("a",obj())),BonePose.identity(1),12,0,7,Map.of(),hooks);
    }
    private static PoseFrame evaluate(PoseAnimationPlayer player,long frame,long nanos,Map<String,Double> queries,SyncMode mode) {
        return player.evaluate(frame,nanos,new SceneInputs(queries,MolangBindings.empty(),true,mode),budget());
    }
    @Test void hooksRunInCandidateOrderAndAreNotRepeatedByMultipass() {
        var hooks=hooks(scripts("v.order=1"),scripts("v.order=v.order*10+2"),scripts("v.order=v.order*10+3"),
                scripts("v.order=v.order*10+4"),List.of(),List.of());
        var player=player(hooks);
        var first=evaluate(player,0,0,Map.of(),SyncMode.HOST);
        assertEquals(14,first.variables().get("order"));
        assertSame(first,evaluate(player,0,1_000_000_000,Map.of(),SyncMode.HOST));
        assertEquals(14234,evaluate(player,1,16_666_667,Map.of(),SyncMode.HOST).variables().get("order"));
        player.reset();
        assertEquals(14,evaluate(player,0,0,Map.of(),SyncMode.HOST).variables().get("order"));
    }
    @Test void deferredInitialBatchIsLifoAndNestedSubmissionsDoNotEscape() {
        var hooks=hooks(scripts("ysm.defer('ignored',1); ysm.defer('ignored',2)"),List.of(),List.of(),List.of(),
                scripts("v.order=(v.order??0)*10+args[0]; ysm.defer('nested',9)","v.order=v.order*10+args[0]+2"),List.of());
        var player=player(hooks);
        var frame=evaluate(player,0,0,Map.of(),SyncMode.HOST);
        assertEquals(2413,frame.variables().get("order"));
        assertTrue(frame.effects().isEmpty());
        assertEquals(2413,evaluate(player,1,16_666_667,Map.of(),SyncMode.HOST).variables().get("order"));
    }
    @Test void syncModesDistinguishCandidateExecutionHostEffectAndIgnoredArguments() {
        var hooks=hooks(scripts("ysm.sync(v.argument=7)"),List.of(),List.of(),List.of(),List.of(),scripts("v.synced=args[0]"));
        var local=evaluate(player(hooks),0,0,Map.of(),SyncMode.LOCAL);
        assertEquals(7,local.variables().get("synced"));assertTrue(local.effects().isEmpty());
        var host=evaluate(player(hooks),0,0,Map.of(),SyncMode.HOST);
        assertEquals(1,host.effects().size());assertEquals("ysm.sync",host.effects().get(0).kind());
        assertFalse(host.variables().containsKey("synced"));
        var ignored=evaluate(player(hooks),0,0,Map.of(),SyncMode.NONE);
        assertFalse(ignored.variables().containsKey("argument"));assertTrue(ignored.effects().isEmpty());
    }
    @Test void lateHookAndDeferFailuresRollbackInitializationAndAllowDeterministicRetry() {
        for(boolean deferred:new boolean[]{false,true}) {
            var hooks=hooks(scripts("v.init=(v.init??0)+1; ysm.defer('ignored',5)"),List.of(),List.of(),
                    deferred?List.of():scripts("q.fail ? math.sqrt(-1) : 0"),
                    deferred?scripts("v.changed=1; q.fail ? math.sqrt(-1) : 0"):List.of(),List.of());
            var player=player(hooks);
            assertThrows(MolangException.class,()->evaluate(player,0,0,Map.of("fail",1.0),SyncMode.HOST));
            assertTrue(player.typedVariables().isEmpty());
            assertEquals(1,evaluate(player,0,0,Map.of(),SyncMode.HOST).variables().get("init"));
        }
    }
    @Test void bothPoseOwnersRegisterOwnerFunctionsAndHooksWithoutSharingState() {
        var library=library(obj("a",obj("loop",true,"bones",obj("root",obj("position",List.of(0,"fn.move()",0))))));
        var functions=Map.of("move",MolangProgram.compile("return 16;"));
        var hooks=hooks(scripts("v.init=1"),List.of(),List.of(),List.of(),List.of(),List.of());
        var player=new PoseAnimationPlayer(library,BonePose.identity(1),12,0,7,functions,hooks);player.play("a");
        var definition=PoseControllerDefinition.decode(obj("animation_controllers",obj("c",obj("states",obj("default",obj("animations",List.of("a")))))),"c",library,budget());
        var controller=new PoseAnimationController(library,definition,BonePose.identity(1),12,7,functions,hooks);
        assertEquals(1,evaluate(player,0,0,Map.of(),SyncMode.HOST).pose().translations()[1]);
        var frame=controller.evaluate(0,0,new SceneInputs(Map.of()),budget());
        assertEquals(1,frame.pose().translations()[1]);assertEquals(1,frame.variables().get("init"));
        assertThrows(UnsupportedOperationException.class,()->hooks.initialize().clear());
        var token=new CancellationToken();token.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,()->controller.evaluate(1,16_666_667,new SceneInputs(Map.of()),new ScriptBudget(1000,128,token)));
    }
}
