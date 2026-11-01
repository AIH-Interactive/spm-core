package dev.spm.core.format.v3d;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import dev.spm.core.format.common.BoundedJson;
import dev.spm.core.api.ModelDocument.*;
import java.io.IOException;
import java.nio.*;
import java.security.*;
import java.util.*;

/** Bounded in-memory V3D admission and exact original-envelope recovery; never re-encodes decoded JSON. */
public final class V3dCodec {
    private V3dCodec() { }
    public enum Verification { FULL, ARCHIVAL_RECOVERY }
    @SuppressWarnings("try")
    public static V3dPackage capture(byte[] source, DecodePolicy policy, ResourceBudget budget,
                                      CancellationToken token) throws IOException {
        Objects.requireNonNull(source); Objects.requireNonNull(policy); Objects.requireNonNull(budget); Objects.requireNonNull(token);
        token.check();
        if (source.length<1 || source.length>Math.min(69_206_016,policy.maxInputBytes())) throw new IOException("V3D source input limit");
        try (var sourceScratch=reserve(budget,source.length)) {
            byte[] stable=source.clone();
            try (var plaintext=YsmEnvelopeCodec.decode(stable,policy,budget,token)) {
                byte[] wire=plaintext.bytes();
                try (var wireScratch=reserve(budget,wire.length); var metadataScratch=reserve(budget,16<<10)) {
                    var manifest=new V3dManifest(V3dManifest.FORMAT,1,1,
                            new V3dManifest.Source(V3dManifest.SOURCE,sha256(stable,token),stable.length,3,plaintext.innerVersion()),
                            new V3dManifest.Wire(V3dManifest.WIRE,"none",sha256(wire,token),wire.length),Optional.empty());
                    var files=new LinkedHashMap<ResourceKey,byte[]>();
                    files.put(V3dManifest.SOURCE,stable); files.put(V3dManifest.WIRE,wire);
                    files.put(V3dManifest.MANIFEST,V3dMetadata.manifestBytes(manifest));
                    files.put(V3dManifest.INTEGRITY,V3dMetadata.integrityBytes(manifest));
                    return parse(files,Verification.FULL,policy,budget,token);
                }
            }
        }
    }
    public static V3dPackage parse(Map<ResourceKey, byte[]> files, Verification verification, DecodePolicy policy,
                                    ResourceBudget budget, CancellationToken token) throws IOException {
        Objects.requireNonNull(files); Objects.requireNonNull(verification); Objects.requireNonNull(policy);
        Objects.requireNonNull(budget); Objects.requireNonNull(token); token.check();
        var reservations=new ArrayList<ResourceBudget.Reservation>();
        try {
            var snapshot=new LinkedHashMap<ResourceKey,byte[]>(); long total=0;
            for (var entry:files.entrySet()) {
                token.check();
                ResourceKey key=Objects.requireNonNull(entry.getKey()); byte[] value=Objects.requireNonNull(entry.getValue());
                if (snapshot.size()>=policy.maxEntries()) throw new IOException("V3D package entry limit");
                if (key.path().split("/",-1).length>policy.maxDepth() || key.path().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>policy.maxStringBytes()) {
                    throw new IOException("V3D logical path limit");
                }
                if (!standard(key) && !decodedPath(key.path())) throw new IOException("Unknown/unsafe V3D package path");
                if (snapshot.containsKey(key)) throw new IOException("Duplicate V3D resource key");
                if (value.length>Math.min(policy.maxInputBytes(),policy.maxExpandedBytes())-total) throw new IOException("Aggregate V3D input/expanded limit");
                reservations.add(reserve(budget,value.length)); total+=value.length; snapshot.put(key,value.clone());
            }
            byte[] manifestBytes=required(snapshot,V3dManifest.MANIFEST), integrityBytes=required(snapshot,V3dManifest.INTEGRITY);
            if (manifestBytes.length>(16<<20) || integrityBytes.length>(16<<20)) throw new IOException("V3D metadata size limit");
            var jsonPolicy=new DecodePolicy(Math.min(policy.maxInputBytes(),16<<20),policy.maxExpandedBytes(),policy.maxEntries(),
                    Math.min(policy.maxStringBytes(),65535),Math.min(policy.maxDepth(),64));
            V3dManifest manifest;
            try (var quota=new BoundedJson.Quota(jsonPolicy,budget,token)) {
                JsonObject main=BoundedJson.read(manifestBytes,quota), integrity=BoundedJson.read(integrityBytes,quota);
                manifest=V3dMetadata.read(main,integrity);
                // Retain a conservative independent charge for immutable metadata after JSON construction closes.
                long metadata=4096+manifest.decoded().map(decoded->decoded.files().keySet().stream()
                        .mapToLong(key->256L+key.path().length()*2L).sum()).orElse(0L);
                if (metadata>policy.maxExpandedBytes()-total) throw new IOException("V3D retained metadata limit");
                reservations.add(reserve(budget,metadata));
            }
            verifyFile(snapshot,manifest.source().path(),manifest.source().size(),manifest.source().sha256(),token);
            verifyFile(snapshot,manifest.wire().path(),manifest.wire().rawSize(),manifest.wire().sha256(),token);
            byte[] wire=snapshot.get(V3dManifest.WIRE);
            if (ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN).getInt()!=manifest.source().innerVersion()) {
                throw new IOException("V3D wire inner version mismatch");
            }
            if (verification==Verification.FULL && manifest.decoded().isPresent()) {
                for (var entry:manifest.decoded().orElseThrow().files().entrySet()) {
                    verifyFile(snapshot,entry.getKey(),entry.getValue().size(),entry.getValue().sha256(),token);
                }
            }
            token.check();
            var result=new V3dPackage(snapshot,manifest,verification,reservations); reservations.clear(); return result;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid V3D package metadata/path",invalid); }
        finally { reservations.forEach(ResourceBudget.Reservation::close); }
    }
    static ResourceBudget.Reservation reserve(ResourceBudget budget,long bytes) throws IOException {
        try { return budget.reserve(bytes); } catch (IllegalStateException exhausted) { throw new IOException("V3D resource budget exceeded",exhausted); }
    }
    static boolean standard(ResourceKey key) { return Set.of(V3dManifest.SOURCE,V3dManifest.WIRE,V3dManifest.MANIFEST,V3dManifest.INTEGRITY).contains(key); }
    static boolean decodedPath(String path) {
        return path.matches("(?:legacy|geometry|animations|controllers|textures|gui|avatars|sounds|functions|lang)/[A-Za-z0-9_.-]+") && !path.contains("..");
    }
    private static byte[] required(Map<ResourceKey,byte[]> files,ResourceKey key) throws IOException {
        byte[] bytes=files.get(key); if (bytes==null) throw new IOException("Required V3D resource missing: "+key.path()); return bytes;
    }
    private static void verifyFile(Map<ResourceKey,byte[]> files,ResourceKey key,long size,String hash,CancellationToken token) throws IOException {
        byte[] bytes=required(files,key);
        if (size!=bytes.length || !sha256(bytes,token).equals(hash)) throw new IOException("V3D resource size/hash mismatch: "+key.path());
    }
    static String sha256(byte[] bytes,CancellationToken token) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for (int offset=0;offset<bytes.length;offset+=8192) { token.check(); digest.update(bytes,offset,Math.min(8192,bytes.length-offset)); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
