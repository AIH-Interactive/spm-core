package dev.spm.core.api;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Shared live-byte budget; child tasks must use the same instance. */
public final class ResourceBudget {
    private final long limit;
    private final AtomicLong used = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    public ResourceBudget(long limit) {
        if (limit < 0) throw new IllegalArgumentException("Negative budget");
        this.limit = limit;
    }
    public long limit() { return limit; }
    public long used() { return used.get(); }
    /** Highest admitted concurrent allocation credits over this budget's lifetime. */
    public long peakUsed() { return peak.get(); }
    /** Failed owner registration returns the already admitted credits. */
    public Reservation reserve(long bytes,Collection<? super Reservation> owner) {
        Objects.requireNonNull(owner);var ticket=reserve(bytes);
        try{if(!owner.add(ticket))throw new IllegalStateException("Reservation owner rejected the ticket");return ticket;}
        catch(RuntimeException|Error failure){ticket.close();throw failure;}
    }
    public Reservation reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
        Reservation reservation=null;
        while (true) {
            long before = used.get();
            if (bytes > limit - before) throw new ResourceBudgetExceededException();
            // Allocate the ticket before admission so allocation failure cannot orphan credits.
            if(reservation==null)reservation=new Reservation(bytes);
            if (used.compareAndSet(before, before + bytes)) {
                long after=before+bytes,observed=peak.get();
                while(after>observed&&!peak.compareAndSet(observed,after))observed=peak.get();
                return reservation;
            }
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
