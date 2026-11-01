package dev.spm.core.api;

import java.util.Objects;

/** A root entry in a Host-authorized resource domain, never an implicit filesystem/network path. */
public record ModelSource(String identity, ResourceKey entry, ResourceResolver resolver) {
    public ModelSource {
        if(Objects.requireNonNull(identity).isBlank() || identity.length()>1024)throw new IllegalArgumentException("Invalid source identity");
        Objects.requireNonNull(entry);Objects.requireNonNull(resolver);
    }
}
