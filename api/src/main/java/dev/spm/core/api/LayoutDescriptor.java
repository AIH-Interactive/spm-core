package dev.spm.core.api;

import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Exact wire layout, separate from Core API, JNI entrypoint version and any Host GPU layout. */
public record LayoutDescriptor(String id, int version, int stride, int alignment,
                               ByteOrder byteOrder, List<Field> fields) {
    public enum Scalar { F32, U32 }
    public record Field(String name, int offset, Scalar scalar, int components) {
        public Field {
            Objects.requireNonNull(scalar);
            if (Objects.requireNonNull(name).isBlank() || offset < 0 || offset % 4 != 0
                    || components < 1 || components > Integer.MAX_VALUE / 4
                    || (long) offset + (long) components * 4 > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid layout field");
        }
        public int bytes() { return components * 4; }
    }
    public LayoutDescriptor {
        Objects.requireNonNull(byteOrder);fields=List.copyOf(fields);
        if (Objects.requireNonNull(id).isBlank() || version < 1 || stride < 1 || alignment < 1
                || (alignment & (alignment-1)) != 0 || stride % alignment != 0)
            throw new IllegalArgumentException("Invalid layout header");
        var names=new HashSet<String>();
        var sorted=fields.stream().sorted(java.util.Comparator.comparingInt(Field::offset)).toList();
        int end=0;
        for (var field:sorted) {
            if (!names.add(field.name()) || field.offset() < end || (long) field.offset()+field.bytes() > stride)
                throw new IllegalArgumentException("Duplicate, overlapping or out-of-bounds layout field");
            end=field.offset()+field.bytes();
        }
    }
    public Field field(String name) {
        return fields.stream().filter(f->f.name().equals(name)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Unknown layout field: "+name));
    }
    /** Rejects differences in identity, version, byte order, field type, order, offset or stride. */
    public void requireCompatible(LayoutDescriptor other) {
        if (!equals(other)) throw new IllegalArgumentException("Incompatible layout: "+id);
    }
    public static LayoutDescriptor boneWireV1() {
        return new LayoutDescriptor("spm.logical-bone",1,128,4,ByteOrder.LITTLE_ENDIAN,List.of(
                new Field("transform",0,Scalar.F32,16),new Field("normal",64,Scalar.F32,9),
                new Field("locator",100,Scalar.F32,3),new Field("visible",112,Scalar.U32,1),
                new Field("descendantVisible",116,Scalar.U32,1),new Field("glow",120,Scalar.U32,1),
                new Field("rgba",124,Scalar.U32,1)));
    }
}
