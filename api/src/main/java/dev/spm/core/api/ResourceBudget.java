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
    /**
     * Admits many charges against this budget while holding a single ticket.
     *
     * <p>Some decoders charge once per parsed node or per projected value, which with {@link #reserve}
     * means one ticket object per charge — and the tickets outweighed what they accounted for. A measured
     * corpus model held 704,146 live {@link Reservation}s: 22.5 MB of tickets plus 11.3 MB of their
     * {@code AtomicBoolean}s, for content of only tens of megabytes.
     *
     * <p>A ledger keeps {@link #used()} exact after every {@link #admit}, so admission and rejection
     * behave exactly as with individual reservations, and it consumes no object per charge. The trade is
     * that bytes must be released together, which is what these decoders do anyway: a quota or a
     * lowering keeps its charges for one import and releases them as a unit.
     *
     * <p>A ledger is confined to one decoder and must be closed by whoever owns it. Admitting after
     * close is a programming error and is rejected rather than silently over-running the budget.
     */
    public Ledger ledger() { return new Ledger(); }
    public final class Ledger implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        /** Admitted-but-not-yet-released bytes; non-zero only while the ledger is open. */
        private final AtomicLong outstanding = new AtomicLong();
        private Ledger() { }
        /** Admits bytes exactly as {@link #reserve(long)} would, without allocating a ticket. */
        public void admit(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
            if (bytes == 0) return;
            if (closed.get()) throw new IllegalStateException("Ledger already closed");
            while (true) {
                long before = used.get();
                if (bytes > limit - before) throw new ResourceBudgetExceededException();
                if (used.compareAndSet(before, before + bytes)) {
                    long after=before+bytes,observed=peak.get();
                    while(after>observed&&!peak.compareAndSet(observed,after))observed=peak.get();
                    outstanding.addAndGet(bytes);
                    return;
                }
            }
        }
        public long outstanding() { return outstanding.get(); }
        /**
         * Converts the outstanding amount into a single ordinary reservation and closes the ledger.
         *
         * <p>This is how a ledger-backed decoder hands its charge to a consumer that expects an
         * ordinary {@link Reservation}: one ticket for the whole amount, instead of one per charge.
         */
        public Reservation freeze() {
            if (!closed.compareAndSet(false, true))
                throw new IllegalStateException("Ledger already closed");
            long bytes = outstanding.getAndSet(0);
            return new Reservation(bytes);
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) used.addAndGet(-outstanding.getAndSet(0));
        }
    }
}
