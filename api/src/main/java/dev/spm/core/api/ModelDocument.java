package dev.spm.core.api;

import java.math.BigDecimal;
import java.util.Arrays;
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
    public sealed interface JsonValue permits JsonObject,JsonArray,JsonVector3,JsonTimeline,JsonString,JsonNumber,JsonBoolean,JsonNull {}
    /**
     * Ordered JSON object backed by parallel arrays.
     *
     * <p>{@link #values()} still returns a {@code Map} for callers that want one, but it is built on
     * demand and is therefore transient rather than retained. Prefer {@link #size()},
     * {@link #key(int)} and {@link #value(int)} when iterating.
     *
     * <p>Objects of the same shape can share one key array through
     * {@link #keys(String[], JsonValue[])}. Measured on an animation-heavy corpus model, 55,342 objects
     * had only 2,115 distinct shapes, so one array per object wasted 53,000 arrays — about 4 MiB —
     * reproducing a shape that already existed.
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
        /**
         * Builds an object reusing a caller-supplied key array.
         *
         * <p>The caller owns deduplication because only it knows the lifetime over which sharing is
         * sound: a pool held by the import releases its shapes with the model, whereas a global one would
         * retain every key string for the life of the process.
         */
        public static JsonObject keys(String[] sharedKeys,JsonValue[] values) {
            if(sharedKeys.length!=values.length)
                throw new IllegalArgumentException("Key and value counts disagree");
            return new JsonObject(sharedKeys,values);
        }
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
    /**
     * Exactly three numbers, inlined to avoid a list, an array and three boxed numbers.
     *
     * <p>Components are {@code float} rather than {@code double}. That is lossless for the only
     * producer, which is the historical YSM projection: its values originate as wire {@code float32} and
     * are widened to {@code double} only so the JSON number type can hold them. Measured across the whole
     * community corpus, all 6,250,275 vector components are exactly representable as {@code float}, so
     * narrowing recovers 8 bytes per vector with no change to any authored value. A vector assembled from
     * anything else must not use this type — the JSON array form still carries arbitrary precision.
     */
    public record JsonVector3(float x,float y,float z) implements JsonValue {
        /** The components widened for callers that compute in double. */
        public double xd() { return x; }
        public double yd() { return y; }
        public double zd() { return z; }
    }
    /**
     * A whole animation track as parallel primitive arrays, plus a sparse per-key exception table.
     *
     * <p>This replaces the object-per-keyframe shape that dominates the corpus. Retained, one
     * object-form key costs a 24-byte {@link JsonVector3} plus a 4-byte slot in the enclosing value
     * array, and a full key costs a further frame object. Here a key costs 4 bytes of tick plus 12
     * bytes of vector, and only the keys that genuinely differ from plain linearity pay for an
     * exception entry.
     *
     * <p>The two base arrays hold every key: {@link #ticks()} the tick, {@link #pre()} the vector.
     * A key needs an entry in the exception table when its interpolation is not linear or its
     * {@code post} vector differs from its {@code pre}, because those are the only two per-key facts
     * the base columns cannot carry. Keys absent from the table are linear with {@code post} equal to
     * {@code pre}, which is exactly what the object form decodes to for the compact shorthand. The
     * table is sparse, so one catmull_rom key in a 700-key track costs one entry rather than forcing
     * the whole track back to objects.
     *
     * <p>{@link #ticks()} holds the tick the wire authored, <em>not</em> seconds. The tick is a
     * {@code float32} in the source, so this is the exact authored value, and dividing by 20 at read
     * time reproduces the same {@code double} the object form derives from its decimal timestamp text.
     * Storing seconds directly would round twice and change sampling.
     *
     * <p>Ticks stay in the order the wire authored them, because that is the order the object form's
     * map preserves and the decoder sorts both shapes by time before sampling. The projection emits
     * pairwise distinct ticks, and a reader still rejects a repeat rather than merging two keyframes,
     * so a hand-built node cannot lose a key silently.
     *
     * <p>A track still falls back to the object form as a whole when <em>any</em> key cannot be
     * expressed numerically: a Molang expression in place of a number, a non-finite tick or
     * component, or a repeated timestamp whose two keyframes a single tick slot cannot hold. Those
     * shapes keep today's exact object path and its recorded limitations, because a partial
     * translation there would silently drop authored semantics.
     *
     * <p>Arrays are copied in and cloned out. {@link #equals(Object)} compares by content, unlike a
     * plain record holding arrays, because the projection pools and compares values; {@link #toString()}
     * deliberately summarises instead of expanding, because a track may hold 65,536 keys.
     */
    public record JsonTimeline(float[] ticks,float[] pre,int[] sparseKeys,float[] sparsePost,byte[] sparseModes)
            implements JsonValue {
        /** Interpolation code for a linear key, matching {@code lerp_mode} "linear". */
        public static final byte LINEAR=0;
        /** Interpolation code for a step key, matching {@code lerp_mode} "step". */
        public static final byte STEP=1;
        /** Interpolation code for a Catmull-Rom key, matching {@code lerp_mode} "catmullrom". */
        public static final byte CATMULL_ROM=2;
        public JsonTimeline {
            Objects.requireNonNull(ticks); Objects.requireNonNull(pre);
            Objects.requireNonNull(sparseKeys); Objects.requireNonNull(sparsePost); Objects.requireNonNull(sparseModes);
            if (ticks.length==0) throw new IllegalArgumentException("Timeline needs at least one key");
            if (pre.length!=Math.multiplyExact(ticks.length,3))
                throw new IllegalArgumentException("Timeline needs three pre components per key");
            if (sparseKeys.length!=sparseModes.length)
                throw new IllegalArgumentException("Timeline needs one mode per sparse key");
            if (sparsePost.length!=Math.multiplyExact(sparseKeys.length,3))
                throw new IllegalArgumentException("Timeline needs three post components per sparse key");
            ticks=ticks.clone(); pre=pre.clone();
            sparseKeys=sparseKeys.clone(); sparsePost=sparsePost.clone(); sparseModes=sparseModes.clone();
            finite(ticks); finite(pre); finite(sparsePost);
            float[] ordered=ticks.clone();
            Arrays.sort(ordered);
            for (int i=1;i<ordered.length;i++) {
                if (ordered[i]==ordered[i-1])
                    throw new IllegalArgumentException("Timeline ticks must be pairwise distinct");
            }
            for (int i=0;i<sparseKeys.length;i++) {
                if (sparseKeys[i]<0 || sparseKeys[i]>=ticks.length)
                    throw new IllegalArgumentException("Timeline sparse key index out of range");
                if (i>0 && sparseKeys[i]<=sparseKeys[i-1])
                    throw new IllegalArgumentException("Timeline sparse key indices must strictly increase");
                if (sparseModes[i]!=LINEAR && sparseModes[i]!=STEP && sparseModes[i]!=CATMULL_ROM)
                    throw new IllegalArgumentException("Timeline interpolation code out of range");
            }
        }
        /** Number of keyframes. */
        public int keyCount() { return ticks.length; }
        /** The authored float32 tick of one key, before conversion to seconds. */
        public float tick(int key) { return ticks[key]; }
        /** One component of one key's {@code pre} vector, ordered x,y,z. */
        public float pre(int key,int axis) { return pre[key*3+axis]; }
        /** Number of keys that are not plain linear with {@code post} equal to {@code pre}. */
        public int sparseCount() { return sparseKeys.length; }
        /** The key index of one sparse entry, strictly increasing across entries. */
        public int sparseKey(int entry) { return sparseKeys[entry]; }
        /** One component of a sparse entry's {@code post} vector, ordered x,y,z. */
        public float sparsePost(int entry,int axis) { return sparsePost[entry*3+axis]; }
        /** The interpolation code of a sparse entry: {@link #LINEAR}, {@link #STEP} or {@link #CATMULL_ROM}. */
        public byte sparseMode(int entry) { return sparseModes[entry]; }
        /** Ticks as authored; the caller owns the returned copy. */
        @Override public float[] ticks() { return ticks.clone(); }
        /** {@code pre} vectors in key-major x,y,z order; the caller owns the returned copy. */
        @Override public float[] pre() { return pre.clone(); }
        /** Sparse key indices; the caller owns the returned copy. */
        @Override public int[] sparseKeys() { return sparseKeys.clone(); }
        /** Sparse {@code post} vectors in entry-major x,y,z order; the caller owns the returned copy. */
        @Override public float[] sparsePost() { return sparsePost.clone(); }
        /** Sparse interpolation codes; the caller owns the returned copy. */
        @Override public byte[] sparseModes() { return sparseModes.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof JsonTimeline timeline && Arrays.equals(ticks,timeline.ticks)
                    &&Arrays.equals(pre,timeline.pre) &&Arrays.equals(sparseKeys,timeline.sparseKeys)
                    &&Arrays.equals(sparsePost,timeline.sparsePost) &&Arrays.equals(sparseModes,timeline.sparseModes);
        }
        @Override public int hashCode() {
            return ((Arrays.hashCode(ticks)*31+Arrays.hashCode(pre))*31+Arrays.hashCode(sparseKeys))*31
                    +Arrays.hashCode(sparsePost)*31+Arrays.hashCode(sparseModes);
        }
        @Override public String toString() {
            return "JsonTimeline["+ticks.length+" keys, "+sparseKeys.length+" sparse]";
        }
    }
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
