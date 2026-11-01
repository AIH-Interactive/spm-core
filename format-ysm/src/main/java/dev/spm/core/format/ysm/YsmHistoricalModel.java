package dev.spm.core.format.ysm;

import dev.spm.core.api.ResourceBudget;
import java.util.*;

/** Owns an archival tree; borrowed root values must not outlive this owner. */
public final class YsmHistoricalModel implements AutoCloseable {
    private final int version;
    private HistoricalValue.Struct root;
    private final List<ResourceBudget.Reservation> reservations;
    YsmHistoricalModel(int version, HistoricalValue.Struct root, List<ResourceBudget.Reservation> reservations) {
        this.version = version; this.root = root; this.reservations = new ArrayList<>(reservations);
    }
    public int innerVersion() { return version; }
    public synchronized HistoricalValue.Struct root() {
        if (root == null) throw new IllegalStateException("Historical model is closed");
        return root;
    }
    @Override public synchronized void close() {
        root = null; reservations.forEach(ResourceBudget.Reservation::close); reservations.clear();
    }
}
