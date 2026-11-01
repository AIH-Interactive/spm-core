package dev.spm.core.format.v3d;

import dev.spm.core.api.ResourceKey;
import dev.spm.core.api.ResourceBudget;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Immutable owned logical package. Hosts own filesystem paths, atomic publication and cache locks. */
public final class V3dPackage implements AutoCloseable {
    private final Map<ResourceKey,byte[]> files;
    private final V3dManifest manifest;
    private final V3dCodec.Verification verification;
    private final List<ResourceBudget.Reservation> reservations;
    private boolean closed;
    V3dPackage(Map<ResourceKey,byte[]> files,V3dManifest manifest,V3dCodec.Verification verification,List<ResourceBudget.Reservation> reservations) {
        this.files=new LinkedHashMap<>(files); this.manifest=manifest; this.verification=verification;
        this.reservations=new ArrayList<>(reservations);
    }
    public synchronized Set<ResourceKey> entries() { open(); return Set.copyOf(files.keySet()); }
    public synchronized byte[] read(ResourceKey key) throws IOException {
        open(); byte[] bytes=files.get(key);
        if (bytes==null) throw new IOException("V3D resource is absent: "+key.path());
        return bytes.clone();
    }
    /** Borrowed immutable stream; the caller must pin this package owner until the stream closes. */
    public synchronized InputStream open(ResourceKey key) throws IOException {
        open(); byte[] bytes=files.get(Objects.requireNonNull(key));
        if (bytes==null) throw new IOException("V3D resource is absent: "+key.path());
        return new ByteArrayInputStream(bytes);
    }
    public V3dManifest manifest() { return manifest; }
    public V3dCodec.Verification verification() { return verification; }
    public String generationName() {
        return manifest.source().sha256()+"-p"+manifest.decoderProfile()
                +manifest.decoded().map(decoded->"-d"+decoded.parserProfile()).orElse("")+".v3d";
    }
    /** Returns a caller-owned defensive copy; it never re-encodes the decoded workspace. */
    public synchronized byte[] restoreOriginal() { open(); return files.get(V3dManifest.SOURCE).clone(); }
    private void open() { if (closed) throw new IllegalStateException("V3D package closed"); }
    /** Idempotent: repeated closes release each reservation once. */
    @Override public synchronized void close() {
        if (closed) return;
        closed=true; files.clear(); reservations.forEach(ResourceBudget.Reservation::close); reservations.clear();
    }
}
