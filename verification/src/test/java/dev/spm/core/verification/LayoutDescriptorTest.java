package dev.spm.core.verification;

import dev.spm.core.api.LayoutDescriptor;
import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LayoutDescriptorTest {
    @Test void logicalBoneWireIsVersionedAndHostMetadataHasDistinctTypes() {
        var bone=LayoutDescriptor.boneWireV1();
        assertEquals("spm.logical-bone",bone.id());assertEquals(1,bone.version());
        assertEquals(128,bone.stride());assertEquals(ByteOrder.LITTLE_ENDIAN,bone.byteOrder());
        assertEquals(0,bone.field("transform").offset());assertEquals(16,bone.field("transform").components());
        assertEquals(64,bone.field("normal").offset());assertEquals(100,bone.field("locator").offset());
        assertEquals(112,bone.field("visible").offset());assertEquals(124,bone.field("rgba").offset());
        assertEquals(LayoutDescriptor.Scalar.U32,bone.field("visible").scalar());
        var host=new LayoutDescriptor("host.float-metadata",1,4,4,ByteOrder.LITTLE_ENDIAN,
                List.of(new LayoutDescriptor.Field("visible",0,LayoutDescriptor.Scalar.F32,1)));
        assertFalse(bone.equals(host));assertThrows(IllegalArgumentException.class,()->bone.requireCompatible(host));
    }
    @Test void rejectsOverlapOverflowMisalignmentDuplicateFieldsAndMutableInput() {
        var a=new LayoutDescriptor.Field("a",0,LayoutDescriptor.Scalar.F32,3);
        var fields=new ArrayList<>(List.of(a));
        var valid=new LayoutDescriptor("custom",1,16,4,ByteOrder.LITTLE_ENDIAN,fields);
        fields.clear();assertEquals(1,valid.fields().size());
        assertThrows(UnsupportedOperationException.class,()->valid.fields().clear());
        assertThrows(IllegalArgumentException.class,()->new LayoutDescriptor("x",1,16,4,ByteOrder.LITTLE_ENDIAN,List.of(a,new LayoutDescriptor.Field("b",8,LayoutDescriptor.Scalar.U32,1))));
        assertThrows(IllegalArgumentException.class,()->new LayoutDescriptor("x",1,8,4,ByteOrder.LITTLE_ENDIAN,List.of(a)));
        assertThrows(IllegalArgumentException.class,()->new LayoutDescriptor("x",1,16,4,ByteOrder.LITTLE_ENDIAN,List.of(new LayoutDescriptor.Field("b",2,LayoutDescriptor.Scalar.F32,1))));
        assertThrows(IllegalArgumentException.class,()->new LayoutDescriptor("x",1,32,4,ByteOrder.LITTLE_ENDIAN,List.of(a,new LayoutDescriptor.Field("a",16,LayoutDescriptor.Scalar.F32,1))));
        assertThrows(IllegalArgumentException.class,()->new LayoutDescriptor.Field("bad",0,LayoutDescriptor.Scalar.F32,Integer.MAX_VALUE));
    }
}
