package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import dev.spm.core.format.v3d.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Verified logical V3D packages. All sibling reads remain inside the Host-authorized resolver. */
public final class V3dModelAdapter implements ModelFormatAdapter {
    private static final List<ResourceKey> STANDARD=List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY,V3dManifest.SOURCE,V3dManifest.WIRE);
    @Override public String formatId(){return "v3d";}
    @Override public boolean supports(FormatHint hint){return hint==FormatHint.AUTO||hint==FormatHint.V3D;}
    @Override public boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix){
        Objects.requireNonNull(entry);Objects.requireNonNull(prefix);
        return supports(hint)&&manifestEntry(entry);
    }
    @Override public ImportResult importModel(ImportRequest request) throws ModelImportException {
        Objects.requireNonNull(request);
        try(var builder=new ParserResultBuilder()) {
            request.cancellation().check();
            if(!supports(request.hint())||!manifestEntry(request.source().entry()))
                throw failure(request,ImportErrorCode.UNSUPPORTED_FORMAT,null);
            var files=new LinkedHashMap<ResourceKey,byte[]>();
            read(request,V3dManifest.MANIFEST,files,builder,16L<<20);
            var policy=request.policy();
            var jsonPolicy=new DecodePolicy(Math.min(policy.maxInputBytes(),16L<<20),policy.maxExpandedBytes(),policy.maxEntries(),
                    Math.min(policy.maxStringBytes(),65535),Math.min(policy.maxDepth(),64),policy.maxObjects(),policy.maxCollectionEntries());
            try(var quota=new BoundedJson.Quota(jsonPolicy,request.budget(),request.cancellation())) {
                var root=BoundedJson.read(files.get(V3dManifest.MANIFEST),quota);
                var keys=new ArrayList<ResourceKey>(STANDARD);
                var decoded=root.values().get("decoded");
                if(decoded!=null&&decoded!=JsonNull.INSTANCE) {
                    if(!(decoded instanceof JsonObject object)||!(object.values().get("files") instanceof JsonObject descriptors))
                        throw new IOException("Invalid V3D decoded file declarations");
                    for(String name:descriptors.values().keySet()) {
                        request.cancellation().check();var key=decodedKey(name,request.policy());
                        if(keys.size()>=policy.maxEntries())throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT,"V3D package entry limit");
                        keys.add(key);
                    }
                }
                if(keys.size()>policy.maxEntries())throw new DecodeLimitException(ImportErrorCode.RESOURCE_LIMIT,"V3D package entry limit");
                for(var key:keys)if(!key.equals(V3dManifest.MANIFEST))
                    read(request,key,files,builder,key.equals(V3dManifest.INTEGRITY)?16L<<20:Long.MAX_VALUE);
            }
            var archive=V3dCodec.parse(files,V3dCodec.Verification.FULL,request.policy(),request.budget(),request.cancellation());
            builder.retain(archive);
            // Reuse the strict historical importer; decoded workspace edits never select executable models.
            var wireSource=new ModelSource(request.source().identity(),V3dManifest.WIRE,archive::open);
            var nested=new ImportRequest(wireSource,FormatHint.YSM_PAYLOAD,request.policy(),request.budget(),request.cancellation(),request.sourceProfile(),request.actionProfile());
            var imported=new SpmModelParser(List.of()).importModel(nested);builder.retain(imported);
            for(var model:imported.models())builder.add(model.key(),model.asset().document());
            for(var diagnostic:imported.diagnostics())builder.diagnostic(diagnostic);
            var archiveKeys=archive.entries();var resources=imported.resources();
            // Package allowlists exclude historical/images/*, so source files cannot override model resources.
            builder.resources(key->archiveKeys.contains(key)?archive.open(key):resources.open(key));
            request.cancellation().check();return builder.finish();
        } catch(ModelImportException nested){throw failure(request,nested.diagnostic().code(),nested);}
        catch(ParserInput.InputLimitException limited){throw failure(request,ImportErrorCode.INPUT_LIMIT,limited);}
        catch(DecodeLimitException limited){throw failure(request,limited.code(),limited);}
        catch(CancellationException cancelled){throw failure(request,ImportErrorCode.CANCELLED,cancelled);}
        catch(ResourceBudgetExceededException exhausted){throw failure(request,ImportErrorCode.BUDGET_EXCEEDED,exhausted);}
        catch(IOException invalid){
            for(Throwable cause=invalid;cause!=null;cause=cause.getCause())if(cause instanceof ResourceBudgetExceededException)
                throw failure(request,ImportErrorCode.BUDGET_EXCEEDED,invalid);
            throw failure(request,ImportErrorCode.MALFORMED_INPUT,invalid);
        }
        catch(IllegalArgumentException invalid){throw failure(request,ImportErrorCode.MALFORMED_INPUT,invalid);}
        catch(RuntimeException internal){throw failure(request,ImportErrorCode.INTERNAL,internal);}
    }
    private static boolean manifestEntry(ResourceKey entry){return entry.path().equals("v3d.json")||entry.path().endsWith("/v3d.json");}
    private static ResourceKey decodedKey(String name,DecodePolicy policy) throws IOException {
        var key=new ResourceKey(name);
        if(!name.matches("(?:legacy|geometry|animations|controllers|textures|gui|avatars|sounds|functions|lang)/[A-Za-z0-9_.-]+")||name.contains(".."))
            throw new IOException("Unsafe V3D decoded path");
        if(name.split("/",-1).length>policy.maxDepth()||name.getBytes(StandardCharsets.UTF_8).length>policy.maxStringBytes())
            throw new IOException("V3D logical path limit");
        return key;
    }
    private static void read(ImportRequest request,ResourceKey key,Map<ResourceKey,byte[]> files,
                             ParserResultBuilder builder,long entryLimit) throws IOException {
        long used=0;for(byte[] bytes:files.values())used+=bytes.length;
        var policy=request.policy();long inputRemaining=Math.min(entryLimit,policy.maxInputBytes()-used);
        boolean expandedControls=policy.maxExpandedBytes()-used<inputRemaining;
        long remaining=Math.min(inputRemaining,policy.maxExpandedBytes()-used);
        if(remaining<0) {
            if(expandedControls)throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"Aggregate V3D expanded limit");
            throw new ParserInput.InputLimitException();
        }
        var bounded=new DecodePolicy(remaining,policy.maxExpandedBytes(),policy.maxEntries(),policy.maxStringBytes(),policy.maxDepth(),policy.maxObjects(),policy.maxCollectionEntries());
        var nested=new ImportRequest(request.source(),request.hint(),bounded,request.budget(),request.cancellation(),request.sourceProfile(),request.actionProfile());
        String entry=request.source().entry().path();int slash=entry.lastIndexOf('/');
        var hostKey=new ResourceKey((slash<0?"":entry.substring(0,slash+1))+key.path());
        ParserInput.Snapshot snapshot;
        try{snapshot=ParserInput.read(nested,hostKey,request.source().resolver());}
        catch(ParserInput.InputLimitException limited){
            if(expandedControls)throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"Aggregate V3D expanded limit");
            throw limited;
        }
        catch(IOException source){throw failure(request,ImportErrorCode.SOURCE_IO,source);}
        builder.retain(snapshot);files.put(key,snapshot.bytes());
    }
    private static ModelImportException failure(ImportRequest request,ImportErrorCode code,Throwable cause) {
        String message=code.name();if(cause!=null&&cause.getMessage()!=null)message+=": "+cause.getMessage();
        if(message.length()>2048)message=message.substring(0,2048);
        return new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR,code,request.source().entry(),-1,message),cause);
    }
}
