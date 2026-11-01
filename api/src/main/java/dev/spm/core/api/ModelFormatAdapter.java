package dev.spm.core.api;

import java.nio.ByteBuffer;

/** Extension SPI for future model formats; implementations share the request's budget and token. */
public interface ModelFormatAdapter extends ModelImporter {
    /** Unique stable format identifier, independent of a filename extension or Host identity. */
    String formatId();
    /** Read-only prefix of at most 64 bytes. This probe must not open resources or allocate a model. */
    boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix);
}
