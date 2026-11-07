package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.bedrock.BedrockJsonImporter;
import dev.spm.core.format.common.BoundedJson;
import dev.spm.core.format.ysm.ModelArchive;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded assembly of raw YSM archives. Media remains in the caller-owned archive domain. */
final class RawYsmParser {
    private static final List<String> ANIMATION_TYPES=List.of("main","arm","fp_arm","extra","tac","carryon",
        "parcool","swem","slashblade","tlm","immersive_melodies","irons_spell_books");
    private final ModelArchive archive;
    private final ImportRequest request;
    private final ParserResultBuilder result;
    private final BoundedJson.Quota quota;
    private final Set<ResourceKey> entries;
    private final ResourceResolver resolver;
    private JsonObject manifest;
    private JsonObject properties;

    private RawYsmParser(ModelArchive archive,ImportRequest request,ParserResultBuilder result,BoundedJson.Quota quota){
        this.archive=archive;this.request=request;this.result=result;this.quota=quota;
        entries=archive.entries();resolver=ResourceCatalog.of(entries,archive::open);
    }
    static void parse(ModelArchive archive,ImportRequest request,ParserResultBuilder result)throws IOException {
        var quota=new BoundedJson.Quota(request.policy(),request.budget(),request.cancellation());
        result.retain(quota);
        new RawYsmParser(archive,request,result,quota).parse();
    }
    private void parse()throws IOException {
        request.cancellation().check();result.resources(resolver);
        manifest=has("ysm.json")?json("ysm.json"):legacy();
        properties=optionalObject(manifest,"properties");
        JsonObject files=requiredObject(manifest,"files"),player=requiredObject(files,"player");
        JsonObject models=requiredObject(player,"model");
        String main=path(required(models,"main"),"model main","json"),arm=path(required(models,"arm"),"model arm","json");
        for(var entry:models.values().entrySet())path(entry.getValue(),"model "+entry.getKey(),"json");
        validateImages(properties);
        validateAuthors(optionalObject(manifest,"metadata"));
        for(String field:List.of("sound_path","function_path","language_path")){
            JsonValue value=files.values().get(field);if(value!=null&&value!=JsonNull.INSTANCE)directory(text(value,field));
        }
        JsonValue oldSound=player.values().get("sound_path");if(oldSound!=null&&oldSound!=JsonNull.INSTANCE)directory(text(oldSound,"sound_path"));
        var animations=animationSets(optionalObject(player,"animation"));
        var controllers=controllerFiles(optionalArray(player,"animation_controllers"));
        var textures=textureSets(optionalArray(player,"texture"));
        if(textures.values().isEmpty())throw new IOException("Raw player has no texture");
        document("player/main","player",main,textures,animations,controllers,List.of());
        document("player/arm","player",arm,textures,animations,controllers,List.of());
        replacements(files,"projectiles","projectile");
        JsonValue arrow=files.values().get("arrow");
        if(arrow!=null&&arrow!=JsonNull.INSTANCE)replacement(object(arrow,"arrow"),"projectile/arrow");
        replacements(files,"vehicles","vehicle");
        for(String key:models.values().keySet())if(!key.equals("main")&&!key.equals("arm"))
            result.diagnostic(new ImportDiagnostic(ImportDiagnostic.Severity.WARNING,ImportErrorCode.UNSUPPORTED_FEATURE,
                new ResourceKey("ysm.json"),-1,"Player geometry key retained without a runtime variant: "+key));
    }

    private JsonObject legacy()throws IOException {
        require("main.json");require("arm.json");
        var animation=new LinkedHashMap<String,JsonValue>();
        for(String name:ANIMATION_TYPES)if(has(name+".animation.json"))animation.put(name,string(name+".animation.json"));
        var textures=new ArrayList<JsonValue>();
        for(String name:entries.stream().map(ResourceKey::path).sorted().toList())
            if(!name.contains("/")&&name.toLowerCase(Locale.ROOT).endsWith(".png")&&!name.equals("arrow.png"))textures.add(string(name));
        boolean arrow=has("arrow.json"),arrowTexture=has("arrow.png");
        if(arrow!=arrowTexture)throw new IOException("Legacy arrow geometry and texture must occur together");
        var files=new LinkedHashMap<String,JsonValue>();
        files.put("player",obj(Map.of("model",obj(Map.of("main",string("main.json"),"arm",string("arm.json"))),
            "animation",obj(animation),"texture",array(textures))));
        if(arrow){
            var replacement=new LinkedHashMap<String,JsonValue>();replacement.put("model",string("arrow.json"));
            replacement.put("texture",string("arrow.png"));replacement.put("match",array(List.of(string("arrow"))));
            if(has("arrow.animation.json"))replacement.put("animation",string("arrow.animation.json"));
            files.put("arrow",obj(replacement));
        }
        var root=new LinkedHashMap<String,JsonValue>();root.put("files",obj(files));
        if(has("info.json")){
            JsonObject info=json("info.json");root.put("legacy_info",info);
            var settings=new LinkedHashMap<String,JsonValue>();
            JsonValue free=info.values().get("free");
            if(free!=null&&!(free instanceof JsonBoolean))throw new IOException("Expected legacy free flag");
            settings.put("free",free==null?new JsonBoolean(false):free);
            var extra=new LinkedHashMap<String,JsonValue>();int ordinal=0;
            for(JsonValue name:optionalArray(info,"extra_animation_names"))extra.put("extra"+ordinal++,string(text(name,"extra animation name")));
            settings.put("extra_animation",obj(extra));root.put("properties",obj(settings));
            var metadata=new LinkedHashMap<String,JsonValue>();
            metadata.put("name",string(optionalText(info,"name","")));metadata.put("tips",string(optionalText(info,"tips","")));
            metadata.put("license",obj(Map.of("type",string(optionalText(info,"license","All Rights Reserved")))));
            var authors=new ArrayList<JsonValue>();
            for(JsonValue author:optionalArray(info,"authors"))authors.add(obj(Map.of("name",string(text(author,"legacy author")))));
            metadata.put("authors",array(authors));root.put("metadata",obj(metadata));
        }
        return obj(root);
    }

    private record Library(JsonObject sources,JsonObject definitions){}
    private Library animationSets(JsonObject paths)throws IOException {
        var sources=new LinkedHashMap<String,JsonValue>();var definitions=new LinkedHashMap<String,JsonValue>();
        for(var entry:paths.values().entrySet()){
            String path=path(entry.getValue(),"animation", "json");JsonObject data=folded(json(path));
            merge(definitions,requiredObject(data,"animations"),"animation");
            sources.put(entry.getKey(),obj(Map.of("path",string(path),"document",data)));
        }
        return new Library(obj(sources),obj(definitions));
    }
    /**
     * Folds exactly-three-number arrays in an animation file into {@link JsonVector3}.
     *
     * <p>The shared reader cannot do this: it also feeds the bbmodel and bedrock importers, which
     * require a literal {@link JsonArray} and fail with {@code Expected array: origin} otherwise.
     * Folding is therefore confined to the raw archive animation trees, where a triple really is a
     * coordinate vector, and where {@code PoseAnimationLibrary} already accepts either shape. Both
     * the archival document and the executable definitions read the returned tree, so a fold is
     * retained once rather than per consumer.
     *
     * <p>A component is folded only when it survives {@code double -> float -> double} exactly.
     * Unlike the historical projection, whose values originate as wire {@code float32}, these values
     * are decimal literals read straight from JSON: on the measured corpus most triples (79,915 of
     * 100,615) are <em>not</em> float-exact, and those keep the array form, which carries arbitrary
     * precision. The check compares raw bits rather than values, so it states exactly what the
     * narrowing guarantees: the same real number <em>and</em> the same sign of zero. A
     * {@link JsonNumber} already rejects a non-finite component, and {@link #exact} rejects a
     * narrowing that would overflow to infinity anyway.
     *
     * <p>The fold is free in charge terms, and deliberately so. The reader already charged 128 bytes
     * for the array node and 128 for each of its three numbers; the vector that replaces all four
     * retains 40. Charging the vector on top would raise the reported figure while the actual figure
     * falls, which is the failure mode that makes a budget reject a model that would have fitted.
     * Leaving the parse charge untouched keeps it a true upper bound and lets folding only ever lower
     * what is really retained. Nothing is charged for rebuilt containers either: each holds exactly
     * the fields of the one it replaces, and the superseded copies are unreachable from the moment
     * their parent is rebuilt, so the transient is bounded by the depth limit, not by tree size.
     */
    private JsonObject folded(JsonObject value)throws IOException {
        JsonValue replaced=folded((JsonValue)value);
        // The root of an animation file is an object; folding never changes that shape.
        return (JsonObject)replaced;
    }
    private JsonValue folded(JsonValue value)throws IOException {
        if(value instanceof JsonObject object){
            LinkedHashMap<String,JsonValue> copy=null;
            for(int i=0;i<object.size();i++){
                JsonValue child=object.value(i),replacement=folded(child);
                if(replacement==child)continue;
                if(copy==null)copy=new LinkedHashMap<>(object.values());
                copy.put(object.key(i),replacement);
            }
            // Not charged: the object holds the same fields as the one it replaces, which was already
            // charged. Charging the rebuild would invent retention for a container of equal size.
            return copy==null?value:new JsonObject(copy);
        }
        if(value instanceof JsonArray array){
            List<JsonValue> values=array.values();
            JsonVector3 vector=vector(values);
            if(vector!=null)return vector;
            JsonValue[] replacements=null;
            for(int i=0;i<values.size();i++){
                JsonValue replacement=folded(values.get(i));
                if(replacement==values.get(i))continue;
                if(replacements==null)replacements=values.toArray(new JsonValue[0]);
                replacements[i]=replacement;
            }
            return replacements==null?value:new JsonArray(List.of(replacements));
        }
        return value;
    }
    /**
     * The inline vector for a foldable triple, or {@code null} when the array form must stay.
     *
     * <p>Package-private so the fold predicate can be exercised directly: JSON cannot carry a
     * non-finite number at all, so the "never fold an unrepresentable component" arms have no
     * textual fixture.
     */
    static JsonVector3 vector(List<JsonValue> values){
        if(values.size()!=3||!(values.get(0) instanceof JsonNumber x)
                ||!(values.get(1) instanceof JsonNumber y)||!(values.get(2) instanceof JsonNumber z))return null;
        float fx=(float)x.value(),fy=(float)y.value(),fz=(float)z.value();
        if(!exact(fx,x.value())||!exact(fy,y.value())||!exact(fz,z.value()))return null;
        return new JsonVector3(fx,fy,fz);
    }
    /** True when narrowing to {@code float} and widening back reproduces the same bits. */
    private static boolean exact(float narrowed,double original){
        return Float.isFinite(narrowed)&&Double.doubleToLongBits(narrowed)==Double.doubleToLongBits(original);
    }
    private Library controllerFiles(List<JsonValue> paths)throws IOException {
        var sources=new LinkedHashMap<String,JsonValue>();var definitions=new LinkedHashMap<String,JsonValue>();
        for(JsonValue value:paths){
            String path=path(value,"controller","json"),name=stem(path);JsonObject data=json(path);
            if(sources.containsKey(name))throw new IOException("Duplicate controller filename: "+name);
            merge(definitions,requiredObject(data,"animation_controllers"),"controller");
            sources.put(name,obj(Map.of("path",string(path),"document",data)));
        }
        return new Library(obj(sources),obj(definitions));
    }
    private void merge(Map<String,JsonValue> output,JsonObject input,String type)throws IOException {
        for(var entry:input.values().entrySet()){
            if(output.putIfAbsent(entry.getKey(),entry.getValue())!=null)throw new IOException("Duplicate "+type+" name: "+entry.getKey());
        }
    }
    private JsonObject textureSets(List<JsonValue> raw)throws IOException {
        var sets=new LinkedHashMap<String,JsonValue>();
        for(JsonValue texture:raw){
            JsonObject set=texture instanceof JsonString?obj(Map.of("uv",texture)):object(texture,"texture");
            String uv=path(required(set,"uv"),"texture UV","image"),name=stem(uv);
            for(String role:List.of("normal","specular")){
                JsonValue value=set.values().get(role);if(value!=null&&value!=JsonNull.INSTANCE&&!text(value,role).isBlank())path(value,"texture "+role,"image");
            }
            if(sets.putIfAbsent(name,set)!=null)throw new IOException("Duplicate skin filename: "+name);
        }
        return obj(sets);
    }
    private void replacements(JsonObject files,String field,String type)throws IOException {
        JsonValue source=files.values().get(field);if(source==null||source==JsonNull.INSTANCE)return;
        int ordinal=0;
        if(source instanceof JsonArray list){for(JsonValue item:list.values())replacement(object(item,field),type+"/"+String.format(Locale.ROOT,"%04d",ordinal++));}
        else {
            for(var entry:object(source,field).values().entrySet()){
                JsonObject data=object(entry.getValue(),field);var copy=new LinkedHashMap<>(data.values());
                var matches=new ArrayList<>(optionalArray(data,"match"));matches.add(string(entry.getKey()));copy.put("match",array(matches));
                replacement(obj(copy),type+"/"+String.format(Locale.ROOT,"%04d",ordinal++));
            }
        }
    }
    private void replacement(JsonObject source,String key)throws IOException {
        List<JsonValue> matches=optionalArray(source,"match");
        if(matches.isEmpty())return;
        var seen=new LinkedHashSet<String>();
        for(JsonValue match:matches){String name=text(match,"replacement match");if(name.isBlank()||!seen.add(name))throw new IOException("Blank or duplicate replacement match");}
        String geo=path(required(source,"model"),"replacement model","json");
        Library animations=emptyLibrary(),controllers=emptyLibrary();
        JsonValue animation=source.values().get("animation"),controller=source.values().get("controller");
        if(nonblank(animation))animations=animationSets(obj(Map.of("main",animation)));
        if(nonblank(controller))controllers=controllerFiles(List.of(controller));
        JsonObject textures=textureSets(List.of(required(source,"texture")));
        document(key+"/main",key.startsWith("vehicle/")?"vehicle":"projectile",geo,textures,animations,controllers,matches);
    }
    private Library emptyLibrary()throws IOException{return new Library(obj(Map.of()),obj(Map.of()));}

    private void document(String key,String type,String path,JsonObject textures,Library animations,Library controllers,List<JsonValue> matches)throws IOException {
        request.cancellation().check();
        ModelAsset asset;
        try(var input=ParserInput.read(request,new ResourceKey(path),resolver)){
            asset=BedrockJsonImporter.importAsset(RawYsmJson.withoutComments(input.bytes(),quota),request.policy(),request.budget(),request.cancellation());
        }
        result.retain(asset);ModelDocument base=asset.document();
        var materials=new ArrayList<Material>();int selected=0,index=0;
        String preferred=type.equals("player")?optionalText(properties,"default_texture",""):"";
        boolean found=preferred.isEmpty();
        int width=base.materials().get(0).textureWidth(),height=base.materials().get(0).textureHeight();
        for(var entry:textures.values().entrySet()){
            JsonObject texture=object(entry.getValue(),"texture");var resources=new ArrayList<ResourceKey>();
            for(String role:List.of("uv","normal","specular")){
                JsonValue value=texture.values().get(role);if(nonblank(value))resources.add(new ResourceKey(text(value,role)));
            }
            materials.add(new Material(entry.getKey(),width,height,resources));
            if(entry.getKey().equals(preferred)){selected=index;found=true;}index++;
        }
        if(!found)throw new IOException("Configured default texture is absent: "+preferred);
        quota.charge(Math.addExact(2048L,Math.multiplyExact((long)materials.size(),512L)));
        var meshes=new ArrayList<Mesh>();
        for(Mesh mesh:base.meshes()){
            // Mesh records defensively copy their arrays, so charge those copies before construction.
            long vertices=mesh.vertices().vertexCount();
            quota.charge(Math.addExact(Math.multiplyExact(vertices,16L),Math.multiplyExact((long)mesh.faces().size(),48L)));
            meshes.add(new Mesh(mesh.name(),mesh.vertices(),mesh.uvs(),mesh.indices(),mesh.faces(),mesh.bounds(),selected));
        }
        JsonObject ysm=obj(Map.of("manifest",manifest,"geometry_path",string(path),"target_type",string(type),
            "animation_sets",animations.sources(),"controller_files",controllers.sources(),"texture_sets",textures,"match",array(matches),
            "archive_version",new JsonNumber(archive.outerVersion())));
        var metadata=new LinkedHashMap<>(base.metadata().values());metadata.put("ysm",ysm);
        metadata.put("animations",animations.definitions());metadata.put("animation_controllers",controllers.definitions());
        ModelDocument document=new ModelDocument(base.identity(),"ysm-raw-v"+archive.outerVersion(),base.skeleton(),meshes,
            base.bindPose(),materials,base.locators(),obj(metadata));
        result.add(key,document);
    }

    private void validateImages(JsonObject source)throws IOException {
        for(String field:List.of("icon","thumbnail","gui_foreground","gui_background")){
            JsonValue value=source.values().get(field);if(nonblank(value))path(value,field,"image");
        }
    }
    private void validateAuthors(JsonObject metadata)throws IOException {
        for(JsonValue value:optionalArray(metadata,"authors")){
            if(value instanceof JsonString)continue;
            JsonObject author=object(value,"author");JsonValue avatar=author.values().get("avatar");
            if(nonblank(avatar))path(avatar,"author avatar","image");
        }
    }
    private JsonObject json(String path)throws IOException {
        require(path);
        try(var input=ParserInput.read(request,new ResourceKey(path),resolver)){return BoundedJson.read(RawYsmJson.withoutComments(input.bytes(),quota),quota);}
    }
    private String path(JsonValue value,String label,String kind)throws IOException {
        String path=text(value,label);if(path.isBlank())throw new IOException("Blank "+label);
        key(path);String lower=path.toLowerCase(Locale.ROOT);
        if(kind.equals("json")&&!lower.endsWith(".json"))throw new IOException("Expected JSON "+label);
        if(kind.equals("image")&&List.of(".png",".jpg",".jpeg",".webp",".avif",".ztx").stream().noneMatch(lower::endsWith))
            throw new IOException("Unsupported image extension for "+label);
        require(path);return path;
    }
    private void directory(String path)throws IOException {if(!path.isBlank())key(path.endsWith("/")?path.substring(0,path.length()-1):path);}
    private static ResourceKey key(String path)throws IOException {try{return new ResourceKey(path);}catch(IllegalArgumentException invalid){throw new IOException("Unsafe raw resource path",invalid);}}
    private boolean has(String path){return entries.contains(new ResourceKey(path));}
    private void require(String path)throws IOException {request.cancellation().check();if(!entries.contains(key(path)))throw new IOException("Missing declared raw resource: "+path);}
    private static String stem(String path){int slash=path.lastIndexOf('/'),dot=path.lastIndexOf('.');return path.substring(slash+1,dot>slash?dot:path.length());}
    private static String text(JsonValue value,String name)throws IOException {if(value instanceof JsonString string)return string.value();throw new IOException("Expected string "+name);}
    private static boolean nonblank(JsonValue value)throws IOException {return value!=null&&value!=JsonNull.INSTANCE&&!text(value,"resource").isBlank();}
    private static JsonValue required(JsonObject object,String field)throws IOException {JsonValue value=object.values().get(field);if(value==null||value==JsonNull.INSTANCE)throw new IOException("Missing raw field: "+field);return value;}
    private static JsonObject object(JsonValue value,String field)throws IOException {if(value instanceof JsonObject object)return object;throw new IOException("Expected raw object: "+field);}
    private static JsonObject requiredObject(JsonObject source,String field)throws IOException{return object(required(source,field),field);}
    private static JsonObject optionalObject(JsonObject source,String field)throws IOException {JsonValue value=source.values().get(field);return value==null||value==JsonNull.INSTANCE?new JsonObject(Map.of()):object(value,field);}
    private static List<JsonValue> optionalArray(JsonObject source,String field)throws IOException {JsonValue value=source.values().get(field);if(value==null||value==JsonNull.INSTANCE)return List.of();if(value instanceof JsonArray array)return array.values();throw new IOException("Expected raw list: "+field);}
    private static String optionalText(JsonObject source,String field,String fallback)throws IOException {JsonValue value=source.values().get(field);return value==null||value==JsonNull.INSTANCE?fallback:text(value,field);}
    private JsonObject obj(Map<String,JsonValue> values)throws IOException {quota.charge(Math.addExact(128L,Math.multiplyExact((long)values.size(),96L)));return new JsonObject(values);}
    private JsonArray array(List<JsonValue> values)throws IOException {quota.charge(Math.addExact(128L,Math.multiplyExact((long)values.size(),32L)));return new JsonArray(values);}
    private static JsonString string(String value){return new JsonString(value);}
}
