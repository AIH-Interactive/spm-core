package dev.spm.core.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Set;

/** An authorized logical resource domain with an immutable entry snapshot; never a filesystem listing. */
public interface ResourceCatalog extends ResourceResolver {
    Set<ResourceKey> entries();

    /** Preserves a caller-authorized entry set without taking ownership of the resolver. */
    static ResourceCatalog of(Set<ResourceKey> entries,ResourceResolver resolver) {
        Set<ResourceKey> snapshot=Set.copyOf(entries);
        Objects.requireNonNull(resolver);
        return new ResourceCatalog() {
            @Override public Set<ResourceKey> entries() { return snapshot; }
            @Override public InputStream open(ResourceKey key) throws IOException {
                if(!snapshot.contains(key)) throw new IOException("Resource outside authorized catalog: "+key.path());
                return resolver.open(key);
            }
        };
    }
}
