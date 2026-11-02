package dev.spm.core.format.v3d;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact supported manifest profile; no Gson classes cross this boundary. */
final class V3dMetadata {
    private V3dMetadata() { }
    static V3dManifest read(JsonObject root,JsonObject integrity) throws IOException {
        keys(root,Set.of("format","format_version","decoder_profile","source","wire"),Set.of("decoded"));
        keys(integrity,Set.of("source","wire"),Set.of("decoded"));
        String format=text(root,"format"); int formatVersion=integer(root,"format_version"), profile=integer(root,"decoder_profile");
        if (!format.equals(V3dManifest.FORMAT) || formatVersion!=1 || profile<1) throw new IOException("Unsupported V3D format/profile");
        JsonObject source=object(root,"source"), wire=object(root,"wire");
        keys(source,Set.of("path","sha256","size","envelope_version","inner_version"),Set.of());
        keys(wire,Set.of("path","compression","sha256","raw_size"),Set.of());
        ResourceKey sourcePath=path(text(source,"path")), wirePath=path(text(wire,"path"));
        String sourceHash=hash(source,"sha256"), wireHash=hash(wire,"sha256"), compression=text(wire,"compression");
        long sourceSize=number(source,"size"), wireSize=number(wire,"raw_size");
        int outer=integer(source,"envelope_version"), inner=integer(source,"inner_version");
        if (!sourcePath.equals(V3dManifest.SOURCE) || !wirePath.equals(V3dManifest.WIRE) || !compression.equals("none")
                || outer!=3 || inner<1 || inner>32 || sourceSize<1 || sourceSize>69_206_016 || wireSize<4 || wireSize>(256L<<20)) {
            throw new IOException("Invalid V3D source/wire metadata");
        }
        var decoded=decoded(root,wireHash);
        JsonObject sourceIntegrity=object(integrity,"source"), wireIntegrity=object(integrity,"wire");
        keys(sourceIntegrity,Set.of("sha256"),Set.of());
        keys(wireIntegrity,Set.of("sha256","derived_from_source_sha256","decoder_profile"),Set.of());
        if (!hash(sourceIntegrity,"sha256").equals(sourceHash) || !hash(wireIntegrity,"sha256").equals(wireHash)
                || !hash(wireIntegrity,"derived_from_source_sha256").equals(sourceHash) || integer(wireIntegrity,"decoder_profile")!=profile
                || !decoded(integrity,wireHash).equals(decoded)) throw new IOException("V3D integrity dependencies disagree");
        return new V3dManifest(format,formatVersion,profile,new V3dManifest.Source(sourcePath,sourceHash,sourceSize,outer,inner),
                new V3dManifest.Wire(wirePath,compression,wireHash,wireSize),decoded);
    }
    private static Optional<V3dManifest.Decoded> decoded(JsonObject owner,String wireHash) throws IOException {
        JsonValue value=owner.values().get("decoded");
        if (value==null || value==JsonNull.INSTANCE) return Optional.empty();
        if (!(value instanceof JsonObject decoded)) throw new IOException("Invalid V3D decoded metadata");
        keys(decoded,Set.of("schema_version","parser_profile","derived_from_wire_sha256","files"),Set.of());
        int schema=integer(decoded,"schema_version"), parser=integer(decoded,"parser_profile");
        String derived=hash(decoded,"derived_from_wire_sha256");
        if (schema!=1 || parser<1 || !derived.equals(wireHash)) throw new IOException("Invalid V3D decoded dependencies");
        var files=new LinkedHashMap<ResourceKey,V3dManifest.FileDigest>();
        for (var entry:object(decoded,"files").values().entrySet()) {
            ResourceKey key=path(entry.getKey());
            if (!V3dCodec.decodedPath(key.path())) throw new IOException("Unsafe V3D decoded path");
            if (!(entry.getValue() instanceof JsonObject descriptor)) throw new IOException("Invalid V3D file digest");
            keys(descriptor,Set.of("size","sha256"),Set.of());
            long size=number(descriptor,"size"); if (size<0) throw new IOException("Negative V3D file size");
            files.put(key,new V3dManifest.FileDigest(size,hash(descriptor,"sha256")));
        }
        if (!files.containsKey(new ResourceKey("legacy/model.json"))) throw new IOException("V3D decoded root is absent");
        return Optional.of(new V3dManifest.Decoded(schema,parser,derived,files));
    }
    private static void keys(JsonObject object,Set<String> required,Set<String> optional) throws IOException {
        if (!object.values().keySet().containsAll(required)) throw new IOException("Missing V3D metadata field");
        for (String name:object.values().keySet()) if (!required.contains(name) && !optional.contains(name)) throw new IOException("Unknown V3D metadata field: "+name);
    }
    private static JsonObject object(JsonObject owner,String name) throws IOException {
        if (owner.values().get(name) instanceof JsonObject object) return object;
        throw new IOException("Expected V3D object: "+name);
    }
    private static String text(JsonObject owner,String name) throws IOException {
        if (owner.values().get(name) instanceof JsonString text) return text.value();
        throw new IOException("Expected V3D string: "+name);
    }
    private static String hash(JsonObject owner,String name) throws IOException {
        String value=text(owner,name); if (!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid V3D SHA-256"); return value;
    }
    private static long number(JsonObject owner,String name) throws IOException {
        if (!(owner.get(name) instanceof JsonNumber number)) throw new IOException("Expected V3D integer: "+name);
        double value=number.value();
        if (value!=Math.rint(value) || value<Long.MIN_VALUE || value>Long.MAX_VALUE)
            throw new IOException("V3D integer outside int64");
        return (long) value;
    }
    private static int integer(JsonObject owner,String name) throws IOException {
        long value=number(owner,name); if (value<Integer.MIN_VALUE || value>Integer.MAX_VALUE) throw new IOException("V3D integer outside int32"); return (int) value;
    }
    private static ResourceKey path(String value) throws IOException {
        try { return new ResourceKey(value); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid V3D logical path",invalid); }
    }
    static byte[] manifestBytes(V3dManifest manifest) {
        var source=manifest.source(); var wire=manifest.wire();
        return json(o("format",manifest.format(),"format_version",manifest.formatVersion(),"decoder_profile",manifest.decoderProfile(),
                "source",o("path",source.path().path(),"sha256",source.sha256(),"size",source.size(),"envelope_version",source.envelopeVersion(),"inner_version",source.innerVersion()),
                "wire",o("path",wire.path().path(),"compression",wire.compression(),"sha256",wire.sha256(),"raw_size",wire.rawSize())));
    }
    static byte[] integrityBytes(V3dManifest manifest) {
        return json(o("source",o("sha256",manifest.source().sha256()),
                "wire",o("sha256",manifest.wire().sha256(),"derived_from_source_sha256",manifest.source().sha256(),"decoder_profile",manifest.decoderProfile())));
    }
    private static JsonObject o(Object... pairs) {
        var fields=new LinkedHashMap<String,JsonValue>();
        for (int i=0;i<pairs.length;i+=2) {
            Object value=pairs[i+1]; JsonValue json=value instanceof JsonValue existing ? existing
                    : value instanceof String text ? new JsonString(text) : new JsonNumber(((Number)value).doubleValue());
            fields.put((String)pairs[i],json);
        }
        return new JsonObject(fields);
    }
    private static byte[] json(JsonObject root) { var output=new StringBuilder(); append(output,root); return output.toString().getBytes(StandardCharsets.UTF_8); }
    private static void append(StringBuilder output,JsonValue value) {
        if (value instanceof JsonObject object) {
            output.append('{'); boolean first=true;
            for (var entry:object.values().entrySet()) {
                if (!first) output.append(','); first=false; append(output,new JsonString(entry.getKey())); output.append(':'); append(output,entry.getValue());
            }
            output.append('}');
        } else if (value instanceof JsonString text) {
            output.append('"');
            for (int i=0;i<text.value().length();i++) {
                char c=text.value().charAt(i);
                if (c=='"' || c=='\\') output.append('\\').append(c);
                else if (c<32) output.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else output.append(c);
            }
            output.append('"');
        } else if (value instanceof JsonNumber number) output.append(number.decimal().stripTrailingZeros().toPlainString());
        else throw new IllegalArgumentException("Unsupported internal V3D JSON value");
    }
}
