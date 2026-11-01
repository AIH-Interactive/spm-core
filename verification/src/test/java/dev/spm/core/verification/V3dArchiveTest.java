package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import dev.spm.core.format.v3d.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class V3dArchiveTest {
    private static final DecodePolicy POLICY = DecodePolicy.conservative();
    private static byte[] sample(String path) throws IOException {
        try (var input = V3dArchiveTest.class.getResourceAsStream("/ysm/" + path)) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static Map<ResourceKey, byte[]> snapshot(V3dPackage archive) throws IOException {
        var files = new LinkedHashMap<ResourceKey, byte[]>();
        for (ResourceKey key : archive.entries()) files.put(key, archive.read(key));
        return files;
    }

    @Test void capturesExactSourceAndWireWithOwnedImmutableBytesAndCompatibleMetadata() throws Exception {
        byte[] wire = sample("historical/v32.wire"); var budget = new ResourceBudget(128L << 20);
        try (var source = YsmEnvelopeCodec.encode(wire, "archive", POLICY, budget, new CancellationToken())) {
            byte[] original = source.bytes(); long before = budget.used();
            try (var archive = V3dCodec.capture(original, POLICY, budget, new CancellationToken())) {
                assertEquals(Set.of(V3dManifest.SOURCE,V3dManifest.WIRE,V3dManifest.MANIFEST,V3dManifest.INTEGRITY), archive.entries());
                assertArrayEquals(wire, archive.read(V3dManifest.WIRE));
                assertArrayEquals(original, archive.restoreOriginal());
                assertEquals(sha(original) + "-p1.v3d", archive.generationName());
                assertEquals(32, archive.manifest().source().innerVersion());
                assertEquals(sha(wire), archive.manifest().wire().sha256());
                assertThrows(UnsupportedOperationException.class, () -> archive.entries().clear());
                byte[] copy = archive.read(V3dManifest.WIRE); copy[0] = 99;
                assertEquals(32, archive.read(V3dManifest.WIRE)[0]);
                Map<ResourceKey,byte[]> files = snapshot(archive);
                try (var reopened = V3dCodec.parse(files,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken())) {
                    Arrays.fill(files.get(V3dManifest.SOURCE),(byte) 0);
                    assertArrayEquals(original,reopened.restoreOriginal());
                }
                assertTrue(budget.used() > before);
            }
            assertEquals(before,budget.used());
        }
        assertEquals(0,budget.used());
    }

    @Test void rejectsCorruptHashesVersionsDependenciesDuplicateJsonAndPackageLimitsWithoutLeaks() throws Exception {
        byte[] source = sample("legacy_v3_dynamic_vector.ysm"); var budget = new ResourceBudget(128L << 20);
        Map<ResourceKey,byte[]> valid;
        try (var captured = V3dCodec.capture(source,POLICY,budget,new CancellationToken())) { valid=snapshot(captured); }
        for (String[] change : List.of(new String[] {"\"inner_version\":1","\"inner_version\":2"},
                new String[] {"\"format_version\":1","\"format_version\":2"},
                new String[] {"source/original.ysm","../escape.ysm"})) {
            var bad = new LinkedHashMap<>(valid);
            String json = new String(bad.get(V3dManifest.MANIFEST),StandardCharsets.UTF_8);
            bad.put(V3dManifest.MANIFEST,json.replace(change[0],change[1]).getBytes(StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> V3dCodec.parse(bad,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken()));
            assertEquals(0,budget.used());
        }
        var corrupt = new LinkedHashMap<>(valid); byte[] changed=corrupt.get(V3dManifest.WIRE).clone(); changed[10]^=1; corrupt.put(V3dManifest.WIRE,changed);
        assertThrows(IOException.class, () -> V3dCodec.parse(corrupt,V3dCodec.Verification.ARCHIVAL_RECOVERY,POLICY,budget,new CancellationToken()));
        var duplicate = new LinkedHashMap<>(valid);
        String text=new String(duplicate.get(V3dManifest.MANIFEST),StandardCharsets.UTF_8);
        duplicate.put(V3dManifest.MANIFEST,("{\"format_version\":1,"+text.substring(1)).getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> V3dCodec.parse(duplicate,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken()));
        for (DecodePolicy limit : List.of(new DecodePolicy(100,100,16,128,16),new DecodePolicy(100000,100000,3,128,16),
                new DecodePolicy(100000,100000,1000,128,1))) {
            assertThrows(IOException.class, () -> V3dCodec.parse(valid,V3dCodec.Verification.FULL,limit,budget,new CancellationToken()));
        }
        var cancelled=new CancellationToken(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> V3dCodec.parse(valid,V3dCodec.Verification.FULL,POLICY,budget,cancelled));
        var tiny = new ResourceBudget(100);
        assertThrows(IOException.class, () -> V3dCodec.parse(valid,V3dCodec.Verification.FULL,POLICY,tiny,new CancellationToken()));
        assertEquals(0,tiny.used()); assertEquals(0,budget.used());
    }

    @Test void recoveryIgnoresOnlyDecodedContentEditsAndRetainsItsMetadataDependencyChecks() throws Exception {
        byte[] source=sample("legacy_v3_dynamic_vector.ysm"); var budget=new ResourceBudget(128L<<20);
        Map<ResourceKey,byte[]> files;
        try (var archive=V3dCodec.capture(source,POLICY,budget,new CancellationToken())) { files=snapshot(archive); }
        byte[] view="{\"schema\":\"LegacyV3Decoded\",\"schema_version\":1}".getBytes(StandardCharsets.UTF_8);
        String wireHash=sha(files.get(V3dManifest.WIRE));
        String decoded="{\"schema_version\":1,\"parser_profile\":1,\"derived_from_wire_sha256\":\""+wireHash
                +"\",\"files\":{\"legacy/model.json\":{\"size\":"+view.length+",\"sha256\":\""+sha(view)+"\"}}}";
        for (ResourceKey key:List.of(V3dManifest.MANIFEST,V3dManifest.INTEGRITY)) {
            String json=new String(files.get(key),StandardCharsets.UTF_8);
            files.put(key,(json.substring(0,json.length()-1)+",\"decoded\":"+decoded+"}").getBytes(StandardCharsets.UTF_8));
        }
        files.put(new ResourceKey("legacy/model.json"),view);
        try (var full=V3dCodec.parse(files,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken())) {
            assertEquals(sha(source)+"-p1-d1.v3d",full.generationName());
        }
        files.put(new ResourceKey("legacy/model.json"),new byte[] {99});
        assertThrows(IOException.class, () -> V3dCodec.parse(files,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken()));
        try (var recovery=V3dCodec.parse(files,V3dCodec.Verification.ARCHIVAL_RECOVERY,POLICY,budget,new CancellationToken())) {
            assertArrayEquals(source,recovery.restoreOriginal());
        }
        String bad=new String(files.get(V3dManifest.INTEGRITY),StandardCharsets.UTF_8).replace("\"parser_profile\":1","\"parser_profile\":2");
        files.put(V3dManifest.INTEGRITY,bad.getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> V3dCodec.parse(files,V3dCodec.Verification.ARCHIVAL_RECOVERY,POLICY,budget,new CancellationToken()));
        assertEquals(0,budget.used());
    }

    @Test void rejectsLogicalDuplicateEntriesAndCaptureCancellationWithoutRetainedAllocations() throws Exception {
        byte[] source=sample("legacy_v3_dynamic_vector.ysm"); var budget=new ResourceBudget(128L<<20);
        var duplicated=new AbstractMap<ResourceKey,byte[]>() {
            @Override public Set<Map.Entry<ResourceKey,byte[]>> entrySet() {
                return new AbstractSet<>() {
                    @Override public int size() { return 2; }
                    @Override public Iterator<Map.Entry<ResourceKey,byte[]>> iterator() {
                        return List.of(Map.entry(V3dManifest.SOURCE,source),Map.entry(V3dManifest.SOURCE,source)).iterator();
                    }
                };
            }
        };
        assertThrows(IOException.class, () -> V3dCodec.parse(duplicated,V3dCodec.Verification.FULL,POLICY,budget,new CancellationToken()));
        var token=new CancellationToken(); token.cancel();
        assertThrows(CancellationException.class, () -> V3dCodec.capture(source,POLICY,budget,token));
        assertThrows(IOException.class, () -> V3dCodec.capture(new byte[80],POLICY,budget,new CancellationToken()));
        var tiny=new ResourceBudget(100);
        assertThrows(IOException.class, () -> V3dCodec.capture(source,POLICY,tiny,new CancellationToken()));
        assertEquals(0,budget.used()); assertEquals(0,tiny.used());
    }

    @Test void rejectsInvalidHashSizesProfilesAndAmbiguousMetadataInBothRecoveryModes() throws Exception {
        byte[] source=sample("legacy_v3_dynamic_vector.ysm"); var budget=new ResourceBudget(128L<<20);
        Map<ResourceKey,byte[]> valid;
        try (var captured=V3dCodec.capture(source,POLICY,budget,new CancellationToken())) { valid=snapshot(captured); }
        String json=new String(valid.get(V3dManifest.MANIFEST),StandardCharsets.UTF_8);
        String sourceHash=sha(source);
        List<String> corruptions=List.of(json.replace(sourceHash,"g".repeat(64)),
                json.replace("\"size\":"+source.length,"\"size\":"+(source.length+1)),
                json.replace("\"decoder_profile\":1","\"decoder_profile\":0"),
                json.replace("\"compression\":\"none\"","\"compression\":\"zstd\""),
                "/* comment */"+json,json+"{}",json.replace("\"format_version\":1","\"format_version\":1e99999"));
        for (String corruption:corruptions) {
            var files=new LinkedHashMap<>(valid); files.put(V3dManifest.MANIFEST,corruption.getBytes(StandardCharsets.UTF_8));
            for (V3dCodec.Verification mode:V3dCodec.Verification.values()) {
                assertThrows(IOException.class, () -> V3dCodec.parse(files,mode,POLICY,budget,new CancellationToken()));
                assertEquals(0,budget.used());
            }
        }
    }
}
