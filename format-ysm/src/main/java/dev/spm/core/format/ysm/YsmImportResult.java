package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.util.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;

/**
 * Owns imported variants and their original texture bytes.
 *
 * <p>This type deliberately does <em>not</em> retain the decoded historical tree. The tree is a
 * generic, node-per-value representation whose retention cost dwarfs the lowered documents: a real
 * 5.6 MiB model expands to about 10.6 million nodes and roughly 1.45 GiB of heap, while the documents
 * derived from it need about 0.32 GiB. Holding both for the lifetime of an import made the retained
 * set more than five times larger than anything the caller could use.
 *
 * <p>Nothing is lost by dropping it. The lowered {@link ModelDocument} trees are plain Core values
 * and never alias a {@code HistoricalValue}: {@code ModelDocument.JsonValue} is a sealed interface
 * over JSON scalars, so it cannot hold one. Lossless archiving stays available through the original
 * source bytes, which the caller owns — {@code V3dCodec} already restores them byte for byte.
 */
public final class YsmImportResult implements AutoCloseable {
    /** A lowered variant. All fields are Core values; none references the decoded tree. */
    public record Variant(String sourceType, String sourceKey, Optional<ModelDocument> document, String limitation) { }
    private List<Variant> variants;
    private Map<ResourceKey,byte[]> resources;
    private final List<ResourceBudget.Reservation> reservations;
    YsmImportResult(List<Variant> variants,Map<ResourceKey,byte[]> resources,
                    List<ResourceBudget.Reservation> reservations) {
        this.variants=List.copyOf(variants);
        this.resources=Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        this.reservations=new ArrayList<>(reservations);
    }
    private void requireOpen(){if(variants==null)throw new IllegalStateException("YSM import is closed");}
    public synchronized List<Variant> variants() { requireOpen();return variants; }
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
        if(variants==null)return;variants=null;resources=Map.of();
        reservations.forEach(ResourceBudget.Reservation::close);reservations.clear();
    }
}
