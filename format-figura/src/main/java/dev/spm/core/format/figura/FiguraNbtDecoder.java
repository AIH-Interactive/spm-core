package dev.spm.core.format.figura;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Converts decoded Figura {@code avatar.nbt} compound trees into {@link ModelDocument}s and resource files. */
final class FiguraNbtDecoder {
    private static final int[][] CORNERS = {
            {6, 7, 5, 4}, {3, 2, 0, 1}, {6, 2, 3, 7}, {5, 1, 0, 4}, {7, 3, 1, 5}, {2, 6, 4, 0}
    };
    private static final float[][] NORMALS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final String[] DIRS = {"e", "w", "u", "d", "s", "n"};
    private static final String[] FULL_DIRS = {"east", "west", "up", "down", "south", "north"};

    record DecodedNbt(List<ModelDocument> documents, List<String> modelKeys) {}

    private FiguraNbtDecoder() {}

    static DecodedNbt decode(Map<String, Object> root, String identity, BoundedJson.Quota quota,
                             Map<ResourceKey, byte[]> outFiles) throws IOException {
        quota.cancellation.check();

        // Extract scripts (.lua UTF-8)
        Object scriptsObj = root.get("scripts");
        if (scriptsObj instanceof Map<?, ?> scriptsMap) {
            for (var e : scriptsMap.entrySet()) {
                String name = String.valueOf(e.getKey());
                String path = name.endsWith(".lua") ? name : name + ".lua";
                byte[] bytes = e.getValue() instanceof byte[] b ? b : String.valueOf(e.getValue()).getBytes(StandardCharsets.UTF_8);
                outFiles.putIfAbsent(new ResourceKey(path), bytes);
            }
        }

        // Extract textures (PNG bytes)
        var materials = new ArrayList<Material>();
        materials.add(new Material("figura:untextured", 16, 16, List.of()));
        var textureIndex = new LinkedHashMap<String, Integer>();
        Object texturesObj = root.get("textures");
        if (texturesObj instanceof Map<?, ?> texCompound) {
            Object dataObj = texCompound.get("data");
            if (dataObj instanceof List<?> dataList) {
                for (int i = 0; i < dataList.size(); i++) {
                    byte[] png = extractTextureBytes(dataList.get(i));
                    if (png != null) {
                        var rk = new ResourceKey("nbt_texture_" + i + ".png");
                        outFiles.putIfAbsent(rk, png);
                        textureIndex.put(String.valueOf(i), materials.size());
                        materials.add(new Material("nbt_texture_" + i, 64, 64, List.of(rk)));
                    }
                }
            }
            Object srcObj = texCompound.get("src");
            if (srcObj instanceof Map<?, ?> srcMap) {
                for (var e : srcMap.entrySet()) {
                    byte[] png = extractTextureBytes(e.getValue());
                    if (png != null) {
                        String keyName = String.valueOf(e.getKey());
                        var rk = new ResourceKey(keyName.endsWith(".png") ? keyName : keyName + ".png");
                        outFiles.putIfAbsent(rk, png);
                        textureIndex.putIfAbsent(keyName, materials.size());
                        materials.add(new Material(keyName, 64, 64, List.of(rk)));
                    }
                }
            }
        }

        // Extract metadata + animations JSON tree
        var metaMap = new LinkedHashMap<String, JsonValue>();
        if (root.get("metadata") instanceof Map<?, ?> md) {
            metaMap.put("figuraAvatar", toJson(md));
        }
        if (root.get("animations") != null) {
            metaMap.put("figuraNbtAnimations", toJson(root.get("animations")));
        }
        var metadata = new JsonObject(metaMap);

        var documents = new ArrayList<ModelDocument>();
        var keys = new ArrayList<String>();
        Object modelsObj = root.get("models");
        if (modelsObj instanceof Map<?, ?> modelsCompound) {
            Object childrenObj = modelsCompound.get("chld");
            if (childrenObj instanceof List<?> modelList && !modelList.isEmpty()) {
                for (int i = 0; i < modelList.size(); i++) {
                    if (modelList.get(i) instanceof Map<?, ?> mdlMap) {
                        String mdlName = str(mdlMap.get("name"), "model_" + i);
                        documents.add(buildModel(identity + "#" + mdlName, mdlMap, materials, metadata, quota));
                        keys.add(mdlName);
                    }
                }
            } else {
                documents.add(buildModel(identity, modelsCompound, materials, metadata, quota));
                keys.add("avatar");
            }
        } else {
            var skel = new Skeleton(List.of("root"), new int[] {-1}, new float[3]);
            documents.add(new ModelDocument(identity, "figura-nbt", skel, List.of(), BonePose.identity(1),
                    materials, List.of(), metadata));
            keys.add("avatar");
        }
        return new DecodedNbt(documents, keys);
    }

    private static byte[] extractTextureBytes(Object item) {
        if (item instanceof byte[] b) return b;
        if (item instanceof Map<?, ?> map && map.get("d") instanceof byte[] b) return b;
        return null;
    }

    private static ModelDocument buildModel(String identity, Map<?, ?> rootPart, List<Material> materials,
                                            JsonObject metadata, BoundedJson.Quota quota) throws IOException {
        var names = new ArrayList<String>();
        var parents = new ArrayList<Integer>();
        var pivots = new ArrayList<Float>();
        var rotations = new ArrayList<Float>();
        var hidden = new ArrayList<Boolean>();
        var posList = new ArrayList<Float>();
        var normList = new ArrayList<Float>();
        var uvList = new ArrayList<Float>();
        var boneList = new ArrayList<Integer>();
        var idxList = new ArrayList<Integer>();
        var unique = new HashSet<String>();

        visitPart(rootPart, -1, names, parents, pivots, rotations, hidden,
                posList, normList, uvList, boneList, idxList, unique, quota, 0);

        if (names.isEmpty()) {
            names.add("root");
            parents.add(-1);
            pivots.addAll(List.of(0f, 0f, 0f));
            rotations.addAll(List.of(0f, 0f, 0f));
            hidden.add(false);
        }
        int n = names.size();
        int[] pArr = new int[n];
        float[] pivArr = new float[n * 3], rotArr = new float[n * 3], scaleArr = new float[n * 3];
        Arrays.fill(scaleArr, 1f);
        boolean[] hidArr = new boolean[n];
        int[] glowArr = new int[n];
        Arrays.fill(glowArr, 255);
        for (int i = 0; i < n; i++) {
            pArr[i] = parents.get(i);
            hidArr[i] = hidden.get(i);
            for (int a = 0; a < 3; a++) {
                pivArr[i * 3 + a] = pivots.get(i * 3 + a);
                rotArr[i * 3 + a] = rotations.get(i * 3 + a);
            }
        }

        var meshes = new ArrayList<Mesh>();
        if (!idxList.isEmpty()) {
            float[] p = toFloatArray(posList), nrm = toFloatArray(normList), uv = toFloatArray(uvList);
            int[] bones = toIntArray(boneList), indices = toIntArray(idxList);
            float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            for (int i = 0; i < p.length; i += 3) {
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], p[i + a]);
                    max[a] = Math.max(max[a], p[i + a]);
                }
            }
            int matIdx = materials.size() > 1 ? 1 : 0;
            meshes.add(new Mesh("body", new VertexBatch(p, nrm, bones), uv, indices, List.of(),
                    new Bounds(min[0], min[1], min[2], max[0], max[1], max[2]), matIdx));
        }

        return new ModelDocument(identity, "figura-nbt", new Skeleton(names, pArr, pivArr), meshes,
                new BonePose(rotArr, new float[n * 3], scaleArr, hidArr, hidArr, glowArr),
                materials, List.of(), metadata);
    }

    private static void visitPart(Map<?, ?> part, int parent, List<String> names, List<Integer> parents,
                                  List<Float> pivots, List<Float> rotations, List<Boolean> hidden,
                                  List<Float> posList, List<Float> normList, List<Float> uvList,
                                  List<Integer> boneList, List<Integer> idxList, Set<String> unique,
                                  BoundedJson.Quota quota, int depth) throws IOException {
        quota.cancellation.check();
        if (depth > 128) throw new IOException("Figura NBT part hierarchy depth exceeded");
        String rawName = str(part.get("name"), "part_" + names.size());
        String name = unique.add(rawName) ? rawName : rawName + "_" + names.size();
        unique.add(name);
        int boneIdx = names.size();
        names.add(name);
        parents.add(parent);
        float[] piv = vec3(part.get("piv"), 16f);
        float[] rot = degVec3(part.get("rot"));
        for (int a = 0; a < 3; a++) {
            pivots.add(piv[a]);
            rotations.add(rot[a]);
        }
        boolean isHidden = Boolean.FALSE.equals(boolOrNull(part.get("vsb")));
        hidden.add(isHidden);

        if (part.get("cube_data") instanceof Map<?, ?> cube) {
            emitCube(cube, boneIdx, posList, normList, uvList, boneList, idxList);
        }
        if (part.get("chld") instanceof List<?> children) {
            for (Object child : children) {
                if (child instanceof Map<?, ?> childMap) {
                    visitPart(childMap, boneIdx, names, parents, pivots, rotations, hidden,
                            posList, normList, uvList, boneList, idxList, unique, quota, depth + 1);
                }
            }
        }
    }

    private static void emitCube(Map<?, ?> cube, int boneIdx, List<Float> posList, List<Float> normList,
                                 List<Float> uvList, List<Integer> boneList, List<Integer> idxList) {
        float[] from = vec3(cube.get("f"), 16f);
        float[] to = vec3(cube.get("t"), 16f);
        float inf = num(cube.get("inf"), 0f) / 16f;
        float x0 = from[0] - inf, y0 = from[1] - inf, z0 = from[2] - inf;
        float x1 = to[0] + inf, y1 = to[1] + inf, z1 = to[2] + inf;
        float[][] corners = {
                {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z0}, {x0, y1, z1},
                {x1, y0, z0}, {x1, y0, z1}, {x1, y1, z0}, {x1, y1, z1}
        };
        Map<?, ?> faces = cube.get("ft") instanceof Map<?, ?> m ? m : Map.of();
        for (int d = 0; d < 6; d++) {
            if (!faces.isEmpty() && !faces.containsKey(DIRS[d]) && !faces.containsKey(FULL_DIRS[d])) continue;
            int baseVert = posList.size() / 3;
            for (int c = 0; c < 4; c++) {
                float[] pt = corners[CORNERS[d][c]];
                posList.add(pt[0]); posList.add(pt[1]); posList.add(pt[2]);
                normList.add(NORMALS[d][0]); normList.add(NORMALS[d][1]); normList.add(NORMALS[d][2]);
                uvList.add(c == 0 || c == 3 ? 0f : 1f);
                uvList.add(c < 2 ? 0f : 1f);
                boneList.add(boneIdx);
            }
            idxList.add(baseVert); idxList.add(baseVert + 1); idxList.add(baseVert + 2);
            idxList.add(baseVert); idxList.add(baseVert + 2); idxList.add(baseVert + 3);
        }
    }

    private static JsonValue toJson(Object obj) {
        if (obj == null) return JsonNull.INSTANCE;
        if (obj instanceof Boolean b) return new JsonBoolean(b);
        if (obj instanceof Number n) return new JsonNumber(n.doubleValue());
        if (obj instanceof String s) return new JsonString(s);
        if (obj instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, JsonValue>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toJson(v)));
            return new JsonObject(out);
        }
        if (obj instanceof List<?> list) {
            var out = new ArrayList<JsonValue>(list.size());
            for (Object e : list) out.add(toJson(e));
            return new JsonArray(out);
        }
        return new JsonString(String.valueOf(obj));
    }

    private static float[] vec3(Object obj, float div) {
        if (!(obj instanceof List<?> list) || list.size() < 3) return new float[3];
        return new float[] {num(list.get(0), 0f) / div, num(list.get(1), 0f) / div, num(list.get(2), 0f) / div};
    }

    private static float[] degVec3(Object obj) {
        float[] deg = vec3(obj, 1f);
        return new float[] {(float) Math.toRadians(deg[0]), (float) Math.toRadians(deg[1]), (float) Math.toRadians(deg[2])};
    }

    private static float num(Object obj, float def) { return obj instanceof Number n ? n.floatValue() : def; }
    private static String str(Object obj, String def) {
        String s = obj == null ? def : String.valueOf(obj);
        return s.isBlank() ? def : s;
    }
    private static Boolean boolOrNull(Object obj) {
        if (obj instanceof Boolean b) return b;
        if (obj instanceof Number n) return n.intValue() != 0;
        return null;
    }
    private static float[] toFloatArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
    private static int[] toIntArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
