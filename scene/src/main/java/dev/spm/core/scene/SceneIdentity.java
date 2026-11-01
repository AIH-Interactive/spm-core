package dev.spm.core.scene;

import java.util.concurrent.atomic.AtomicLong;

/** Shared identity domain across scalar scenes and typed pose players. */
final class SceneIdentity {
    private static final AtomicLong NEXT=new AtomicLong();
    private SceneIdentity() { }
    static long next() {
        long identity=NEXT.incrementAndGet();
        if (identity<=0) throw new IllegalStateException("Scene identity exhausted");
        return identity;
    }
}
