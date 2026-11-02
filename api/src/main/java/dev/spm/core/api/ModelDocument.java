package dev.spm.core.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable host-neutral model data. Metadata archives source data; it does not imply executable support. */
public record ModelDocument(String identity, String sourceFormat, Skeleton skeleton, List<Mesh> meshes,
                            BonePose bindPose, List<Material> materials, List<Locator> locators, JsonObject metadata) {
    public ModelDocument {
        if (Objects.requireNonNull(identity).isBlank() || Objects.requireNonNull(sourceFormat).isBlank())
            throw new IllegalArgumentException("Missing model identity or source format");
        Objects.requireNonNull(skeleton); Objects.requireNonNull(bindPose); Objects.requireNonNull(metadata);
        meshes=List.copyOf(meshes); materials=List.copyOf(materials); locators=List.copyOf(locators);
        if (bindPose.boneCount()!=skeleton.boneCount()) throw new IllegalArgumentException("Bind pose does not match skeleton");
        for (Mesh mesh : meshes) {
            if (mesh.materialIndex()>=materials.size()) throw new IllegalArgumentException("Unknown mesh material");
            for (int bone : mesh.vertices().boneIndices()) if (bone>=skeleton.boneCount()) throw new IllegalArgumentException("Unknown vertex bone");
        }
        for (Locator locator : locators) if (locator.boneIndex()>=skeleton.boneCount()) throw new IllegalArgumentException("Unknown locator bone");
    }

    /** Each quad occupies four consecutive vertices. Triangles use 0,1,2 and 0,2,3 in source face order.
     * Bounds are in model space before bind-pose bone transforms. */
    public record Mesh(String name, VertexBatch vertices, float[] uvs, int[] indices, List<Face> faces,
                       Bounds bounds, int materialIndex) {
        public Mesh {
            Objects.requireNonNull(name); Objects.requireNonNull(vertices); Objects.requireNonNull(bounds);
            uvs=uvs.clone(); indices=indices.clone(); faces=List.copyOf(faces);
            if (uvs.length!=Math.multiplyExact(vertices.vertexCount(),2) || indices.length%3!=0 || materialIndex<0)
                throw new IllegalArgumentException("Invalid mesh sizes or material");
            finite(uvs);
            for (int index : indices) if (index<0 || index>=vertices.vertexCount()) throw new IllegalArgumentException("Invalid triangle index");
            int[] vertexBones=vertices.boneIndices();
            for (Face face : faces) {
                if (face.firstVertex()>vertices.vertexCount()-4) throw new IllegalArgumentException("Invalid face range");
                for (int i=0;i<4;i++) if (vertexBones[face.firstVertex()+i]!=face.boneIndex())
                    throw new IllegalArgumentException("Face and vertex bone disagree");
            }
        }
        @Override public float[] uvs() { return uvs.clone(); }
        @Override public int[] indices() { return indices.clone(); }
    }
    public record Face(int boneIndex, int cubeIndex, int firstVertex, String direction, boolean mirrored) {
        public Face {
            if (boneIndex<0 || cubeIndex<0 || firstVertex<0 || Objects.requireNonNull(direction).isBlank())
                throw new IllegalArgumentException("Invalid face metadata");
        }
    }
    public record Bounds(float minX,float minY,float minZ,float maxX,float maxY,float maxZ) {
        public Bounds {
            finite(new float[] {minX,minY,minZ,maxX,maxY,maxZ});
            if (minX>maxX || minY>maxY || minZ>maxZ) throw new IllegalArgumentException("Reversed bounds");
        }
    }
    /** Logical texture references never authorize host filesystem access. */
    public record Material(String name, int textureWidth, int textureHeight, List<ResourceKey> resources) {
        public Material {
            if (Objects.requireNonNull(name).isBlank() || textureWidth<1 || textureHeight<1)
                throw new IllegalArgumentException("Invalid material dimensions");
            resources=List.copyOf(resources);
        }
    }
    /** Source attachment metadata in normalized model space. No game-specific locator registry is implied. */
    public record Locator(String name,int boneIndex,float[] offset,float[] rotation,boolean ignoreInheritedScale) {
        public Locator {
            offset=offset.clone(); rotation=rotation.clone();
            if (Objects.requireNonNull(name).isBlank() || boneIndex<0 || offset.length!=3 || rotation.length!=3)
                throw new IllegalArgumentException("Invalid locator");
            finite(offset); finite(rotation);
        }
        @Override public float[] offset() { return offset.clone(); }
        @Override public float[] rotation() { return rotation.clone(); }
    }

    /**
     * Archival JSON. Objects retain declaration order for controllers and events.
     *
     * <p>The representation is chosen for retained size, not for convenience, because a real
     * animation-heavy model spends far more heap on this graph than on its geometry: measured on a
     * 5.6 MiB community model, mesh arrays held 1.0 MiB while this graph held 193.0 MiB.
     *
     * <p>Three measurable choices, each verified by experiment rather than assumption:
     * <ul>
     *   <li>{@link JsonNumber} stores a primitive {@code double}. Every number in the corpus
     *       round-trips exactly (measured: 1,794,719 of 1,794,719), and dropping {@code BigDecimal}
     *       removes 40 bytes per number. Parsers reject a literal that would not round-trip rather
     *       than silently rounding it.</li>
     *   <li>{@link JsonVector3} inlines the overwhelmingly common three-number array as three
     *       primitives. Measured: 689,563 of 690,180 distinct arrays are exactly three numbers, and
     *       the previous {@code JsonArray}+{@code List}+{@code Object[]}+3x{@code JsonNumber} cost
     *       about 152 bytes against 40 for this record.</li>
     *   <li>{@link JsonObject} stores parallel key/value arrays and <em>shares</em> its key array with
     *       every object of the same shape. Measured: 45,788 distinct objects have only 2,120 distinct
     *       shapes, so a per-object {@code LinkedHashMap} was mostly redundant entries.</li>
     * </ul>
     * Pooling three-number vectors by value was tried and rejected: it cost 12 MiB more than it saved,
     * because 496,419 distinct values needed a hash node each to dedupe only 193,144 instances.
     */
    public sealed interface JsonValue permits JsonObject,JsonArray,JsonVector3,JsonString,JsonNumber,JsonBoolean,JsonNull {}
    /**
     * Ordered JSON object backed by parallel arrays.
     *
     * <p>{@link #values()} still returns a {@code Map} for callers that want one, but it is built on
     * demand and is therefore transient rather than retained. Prefer {@link #size()},
     * {@link #key(int)} and {@link #value(int)} when iterating.
     */
    public static final class JsonObject implements JsonValue {
        private final String[] keys;
        private final JsonValue[] values;
        public JsonObject(Map<String,JsonValue> source) {
            this.keys=new String[source.size()]; this.values=new JsonValue[source.size()];
            int i=0;
            for(var entry:source.entrySet()) {
                keys[i]=Objects.requireNonNull(entry.getKey());
                values[i]=Objects.requireNonNull(entry.getValue());
                i++;
            }
        }
        private JsonObject(String[] keys,JsonValue[] values) { this.keys=keys; this.values=values; }
        public int size() { return keys.length; }
        public String key(int index) { return keys[index]; }
        public JsonValue value(int index) { return values[index]; }
        /** Lookup by key; objects hold few fields so a scan beats a hash table here. */
        public JsonValue get(String key) {
            for(int i=0;i<keys.length;i++) if(keys[i].equals(key)) return values[i];
            return null;
        }
        public boolean containsKey(String key) { return get(key)!=null; }
        public Set<String> keySet() { return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(keys))); }
        /** Built on demand; callers must not assume identity stability across calls. */
        public Map<String,JsonValue> values() {
            var copy=new LinkedHashMap<String,JsonValue>(keys.length*2);
            for(int i=0;i<keys.length;i++) copy.put(keys[i],values[i]);
            return Collections.unmodifiableMap(copy);
        }
        /** Replaces one field, preserving order; used to annotate a shared archive projection. */
        public JsonObject with(String key,JsonValue value) {
            var copy=new LinkedHashMap<String,JsonValue>(keys.length*2);
            for(int i=0;i<keys.length;i++) copy.put(keys[i],values[i]);
            copy.put(Objects.requireNonNull(key),Objects.requireNonNull(value));
            return new JsonObject(copy);
        }
        @Override public boolean equals(Object other) {
            return other instanceof JsonObject object && values().equals(object.values());
        }
        @Override public int hashCode() { return values().hashCode(); }
        @Override public String toString() { return values().toString(); }
    }
    public record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray { values=List.copyOf(values); }
    }
    /** Exactly three numbers, inlined to avoid a list, an array and three boxed numbers. */
    public record JsonVector3(double x,double y,double z) implements JsonValue { }
    public record JsonString(String value) implements JsonValue {
        public JsonString { Objects.requireNonNull(value); }
    }
    /**
     * A JSON number as a primitive double.
     *
     * <p>The previous representation held a {@code BigDecimal}, which cost 40 bytes per number on top
     * of the 16-byte record. Parsers must verify that a literal survives the round trip exactly and
     * reject it otherwise, so no authored value is silently rounded.
     */
    public record JsonNumber(double value) implements JsonValue {
        /** Rejects a non-finite double, which JSON cannot represent. */
        public JsonNumber { if(!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite JSON number"); }
        /** Decimal text as authored, without an exponent or trailing zeros. */
        public BigDecimal decimal() { return BigDecimal.valueOf(value); }
    }
    public record JsonBoolean(boolean value) implements JsonValue {}
    public enum JsonNull implements JsonValue { INSTANCE }

    private static void finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite model data");
    }
}
