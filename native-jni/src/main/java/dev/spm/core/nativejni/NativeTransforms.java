package dev.spm.core.nativejni;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.acceleration.ScalarTransforms;
import dev.spm.core.acceleration.TransformResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Explicit, content-checked optional provider. Never downloads or loads old libraries. */
public final class NativeTransforms {
    public static final int ABI_VERSION=4;
    private static Path loadedPath;
    private static String loadedDigest;
    private static boolean ready;
    /** Set when a provider loaded but failed validation; the JVM cannot rebind, so retries fail. */
    private static String poisoned;
    private NativeTransforms() { }
    public static synchronized NativeTransforms load(Path library,String sha256) throws IOException {
        Objects.requireNonNull(library); Objects.requireNonNull(sha256);
        if(!sha256.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Expected SHA-256 required");
        Path real=library.toRealPath();
        String actual;
        try { actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(real))); }
        catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        if(!actual.equalsIgnoreCase(sha256)) throw new IllegalArgumentException("Native content digest mismatch");
        if(loadedPath!=null && (!loadedPath.equals(real)||!loadedDigest.equals(actual))) {
            throw new IllegalStateException("Another native provider is already loaded");
        }
        if(loadedPath==null) {
            System.load(real.toString());
            // The library is now bound to this class, so a later load of a different file cannot
            // rebind the native methods. Validate first and only then publish the provider; on
            // failure record the real reason instead of reporting a false "already loaded".
            int abi=nAbiVersion();
            if(abi!=ABI_VERSION) {
                poisoned="Loaded native provider reports ABI "+abi+" but "+ABI_VERSION+" is required; the JVM cannot rebind, restart required";
                throw new IllegalArgumentException("Native ABI mismatch: "+abi+" != "+ABI_VERSION);
            }
            loadedPath=real; loadedDigest=actual; ready=true;
        }
        requireReady();
        return new NativeTransforms();
    }
    static synchronized void requireReady(){
        if(!ready) throw new IllegalStateException(poisoned!=null?poisoned
                :"Native provider unavailable; keep Java fallback or restart after a failed load");
    }
    public boolean supported(Isa isa) { return nSupported(Objects.requireNonNull(isa).code()); }
    public TransformResult transform(float[] matrix,float[] positions,Isa requested) {
        ScalarTransforms.validate(matrix,positions);
        Objects.requireNonNull(requested);
        int bytes=Math.multiplyExact(positions.length,4);
        try(NativeArena arena=NativeArena.allocate(64,bytes,bytes)) {
            ByteBuffer m=arena.slice(64), p=arena.slice(bytes), out=arena.slice(bytes);
            for(float v:matrix)m.putFloat(v);
            for(float v:positions)p.putFloat(v);
            long outcome=transformBuffers(m,p,out,positions.length/3,requested);
            float[] result=new float[positions.length];
            for(int i=0;i<result.length;i++)result[i]=out.getFloat(i*4);
            return new TransformResult(result,(int)outcome,Isa.fromCode((int)(outcome>>>32)));
        }
    }
    /** Read-only count of live per-call native arenas; never requires a GC to return to zero. */
    public static long ownedArenaCount() { return NativeArena.owned(false); }
    /** Physical bytes retained by live native arenas (a zero-capacity arena retains one byte). */
    public static long ownedArenaBytes() { return NativeArena.owned(true); }
    /** Operates from each buffer's base, ignoring position/limit; caller owns buffers for the call. */
    public long transformBuffers(ByteBuffer matrix,ByteBuffer positions,ByteBuffer output,int count,Isa requested) {
        Objects.requireNonNull(matrix); Objects.requireNonNull(positions); Objects.requireNonNull(output); Objects.requireNonNull(requested);
        long bytes=(long)count*12;
        if(count<0 || !matrix.isDirect() || !positions.isDirect() || !output.isDirect() || output.isReadOnly()
                || matrix.capacity()<64 || positions.capacity()<bytes || output.capacity()<bytes
                || matrix.order()!=ByteOrder.LITTLE_ENDIAN || positions.order()!=ByteOrder.LITTLE_ENDIAN || output.order()!=ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("Invalid native buffers, count or byte order");
        }
        long result=nTransform(matrix,positions,output,count,requested.code());
        if(result<0) throw new IllegalArgumentException("Native transform rejected, status="+result);
        if((int)result!=count) throw new IllegalStateException("Incomplete native result");
        return result;
    }
    private static native int nAbiVersion();
    private static native boolean nSupported(int isa);
    private static native long nTransform(ByteBuffer matrix,ByteBuffer input,ByteBuffer output,int count,int isa);
}
