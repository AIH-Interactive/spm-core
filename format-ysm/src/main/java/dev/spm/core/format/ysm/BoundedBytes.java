package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.*;
import java.util.*;

/** Scope-owned staging buffer. All backing-array capacity is reserved before growth. */
final class BoundedBytes extends OutputStream {
    private final long limit;
    private final ResourceBudget budget;
    private final CancellationToken token;
    /** Live backing-array capacity; replaced on every growth after the new array is charged. */
    private final List<ResourceBudget.Reservation> capacity = new ArrayList<>();
    private byte[] data = new byte[0];
    private int size;
    private boolean closed;
    BoundedBytes(long limit, ResourceBudget budget, CancellationToken token) {
        this.limit = Math.min(limit, Integer.MAX_VALUE - 16L); this.budget = budget; this.token = token;
    }
    int size() { return size; }
    /**
     * Copies the retained bytes. Callers must reserve the returned array's bytes before this call so
     * the copy is charged while the backing array is still live, and must transfer that reservation
     * to whoever keeps the copy.
     */
    byte[] copy() {
        if (closed) throw new IllegalStateException("Staging buffer closed");
        return Arrays.copyOf(data, size);
    }
    private void prepare(int extra) throws IOException {
        token.check();
        if (closed) throw new IOException("Staging buffer closed");
        if (extra < 0 || extra > limit - size) throw new IOException("Encoded byte limit");
        int needed = size + extra;
        if (needed > data.length) {
            int next = (int) Math.min(limit, Math.max(needed, Math.max(256L, (long) data.length * 2)));
            // Charge both old and replacement backing arrays during the copy.
            var reservation = YsmArchiveCodec.reserve(budget, next);
            try { data = Arrays.copyOf(data, next); }
            catch (RuntimeException | Error failed) { reservation.close(); throw failed; }
            capacity.forEach(ResourceBudget.Reservation::close); capacity.clear(); capacity.add(reservation);
        }
    }
    @Override public void write(int value) throws IOException { prepare(1); data[size++] = (byte) value; }
    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, bytes.length); prepare(count);
        System.arraycopy(bytes, offset, data, size, count); size += count;
    }
    void littleInt(int bits) throws IOException { for (int i = 0; i < 4; i++) write(bits >>> (8 * i)); }
    void varint(long bits) throws IOException {
        do { int low = (int) (bits & 127); bits >>>= 7; write(low | (bits == 0 ? 0 : 128)); } while (bits != 0);
    }
    @Override public void close() {
        closed = true; data = new byte[0]; capacity.forEach(ResourceBudget.Reservation::close); capacity.clear();
    }
}
