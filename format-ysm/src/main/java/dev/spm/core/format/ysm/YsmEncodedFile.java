package dev.spm.core.format.ysm;

import dev.spm.core.api.ResourceBudget;

/** Owned complete .ysm file produced by the legacy resource writer. */
public final class YsmEncodedFile implements AutoCloseable {
    private byte[] encoded;
    private final ResourceBudget.Reservation reservation;
    YsmEncodedFile(byte[] bytes, ResourceBudget.Reservation reservation) { this.encoded = bytes; this.reservation = reservation; }
    public synchronized byte[] bytes() {
        if (encoded == null) throw new IllegalStateException("Encoded file is closed");
        return encoded.clone();
    }
    @Override public synchronized void close() { encoded = null; reservation.close(); }
}
