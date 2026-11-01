package dev.spm.core.api;

import java.io.IOException;
import java.util.Objects;

/** Import failed transactionally; no partially owned result is published. */
public final class ModelImportException extends IOException {
    private static final long serialVersionUID=1L;
    // Structured diagnostics are an explicit API value, not Java's object-serialization wire.
    @SuppressWarnings("serial")
    private final ImportDiagnostic diagnostic;
    public ModelImportException(ImportDiagnostic diagnostic,Throwable cause) {
        super(Objects.requireNonNull(diagnostic).message(),cause);this.diagnostic=diagnostic;
    }
    public ImportDiagnostic diagnostic(){return diagnostic;}
}
