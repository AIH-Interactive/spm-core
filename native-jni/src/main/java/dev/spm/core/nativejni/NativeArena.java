package dev.spm.core.nativejni;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Call-scoped Rust allocation. Views are internal borrowed memory: never publish them, retain them
 * beyond close, or share this owner with another thread. There is deliberately no Cleaner and no
 * provider-wide close that could invalidate an active call. Only copied Java results may escape.
 */
final class NativeArena implements AutoCloseable {
    private long handle;
    private ByteBuffer memory;
    private int offset;

    // JNI constructs this only after allocation registration. Keep construction free of callbacks.
    private NativeArena(long handle, ByteBuffer memory) {
        this.handle=handle;
        this.memory=memory;
    }

    static NativeArena allocate(int... lengths) {
        NativeTransforms.requireReady();
        int total=0;
        for(int length:lengths) {
            if(length<0)throw new IllegalArgumentException("Negative native arena length");
            total=Math.addExact(total,length);
        }
        NativeArena arena=nAllocate(total);
        if(arena==null)throw new OutOfMemoryError("Native arena allocation failed");
        return arena;
    }

    ByteBuffer slice(int length) {
        if(memory==null)throw new IllegalStateException("Native arena is closed");
        if(length<0 || length>memory.capacity()-offset)throw new IndexOutOfBoundsException("Native arena slice exceeds allocation");
        ByteBuffer view=memory.slice(offset,length).order(ByteOrder.LITTLE_ENDIAN);
        offset+=length;
        return view;
    }

    @Override public void close() {
        if(memory!=null) {
            memory=null;
            long owned=handle;
            handle=0;
            nFree(owned);
        }
    }

    static long owned(boolean bytes) {NativeTransforms.requireReady();return nOwned(bytes);}
    private static native NativeArena nAllocate(int bytes);
    private static native void nFree(long handle);
    private static native long nOwned(boolean bytes);
}
