package dev.spm.core.api;

import java.util.Objects;

/** Explicit shared security context. Profiles describe conversion/binding, never authorization. */
public record ImportRequest(ModelSource source, FormatHint hint, DecodePolicy policy, ResourceBudget budget,
                            CancellationToken cancellation, String sourceProfile, String actionProfile) {
    public ImportRequest(ModelSource source,FormatHint hint,DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) {
        this(source,hint,policy,budget,cancellation,"default","none");
    }
    public ImportRequest {
        Objects.requireNonNull(source);Objects.requireNonNull(hint);Objects.requireNonNull(policy);
        Objects.requireNonNull(budget);Objects.requireNonNull(cancellation);
        if(Objects.requireNonNull(sourceProfile).isBlank()||sourceProfile.length()>128
                ||Objects.requireNonNull(actionProfile).isBlank()||actionProfile.length()>128)
            throw new IllegalArgumentException("Invalid import profile");
    }
}
