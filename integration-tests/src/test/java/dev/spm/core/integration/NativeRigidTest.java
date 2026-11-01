package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.*;
import dev.spm.core.nativejni.*;
import dev.spm.core.render.cpu.*;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="spm.native.library",matches=".+")
class NativeRigidTest {
    @BeforeAll static void recordRuntimeAndAvailableInstructionSets() throws Exception {
        var provider=load();var supported=new ArrayList<String>();
        for(Isa isa:Isa.values())if(isa!=Isa.AUTO&&provider.supported(isa))supported.add(isa.name());
        System.out.println("Rigid JNI java.runtime.version="+System.getProperty("java.runtime.version")+"; os.arch="+System.getProperty("os.arch")+"; supported="+supported);
    }
    private static NativeTransforms load() throws Exception {
        Path path=Path.of(System.getProperty("spm.native.library"));
        return NativeTransforms.load(path,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
    }
    private static BoneMatrices bones(boolean hidden) {
        var skeleton=new Skeleton(List.of("a","b","c"),new int[]{-1,0,-1},new float[]{1,2,3,2,3,4,0,0,0});
        var pose=new BonePose(new float[]{.2f,.3f,.4f,-.1f,.5f,-.2f,0,0,0},new float[]{1,2,3,4,5,6,0,0,0},
                new float[]{-2,3,4,1,2,3,1,1,1},new boolean[]{hidden,false,true},new boolean[]{false,false,false},
                new int[]{7,255,0},new int[]{0x80402010,0x0102feff,0xffffffff});
        return BoneEvaluator.evaluate(skeleton,pose);
    }
    private static VertexBatch batch(int count) {
        float[] p=new float[count*3],n=new float[count*3]; int[] b=new int[count],g=new int[count],c=new int[count];
        for(int i=0;i<count;i++) {
            p[i*3]=i*.125f;p[i*3+1]=-i*.25f;p[i*3+2]=i*.375f;
            if(i%5!=0) {n[i*3]=1;n[i*3+1]=2;n[i*3+2]=-3;}
            b[i]=i%3;g[i]=15;c[i]=0x8080ff40+i;
        }
        return new VertexBatch(p,n,b,g,c);
    }
    private static void equal(VertexBatch expected,VertexBatch actual) {
        assertArrayEquals(expected.positions(),actual.positions(),0.00001f);
        assertArrayEquals(expected.normals(),actual.normals(),0.00001f);
        assertArrayEquals(expected.boneIndices(),actual.boneIndices());assertArrayEquals(expected.glow(),actual.glow());
        assertArrayEquals(expected.colorsRgba(),actual.colorsRgba());
    }
    @Test void everySupportedIsaMatchesJavaForOddLengthsMirrorsNormalsColorAndHiddenCompaction() throws Exception {
        var nativeProvider=load();
        for(Isa isa:Isa.values()) if(nativeProvider.supported(isa)) {
            for(int count:new int[]{0,1,2,3,4,7,8,15,16,17,33}) for(boolean hidden:new boolean[]{false,true}) {
                var source=batch(count);var bones=bones(hidden);
                equal(JavaCpuRenderer.transform(source,bones),NativeRigidRenderer.transform(source,bones,isa));
            }
        }
    }
    @Test void concurrentCallsShareNoMutableScratchOrHostReferences() throws Exception {
        load();var pool=Executors.newFixedThreadPool(4);
        try {
            var jobs=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<24;i++) {
                final int seed=i;
                jobs.add(()->{var source=batch(seed+1);var state=bones(seed%2==0);equal(JavaCpuRenderer.transform(source,state),NativeRigidRenderer.transform(source,state,Isa.AUTO));return true;});
            }
            for(var result:pool.invokeAll(jobs)) assertTrue(result.get(10,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
    @Test void rawJniChecksCapacitiesReadonlyFlagsAndStagesBeforeCommittingUnalignedOutput() throws Exception {
        load();
        Method raw=NativeRigidRenderer.class.getDeclaredMethod("nTransform",ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,int.class,int.class,int.class);raw.setAccessible(true);
        ByteBuffer bones=buffer(128),input=buffer(32),out=buffer(40);
        putBone(bones);input.putFloat(0,1).putFloat(4,2).putFloat(8,3).putInt(24,0).putInt(28,0x80ff8080);
        fill(out);
        ByteBuffer shortOutput=buffer(39);fill(shortOutput);
        assertTrue((long)raw.invoke(null,bones,input,shortOutput,1,1,Isa.SCALAR.code())<0);unchanged(shortOutput);
        assertTrue((long)raw.invoke(null,bones,ByteBuffer.allocate(32),out,1,1,Isa.SCALAR.code())<0);unchanged(out);
        assertTrue((long)raw.invoke(null,bones,input,out,-1,1,Isa.SCALAR.code())<0);unchanged(out);
        assertTrue((long)raw.invoke(null,bones,input,out,1,1,99)<0);unchanged(out);
        assertTrue((long)raw.invoke(null,bones,input,out.asReadOnlyBuffer(),1,1,Isa.SCALAR.code())<0);unchanged(out);
        bones.putInt(112,2);assertTrue((long)raw.invoke(null,bones,input,out,1,1,Isa.SCALAR.code())<0);unchanged(out);bones.putInt(112,1);
        bones.putInt(116,2);assertTrue((long)raw.invoke(null,bones,input,out,1,1,Isa.SCALAR.code())<0);unchanged(out);bones.putInt(116,1);
        bones.putInt(120,16);assertTrue((long)raw.invoke(null,bones,input,out,1,1,Isa.SCALAR.code())<0);unchanged(out);bones.putInt(120,255);
        input.putInt(24,1);assertTrue((long)raw.invoke(null,bones,input,out,1,1,Isa.SCALAR.code())<0);unchanged(out);input.putInt(24,0);
        input.putFloat(0,Float.NaN);assertTrue((long)raw.invoke(null,bones,input,out,1,1,Isa.SCALAR.code())<0);unchanged(out);input.putFloat(0,1);
        ByteBuffer unalignedBone=unaligned(bones),unalignedInput=unaligned(input),unalignedOut=unaligned(out);
        long success=(long)raw.invoke(null,unalignedBone,unalignedInput,unalignedOut,1,1,Isa.SCALAR.code());
        assertEquals(1,(int)success);assertEquals(Isa.SCALAR.code(),(int)(success>>>32));
        assertEquals(1,unalignedOut.getFloat(0));assertEquals(255,unalignedOut.getInt(28));
        ByteBuffer overlap=buffer(40);for(int i=0;i<32;i++)overlap.put(i,input.get(i));
        assertEquals(1,(int)(long)raw.invoke(null,bones,overlap,overlap,1,1,Isa.SCALAR.code()));
    }
    @Test void lateOverflowDoesNotCommitAnEarlierValidVertexAndHiddenOutputNeedsNoStorage() throws Exception {
        load();
        Method raw=NativeRigidRenderer.class.getDeclaredMethod("nTransform",ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,int.class,int.class,int.class);raw.setAccessible(true);
        ByteBuffer bones=buffer(128),input=buffer(64),out=buffer(80);putBone(bones);fill(out);
        bones.putFloat(0,2);
        input.putFloat(0,1).putInt(24,0).putInt(28,0xffffffff);
        input.putFloat(32,Float.MAX_VALUE).putInt(56,0).putInt(60,0xffffffff);
        assertTrue((long)raw.invoke(null,bones,input,out,1,2,Isa.SCALAR.code())<0);unchanged(out);
        bones.putInt(112,0);
        long empty=(long)raw.invoke(null,bones,input,buffer(0),1,2,Isa.SCALAR.code());
        assertEquals(0,(int)empty);assertEquals(Isa.SCALAR.code(),(int)(empty>>>32));
    }
    @Test void readonlyInputsAndBufferBaseAddressWorkWithoutOverwritingSurroundingBytes() throws Exception {
        load();
        Method raw=NativeRigidRenderer.class.getDeclaredMethod("nTransform",ByteBuffer.class,ByteBuffer.class,ByteBuffer.class,int.class,int.class,int.class);raw.setAccessible(true);
        ByteBuffer bones=buffer(128),input=buffer(32),guard=buffer(42);putBone(bones);fill(guard);
        input.putFloat(0,1).putFloat(4,2).putFloat(8,3).putInt(24,0).putInt(28,0x12345678);
        input.position(31);bones.position(127);
        guard.position(1).limit(41);ByteBuffer output=guard.slice().order(ByteOrder.LITTLE_ENDIAN);output.position(39);
        long result=(long)raw.invoke(null,bones.asReadOnlyBuffer(),input.asReadOnlyBuffer(),output,1,1,Isa.SCALAR.code());
        assertEquals(1,(int)result);assertEquals(1,output.getFloat(0));assertEquals(0x12345678,output.getInt(32));
        guard.limit(42);assertEquals((byte)0x5a,guard.get(0));assertEquals((byte)0x5a,guard.get(41));
    }
    @Test void finiteExtremeProductsAndNormalLengthsMatchTheDoublePrecisionOracle() throws Exception {
        var provider=load();
        float[] matrix={Float.MAX_VALUE,0,0,0,-Float.MAX_VALUE,1,0,0,0,0,1,0,0,0,0,1};
        float[] normal={1/Float.MAX_VALUE,1,0,0,1,0,0,0,1};
        var state=new BoneMatrices(matrix,normal,new boolean[]{true},new boolean[]{true},new float[3],new int[]{255});
        var source=new VertexBatch(new float[]{Float.MAX_VALUE,Float.MAX_VALUE,0},new float[]{Float.MAX_VALUE,Float.MAX_VALUE,0},new int[]{0});
        var expected=JavaCpuRenderer.transform(source,state);
        assertArrayEquals(new float[]{0,Float.MAX_VALUE,0},expected.positions());
        for(Isa isa:Isa.values())if(provider.supported(isa))equal(expected,NativeRigidRenderer.transform(source,state,isa));
    }
    private static ByteBuffer buffer(int bytes){return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static void fill(ByteBuffer buffer){for(int i=0;i<buffer.capacity();i++)buffer.put(i,(byte)0x5a);}
    private static void unchanged(ByteBuffer buffer){for(int i=0;i<buffer.capacity();i++)assertEquals((byte)0x5a,buffer.get(i));}
    private static void putBone(ByteBuffer b){for(int i=0;i<16;i++)b.putFloat(i*4,i%5==0?1:0);for(int i=0;i<9;i++)b.putFloat(64+i*4,i%4==0?1:0);b.putInt(112,1).putInt(116,1).putInt(120,255).putInt(124,0xffffffff);}
    private static ByteBuffer unaligned(ByteBuffer source){ByteBuffer outer=buffer(source.capacity()+1);outer.position(1);ByteBuffer slice=outer.slice().order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<source.capacity();i++)slice.put(i,source.get(i));return slice;}
}
