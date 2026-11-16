package dev.spm.core.runtime;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.LayoutDescriptor;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Packs logical {@link BoneMatrices} into a Host-supplied {@link ByteBuffer} described by a
 * {@link LayoutDescriptor}, preserving explicit {@link LayoutDescriptor.Scalar#U32} versus numeric
 * {@link LayoutDescriptor.Scalar#F32} conversion for OpenGL/Vulkan GPU upload paths.
 */
public final class BoneWirePacker {
    private BoneWirePacker() {}

    /** Packs all bones using {@link LayoutDescriptor#boneWireV1()}. */
    public static int packLogicalV1(BoneMatrices matrices, ByteBuffer target) {
        return pack(matrices, LayoutDescriptor.boneWireV1(), target);
    }

    /**
     * Packs all bones according to {@code layout} starting at {@code target.position()}.
     *
     * @return total bytes written ({@code matrices.boneCount() * layout.stride()})
     */
    public static int pack(BoneMatrices matrices, LayoutDescriptor layout, ByteBuffer target) {
        Objects.requireNonNull(matrices);
        Objects.requireNonNull(layout);
        Objects.requireNonNull(target);
        if (target.isReadOnly()) throw new IllegalArgumentException("Target buffer is read-only");
        if (!layout.byteOrder().equals(target.order())) {
            throw new IllegalArgumentException("Target buffer byte order must match " + layout.byteOrder());
        }
        int bones = matrices.boneCount();
        int totalBytes = Math.multiplyExact(bones, layout.stride());
        if (target.remaining() < totalBytes) {
            throw new IllegalArgumentException("Target buffer too small: need " + totalBytes + " bytes");
        }
        var transformField = layout.field("transform");
        var normalField = layout.field("normal");
        var locatorField = layout.field("locator");
        var visibleField = layout.field("visible");
        var descendantField = layout.field("descendantVisible");
        var glowField = layout.field("glow");
        var rgbaField = layout.field("rgba");
        requireField(transformField, LayoutDescriptor.Scalar.F32, 16);
        requireField(normalField, LayoutDescriptor.Scalar.F32, 9);
        requireField(locatorField, LayoutDescriptor.Scalar.F32, 3);
        requireScalarCount(visibleField, 1);
        requireScalarCount(descendantField, 1);
        requireScalarCount(glowField, 1);
        requireScalarCount(rgbaField, 1);

        int basePosition = target.position();
        float[] transforms = matrices.transforms();
        float[] normals = matrices.normals();
        float[] locators = matrices.locatorPositions();
        boolean[] visible = matrices.visible();
        boolean[] descendant = matrices.descendantVisible();
        int[] glow = matrices.glow();
        int[] rgba = matrices.colorsRgba();

        for (int bone = 0; bone < bones; bone++) {
            int recordBase = basePosition + bone * layout.stride();
            for (int i = 0; i < 16; i++) {
                target.putFloat(recordBase + transformField.offset() + i * 4, transforms[bone * 16 + i]);
            }
            for (int i = 0; i < 9; i++) {
                target.putFloat(recordBase + normalField.offset() + i * 4, normals[bone * 9 + i]);
            }
            for (int i = 0; i < 3; i++) {
                target.putFloat(recordBase + locatorField.offset() + i * 4, locators[bone * 3 + i]);
            }
            putUnsigned(target, recordBase + visibleField.offset(), visibleField.scalar(), visible[bone] ? 1 : 0);
            putUnsigned(target, recordBase + descendantField.offset(), descendantField.scalar(), descendant[bone] ? 1 : 0);
            putUnsigned(target, recordBase + glowField.offset(), glowField.scalar(), glow[bone]);
            putUnsigned(target, recordBase + rgbaField.offset(), rgbaField.scalar(), rgba[bone]);
        }
        target.position(basePosition + totalBytes);
        return totalBytes;
    }

    private static void requireField(LayoutDescriptor.Field field, LayoutDescriptor.Scalar scalar, int components) {
        if (field.scalar() != scalar || field.components() != components) {
            throw new IllegalArgumentException("Incompatible bone wire field: " + field.name());
        }
    }

    private static void requireScalarCount(LayoutDescriptor.Field field, int components) {
        if (field.components() != components) {
            throw new IllegalArgumentException("Incompatible bone wire metadata field: " + field.name());
        }
    }

    private static void putUnsigned(ByteBuffer target, int offset, LayoutDescriptor.Scalar scalar, int value) {
        if (scalar == LayoutDescriptor.Scalar.U32) {
            target.putInt(offset, value);
        } else {
            // Explicit numeric conversion rather than Float.intBitsToFloat bitcasting.
            target.putFloat(offset, (float) Integer.toUnsignedLong(value));
        }
    }
}
