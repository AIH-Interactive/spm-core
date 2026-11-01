package dev.spm.core.api;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit request-scoped cancellation, safe to share with worker tasks. */
public final class CancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void check() {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Core request cancelled");
        }
    }
}
