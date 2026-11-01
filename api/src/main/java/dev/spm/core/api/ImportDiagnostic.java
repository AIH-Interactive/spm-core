package dev.spm.core.api;

import java.util.Objects;

/** Bounded diagnostic data; byteOffset=-1 means the decoder did not provide a location. */
public record ImportDiagnostic(Severity severity, ImportErrorCode code, ResourceKey resource, long byteOffset, String message) {
    public enum Severity { INFO, WARNING, ERROR }
    public ImportDiagnostic {
        Objects.requireNonNull(severity);Objects.requireNonNull(code);Objects.requireNonNull(resource);
        if(byteOffset < -1 || Objects.requireNonNull(message).isBlank() || message.length()>2048)
            throw new IllegalArgumentException("Invalid import diagnostic");
    }
}
