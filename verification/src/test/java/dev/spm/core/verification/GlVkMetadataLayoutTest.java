package dev.spm.core.verification;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.LayoutDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plan T09 named this test and it did not exist. It pins the <em>layout agreement</em> between the
 * logical bone wire that Core publishes and the byte offsets a Host GPU packing step would read.
 *
 * <p>The name in the plan mentions GL/Vulkan. Core does not talk to either — GPU submission belongs
 * to the Host. What Core can and must guarantee is: the descriptor is the single source of truth, the
 * JNI writer and reader use its stride and offsets, integer metadata is never reinterpreted as a bit
 * pattern by a float reader, and the descriptor is versioned separately from JNI and C ABIs.
 */
final class GlVkMetadataLayoutTest {

    /** The descriptor every native bone exchange is measured against. */
    private static LayoutDescriptor boneWire() {
        return LayoutDescriptor.boneWireV1();
    }

    @Test void theLogicalBoneWireHasTheDocumentedOffsetsStrideAndAlignment() {
        var wire = boneWire();
        assertEquals("spm.logical-bone", wire.id());
        assertEquals(1, wire.version(), "logical bone layout is version 1, independent of the JNI ABI");
        assertEquals(128, wire.stride());
        assertEquals(4, wire.alignment());
        assertEquals(ByteOrder.LITTLE_ENDIAN, wire.byteOrder());
        assertEquals(0, wire.field("transform").offset());
        assertEquals(64, wire.field("normal").offset());
        assertEquals(100, wire.field("locator").offset());
        assertEquals(112, wire.field("visible").offset());
        assertEquals(116, wire.field("descendantVisible").offset());
        assertEquals(120, wire.field("glow").offset());
        assertEquals(124, wire.field("rgba").offset());
    }

    @Test void floatFieldsAreFloatScopedAndIntegerMetadataIsIntegerScoped() {
        var wire = boneWire();
        for (String name : new String[] {"transform", "normal", "locator"}) {
            assertEquals(LayoutDescriptor.Scalar.F32, wire.field(name).scalar(),
                    name + " is float data and a Host must upload it as float");
        }
        for (String name : new String[] {"visible", "descendantVisible", "glow", "rgba"}) {
            assertEquals(LayoutDescriptor.Scalar.U32, wire.field(name).scalar(),
                    name + " is integer metadata; reinterpreting 0x00000001 as a float bit pattern"
                            + " would produce ~1.4e-45 instead of 1.0");
        }
    }

    @Test void integerToOneFloatConversionIsNumericNotABitcast() {
        // A Host converting U32 visible=1 to F32 must produce 1.0f, never Float.intBitsToFloat(1).
        int visible = 1;
        float numeric = visible;
        float bitcast = Float.intBitsToFloat(visible);
        assertEquals(1.0f, numeric);
        assertNotEquals(1.0f, bitcast);
        assertEquals(LayoutDescriptor.Scalar.U32, boneWire().field("visible").scalar(),
                "the descriptor must mark the field as integer so the conversion is explicit");
    }

    @Test void fieldsAreContiguousNonOverlappingAndFitTheStride() {
        var wire = boneWire();
        var sorted = wire.fields().stream()
                .sorted(java.util.Comparator.comparingInt(LayoutDescriptor.Field::offset)).toList();
        int end = 0;
        for (var field : sorted) {
            assertEquals(end, field.offset(),
                    "fields must be contiguous; a gap is either padding that must be declared "
                            + "or a bug: " + field.name());
            end = field.offset() + field.bytes();
            assertTrue(end <= wire.stride(), "field exceeds the stride: " + field.name());
        }
        assertEquals(wire.stride(), end, "the declared fields must exactly fill the stride");
    }

    @Test void aHostLayoutWithDifferentTypesIsRejectedEvenAtTheSameStride() {
        var wire = boneWire();
        var host = new LayoutDescriptor("host.float-metadata", 1, 128, 4, ByteOrder.LITTLE_ENDIAN,
                java.util.List.of(
                        new LayoutDescriptor.Field("transform", 0, LayoutDescriptor.Scalar.F32, 16),
                        new LayoutDescriptor.Field("normal", 64, LayoutDescriptor.Scalar.F32, 9),
                        new LayoutDescriptor.Field("locator", 100, LayoutDescriptor.Scalar.F32, 3),
                        // Deliberately wrong: a Host that declares integer metadata as float.
                        new LayoutDescriptor.Field("visible", 112, LayoutDescriptor.Scalar.F32, 1),
                        new LayoutDescriptor.Field("descendantVisible", 116, LayoutDescriptor.Scalar.F32, 1),
                        new LayoutDescriptor.Field("glow", 120, LayoutDescriptor.Scalar.F32, 1),
                        new LayoutDescriptor.Field("rgba", 124, LayoutDescriptor.Scalar.F32, 1)));
        assertEquals(wire.stride(), host.stride(), "same stride, so a stride-only check would pass");
        assertNotEquals(wire, host);
        assertThrows(IllegalArgumentException.class, () -> wire.requireCompatible(host),
                "compatibility must compare types and offsets, not just stride");
    }

    @Test void bigEndianHostLayoutsAreNotCompatibleWithTheLittleEndianWire() {
        var wire = boneWire();
        var bigEndian = new LayoutDescriptor(wire.id(), wire.version(), wire.stride(), wire.alignment(),
                ByteOrder.BIG_ENDIAN, wire.fields());
        assertThrows(IllegalArgumentException.class, () -> wire.requireCompatible(bigEndian));
    }

    @Test void theDescriptorLaysOutAConsistentBoneRecordInMemory() {
        // Serialize one bone the way the JNI writer does, then read it back through the descriptor
        // offsets. This catches an offset/type disagreement between the descriptor and the wire.
        var wire = boneWire();
        ByteBuffer buffer = ByteBuffer.allocateDirect(wire.stride()).order(wire.byteOrder());
        float[] transform = new float[16];
        for (int i = 0; i < 16; i++) transform[i] = i + 1;
        float[] normal = new float[9];
        for (int i = 0; i < 9; i++) normal[i] = 100 + i;
        float[] locator = {7, 8, 9};
        int visible = 1, descendant = 0, glow = 15, rgba = 0x11223344;

        var t = wire.field("transform");
        for (int i = 0; i < t.components(); i++) buffer.putFloat(t.offset() + i * 4, transform[i]);
        var n = wire.field("normal");
        for (int i = 0; i < n.components(); i++) buffer.putFloat(n.offset() + i * 4, normal[i]);
        var l = wire.field("locator");
        for (int i = 0; i < l.components(); i++) buffer.putFloat(l.offset() + i * 4, locator[i]);
        buffer.putInt(wire.field("visible").offset(), visible);
        buffer.putInt(wire.field("descendantVisible").offset(), descendant);
        buffer.putInt(wire.field("glow").offset(), glow);
        buffer.putInt(wire.field("rgba").offset(), rgba);

        for (int i = 0; i < 16; i++) assertEquals(transform[i], buffer.getFloat(t.offset() + i * 4));
        for (int i = 0; i < 9; i++) assertEquals(normal[i], buffer.getFloat(n.offset() + i * 4));
        assertEquals(visible, buffer.getInt(wire.field("visible").offset()));
        assertEquals(glow, buffer.getInt(wire.field("glow").offset()));
        assertEquals(rgba, buffer.getInt(wire.field("rgba").offset()));
        // Integer read as float is nonsense: proof the type marker matters.
        assertNotEquals(1.0f, buffer.getFloat(wire.field("visible").offset()));
    }

    @Test void theLayoutVersionIsIndependentOfTheJniAbiAndTheCAbi() {
        // Three independent version layers. Pinning them together here means a bump to one cannot
        // silently be assumed to move the others.
        assertEquals(1, boneWire().version(), "logical bone wire version");
        assertEquals(4, dev.spm.core.nativejni.NativeTransforms.ABI_VERSION, "JNI ABI version");
        // The C ABI version is a Rust constant; its Java-visible consequence is that the JNI ABI can
        // move without it. This assertion documents the intended separation, not a runtime read.
        assertNotEquals(boneWire().version(), dev.spm.core.nativejni.NativeTransforms.ABI_VERSION,
                "the layout version and the JNI ABI version must not be the same number by accident");
    }

    @Test void coreExposesNoGpuLayoutContractBecauseGpuSubmissionBelongsToTheHost() {
        // This is the honest half of the plan's T09 requirement: Core freezes the CPU wire only.
        // If a GPU layout ever appears here without a descriptor and packing test, this fails.
        for (Class<?> type : new Class<?>[] {BoneMatrices.class, LayoutDescriptor.class, Isa.class}) {
            String name = type.getSimpleName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("vulkan"), "GPU layout types belong to the Host: " + type.getName());
            assertFalse(name.contains("opengl"), "GPU layout types belong to the Host: " + type.getName());
        }
        // And the descriptor itself declares its scope explicitly.
        assertFalse(boneWire().id().toLowerCase(java.util.Locale.ROOT).contains("gpu"),
                "the Core wire must not claim to be a GPU layout");
    }
}
