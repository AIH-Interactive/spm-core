package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.util.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;

/** Owns imported variants, exact archival tree and original texture bytes. */
public final class YsmImportResult implements AutoCloseable {
    public record Variant(String sourceType, String sourceKey, Optional<ModelDocument> document, String limitation,
                          HistoricalValue geometry, HistoricalValue animations, HistoricalValue controllers) { }
    private final YsmHistoricalModel historical;
    private List<Variant> variants;
    private Map<ResourceKey,byte[]> resources;
    private final List<ResourceBudget.Reservation> reservations;
    YsmImportResult(YsmHistoricalModel historical,List<Variant> variants,Map<ResourceKey,byte[]> resources,
                    List<ResourceBudget.Reservation> reservations) {
        this.historical=historical;this.variants=List.copyOf(variants);
        this.resources=Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        this.reservations=new ArrayList<>(reservations);
    }
    private void requireOpen(){if(variants==null)throw new IllegalStateException("YSM import is closed");}
    public synchronized List<Variant> variants() { requireOpen();return variants; }
    public synchronized HistoricalValue.Struct archivalRoot() { requireOpen();return historical.root(); }
    public synchronized Set<ResourceKey> resources() { requireOpen();return Set.copyOf(resources.keySet()); }
    public synchronized byte[] read(ResourceKey key) throws IOException {
        requireOpen();byte[] bytes=resources.get(key);if(bytes==null)throw new IOException("Unknown YSM resource");return bytes.clone();
    }
    /** Borrowed stream; the caller must pin this import owner for the lifetime of the stream. */
    public synchronized InputStream open(ResourceKey key) throws IOException {
        requireOpen();byte[] bytes=resources.get(key);if(bytes==null)throw new IOException("Unknown YSM resource");
        return new ByteArrayInputStream(bytes);
    }
    @Override public synchronized void close() {
        if(variants==null)return;variants=null;resources=Map.of();historical.close();
        reservations.forEach(ResourceBudget.Reservation::close);reservations.clear();
    }
}
