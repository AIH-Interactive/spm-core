package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.VertexBatch;
import dev.spm.core.nativejni.NativeBoneEvaluator;
import dev.spm.core.nativejni.NativeRigidRenderer;
import dev.spm.core.nativejni.NativeTransforms;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Leaks, delayed Cleaner release, shared scratch and exceptional exits must all fail here. */
@EnabledIfSystemProperty(named="spm.native.library", matches=".+")
class NativeOwnedBufferTest {
    private static final float[] IDENTITY={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};

    private static NativeTransforms load() throws Exception {
        Path path=Path.of(System.getProperty("spm.native.library"));
        return NativeTransforms.load(path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }

    // Reflection allows the RED test to run against the former provider with no ownership API.
    private static long owned(String diagnostic) throws Exception {
        Method method=null;
        for(Method candidate:NativeTransforms.class.getMethods()) if(candidate.getName().equals(diagnostic)) method=candidate;
        assertNotNull(method,"Native ownership must expose live physical allocation diagnostics");
        return (long)method.invoke(null);
    }

    private static void empty() throws Exception {
        assertEquals(0,owned("ownedArenaCount"),"All per-call owners must close without waiting for GC");
        assertEquals(0,owned("ownedArenaBytes"),"Physical native memory must be released before return");
    }

    @Test void repeatedCompleteBatchesReleaseMemoryBeforeReturningWithoutGc() throws Exception {
        var provider=load();
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        var pose=BonePose.identity(1);
        var vertices=new VertexBatch(new float[]{1,2,3},new float[]{0,1,0},new int[]{0});
        empty();
        for(int i=0;i<1000;i++) {
            assertArrayEquals(new float[]{1,2,3},provider.transform(IDENTITY,new float[]{1,2,3},Isa.SCALAR).positions());
            var bones=NativeBoneEvaluator.evaluate(skeleton,pose,IDENTITY,Isa.SCALAR);
            assertArrayEquals(new float[]{1,2,3},NativeRigidRenderer.transform(vertices,bones,Isa.SCALAR).positions());
            assertEquals(0,provider.transform(IDENTITY,new float[0],Isa.SCALAR).completed());
            empty();
        }
        var noBones=NativeBoneEvaluator.evaluate(new Skeleton(List.of(),new int[0],new float[0]),BonePose.identity(0),IDENTITY,Isa.SCALAR);
        assertEquals(0,NativeRigidRenderer.transform(new VertexBatch(new float[0],new float[0],new int[0]),noBones,Isa.SCALAR).vertexCount());
        empty();
    }

    @Test void validationNativeFailureAndCallerOwnedOutputRemainAtomic() throws Exception {
        var provider=load();
        assertThrows(IllegalArgumentException.class,()->provider.transform(new float[15],new float[]{1,2,3},Isa.SCALAR));
        assertThrows(NullPointerException.class,()->provider.transform(IDENTITY,new float[]{1,2,3},null));
        float[] overflow=IDENTITY.clone();overflow[0]=Float.MAX_VALUE;
        assertThrows(IllegalArgumentException.class,()->provider.transform(overflow,new float[]{2,0,0},Isa.SCALAR));
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[3]);
        float[] invalid=IDENTITY.clone();invalid[0]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->NativeBoneEvaluator.evaluate(skeleton,BonePose.identity(1),invalid,Isa.SCALAR));
        var bones=NativeBoneEvaluator.evaluate(skeleton,BonePose.identity(1),IDENTITY,Isa.SCALAR);
        var badVertices=new VertexBatch(new float[]{1,2,3},new float[]{0,1,0},new int[]{1});
        assertThrows(IllegalArgumentException.class,()->NativeRigidRenderer.transform(badVertices,bones,Isa.SCALAR));
        ByteBuffer matrix=buffer(64),input=buffer(12),output=buffer(12);
        for(float value:IDENTITY)matrix.putFloat(value);
        input.putFloat(1).putFloat(2).putFloat(3);
        for(int i=0;i<12;i++)output.put(i,(byte)0x5a);
        assertThrows(IllegalArgumentException.class,()->provider.transformBuffers(matrix,input,output,-1,Isa.SCALAR));
        matrix.putFloat(0,Float.NaN);
        assertThrows(IllegalArgumentException.class,()->provider.transformBuffers(matrix,input,output,1,Isa.SCALAR));
        for(int i=0;i<12;i++)assertEquals((byte)0x5a,output.get(i));
        matrix.putFloat(0,1);
        provider.transformBuffers(matrix,input,output,1,Isa.SCALAR);
        assertEquals(1,output.getFloat(0)); // Borrowed buffers remain usable and caller-owned.
        empty();
    }

    @Test void concurrentOwnersDoNotFreeOrOverwriteAnotherCall() throws Exception {
        var provider=load();
        var pool=Executors.newFixedThreadPool(4);
        try {
            var jobs=new ArrayList<Callable<Void>>();
            for(int task=0;task<8;task++) {
                final int seed=task;
                jobs.add(()->{
                    for(int i=0;i<125;i++) {
                        float[] input={seed,i,seed+i};
                        assertArrayEquals(input,provider.transform(IDENTITY,input,Isa.SCALAR).positions());
                    }
                    return null;
                });
            }
            for(var result:pool.invokeAll(jobs))result.get(20,TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
        empty();
    }

    @Test void ownerBoundsCloseAndInvalidHandlesAreSafe() throws Exception {
        load();
        Class<?> owner=Class.forName("dev.spm.core.nativejni.NativeArena");
        Method allocate=owner.getDeclaredMethod("allocate",int[].class);allocate.setAccessible(true);
        Method slice=owner.getDeclaredMethod("slice",int.class);slice.setAccessible(true);
        Method close=owner.getDeclaredMethod("close");close.setAccessible(true);
        Method free=owner.getDeclaredMethod("nFree",long.class);free.setAccessible(true);
        Object arena=allocate.invoke(null,(Object)new int[]{8,0,12});
        try {
            assertEquals(1,owned("ownedArenaCount"));assertEquals(20,owned("ownedArenaBytes"));
            assertEquals(8,((ByteBuffer)slice.invoke(arena,8)).capacity());
            assertEquals(0,((ByteBuffer)slice.invoke(arena,0)).capacity());
            var exceeded=assertThrows(InvocationTargetException.class,()->slice.invoke(arena,13));
            assertInstanceOf(IndexOutOfBoundsException.class,exceeded.getCause());
            assertEquals(12,((ByteBuffer)slice.invoke(arena,12)).capacity());
            free.invoke(null,-1L);free.invoke(null,Long.MAX_VALUE);free.invoke(null,0L);
            assertEquals(1,owned("ownedArenaCount"));
        } finally {close.invoke(arena);close.invoke(arena);}
        var closed=assertThrows(InvocationTargetException.class,()->slice.invoke(arena,0));
        assertInstanceOf(IllegalStateException.class,closed.getCause());
        var overflow=assertThrows(InvocationTargetException.class,()->allocate.invoke(null,(Object)new int[]{Integer.MAX_VALUE,1}));
        assertInstanceOf(ArithmeticException.class,overflow.getCause());
        var negative=assertThrows(InvocationTargetException.class,()->allocate.invoke(null,(Object)new int[]{-1}));
        assertInstanceOf(IllegalArgumentException.class,negative.getCause());
        empty();
        Object zero=allocate.invoke(null,(Object)new int[]{0});
        try {assertEquals(1,owned("ownedArenaCount"));assertEquals(1,owned("ownedArenaBytes"));}
        finally {close.invoke(zero);}
        empty();
    }

    private static ByteBuffer buffer(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
}
