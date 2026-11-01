package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.format.bedrock.BedrockJsonImporter;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import dev.spm.core.format.common.BoundedJson;
import dev.spm.core.format.ysm.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Unified independent parser. File envelopes and compiled payloads have separate entry contracts. */
public final class SpmModelParser implements ModelImporter {
    private final List<ModelFormatAdapter> extensions;
    public SpmModelParser(){this(List.of());}
    public SpmModelParser(List<ModelFormatAdapter> extensions){
        var registered=new java.util.ArrayList<>(List.copyOf(extensions));var ids=new HashSet<String>();
        ids.addAll(Set.of("ysm","ysm-payload","bedrock","bbmodel"));
        for(var adapter:registered)if(!adapter.formatId().matches("[a-z][a-z0-9-]{0,63}")||!ids.add(adapter.formatId()))
            throw new IllegalArgumentException("Duplicate or invalid format adapter id");
        if(!ids.contains("v3d"))registered.add(new V3dModelAdapter());
        this.extensions=List.copyOf(registered);
    }
    @Override public boolean supports(FormatHint hint){
        Objects.requireNonNull(hint);
        return switch(hint){case AUTO,YSM,YSM_PAYLOAD,BEDROCK,BBMODEL->true;default->extensions.stream().anyMatch(e->e.supports(hint));};
    }
    @Override public ImportResult importModel(ImportRequest request) throws ModelImportException {
        Objects.requireNonNull(request);
        try(var builder=new ParserResultBuilder()){
            request.cancellation().check();
            ParserInput.Snapshot snapshot;
            try{snapshot=ParserInput.read(request,request.source().entry(),request.source().resolver());}
            catch(ParserInput.InputLimitException limit){throw failure(request,ImportErrorCode.INPUT_LIMIT,limit);}
            catch(IOException source){throw failure(request,ImportErrorCode.SOURCE_IO,source);}
            try(snapshot){
                byte[] bytes=snapshot.bytes();FormatHint hint=request.hint();
                if(hint==FormatHint.AUTO)hint=detect(bytes,request);
                if(hint==null || !supportsBuiltin(hint)){
                    for(var adapter:extensions)if(adapter.supports(request.hint()) && adapter.accepts(request.hint(),request.source().entry(),prefix(bytes))) {
                        request.cancellation().check();builder.retain(snapshot.retain());
                        ResourceResolver replay=key->key.equals(request.source().entry())?new ByteArrayInputStream(bytes):request.source().resolver().open(key);
                        var source=new ModelSource(request.source().identity(),request.source().entry(),replay);
                        var replayRequest=new ImportRequest(source,request.hint(),request.policy(),request.budget(),request.cancellation(),request.sourceProfile(),request.actionProfile());
                        var imported=adapter.importModel(replayRequest);builder.retain(imported);
                        for(var model:imported.models())builder.add(model.key(),model.asset().document());
                        for(var diagnostic:imported.diagnostics())builder.diagnostic(diagnostic);
                        builder.resources(imported.resources());request.cancellation().check();return builder.finish();
                    }
                    throw failure(request,ImportErrorCode.UNSUPPORTED_FORMAT,null);
                }
                switch(hint){
                    case BEDROCK -> single(builder,BedrockJsonImporter.importAsset(bytes,request.policy(),request.budget(),request.cancellation()),request);
                    case BBMODEL -> single(builder,BbmodelJsonImporter.importAsset(bytes,request.policy(),request.budget(),request.cancellation()),request);
                    case YSM -> {
                        if(isV3(bytes)){
                            try(var payload=YsmEnvelopeCodec.decode(bytes,request.policy(),request.budget(),request.cancellation())){
                                historical(builder,YsmModelImporter.importPayload(payload,request.policy(),request.budget(),request.cancellation()),request);
                            }
                        } else {
                            var archive=YsmArchiveCodec.decode(bytes,request.policy(),request.budget(),request.cancellation());builder.retain(archive);
                            RawYsmParser.parse(archive,request,builder);
                        }
                    }
                    case YSM_PAYLOAD -> {
                        var historical=YsmHistoricalCodec.decode(bytes,request.policy(),request.budget(),request.cancellation());
                        YsmImportResult imported;
                        try{imported=YsmModelImporter.importPayload(historical,request.policy(),request.budget(),request.cancellation());}
                        catch(IOException|RuntimeException|Error problem){historical.close();throw problem;}
                        historical(builder,imported,request);
                    }
                    default -> throw failure(request,ImportErrorCode.UNSUPPORTED_FORMAT,null);
                }
                request.cancellation().check();return builder.finish();
            }
        }catch(ModelImportException failure){throw failure;}
        catch(CancellationException cancelled){throw failure(request,ImportErrorCode.CANCELLED,cancelled);}
        catch(ResourceBudgetExceededException exhausted){throw failure(request,ImportErrorCode.BUDGET_EXCEEDED,exhausted);}
        catch(DecodeLimitException limited){throw failure(request,limited.code(),limited);}
        catch(IOException invalid){
            for(Throwable cause=invalid;cause!=null;cause=cause.getCause())if(cause instanceof ResourceBudgetExceededException)
                throw failure(request,ImportErrorCode.BUDGET_EXCEEDED,invalid);
            throw failure(request,ImportErrorCode.MALFORMED_INPUT,invalid);
        }
        catch(IllegalArgumentException invalid){throw failure(request,ImportErrorCode.MALFORMED_INPUT,invalid);}
        catch(RuntimeException internal){throw failure(request,ImportErrorCode.INTERNAL,internal);}
    }
    private static void single(ParserResultBuilder builder,ModelAsset asset,ImportRequest request){
        builder.retain(asset);builder.add("main",asset.document());builder.resources(request.source().resolver());
    }
    private static void historical(ParserResultBuilder builder,YsmImportResult imported,ImportRequest request) throws ModelImportException {
        builder.retain(imported);builder.resources(imported::open);
        for(var variant:imported.variants()){
            if(variant.document().isEmpty())throw failure(request,ImportErrorCode.UNSUPPORTED_FEATURE,new IOException(variant.limitation()));
            builder.add(variant.sourceType()+"/"+variant.sourceKey(),variant.document().orElseThrow());
        }
    }
    private static boolean supportsBuiltin(FormatHint hint){return hint==FormatHint.YSM||hint==FormatHint.YSM_PAYLOAD||hint==FormatHint.BEDROCK||hint==FormatHint.BBMODEL;}
    private static FormatHint detect(byte[] bytes,ImportRequest request) throws IOException {
        if(isV3(bytes)||bytes.length>=4&&bytes[0]=='Y'&&bytes[1]=='S'&&bytes[2]=='G'&&bytes[3]=='P')return FormatHint.YSM;
        int first=0;while(first<bytes.length&&(bytes[first]==' '||bytes[first]=='\t'||bytes[first]=='\r'||bytes[first]=='\n'))first++;
        if(first==bytes.length||bytes[first]!='{')return null;
        try(var quota=new BoundedJson.Quota(request.policy(),request.budget(),request.cancellation())){
            var root=BoundedJson.read(bytes,quota);
            if(root.values().containsKey("minecraft:geometry"))return FormatHint.BEDROCK;
            if(root.values().containsKey("elements")&&root.values().containsKey("outliner"))return FormatHint.BBMODEL;
            return null;
        }
    }
    private static boolean isV3(byte[] bytes){return bytes.length>=7&&(bytes[0]&255)==0xef&&(bytes[1]&255)==0xbb&&(bytes[2]&255)==0xbf&&bytes[3]=='Y'&&bytes[4]=='S'&&bytes[5]=='G'&&bytes[6]=='P';}
    private static ByteBuffer prefix(byte[] bytes){return ByteBuffer.wrap(bytes,0,Math.min(bytes.length,64)).slice().asReadOnlyBuffer();}
    private static ModelImportException failure(ImportRequest request,ImportErrorCode code,Throwable cause){
        String text=code.name();if(cause!=null&&cause.getMessage()!=null&&!cause.getMessage().isBlank())text+=": "+cause.getMessage();
        if(text.length()>2048)text=text.substring(0,2048);
        return new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR,code,request.source().entry(),-1,text),cause);
    }
}
