package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.format.v3d.*;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import dev.spm.core.runtime.ModelFrameRenderer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class V3dModelAdapterTest {
    @Test void defaultParserIncludesTheConcreteV3dFormat(){
        assertTrue(new SpmModelParser().supports(FormatHint.V3D));
    }
    private static final DecodePolicy POLICY=DecodePolicy.conservative();
    private static Map<ResourceKey,byte[]> fixture() throws IOException {
        byte[] wire=Files.readAllBytes(Path.of("../verification/src/test/resources/ysm/historical/v32.wire"));
        var budget=new ResourceBudget(128L<<20);
        try(var source=YsmEnvelopeCodec.encode(wire,"independent synthetic V3D",POLICY,budget,new CancellationToken());
            var archive=V3dCodec.capture(source.bytes(),POLICY,budget,new CancellationToken())) {
            var files=new LinkedHashMap<ResourceKey,byte[]>();
            for(var key:archive.entries())files.put(key,archive.read(key));
            return files;
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    @Test void rejectsIndividuallyValidSourceAndWireWhenTheirActualPayloadsDisagree() throws Exception {
        var files=fixture();byte[] changed=files.get(V3dManifest.WIRE).clone();changed[changed.length-1]^=1;
        String oldHash=sha(files.get(V3dManifest.WIRE)),newHash=sha(changed);
        files.put(V3dManifest.WIRE,changed);
        for(var key:List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY))
            files.put(key,new String(files.get(key),StandardCharsets.UTF_8).replace(oldHash,newHash).getBytes(StandardCharsets.UTF_8));
        var budget=new ResourceBudget(16<<20);
        for(var verification:V3dCodec.Verification.values()) {
            V3dPackage[] published=new V3dPackage[1];
            try {
                assertThrows(IOException.class,()->published[0]=V3dCodec.parse(files,verification,POLICY,budget,new CancellationToken()));
            } finally {if(published[0]!=null)published[0].close();}
            assertEquals(0,budget.used());
        }
    }
    private static ModelFormatAdapter adapter() {
        return new V3dModelAdapter();
    }
    private static ImportRequest request(Map<ResourceKey,byte[]> files,FormatHint hint,ResourceBudget budget,List<ResourceKey> opened) {
        return new ImportRequest(new ModelSource("synthetic-v3d",new ResourceKey("models/avatar/v3d.json"),key->{
            opened.add(key);String prefix="models/avatar/";
            if(!key.path().startsWith(prefix))throw new IOException("Outside authorized V3D package");
            byte[] bytes=files.get(new ResourceKey(key.path().substring(prefix.length())));
            if(bytes==null)throw new IOException("Missing authorized resource");return new ByteArrayInputStream(bytes);
        }),hint,POLICY,budget,new CancellationToken());
    }
    @Test void importsVerifiedWireViaAuthorizedRelativeResourcesAndRetainsAllOwnersUntilLastLease() throws Exception {
        var files=fixture();var budget=new ResourceBudget(16<<20);var opened=new ArrayList<ResourceKey>();
        var result=new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.AUTO,budget,opened));
        assertEquals(List.of("player/main","player/arm"),result.models().stream().map(ImportResult.Model::key).toList());
        assertEquals(4,opened.size());assertEquals(4,new HashSet<>(opened).size(),"outer source snapshot must be replayed instead of reopened");
        var model=result.models().get(0).asset();var lease=model.acquire();
        var frame=ModelFrameRenderer.render(model,model.document().bindPose(),budget,new CancellationToken());
        ResourceKey texture=model.document().materials().get(0).resources().get(0);
        var image=result.resources().open(texture);var source=result.resources().open(V3dManifest.SOURCE);
        result.close();assertTrue(budget.used()>0);
        assertEquals("ysm-v3-inner-32",lease.document().sourceFormat());
        assertArrayEquals(new byte[]{16,32,48,(byte)255},image.readAllBytes());
        assertArrayEquals(files.get(V3dManifest.SOURCE),source.readAllBytes());
        image.close();source.close();assertTrue(budget.used()>0);lease.close();assertTrue(budget.used()>0);
        assertFalse(frame.meshes().isEmpty());frame.close();assertEquals(0,budget.used());
    }
    @Test void fullVerificationReadsDeclaredDecodedFilesAndRefusesEditedDerivedBytes() throws Exception {
        var files=fixture();byte[] view="{}".getBytes(StandardCharsets.UTF_8);
        addDecoded(files,"legacy/model.json",view);var budget=new ResourceBudget(16<<20);var opened=new ArrayList<ResourceKey>();
        try(var result=new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.V3D,budget,opened))) {
            try(var stream=result.resources().open(new ResourceKey("legacy/model.json"))){assertArrayEquals(view,stream.readAllBytes());}
        }
        assertTrue(opened.contains(new ResourceKey("models/avatar/legacy/model.json")));assertEquals(0,budget.used());
        files.put(new ResourceKey("legacy/model.json"),new byte[]{99});
        var failure=assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.V3D,budget,new ArrayList<>())));
        assertEquals(ImportErrorCode.MALFORMED_INPUT,failure.diagnostic().code());assertEquals(0,budget.used());
    }
    @Test void decodedWorkspaceFunctionsCannotBecomeAuthoritativeExecutableResources() throws Exception {
        var files=fixture();
        // A valid decoded workspace always includes its root as well as the injected script.
        byte[] root="{}".getBytes(StandardCharsets.UTF_8),script="v.injected=1;".getBytes(StandardCharsets.UTF_8);
        addDecoded(files,"legacy/model.json",root);
        for(var key:List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY)) {
            String metadata=new String(files.get(key),StandardCharsets.UTF_8);
            String entry="\"functions/injected.molang\":{\"size\":"+script.length+",\"sha256\":\""+sha(script)+"\"},";
            files.put(key,metadata.replace("\"files\":{","\"files\":{"+entry).getBytes(StandardCharsets.UTF_8));
        }
        files.put(new ResourceKey("functions/injected.molang"),script);
        var budget=new ResourceBudget(16<<20);
        try(var result=new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.V3D,budget,new ArrayList<>()))) {
            var catalog=assertInstanceOf(ResourceCatalog.class,result.resources());
            assertFalse(catalog.entries().contains(new ResourceKey("functions/injected.molang")));
        }
        assertEquals(0,budget.used());
    }
    @Test void refusesUnsafeDecodedNamesBeforeOpeningThemAndNeverFallsThroughToHostResources() throws Exception {
        var files=fixture();addDecoded(files,"legacy/model.json",new byte[]{1});
        for(var key:List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY))
            files.put(key,new String(files.get(key),StandardCharsets.UTF_8).replace("legacy/model.json","../private.json").getBytes(StandardCharsets.UTF_8));
        var budget=new ResourceBudget(16<<20);var opened=new ArrayList<ResourceKey>();
        assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.V3D,budget,opened)));
        assertEquals(List.of(new ResourceKey("models/avatar/v3d.json")),opened);assertEquals(0,budget.used());
        try(var result=new SpmModelParser(List.of(adapter())).importModel(request(fixture(),FormatHint.V3D,budget,new ArrayList<>()))) {
            assertThrows(IOException.class,()->result.resources().open(new ResourceKey("private.json")));
        }
        assertEquals(0,budget.used());
    }
    @Test void boundedAggregateInputCancellationAndResolverFailureReturnStableErrorsWithoutLeaks() throws Exception {
        var files=fixture();var budget=new ResourceBudget(16<<20);var original=request(files,FormatHint.V3D,budget,new ArrayList<>());
        long total=files.values().stream().mapToLong(value->value.length).sum();
        var limited=new ImportRequest(original.source(),original.hint(),new DecodePolicy(total-1,POLICY.maxExpandedBytes(),POLICY.maxEntries(),POLICY.maxStringBytes(),POLICY.maxDepth()),budget,new CancellationToken());
        assertEquals(ImportErrorCode.INPUT_LIMIT,assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(limited)).diagnostic().code());assertEquals(0,budget.used());
        var token=new CancellationToken();var count=new AtomicInteger();
        var cancelled=new ImportRequest(new ModelSource("cancelled-v3d",original.source().entry(),key->{if(count.incrementAndGet()==2)token.cancel();return original.source().resolver().open(key);}),FormatHint.V3D,POLICY,budget,token);
        assertEquals(ImportErrorCode.CANCELLED,assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(cancelled)).diagnostic().code());assertEquals(0,budget.used());
        files.remove(V3dManifest.SOURCE);
        assertEquals(ImportErrorCode.SOURCE_IO,assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(original)).diagnostic().code());assertEquals(0,budget.used());
        var tiny=new ResourceBudget(1);
        assertEquals(ImportErrorCode.BUDGET_EXCEEDED,assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(request(files,FormatHint.V3D,tiny,new ArrayList<>()))).diagnostic().code());assertEquals(0,tiny.used());
    }
    @Test void aggregateExpandedLimitIsDistinctFromEncodedInputAndReleasesEverySnapshot() throws Exception {
        var files=fixture();addDecoded(files,"legacy/model.json",new byte[65536]);
        var budget=new ResourceBudget(16<<20);var original=request(files,FormatHint.V3D,budget,new ArrayList<>());
        long total=files.values().stream().mapToLong(value->value.length).sum();
        var policy=new DecodePolicy(POLICY.maxInputBytes(),total-1,POLICY.maxEntries(),POLICY.maxStringBytes(),POLICY.maxDepth());
        var request=new ImportRequest(original.source(),FormatHint.V3D,policy,budget,new CancellationToken());
        assertEquals(ImportErrorCode.EXPANDED_LIMIT,assertThrows(ModelImportException.class,()->new SpmModelParser(List.of(adapter())).importModel(request)).diagnostic().code());
        assertEquals(0,budget.used());
    }
    private static void addDecoded(Map<ResourceKey,byte[]> files,String name,byte[] bytes) throws Exception {
        String decoded="{\"schema_version\":1,\"parser_profile\":1,\"derived_from_wire_sha256\":\""+sha(files.get(V3dManifest.WIRE))
                +"\",\"files\":{\""+name+"\":{\"size\":"+bytes.length+",\"sha256\":\""+sha(bytes)+"\"}}}";
        for(var key:List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY)) {
            String json=new String(files.get(key),StandardCharsets.UTF_8);
            files.put(key,(json.substring(0,json.length()-1)+",\"decoded\":"+decoded+"}").getBytes(StandardCharsets.UTF_8));
        }
        files.put(new ResourceKey(name),bytes);
    }
}
