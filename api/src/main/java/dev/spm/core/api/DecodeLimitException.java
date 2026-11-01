package dev.spm.core.api;

import java.io.IOException;
import java.util.Objects;

/** Typed refusal at a decode limit, distinct from malformed wire bytes. */
public final class DecodeLimitException extends IOException {
    private static final long serialVersionUID=1L;
    private final ImportErrorCode code;
    public DecodeLimitException(ImportErrorCode code,String message){super(message);this.code=Objects.requireNonNull(code);}
    public ImportErrorCode code(){return code;}
}
