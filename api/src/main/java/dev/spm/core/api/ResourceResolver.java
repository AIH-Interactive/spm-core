package dev.spm.core.api;

import java.io.IOException;
import java.io.InputStream;

/** Host authorizes the domain; callers close each returned stream. */
@FunctionalInterface
public interface ResourceResolver {
    InputStream open(ResourceKey key) throws IOException;
}
