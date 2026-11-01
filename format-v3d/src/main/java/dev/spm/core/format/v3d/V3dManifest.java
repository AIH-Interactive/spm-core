package dev.spm.core.format.v3d;

import dev.spm.core.api.ResourceKey;
import java.util.*;

/** Public archival metadata; all types are JDK/Core types, independent of the JSON implementation. */
public record V3dManifest(String format, int formatVersion, int decoderProfile, Source source, Wire wire,
                           Optional<Decoded> decoded) {
    public static final String FORMAT = "ysm-v3-decoded";
    public static final int VERSION = 1;
    public static final ResourceKey SOURCE = new ResourceKey("source/original.ysm");
    public static final ResourceKey WIRE = new ResourceKey("source/wire.bin");
    public static final ResourceKey MANIFEST = new ResourceKey("v3d.json");
    public static final ResourceKey INTEGRITY = new ResourceKey("integrity.json");
    public V3dManifest { Objects.requireNonNull(format); Objects.requireNonNull(source); Objects.requireNonNull(wire); Objects.requireNonNull(decoded); }
    public record Source(ResourceKey path, String sha256, long size, int envelopeVersion, int innerVersion) { }
    public record Wire(ResourceKey path, String compression, String sha256, long rawSize) { }
    public record Decoded(int schemaVersion, int parserProfile, String derivedFromWireSha256,
                           Map<ResourceKey, FileDigest> files) {
        public Decoded { files = Collections.unmodifiableMap(new LinkedHashMap<>(files)); }
    }
    public record FileDigest(long size, String sha256) { }
}
