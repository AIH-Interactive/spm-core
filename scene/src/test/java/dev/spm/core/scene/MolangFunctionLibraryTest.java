package dev.spm.core.scene;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static dev.spm.core.scene.PoseControllerTest.obj;
import static org.junit.jupiter.api.Assertions.*;

class MolangFunctionLibraryTest {
    private static ScriptBudget budget() { return new ScriptBudget(1_000_000,128,new CancellationToken()); }
    private static ModelDocument model(JsonObject metadata) {
        return new ModelDocument("test","ysm-v1",new Skeleton(List.of("root"),new int[]{-1},new float[3]),
                List.of(),BonePose.identity(1),List.of(),List.of(),metadata);
    }
    private static final class Catalog implements ResourceCatalog {
        private final Map<ResourceKey,byte[]> files;
        private int opened,closed;
        Catalog(Map<String,String> scripts) {
            files=new HashMap<>();scripts.forEach((key,value)->files.put(new ResourceKey(key),value.getBytes(StandardCharsets.UTF_8)));
        }
        @Override public Set<ResourceKey> entries() { return Set.copyOf(files.keySet()); }
        @Override public InputStream open(ResourceKey key) throws IOException {
            byte[] value=files.get(key);if(value==null)throw new IOException("Missing test resource");
            opened++;
            return new ByteArrayInputStream(value) {
                @Override public void close() throws IOException { super.close();closed++; }
            };
        }
    }

    @Test void loadsOnlyDeclaredDirectoryAndRetainsHooksAndUtf8Case() throws Exception {
        var source=new Catalog(Map.of("custom/Echo.molang","return '雪MiXeD';", "custom/@player_init.molang","v.x=1;",
                "functions/ignored.molang","math.sqrt(-1)","custom/nested/value.molang","return 9;","custom/readme.txt","not code"));
        var metadata=obj("ysm",obj("manifest",obj("files",obj("function_path","custom"))));
        var library=MolangFunctionLibrary.load(model(metadata),source,budget(),false);
        assertEquals(Set.of("echo","nested/value"),library.programs().keySet());
        assertEquals(Set.of("echo","@player_init","nested/value"),library.assets().keySet());
        assertEquals(1,library.hooks().get("player_init").size());
        var context=new MolangContext(Map.of(),Map.of(),library.programs(),budget());
        assertEquals(MolangValue.text("雪MiXeD"),MolangProgram.compile("fn.echo()").evaluateValue(context));
        assertEquals(3,source.opened);assertEquals(source.opened,source.closed);
        assertThrows(UnsupportedOperationException.class,()->library.programs().clear());
        var replacement=MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/echo.molang","return 7;")),budget(),false);
        assertEquals(7,replacement.programs().get("echo").evaluate(new MolangContext(Map.of(),Map.of(),Map.of(),budget())));
    }

    @Test void absentCatalogIsExplicitAndEmptyArchivesAreValid() throws Exception {
        assertTrue(MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of()),budget(),false).programs().isEmpty());
        ResourceResolver unknown=key->{throw new IOException("not authorized");};
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),unknown,budget(),false));
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj("ysm",obj("manifest",obj("files",obj("function_path","../outside"))))),new Catalog(Map.of()),budget(),false));
    }

    @Test void rejectsInvalidUtf8UnsafeNamesAndCaseCollisionsAndClosesOnCompileFailure() {
        var malformed=new Catalog(Map.of("functions/a.molang","x"));
        malformed.files.put(new ResourceKey("functions/a.molang"),new byte[]{(byte)0xc3,0x28});
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),malformed,budget(),false));
        assertEquals(malformed.opened,malformed.closed);
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/a-b.molang","1")),budget(),false));
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/A.molang","1","functions/a.molang","2")),budget(),false));
        var invalid=new Catalog(Map.of("functions/a.molang","unsupported_name"));
        assertThrows(MolangException.class,()->MolangFunctionLibrary.load(model(obj()),invalid,budget(),false));
        assertEquals(invalid.opened,invalid.closed);
    }

    @Test void enforcesPeerAndLocalSourceAndCollectionLimits() throws Exception {
        String large=" ".repeat(70_000)+"1";
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/a.molang",large)),budget(),true));
        assertEquals(Set.of("a"),MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/a.molang",large)),budget(),false).programs().keySet());
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(Map.of("functions/a.molang"," ".repeat(131_073))),budget(),false));
        var many=new HashMap<String,String>();
        for(int i=0;i<4097;i++)many.put("functions/a"+i+".molang","1");
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(many),budget(),false));
        var aggregate=new HashMap<String,String>();
        for(int i=0;i<65;i++)aggregate.put("functions/a"+i+".molang"," ".repeat(65_536));
        assertThrows(IOException.class,()->MolangFunctionLibrary.load(model(obj()),new Catalog(aggregate),budget(),false));
    }

    @Test void cancellationAndSharedStepLimitsApplyBeforeIo() {
        var source=new Catalog(Map.of("functions/a.molang","1"));
        var cancellation=new CancellationToken();cancellation.cancel();
        assertThrows(CancellationException.class,()->MolangFunctionLibrary.load(model(obj()),source,new ScriptBudget(100,128,cancellation),false));
        assertEquals(0,source.opened);
        assertThrows(MolangException.class,()->MolangFunctionLibrary.load(model(obj()),source,new ScriptBudget(0,128,new CancellationToken()),false));
        assertEquals(0,source.opened);
    }
}
