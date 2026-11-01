package dev.spm.core.format.ysm;

import dev.spm.core.api.ResourceBudget;
import java.util.*;

/** Owned decrypted historical wire. The first four bytes are its little-endian inner version. */
public final class YsmWirePayload implements AutoCloseable {
    private final int version;
    private byte[] wire;
    private final List<ResourceBudget.Reservation> reservations;
    YsmWirePayload(int version, byte[] bytes, List<ResourceBudget.Reservation> reservations) {
        this.version = version;
        this.wire = bytes;
        this.reservations = new ArrayList<>(reservations);
    }
    public int innerVersion() { return version; }
    public synchronized byte[] bytes() {
        if (wire == null) throw new IllegalStateException("Wire payload is closed");
        return wire.clone();
    }
    @Override public synchronized void close() {
        wire = null;
        reservations.forEach(ResourceBudget.Reservation::close);
        reservations.clear();
    }
}
