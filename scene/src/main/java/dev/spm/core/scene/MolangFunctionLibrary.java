package dev.spm.core.scene;

import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.JsonString;
import dev.spm.core.api.ModelDocument.JsonValue;
import dev.spm.core.api.ResourceCatalog;
import dev.spm.core.api.ResourceKey;
import dev.spm.core.api.ResourceResolver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Compiled immutable functions belonging to one imported asset generation; contains no live resolver or global cache. */
public final class MolangFunctionLibrary {
    private static final int MAX_FUNCTIONS=4096;
    private static final int MAX_TOTAL_BYTES=4<<20;
    private final Map<String,MolangProgram> assets;
    private final Map<String,MolangProgram> programs;
    private final Map<String,List<MolangProgram>> hooks;

    private MolangFunctionLibrary(Map<String,MolangProgram> assets) throws IOException {
        this.assets=Map.copyOf(assets);
        var functions=new LinkedHashMap<String,MolangProgram>();
        var handlers=new LinkedHashMap<String,List<MolangProgram>>();
        for(var entry:assets.entrySet()) {
            String name=entry.getKey();int separator=name.indexOf('@');
            if(separator!=0) {
                String function=separator<0?name:name.substring(0,separator);
                if(functions.putIfAbsent(function,entry.getValue())!=null)throw new IOException("Duplicate function alias: "+function);
            }
            if(separator>=0 && separator+1<name.length())
                handlers.computeIfAbsent(name.substring(separator+1),key->new ArrayList<>()).add(entry.getValue());
        }
        this.programs=Map.copyOf(functions);
        var immutableHandlers=new LinkedHashMap<String,List<MolangProgram>>();
        handlers.forEach((key,value)->immutableHandlers.put(key,List.copyOf(value)));
        this.hooks=Map.copyOf(immutableHandlers);
    }

    public Map<String,MolangProgram> programs() { return programs; }
    public Map<String,List<MolangProgram>> hooks() { return hooks; }
    public Map<String,MolangProgram> assets() { return assets; }

    /**
     * Loads only catalogued resources below the declared function_path (default functions).
     * Local: 131072 chars/32768 tokens/depth128; Peer: 65536 chars/16384 tokens/depth32.
     * Both cap 4096 assets and 4 MiB aggregate UTF-8 source bytes. The library owns no input streams
     * or ResourceBudget reservations; these hard limits are not a JVM retained-byte measurement.
     */
    public static MolangFunctionLibrary load(ModelDocument model,ResourceResolver resolver,ScriptBudget budget,boolean peer)throws IOException {
        Objects.requireNonNull(model);Objects.requireNonNull(resolver);Objects.requireNonNull(budget);
        budget.step();
        if(!(resolver instanceof ResourceCatalog catalog))throw new IOException("Function loading requires an authorized ResourceCatalog");
        String directory=directory(model.metadata());
        String prefix=directory+"/";
        var selected=new LinkedHashMap<String,ResourceKey>();
        for(ResourceKey key:catalog.entries()) {
            budget.step();String path=key.path();
            if(!path.startsWith(prefix) || !path.toLowerCase(Locale.ROOT).endsWith(".molang"))continue;
            String name=path.substring(prefix.length(),path.length()-7);
            validateName(name);
            String normalized=name.toLowerCase(Locale.ROOT);
            if(selected.putIfAbsent(normalized,key)!=null)throw new IOException("Duplicate function asset: "+normalized);
            if(selected.size()>MAX_FUNCTIONS)throw new IOException("At most 4096 function assets");
        }
        int characterLimit=peer?65_536:131_072;
        int totalBytes=0;
        var compiled=new LinkedHashMap<String,MolangProgram>();
        for(var entry:selected.entrySet().stream().sorted(java.util.Comparator.comparing(value->value.getValue().path())).toList()) {
            budget.step();
            byte[] bytes=read(resolver,entry.getValue(),Math.min(MAX_TOTAL_BYTES-totalBytes,characterLimit*4),budget);
            totalBytes+=bytes.length;
            String source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(source.length()>characterLimit)throw new IOException("Function source character limit exceeded: "+entry.getKey());
            budget.step();
            compiled.put(entry.getKey(),MolangProgram.compile(source,characterLimit,peer?16_384:32_768,peer?32:128));
        }
        budget.checkCancelled();
        return new MolangFunctionLibrary(compiled);
    }

    private static byte[] read(ResourceResolver resolver,ResourceKey key,int limit,ScriptBudget budget)throws IOException {
        try(var input=Objects.requireNonNull(resolver.open(key));var output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];
            while(true) {
                budget.step();int count=input.read(buffer,0,Math.min(buffer.length,limit-output.size()+1));
                if(count<0)break;
                if(count==0) {
                    int one=input.read();if(one<0)break;
                    if(output.size()==limit)throw new IOException("Function source byte limit exceeded");
                    output.write(one);continue;
                }
                if(count>limit-output.size())throw new IOException("Function source byte limit exceeded");
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }

    private static String directory(JsonObject metadata)throws IOException {
        JsonValue ysm=metadata.get("ysm");
        JsonValue manifest=ysm instanceof JsonObject object?object.get("manifest"):null;
        JsonValue files=manifest instanceof JsonObject object?object.get("files"):null;
        JsonValue value=files instanceof JsonObject object?object.get("function_path"):null;
        if(value==null || value==ModelDocument.JsonNull.INSTANCE)return "functions";
        if(!(value instanceof JsonString text))throw new IOException("function_path must be text");
        String path=text.value();if(path.isBlank())return "functions";
        while(path.endsWith("/"))path=path.substring(0,path.length()-1);
        try{new ResourceKey(path);}catch(IllegalArgumentException invalid){throw new IOException("Invalid function_path",invalid);}
        if(path.length()>256)throw new IOException("Function directory too long");
        return path;
    }

    private static void validateName(String name)throws IOException {
        if(name.length()>256 || name.isEmpty() || name.contains("..") || !name.matches("[A-Za-z_@][A-Za-z0-9_@./]*"))
            throw new IOException("Invalid function name: "+name);
        try{new ResourceKey(name);}catch(IllegalArgumentException invalid){throw new IOException("Invalid function name",invalid);}
    }
}
