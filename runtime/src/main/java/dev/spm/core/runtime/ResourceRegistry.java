package dev.spm.core.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** CPU asset generations. Retired assets close only after their last lease.
 * GPU completion is a separate Host owner and must not be attached here prematurely. */
public final class ResourceRegistry<T> implements AutoCloseable {
    private final Map<String,Slot> current=new HashMap<>();
    private final Map<String,Long> pending=new HashMap<>();
    /** Slots retired while still leased: only their outstanding leases can close them. */
    private final Set<Slot> orphaned=new LinkedHashSet<>();
    private final ArrayDeque<RuntimeException> cleanupFailures=new ArrayDeque<>();
    private long generation;
    private boolean closed;

    public synchronized long begin(String reference) {
        requireOpen();requireReference(reference);
        long ticket=next();pending.put(reference,ticket);return ticket;
    }
    /**
     * Publishes an asset. A value offered to an already closed registry is closed here instead of
     * dropped, mirroring {@link #publishIfCurrent} so a late asynchronous publish cannot leak it.
     */
    public long publish(String reference,T value,Runnable closeAsset) {
        requireReference(reference);Objects.requireNonNull(value);Objects.requireNonNull(closeAsset);
        Slot release;
        long ticket;
        boolean accepted;
        synchronized(this) {
            accepted=!closed;
            if(accepted) {
                ticket=next();pending.remove(reference);
                release=retireSlot(current.put(reference,new Slot(reference,ticket,value,closeAsset)));
            } else {ticket=0;release=reject(reference,value,closeAsset);}
        }
        clean(release);
        if(!accepted) throw new IllegalStateException("Registry closed");
        return ticket;
    }
    /** Rejected staged assets are closed by this method, including after registry shutdown. */
    public boolean publishIfCurrent(String reference,long ticket,T value,Runnable closeAsset) {
        requireReference(reference);Objects.requireNonNull(value);Objects.requireNonNull(closeAsset);
        Slot release;
        boolean accepted;
        synchronized(this) {
            accepted=!closed && ticket>0 && Objects.equals(pending.get(reference),ticket);
            if(accepted) {
                // One ticket publishes at most once; late repeats are rejected.
                pending.remove(reference);
                release=retireSlot(current.put(reference,new Slot(reference,ticket,value,closeAsset)));
            } else release=reject(reference,value,closeAsset);
        }
        clean(release);return accepted;
    }
    public synchronized Optional<Lease> acquire(String reference) {
        requireReference(reference);
        Slot slot=current.get(reference);
        if(closed||slot==null)return Optional.empty();
        slot.leases=Math.incrementExact(slot.leases);return Optional.of(new Lease(slot));
    }
    public void retire(String reference) {
        Slot release;
        synchronized(this) {requireReference(reference);pending.remove(reference);release=retireSlot(current.remove(reference));}
        clean(release);
    }
    /**
     * Closes every asset this registry still owns. A slot retired earlier while leased cannot be
     * closed here, because an outstanding lease may still read it; those slots are reported through
     * {@link #leakedLeases()} instead of being forgotten, so a leaked lease stays visible.
     */
    @Override public void close() {
        List<Slot> releases=new ArrayList<>();
        synchronized(this) {
            if(closed)return;closed=true;pending.clear();
            for(Slot slot:new ArrayList<>(current.values())){Slot release=retireSlot(slot);if(release!=null)releases.add(release);}
            current.clear();
        }
        releases.forEach(this::clean);
    }
    /** References of slots retired while still leased and never returned; non-empty means a leak. */
    public synchronized List<String> leakedLeases() {
        List<String> result=new ArrayList<>(orphaned.size());
        for(Slot slot:orphaned)result.add(slot.reference);
        return List.copyOf(result);
    }
    public synchronized List<RuntimeException> cleanupFailures() {return List.copyOf(cleanupFailures);}
    private synchronized void release(Slot slot) {
        if(--slot.leases<0)throw new IllegalStateException("Unbalanced asset lease");
    }
    private Slot reject(String reference,T value,Runnable closeAsset) {
        Slot slot=new Slot(reference,0,value,closeAsset);
        slot.retired=true;slot.cleaned=true;return slot;
    }
    private Slot retireSlot(Slot slot) {
        if(slot==null)return null;slot.retired=true;
        if(slot.leases==0&&!slot.cleaned){slot.cleaned=true;orphaned.remove(slot);return slot;}
        orphaned.add(slot);return null;
    }
    private void clean(Slot slot) {
        if(slot==null)return;
        try {slot.closeAsset.run();}
        catch(RuntimeException failure) {record(failure);}
    }
    private synchronized void record(RuntimeException failure) {
        if(cleanupFailures.size()==64)cleanupFailures.removeFirst();
        cleanupFailures.addLast(failure);
    }
    private long next(){generation=Math.incrementExact(generation);return generation;}
    private void requireOpen(){if(closed)throw new IllegalStateException("Registry closed");}
    private static void requireReference(String ref){if(Objects.requireNonNull(ref).isBlank())throw new IllegalArgumentException("Blank asset reference");}
    private final class Slot {
        final String reference;final long generation;final T value;final Runnable closeAsset;
        int leases;boolean retired;boolean cleaned;
        Slot(String reference,long generation,T value,Runnable closeAsset){
            this.reference=reference;this.generation=generation;this.value=value;this.closeAsset=closeAsset;
        }
    }
    public final class Lease implements AutoCloseable {
        private final Slot slot;private final AtomicBoolean returned=new AtomicBoolean();
        private Lease(Slot slot){this.slot=slot;}
        public long generation(){return slot.generation;}
        /** Read under the registry monitor so a concurrent last-lease close cannot free it first. */
        public T value(){
            synchronized(ResourceRegistry.this) {
                if(returned.get())throw new IllegalStateException("Lease returned");
                return slot.value;
            }
        }
        @Override public void close(){
            Slot release=null;
            synchronized(ResourceRegistry.this){
                if(!returned.compareAndSet(false,true))return;
                release(slot);
                if(slot.retired&&slot.leases==0&&!slot.cleaned){slot.cleaned=true;orphaned.remove(slot);release=slot;}
            }
            clean(release);
        }
    }
}
