package dev.spm.core.api;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    /** Deep immutable archival JSON. Objects retain declaration order for controllers and events. */
    public sealed interface JsonValue permits JsonObject,JsonArray,JsonString,JsonNumber,JsonBoolean,JsonNull {}
    public record JsonObject(Map<String,JsonValue> values) implements JsonValue {
        public JsonObject {
            var copy=new LinkedHashMap<String,JsonValue>();
            values.forEach((key,value)->copy.put(Objects.requireNonNull(key),Objects.requireNonNull(value)));
            values=Collections.unmodifiableMap(copy);
        }
    }
    public record JsonArray(List<JsonValue> values) implements JsonValue {
        public JsonArray { values=List.copyOf(values); }
    }
    public record JsonString(String value) implements JsonValue {
        public JsonString { Objects.requireNonNull(value); }
    }
    public record JsonNumber(BigDecimal value) implements JsonValue {
        public JsonNumber { Objects.requireNonNull(value); }
    }
    public record JsonBoolean(boolean value) implements JsonValue {}
    public enum JsonNull implements JsonValue { INSTANCE }

    private static void finite(float[] values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite model data");
    }
}
