package dev.spm.core.api;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Shared live-byte budget; child tasks must use the same instance. */
public final class ResourceBudget {
    private final long limit;
    private final AtomicLong used = new AtomicLong();
    public ResourceBudget(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative budget");
        this.limit = limit;
    }
    public long limit() { return limit; }
    public long used() { return used.get(); }
    public Reservation reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
        while (true) {
            long before = used.get();
            if (bytes > limit - before) throw new ResourceBudgetExceededException();
            if (used.compareAndSet(before, before + bytes)) return new Reservation(bytes);
        }
    }
    public final class Reservation implements AutoCloseable {
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Reservation(long bytes) { this.bytes = bytes; }
        public long bytes() { return bytes; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) used.addAndGet(-bytes);
        }
    }
}
