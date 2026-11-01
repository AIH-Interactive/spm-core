package dev.spm.core.format.ysm;

import dev.spm.core.api.ResourceKey;
import dev.spm.core.api.ResourceBudget;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Owned raw resources from a historical V1/V2 file. Close releases its live byte budget. */
public final class ModelArchive implements AutoCloseable {
    private final int version;
    private final Map<ResourceKey, byte[]> files;
    private final List<ResourceBudget.Reservation> reservations;
    private boolean closed;

    ModelArchive(int version, Map<ResourceKey, byte[]> files, List<ResourceBudget.Reservation> reservations) {
        this.version = version;
        this.files = new LinkedHashMap<>(files);
        this.reservations = new ArrayList<>(reservations);
    }
    public synchronized Set<ResourceKey> entries() { ensureOpen(); return Set.copyOf(files.keySet()); }
    public synchronized byte[] read(ResourceKey key) throws IOException {
        ensureOpen();
        byte[] value = files.get(key);
        if (value == null) throw new IOException("Resource is absent: " + key.path());
        return value.clone();
    }
    public int outerVersion() { return version; }
    /** Borrowed bytes, valid while this archive owner remains open. No uncharged clone is made. */
    public synchronized InputStream open(ResourceKey key) throws IOException {
        ensureOpen();byte[] value=files.get(key);
        if(value==null)throw new IOException("Resource is absent: "+key.path());
        return new ByteArrayInputStream(value);
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Archive is closed"); }
    @Override public synchronized void close() {
        if (!closed) {
            closed = true;
            files.clear();
            reservations.forEach(ResourceBudget.Reservation::close);
            reservations.clear();
        }
    }
}
