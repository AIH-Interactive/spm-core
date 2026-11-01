package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.acceleration.ScalarTransforms;
import dev.spm.core.nativejni.NativeTransforms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class NativeTransformTest {
    @Test void nativeAbsenceKeepsFullScalarTransformAvailable() {
        float[] matrix = {2,0,0,0, 0,3,0,0, 0,0,4,0, 5,6,7,1};
        assertArrayEquals(new float[]{7,12,19}, ScalarTransforms.transform(matrix,new float[]{1,2,3}));
        assertThrows(IllegalArgumentException.class, () -> ScalarTransforms.transform(matrix,new float[]{1,2}));
    }
    @Test @EnabledIfSystemProperty(named="spm.native.library", matches=".+")
    void rustBatchMatchesJavaForEachSupportedIsaAndRejectsUnsafeBuffers() throws Exception {
        Path path = Path.of(System.getProperty("spm.native.library"));
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        var nativeCode = NativeTransforms.load(path,digest);
        float[] matrix = {2,0.25f,0,0, 0,3,0.125f,0, 0.5f,0,4,0, 5,6,7,1};
        float[] input = new float[65*3];
        for(int i=0;i<input.length;i++) input[i]=i*0.03125f-2;
        float[] expected=ScalarTransforms.transform(matrix,input);
        for(Isa isa:Isa.values()) {
            if(nativeCode.supported(isa)) {
                var result=nativeCode.transform(matrix,input,isa);
                assertArrayEquals(expected,result.positions(),0.00001f);
                assertEquals(65,result.completed());
                assertNotEquals(Isa.AUTO,result.isa());
            } else assertThrows(IllegalArgumentException.class,()->nativeCode.transform(matrix,input,isa));
        }
        ByteBuffer m=ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        for(float f:matrix)m.putFloat(f);
        ByteBuffer p=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        p.putFloat(1).putFloat(2).putFloat(3);
        ByteBuffer out=ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        out.putLong(0,0x123456789abcdef0L);
        assertThrows(IllegalArgumentException.class,()->nativeCode.transformBuffers(m,p,out,2,Isa.AUTO));
        assertEquals(0x123456789abcdef0L,out.getLong(0));
        m.putFloat(0,Float.NaN);
        assertThrows(IllegalArgumentException.class,()->nativeCode.transformBuffers(m,p,out,1,Isa.AUTO));
        assertEquals(0x123456789abcdef0L,out.getLong(0));
        assertThrows(IllegalArgumentException.class,()->NativeTransforms.load(path,"0".repeat(64)));
    }
}
