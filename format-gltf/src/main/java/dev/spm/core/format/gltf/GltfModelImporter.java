package dev.spm.core.format.gltf;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.common.BoundedJson;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * Bounded glTF 2.0 and GLB v2 importer supporting node TRS/matrix hierarchies, PBR materials and
 * textures, skeletal skins ({@code JOINTS_0}/{@code WEIGHTS_0}/inverse bind matrices), morph targets
 * and {@code LINEAR}/{@code STEP}/{@code CUBICSPLINE} animation tracks.
 */
public final class GltfModelImporter implements ModelFormatAdapter {
    private static final int GLB_MAGIC = 0x46546c67, JSON_CHUNK = 0x4e4f534a, BIN_CHUNK = 0x004e4942;

    @Override public String formatId() { return "gltf2"; }

    @Override public boolean supports(FormatHint hint) { return hint == FormatHint.GLTF || hint == FormatHint.GLB; }

    @Override public boolean accepts(FormatHint hint, ResourceKey entry, ByteBuffer prefix) {
        if (!supports(hint)) return false;
        if (hint == FormatHint.GLB) {
            int p = prefix.position();
            return prefix.remaining() >= 4 && prefix.get(p) == 'g' && prefix.get(p + 1) == 'l'
                    && prefix.get(p + 2) == 'T' && prefix.get(p + 3) == 'F';
        }
        return entry.path().toLowerCase(Locale.ROOT).endsWith(".gltf")
                || prefix.hasRemaining() && prefix.get(prefix.position()) == '{';
    }

    @Override public ImportResult importModel(ImportRequest request) throws ModelImportException {
        Objects.requireNonNull(request);
        var quota = new BoundedJson.Quota(request.policy(), request.budget(), request.cancellation());
        boolean transferred = false;
        long[] inputBytes = {0};
        try {
            byte[] source = read(request.source().resolver(), request.source().entry(), request, quota, inputBytes);
            boolean glb = request.hint() == FormatHint.GLB
                    || request.hint() == FormatHint.AUTO && request.source().entry().path().toLowerCase(Locale.ROOT).endsWith(".glb");
            byte[] json, embedded = null;
            if (glb) {
                var parts = glb(source);
                json = parts.json();
                embedded = parts.binary();
            } else {
                json = source;
            }
            quota.charge(json.length);
            var root = BoundedJson.read(json, quota);
            require("2.0".equals(string(obj(root.get("asset"), "asset").get("version"), "asset.version")),
                    "Only glTF 2.0 is supported");
            rejectUnsupported(root);
            GltfAccessors.validateBufferViews(root);
            List<byte[]> buffers = loadBuffers(root, request, quota, embedded, inputBytes);
            for (byte[] buffer : buffers) quota.charge(buffer.length);
            List<JsonValue> views = arrayOptional(root.get("bufferViews"));
            List<JsonValue> accessors = arrayOptional(root.get("accessors"));
            List<JsonValue> meshValues = array(root.get("meshes"), "meshes");
            GltfAccessors.validateAllAccessors(accessors, views, buffers, quota);

            var resources = new LinkedHashMap<ResourceKey, byte[]>();
            var imageKeys = loadImages(root, views, buffers, request, quota, inputBytes, resources);
            var materials = loadMaterials(root, imageKeys);
            var materialList = materials.materials();

            boolean hasNodes = root.get("nodes") != null && !arrayOptional(root.get("nodes")).isEmpty();
            List<ImportResult.Model> models;
            var retained = new ArrayList<ResourceBudget.Reservation>();
            if (!hasNodes) {
                models = importMeshesDirectly(root, meshValues, accessors, views, buffers, materialList,
                        materials.metadata(), request, quota, retained);
            } else {
                models = importScene(root, meshValues, accessors, views, buffers, materialList,
                        materials.metadata(), request, quota, retained);
            }
            if (models.isEmpty()) throw new IOException("No supported triangle meshes");
            transferred = true;
            var ownedResources = Map.copyOf(resources);
            ResourceResolver resolver = key -> {
                byte[] bytes = ownedResources.get(Objects.requireNonNull(key));
                if (bytes == null) throw new IOException("Unknown glTF resource: " + key.path());
                return new ByteArrayInputStream(bytes);
            };
            return new ImportResult(models, List.of(), resolver, () -> {});
        } catch (Exception failure) {
            if (!transferred) quota.close();
            throw error(request, failure);
        } catch (Error failure) {
            if (!transferred) quota.close();
            throw failure;
        }
    }

    private List<ImportResult.Model> importMeshesDirectly(
            JsonObject root, List<JsonValue> meshValues, List<JsonValue> accessors, List<JsonValue> views,
            List<byte[]> buffers, List<Material> materialList, JsonArray materialMetadata,
            ImportRequest request, BoundedJson.Quota quota, List<ResourceBudget.Reservation> outReservations)
            throws IOException {
        List<ModelDocument> documents = new ArrayList<>();
        List<SkinningRig> rigs = new ArrayList<>();
        List<SkinState> states = new ArrayList<>();
        List<String> modelNames = new ArrayList<>();
        var skeleton = new Skeleton(List.of("root"), new int[] {-1}, new float[3]);
        var bindPose = BonePose.identity(1);
        int modelIndex = 0;
        for (JsonValue meshValue : meshValues) {
            request.cancellation().check();
            var meshObject = obj(meshValue, "mesh");
            String meshName = optionalString(meshObject.get("name"), "mesh-" + modelIndex);
            var built = buildMeshPrimitives(meshObject, meshName, 0, null, null, 1,
                    accessors, views, buffers, materialList.size(), request, quota);
            if (!built.meshes().isEmpty()) {
                var meta = new LinkedHashMap<String, JsonValue>();
                meta.put("extensionsUsed", new JsonArray(jsonStrings(strings(root.get("extensionsUsed")))));
                meta.put("gltfMaterials", materialMetadata);
                var doc = new ModelDocument(request.source().identity() + "#" + meshName, "gltf2",
                        skeleton, built.meshes(), bindPose, materialList, List.of(), new JsonObject(meta));
                SkinningRig rig = built.skins().isEmpty() ? null : SkinningRig.of(doc, built.skins());
                SkinState state = built.morphDefaults().isEmpty() ? SkinState.rest() : SkinState.of(built.morphDefaults());
                documents.add(doc);
                rigs.add(rig);
                states.add(state);
                modelNames.add(meshName);
            }
            modelIndex++;
        }
        return packageModels(documents, rigs, states, modelNames, quota, outReservations);
    }

    private List<ImportResult.Model> importScene(
            JsonObject root, List<JsonValue> meshValues, List<JsonValue> accessors, List<JsonValue> views,
            List<byte[]> buffers, List<Material> materialList, JsonArray materialMetadata,
            ImportRequest request, BoundedJson.Quota quota, List<ResourceBudget.Reservation> outReservations)
            throws IOException {
        List<JsonValue> nodeValues = array(root.get("nodes"), "nodes");
        int nodeCount = nodeValues.size();
        require(nodeCount > 0 && nodeCount <= quota.policy.maxCollectionEntries(), "Invalid glTF node count");
        int[] parents = new int[nodeCount];
        Arrays.fill(parents, -1);
        List<String> boneNames = new ArrayList<>(nodeCount);
        var uniqueNames = new HashSet<String>();
        float[] rotations = new float[Math.multiplyExact(nodeCount, 3)];
        float[] translations = new float[Math.multiplyExact(nodeCount, 3)];
        float[] scales = new float[Math.multiplyExact(nodeCount, 3)];
        int[] glow = new int[nodeCount];
        Arrays.fill(glow, 255);

        for (int i = 0; i < nodeCount; i++) {
            request.cancellation().check();
            var node = obj(nodeValues.get(i), "node");
            String rawName = optionalString(node.get("name"), "node-" + i);
            if (rawName.isBlank()) rawName = "node-" + i;
            String name = uniqueNames.add(rawName) ? rawName : rawName + "#" + i;
            uniqueNames.add(name);
            boneNames.add(name);
            for (JsonValue childVal : arrayOptional(node.get("children"))) {
                int child = integer(childVal, "node.child");
                require(child >= 0 && child < nodeCount && child != i, "Invalid glTF child node index");
                require(parents[child] == -1, "glTF node has multiple parents");
                parents[child] = i;
            }
            if (node.get("matrix") != null) {
                require(node.get("translation") == null && node.get("rotation") == null && node.get("scale") == null,
                        "glTF node cannot declare both matrix and TRS");
                float[] m = floatArray(node.get("matrix"), 16, "node.matrix");
                var trs = GltfTransforms.decomposeMatrix(m);
                System.arraycopy(trs.translation(), 0, translations, i * 3, 3);
                System.arraycopy(trs.eulerZyx(), 0, rotations, i * 3, 3);
                System.arraycopy(trs.scale(), 0, scales, i * 3, 3);
            } else {
                float[] t = node.get("translation") == null ? new float[3] : floatArray(node.get("translation"), 3, "node.translation");
                float[] q = node.get("rotation") == null ? new float[] {0, 0, 0, 1} : floatArray(node.get("rotation"), 4, "node.rotation");
                float[] s = node.get("scale") == null ? new float[] {1, 1, 1} : floatArray(node.get("scale"), 3, "node.scale");
                System.arraycopy(t, 0, translations, i * 3, 3);
                System.arraycopy(GltfTransforms.quaternionToEulerZyx(q[0], q[1], q[2], q[3]), 0, rotations, i * 3, 3);
                System.arraycopy(s, 0, scales, i * 3, 3);
            }
        }
        var skeleton = new Skeleton(boneNames, parents, new float[nodeCount * 3]);
        var bindPose = new BonePose(rotations, translations, scales, new boolean[nodeCount], new boolean[nodeCount], glow);

        List<JsonValue> skinValues = arrayOptional(root.get("skins"));
        List<ParsedSkin> parsedSkins = new ArrayList<>(skinValues.size());
        for (JsonValue skinVal : skinValues) {
            parsedSkins.add(parseSkin(obj(skinVal, "skin"), nodeCount, accessors, views, buffers, quota));
        }

        List<Integer> activeNodes = resolveSceneNodes(root, nodeCount);
        var meshes = new ArrayList<Mesh>();
        var skinBindings = new LinkedHashMap<String, MeshSkin>();
        var morphDefaults = new LinkedHashMap<String, MorphWeights>();
        var meshNodeMapping = new ArrayList<JsonValue>();
        var usedMeshNames = new HashSet<String>();
        for (int nodeIndex : activeNodes) {
            var node = obj(nodeValues.get(nodeIndex), "node");
            if (node.get("mesh") == null) continue;
            int meshIndex = integer(node.get("mesh"), "node.mesh");
            require(meshIndex >= 0 && meshIndex < meshValues.size(), "node.mesh out of range");
            var meshObj = obj(meshValues.get(meshIndex), "mesh");
            String baseName = optionalString(meshObj.get("name"), "mesh-" + meshIndex);
            if (!usedMeshNames.add(baseName)) baseName = baseName + "-node-" + nodeIndex;
            usedMeshNames.add(baseName);
            ParsedSkin skin = null;
            if (node.get("skin") != null) {
                int skinIndex = integer(node.get("skin"), "node.skin");
                require(skinIndex >= 0 && skinIndex < parsedSkins.size(), "node.skin out of range");
                skin = parsedSkins.get(skinIndex);
            }
            var built = buildMeshPrimitives(meshObj, baseName, nodeIndex, skin, null, nodeCount,
                    accessors, views, buffers, materialList.size(), request, quota);
            meshes.addAll(built.meshes());
            skinBindings.putAll(built.skins());
            morphDefaults.putAll(built.morphDefaults());
            for (var m : built.meshes()) {
                meshNodeMapping.add(new JsonObject(Map.of(
                        "mesh", new JsonString(m.name()),
                        "nodeIndex", new JsonNumber(nodeIndex),
                        "targetCount", new JsonNumber(built.targetCount()))));
            }
        }
        if (meshes.isEmpty()) throw new IOException("No supported triangle meshes");
        JsonArray animArray = parseAnimations(root, nodeCount, accessors, views, buffers, quota);
        var meta = new LinkedHashMap<String, JsonValue>();
        meta.put("extensionsUsed", new JsonArray(jsonStrings(strings(root.get("extensionsUsed")))));
        meta.put("gltfMaterials", materialMetadata);
        meta.put("gltfNodeMeshes", new JsonArray(meshNodeMapping));
        meta.put("gltfAnimations", animArray);
        var doc = new ModelDocument(request.source().identity(), "gltf2",
                skeleton, meshes, bindPose, materialList, List.of(), new JsonObject(meta));
        SkinningRig rig = skinBindings.isEmpty() ? null : SkinningRig.of(doc, skinBindings);
        SkinState state = morphDefaults.isEmpty() ? SkinState.rest() : SkinState.of(morphDefaults);
        return packageModels(List.of(doc), Collections.singletonList(rig), Collections.singletonList(state),
                List.of("scene"), quota, outReservations);
    }

    private record ParsedSkin(int[] joints, float[] boneInverseBindMatrices) {}

    private static ParsedSkin parseSkin(JsonObject skin, int nodeCount, List<JsonValue> accessors,
                                        List<JsonValue> views, List<byte[]> buffers, BoundedJson.Quota quota)
            throws IOException {
        List<JsonValue> jointValues = array(skin.get("joints"), "skin.joints");
        require(!jointValues.isEmpty() && jointValues.size() <= quota.policy.maxCollectionEntries(), "Invalid skin.joints");
        int[] joints = new int[jointValues.size()];
        for (int i = 0; i < joints.length; i++) {
            int j = integer(jointValues.get(i), "skin.joint");
            require(j >= 0 && j < nodeCount, "Skin joint index out of range");
            joints[i] = j;
        }
        float[] fullIbm = new float[Math.multiplyExact(nodeCount, 16)];
        for (int b = 0; b < nodeCount; b++) {
            fullIbm[b * 16] = 1; fullIbm[b * 16 + 5] = 1; fullIbm[b * 16 + 10] = 1; fullIbm[b * 16 + 15] = 1;
        }
        if (skin.get("inverseBindMatrices") != null) {
            int accIdx = integer(skin.get("inverseBindMatrices"), "skin.inverseBindMatrices");
            float[] raw = GltfAccessors.readMat4Array(accessor(accessors, accIdx), views, buffers, quota, joints.length);
            for (int j = 0; j < joints.length; j++) {
                System.arraycopy(raw, j * 16, fullIbm, joints[j] * 16, 16);
            }
        }
        return new ParsedSkin(joints, fullIbm);
    }

    private record BuiltPrimitives(List<Mesh> meshes, Map<String, MeshSkin> skins,
                                   Map<String, MorphWeights> morphDefaults, int targetCount) {}

    private static BuiltPrimitives buildMeshPrimitives(
            JsonObject meshObject, String baseName, int defaultBone, ParsedSkin skin, float[] explicitWeights,
            int boneCount, List<JsonValue> accessors, List<JsonValue> views, List<byte[]> buffers,
            int materialCount, ImportRequest request, BoundedJson.Quota quota) throws IOException {
        List<JsonValue> primitives = array(meshObject.get("primitives"), "primitives");
        float[] meshWeights = null;
        if (meshObject.get("weights") != null) {
            List<JsonValue> wVals = array(meshObject.get("weights"), "mesh.weights");
            meshWeights = new float[wVals.size()];
            for (int i = 0; i < meshWeights.length; i++) {
                float w = (float) number(wVals.get(i), "mesh.weights");
                require(Float.isFinite(w), "Non-finite morph weight");
                meshWeights[i] = w;
            }
        }
        List<Mesh> meshes = new ArrayList<>();
        var skins = new LinkedHashMap<String, MeshSkin>();
        var morphDefaults = new LinkedHashMap<String, MorphWeights>();
        int primTargetCount = 0;
        int primitiveIndex = 0;
        for (JsonValue primitiveValue : primitives) {
            request.cancellation().check();
            var primitive = obj(primitiveValue, "primitive");
            require(integer(optional(primitive.get("mode"), 4), "primitive.mode") == 4, "Only TRIANGLES primitives are supported");
            var attributes = obj(primitive.get("attributes"), "primitive.attributes");
            for (String key : attributes.values().keySet()) {
                requireSupported(key.equals("POSITION") || key.equals("NORMAL") || key.equals("TEXCOORD_0")
                        || key.equals("COLOR_0") || key.equals("JOINTS_0") || key.equals("WEIGHTS_0"),
                        "Unsupported vertex attribute: " + key);
            }
            int matIndex = 0;
            if (primitive.get("material") != null) {
                matIndex = integer(primitive.get("material"), "primitive.material") + 1; // +1 after default material 0
                require(matIndex >= 1 && matIndex < materialCount, "primitive.material out of range");
            }
            int positionAccessor = integer(attributes.get("POSITION"), "POSITION accessor");
            require(positionAccessor >= 0 && positionAccessor < accessors.size(), "POSITION accessor index out of range");
            int vertices = integer(obj(accessors.get(positionAccessor), "POSITION accessor").get("count"), "POSITION count");
            JsonValue indexValue = primitive.get("indices");
            int indexCount = indexValue == null ? vertices : integer(accessor(accessors, integer(indexValue, "indices")).get("count"), "indices.count");
            require(indexCount % 3 == 0, "Triangle index count is not divisible by three");
            quota.charge(modelAllocationBytes(vertices, indexCount));
            float[] positions = GltfAccessors.readVec3(obj(accessors.get(positionAccessor), "POSITION accessor"), views, buffers, quota, "POSITION");
            require(positions.length / 3 == vertices, "POSITION count changed during decoding");
            JsonValue uv = attributes.get("TEXCOORD_0");
            float[] uvs = uv == null ? new float[Math.multiplyExact(vertices, 2)]
                    : GltfAccessors.readVec2(accessor(accessors, integer(uv, "TEXCOORD_0")), views, buffers, quota, vertices);
            int[] indices = indexValue == null ? sequence(vertices)
                    : GltfAccessors.readIndices(accessor(accessors, integer(indexValue, "indices")), views, buffers, quota, vertices);
            require(indices.length == indexCount, "Index count changed during decoding");
            for (int index : indices) require(index >= 0 && index < vertices, "Index outside POSITION accessor");
            JsonValue normalAccessor = attributes.get("NORMAL");
            float[] normals = normalAccessor == null ? generatedNormals(positions, indices, quota)
                    : GltfAccessors.readVec3(accessor(accessors, integer(normalAccessor, "NORMAL")), views, buffers, quota, "NORMAL");
            require(normals.length == positions.length, "NORMAL count must match POSITION count");

            int[] colors;
            if (attributes.get("COLOR_0") != null) {
                colors = GltfAccessors.readColorsRgba(accessor(accessors, integer(attributes.get("COLOR_0"), "COLOR_0")),
                        views, buffers, quota, vertices);
            } else {
                colors = new int[vertices];
                Arrays.fill(colors, 0xffffffff);
            }
            int[] bones = new int[vertices], glow = new int[vertices];
            Arrays.fill(bones, defaultBone);
            Arrays.fill(glow, 255);

            SkinWeights skinWeights = null;
            if (skin != null && (attributes.get("JOINTS_0") != null || attributes.get("WEIGHTS_0") != null)) {
                require(attributes.get("JOINTS_0") != null && attributes.get("WEIGHTS_0") != null,
                        "Skinned primitive requires both JOINTS_0 and WEIGHTS_0");
                int[] rawJoints = GltfAccessors.readJoints(accessor(accessors, integer(attributes.get("JOINTS_0"), "JOINTS_0")),
                        views, buffers, quota, vertices);
                float[] rawWeights = GltfAccessors.readWeights(accessor(accessors, integer(attributes.get("WEIGHTS_0"), "WEIGHTS_0")),
                        views, buffers, quota, vertices);
                skinWeights = buildSkinWeights(vertices, boneCount, skin.joints(), rawJoints, rawWeights, bones);
            }

            List<MorphTarget> targets = new ArrayList<>();
            if (primitive.get("targets") != null) {
                List<JsonValue> targetList = array(primitive.get("targets"), "primitive.targets");
                require(targetList.size() <= 256, "Too many morph targets");
                for (int t = 0; t < targetList.size(); t++) {
                    var targetObj = obj(targetList.get(t), "morph target");
                    for (String k : targetObj.values().keySet()) {
                        requireSupported(k.equals("POSITION") || k.equals("NORMAL"),
                                "Unsupported morph target attribute: " + k);
                    }
                    int posDeltaIdx = integer(targetObj.get("POSITION"), "morph POSITION");
                    float[] posDelta = GltfAccessors.readVec3(accessor(accessors, posDeltaIdx), views, buffers, quota, "morph POSITION");
                    require(posDelta.length == positions.length, "Morph POSITION length mismatch");
                    float[] normDelta = new float[0];
                    if (targetObj.get("NORMAL") != null) {
                        int normDeltaIdx = integer(targetObj.get("NORMAL"), "morph NORMAL");
                        normDelta = GltfAccessors.readVec3(accessor(accessors, normDeltaIdx), views, buffers, quota, "morph NORMAL");
                        require(normDelta.length == normals.length, "Morph NORMAL length mismatch");
                    }
                    targets.add(new MorphTarget("target-" + t, posDelta, normDelta));
                }
                primTargetCount = targets.size();
            }

            float[] min = {0, 0, 0}, max = {0, 0, 0};
            if (vertices > 0) {
                Arrays.fill(min, Float.POSITIVE_INFINITY);
                Arrays.fill(max, Float.NEGATIVE_INFINITY);
                for (int i = 0; i < positions.length; i++) {
                    int axis = i % 3;
                    min[axis] = Math.min(min[axis], positions[i]);
                    max[axis] = Math.max(max[axis], positions[i]);
                }
            }
            String primName = primitiveIndex == 0 ? baseName : baseName + "-" + primitiveIndex;
            meshes.add(new Mesh(primName, new VertexBatch(positions, normals, bones, glow, colors),
                    uvs, indices, List.of(), new Bounds(min[0], min[1], min[2], max[0], max[1], max[2]), matIndex));
            if (skinWeights != null || !targets.isEmpty()) {
                SkinWeights effectiveWeights = skinWeights != null ? skinWeights : SkinWeights.rigid(boneCount, bones);
                float[] ibm = skin != null ? skin.boneInverseBindMatrices() : new float[0];
                skins.put(primName, new MeshSkin.Weighted(effectiveWeights, SkinningMethod.BDEF, targets,
                        SdefParameters.none(), ibm));
                if (!targets.isEmpty()) {
                    float[] initialWeights = new float[targets.size()];
                    if (meshWeights != null) {
                        require(meshWeights.length == targets.size(), "mesh.weights length must match morph targets");
                        System.arraycopy(meshWeights, 0, initialWeights, 0, targets.size());
                    }
                    morphDefaults.put(primName, new MorphWeights(initialWeights));
                }
            }
            primitiveIndex++;
        }
        return new BuiltPrimitives(meshes, skins, morphDefaults, primTargetCount);
    }

    private static SkinWeights buildSkinWeights(int vertices, int boneCount, int[] joints, int[] rawJoints,
                                              float[] rawWeights, int[] primaryBoneOut) throws IOException {
        int[][] packedBones = new int[vertices][];
        float[][] packedWeights = new float[vertices][];
        for (int v = 0; v < vertices; v++) {
            int[] slotBones = new int[4];
            float[] slotWeights = new float[4];
            int count = 0;
            double sum = 0;
            for (int s = 0; s < 4; s++) {
                int jIdx = rawJoints[v * 4 + s];
                float w = rawWeights[v * 4 + s];
                if (w == 0f) continue;
                require(jIdx >= 0 && jIdx < joints.length, "Vertex JOINTS_0 index out of skin.joints bounds");
                int bone = joints[jIdx];
                boolean merged = false;
                for (int prev = 0; prev < count; prev++) {
                    if (slotBones[prev] == bone) {
                        slotWeights[prev] += w;
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    slotBones[count] = bone;
                    slotWeights[count] = w;
                    count++;
                }
                sum += w;
            }
            if (count == 0) {
                // Spec fallback when all weights are zero: bind rigidly to the first joint or primary bone.
                slotBones[0] = joints.length > 0 ? joints[0] : primaryBoneOut[v];
                slotWeights[0] = 1.0f;
                count = 1;
                sum = 1.0;
            }
            packedBones[v] = Arrays.copyOf(slotBones, count);
            packedWeights[v] = new float[count];
            int bestBone = packedBones[v][0];
            float bestW = -1f;
            for (int k = 0; k < count; k++) {
                float normW = (float) (slotWeights[k] / sum);
                packedWeights[v][k] = normW;
                if (normW > bestW) {
                    bestW = normW;
                    bestBone = packedBones[v][k];
                }
            }
            primaryBoneOut[v] = bestBone;
        }
        return SkinWeights.of(boneCount, packedBones, packedWeights);
    }

    private record MaterialLoad(List<Material> materials, JsonArray metadata) {}

    private static List<ResourceKey> loadImages(JsonObject root, List<JsonValue> views, List<byte[]> buffers,
                                                ImportRequest request, BoundedJson.Quota quota, long[] inputBytes,
                                                Map<ResourceKey, byte[]> outResources) throws IOException {
        List<JsonValue> images = arrayOptional(root.get("images"));
        List<ResourceKey> keys = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            request.cancellation().check();
            var img = obj(images.get(i), "image");
            String mime = img.get("mimeType") == null ? null : string(img.get("mimeType"), "image.mimeType");
            byte[] data;
            ResourceKey key;
            if (img.get("bufferView") != null) {
                int viewIndex = integer(img.get("bufferView"), "image.bufferView");
                require(viewIndex >= 0 && viewIndex < views.size(), "image.bufferView out of range");
                var view = obj(views.get(viewIndex), "bufferView");
                int bufIdx = integer(optional(view.get("buffer"), 0), "bufferView.buffer");
                int viewOff = integer(optional(view.get("byteOffset"), 0), "bufferView.byteOffset");
                int viewLen = integer(view.get("byteLength"), "bufferView.byteLength");
                byte[] buf = buffers.get(bufIdx);
                require(viewOff >= 0 && (long) viewOff + viewLen <= buf.length, "image bufferView exceeds buffer");
                data = Arrays.copyOfRange(buf, viewOff, viewOff + viewLen);
                quota.charge(data.length);
                String ext = imageExt(mime, data);
                key = new ResourceKey("gltf/images/" + i + "." + ext);
            } else if (img.get("uri") != null) {
                String uri = string(img.get("uri"), "image.uri");
                if (uri.startsWith("data:")) {
                    data = decodeImageDataUri(uri, quota);
                    quota.charge(data.length);
                    String ext = imageExt(mime, data);
                    key = new ResourceKey("gltf/images/" + i + "." + ext);
                } else {
                    key = resolve(request.source().entry(), uri);
                    data = read(request.source().resolver(), key, request, quota, inputBytes);
                }
            } else {
                throw new IOException("glTF image requires uri or bufferView");
            }
            outResources.put(key, data);
            keys.add(key);
        }
        return keys;
    }

    private static MaterialLoad loadMaterials(JsonObject root, List<ResourceKey> imageKeys) throws IOException {
        List<JsonValue> textures = arrayOptional(root.get("textures"));
        List<JsonValue> materialsJson = arrayOptional(root.get("materials"));
        var materials = new ArrayList<Material>(materialsJson.size() + 1);
        materials.add(new Material("default", 1, 1, List.of()));
        var metaList = new ArrayList<JsonValue>(materialsJson.size());
        for (int i = 0; i < materialsJson.size(); i++) {
            var mat = obj(materialsJson.get(i), "material");
            String name = optionalString(mat.get("name"), "material-" + i);
            if (name.isBlank()) name = "material-" + i;
            var matResources = new ArrayList<ResourceKey>();
            var matMeta = new LinkedHashMap<String, JsonValue>();
            matMeta.put("name", new JsonString(name));
            matMeta.put("alphaMode", new JsonString(optionalString(mat.get("alphaMode"), "OPAQUE")));
            matMeta.put("alphaCutoff", new JsonNumber(mat.get("alphaCutoff") == null ? 0.5 : number(mat.get("alphaCutoff"), "alphaCutoff")));
            matMeta.put("doubleSided", new JsonBoolean(mat.get("doubleSided") instanceof JsonBoolean b && b.value()));
            if (mat.get("pbrMetallicRoughness") != null) {
                var pbr = obj(mat.get("pbrMetallicRoughness"), "pbrMetallicRoughness");
                if (pbr.get("baseColorFactor") != null) {
                    matMeta.put("baseColorFactor", pbr.get("baseColorFactor"));
                }
                if (pbr.get("metallicFactor") != null) {
                    matMeta.put("metallicFactor", pbr.get("metallicFactor"));
                }
                if (pbr.get("roughnessFactor") != null) {
                    matMeta.put("roughnessFactor", pbr.get("roughnessFactor"));
                }
                addTextureRef(pbr.get("baseColorTexture"), "baseColorTexture", textures, imageKeys, matResources, matMeta);
                addTextureRef(pbr.get("metallicRoughnessTexture"), "metallicRoughnessTexture", textures, imageKeys, matResources, matMeta);
            }
            addTextureRef(mat.get("normalTexture"), "normalTexture", textures, imageKeys, matResources, matMeta);
            addTextureRef(mat.get("emissiveTexture"), "emissiveTexture", textures, imageKeys, matResources, matMeta);
            if (mat.get("emissiveFactor") != null) matMeta.put("emissiveFactor", mat.get("emissiveFactor"));
            materials.add(new Material(name, 1, 1, matResources));
            metaList.add(new JsonObject(matMeta));
        }
        return new MaterialLoad(materials, new JsonArray(metaList));
    }

    private static void addTextureRef(JsonValue texInfo, String role, List<JsonValue> textures,
                                      List<ResourceKey> imageKeys, List<ResourceKey> matResources,
                                      Map<String, JsonValue> matMeta) throws IOException {
        if (texInfo == null) return;
        int texIdx = integer(obj(texInfo, role).get("index"), role + ".index");
        require(texIdx >= 0 && texIdx < textures.size(), "Texture index out of range");
        var tex = obj(textures.get(texIdx), "texture");
        if (tex.get("source") == null) return;
        int srcIdx = integer(tex.get("source"), "texture.source");
        require(srcIdx >= 0 && srcIdx < imageKeys.size(), "Image source index out of range");
        ResourceKey key = imageKeys.get(srcIdx);
        matResources.add(key);
        matMeta.put(role, new JsonString(key.path()));
    }

    private static JsonArray parseAnimations(JsonObject root, int nodeCount, List<JsonValue> accessors,
                                           List<JsonValue> views, List<byte[]> buffers, BoundedJson.Quota quota)
            throws IOException {
        List<JsonValue> animations = arrayOptional(root.get("animations"));
        var out = new ArrayList<JsonValue>(animations.size());
        for (int a = 0; a < animations.size(); a++) {
            var anim = obj(animations.get(a), "animation");
            String name = optionalString(anim.get("name"), "animation-" + a);
            if (name.isBlank()) name = "animation-" + a;
            List<JsonValue> samplers = array(anim.get("samplers"), "animation.samplers");
            List<JsonValue> channels = array(anim.get("channels"), "animation.channels");
            var channelList = new ArrayList<JsonValue>(channels.size());
            double duration = 0.0;
            for (JsonValue chVal : channels) {
                var ch = obj(chVal, "animation.channel");
                var target = obj(ch.get("target"), "channel.target");
                if (target.get("node") == null) continue;
                int nodeIdx = integer(target.get("node"), "target.node");
                require(nodeIdx >= 0 && nodeIdx < nodeCount, "Animation target node out of range");
                String path = string(target.get("path"), "target.path");
                require(path.equals("translation") || path.equals("rotation") || path.equals("scale") || path.equals("weights"),
                        "Unsupported animation path: " + path);
                int samplerIdx = integer(ch.get("sampler"), "channel.sampler");
                require(samplerIdx >= 0 && samplerIdx < samplers.size(), "Animation sampler out of range");
                var sampler = obj(samplers.get(samplerIdx), "animation.sampler");
                String interp = optionalString(sampler.get("interpolation"), "LINEAR");
                require(interp.equals("LINEAR") || interp.equals("STEP") || interp.equals("CUBICSPLINE"),
                        "Unsupported animation interpolation: " + interp);
                int inIdx = integer(sampler.get("input"), "sampler.input");
                int outIdx = integer(sampler.get("output"), "sampler.output");
                float[] times = GltfAccessors.readFloats(accessor(accessors, inIdx), views, buffers, quota, "SCALAR", 1);
                for (int t = 1; t < times.length; t++) {
                    require(times[t] >= times[t - 1], "Animation timestamps must be non-decreasing");
                }
                if (times.length > 0) duration = Math.max(duration, times[times.length - 1]);
                String expectedOutType = switch (path) {
                    case "translation", "scale" -> "VEC3";
                    case "rotation" -> "VEC4";
                    case "weights" -> "SCALAR";
                    default -> throw new IllegalStateException();
                };
                int lanes = switch (expectedOutType) {
                    case "VEC3" -> 3;
                    case "VEC4" -> 4;
                    default -> 1;
                };
                float[] values = GltfAccessors.readFloats(accessor(accessors, outIdx), views, buffers, quota, expectedOutType, lanes);
                int keyMultiplier = interp.equals("CUBICSPLINE") ? 3 : 1;
                if (path.equals("weights")) {
                    require(values.length >= times.length * keyMultiplier && values.length % (times.length * keyMultiplier) == 0,
                            "Morph animation output count mismatch");
                } else {
                    require(values.length == Math.multiplyExact(times.length * keyMultiplier, lanes),
                            "Animation sampler input/output count mismatch");
                }
                var tArr = new ArrayList<JsonValue>(times.length);
                for (float tv : times) tArr.add(new JsonNumber(tv));
                var vArr = new ArrayList<JsonValue>(values.length);
                for (float vv : values) vArr.add(new JsonNumber(vv));
                channelList.add(new JsonObject(Map.of(
                        "node", new JsonNumber(nodeIdx),
                        "path", new JsonString(path),
                        "interpolation", new JsonString(interp),
                        "times", new JsonArray(tArr),
                        "values", new JsonArray(vArr))));
            }
            out.add(new JsonObject(Map.of(
                    "name", new JsonString(name),
                    "duration", new JsonNumber(duration),
                    "channels", new JsonArray(channelList))));
        }
        return new JsonArray(out);
    }

    private static List<Integer> resolveSceneNodes(JsonObject root, int nodeCount) throws IOException {
        List<JsonValue> scenes = arrayOptional(root.get("scenes"));
        if (scenes.isEmpty()) {
            var all = new ArrayList<Integer>(nodeCount);
            for (int i = 0; i < nodeCount; i++) all.add(i);
            return all;
        }
        int sceneIndex = root.get("scene") == null ? 0 : integer(root.get("scene"), "scene");
        require(sceneIndex >= 0 && sceneIndex < scenes.size(), "Default glTF scene out of range");
        var scene = obj(scenes.get(sceneIndex), "scene");
        List<JsonValue> roots = arrayOptional(scene.get("nodes"));
        List<JsonValue> nodes = array(root.get("nodes"), "nodes");
        var result = new ArrayList<Integer>();
        var visited = new boolean[nodeCount];
        var stack = new ArrayDeque<Integer>();
        for (JsonValue r : roots) stack.add(integer(r, "scene.node"));
        while (!stack.isEmpty()) {
            int idx = stack.removeFirst();
            require(idx >= 0 && idx < nodeCount, "Scene node index out of range");
            require(!visited[idx], "Cyclic glTF scene node graph");
            visited[idx] = true;
            result.add(idx);
            for (JsonValue c : arrayOptional(obj(nodes.get(idx), "node").get("children"))) {
                stack.addLast(integer(c, "node.child"));
            }
        }
        return result;
    }

    private static List<ImportResult.Model> packageModels(
            List<ModelDocument> documents, List<SkinningRig> rigs, List<SkinState> states,
            List<String> modelNames, BoundedJson.Quota quota, List<ResourceBudget.Reservation> outReservations)
            throws IOException {
        var retained = quota.transfer();
        outReservations.addAll(retained);
        List<ImportResult.Model> models = new ArrayList<>(documents.size());
        SharedRetention shared = null;
        int assigned = 0;
        try {
            shared = new SharedRetention(retained, documents.size());
            for (int i = 0; i < documents.size(); i++) {
                models.add(new ImportResult.Model(modelNames.get(i),
                        new ModelAsset(documents.get(i), rigs.get(i), states.get(i), List.of(), shared::releaseOne)));
                assigned++;
            }
            return models;
        } catch (RuntimeException | Error construction) {
            if (shared == null) retained.forEach(ResourceBudget.Reservation::close);
            else {
                models.forEach(model -> model.asset().close());
                shared.closeUnassigned(documents.size() - assigned);
            }
            throw construction;
        }
    }

    private record Glb(byte[] json, byte[] binary) {}

    private static Glb glb(byte[] bytes) throws IOException {
        if (bytes.length < 20) throw new IOException("Truncated GLB header");
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(in.getInt() == GLB_MAGIC && in.getInt() == 2, "Invalid GLB magic or version");
        require(Integer.toUnsignedLong(in.getInt()) == bytes.length, "GLB declared length mismatch");
        byte[] json = null, bin = null;
        while (in.remaining() >= 8) {
            int length = in.getInt(), type = in.getInt();
            require(length >= 0 && length <= in.remaining() && length % 4 == 0, "Invalid GLB chunk length");
            byte[] chunk = new byte[length];
            in.get(chunk);
            if (type == JSON_CHUNK) {
                require(json == null && bin == null, "Duplicate or misplaced GLB JSON chunk");
                int end = chunk.length;
                while (end > 0 && chunk[end - 1] == 0x20) end--;
                json = Arrays.copyOf(chunk, end);
            } else if (type == BIN_CHUNK) {
                require(json != null && bin == null, "Duplicate or misplaced GLB BIN chunk");
                bin = chunk;
            } else {
                throw new IOException("Unsupported GLB chunk");
            }
        }
        require(!in.hasRemaining() && json != null, "Missing or truncated GLB JSON chunk");
        return new Glb(json, bin);
    }

    private static void rejectUnsupported(JsonObject root) throws IOException {
        requireSupported(arrayOptional(root.get("extensionsRequired")).isEmpty()
                && arrayOptional(root.get("extensionsUsed")).isEmpty(), "Unsupported glTF extension");
        for (JsonValue mesh : arrayOptional(root.get("meshes"))) {
            for (JsonValue primitive : array(obj(mesh, "mesh").get("primitives"), "primitives")) {
                var p = obj(primitive, "primitive");
                requireSupported(p.get("extensions") == null, "Unsupported primitive extension");
            }
        }
        requireSupported(root.get("extensions") == null, "Unsupported glTF extension");
        requireSupported(root.get("cameras") == null, "glTF cameras are not supported");
        require(root.get("accessors") == null
                || array(root.get("accessors"), "accessors").stream().allMatch(v -> v instanceof JsonObject),
                "Invalid accessors array");
    }

    private static List<byte[]> loadBuffers(JsonObject root, ImportRequest request, BoundedJson.Quota quota,
                                            byte[] embedded, long[] inputBytes) throws IOException {
        List<JsonValue> declared = arrayOptional(root.get("buffers"));
        List<byte[]> result = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < declared.size(); i++) {
            var object = obj(declared.get(i), "buffer");
            int length = integer(object.get("byteLength"), "buffer.byteLength");
            total = Math.addExact(total, length);
            require(total <= quota.policy.maxExpandedBytes(), "Aggregate glTF buffer limit");
            byte[] data;
            JsonValue uri = object.get("uri");
            if (uri == null) {
                require(i == 0 && embedded != null, "Missing embedded GLB buffer");
                data = embedded;
            } else {
                String text = string(uri, "buffer.uri");
                data = text.startsWith("data:") ? decodeDataUri(text, length, quota)
                        : read(request.source().resolver(), resolve(request.source().entry(), text), request, quota, inputBytes);
            }
            require(data.length >= length, "Buffer shorter than declared byteLength");
            if (uri == null) require(data.length - length <= 3, "GLB BIN padding exceeds three bytes");
            result.add(data.length == length ? data : Arrays.copyOf(data, length));
        }
        for (JsonValue viewValue : arrayOptional(root.get("bufferViews"))) {
            var view = obj(viewValue, "bufferView");
            int index = integer(optional(view.get("buffer"), 0), "bufferView.buffer");
            require(index >= 0 && index < result.size(), "bufferView buffer out of range");
        }
        require(embedded == null || declared.stream().anyMatch(v -> v instanceof JsonObject o && o.get("uri") == null),
                "Unused GLB BIN chunk");
        return result;
    }

    private static ResourceKey resolve(ResourceKey base, String uri) throws IOException {
        final java.net.URI parsed;
        try { parsed = new java.net.URI(uri); }
        catch (java.net.URISyntaxException invalid) { throw new IOException("Malformed URI", invalid); }
        require(!parsed.isAbsolute() && parsed.getRawAuthority() == null && parsed.getRawQuery() == null
                && parsed.getRawFragment() == null, "Unsafe URI");
        String decoded = parsed.getPath();
        require(decoded != null && !decoded.startsWith("/") && !decoded.contains(":") && !decoded.contains("\\"), "Unsafe URI");
        for (String segment : decoded.split("/", -1)) {
            require(!segment.equals(".") && !segment.equals("..") && !segment.isEmpty(), "Unsafe URI");
        }
        String parent = base.path().contains("/") ? base.path().substring(0, base.path().lastIndexOf('/') + 1) : "";
        try { return new ResourceKey(parent + decoded); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unsafe URI", invalid); }
    }

    private static byte[] read(ResourceResolver resolver, ResourceKey key, ImportRequest request,
                               BoundedJson.Quota quota, long[] inputBytes) throws IOException {
        try (InputStream in = resolver.open(key); var out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Resolver returned null stream");
            byte[] block = new byte[8192];
            int n;
            while ((n = in.read(block)) != -1) {
                request.cancellation().check();
                inputBytes[0] = Math.addExact(inputBytes[0], n);
                if (inputBytes[0] > request.policy().maxInputBytes()) throw new IOException("aggregate glTF input limit exceeded");
                out.write(block, 0, n);
            }
            byte[] bytes = out.toByteArray();
            quota.charge(bytes.length);
            return bytes;
        }
    }

    private static int[] sequence(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = i;
        return r;
    }

    private static long modelAllocationBytes(int vertices, int indices) {
        long vertexFloats = arrayAllocationBytes(Math.multiplyExact((long) vertices, 3L), 4L);
        long vertexInts = arrayAllocationBytes(vertices, 4L);
        long uvFloats = arrayAllocationBytes(Math.multiplyExact((long) vertices, 2L), 4L);
        long indexInts = arrayAllocationBytes(indices, 4L);
        return Math.addExact(256L, Math.addExact(
                Math.addExact(Math.multiplyExact(4L, vertexFloats), Math.multiplyExact(6L, vertexInts)),
                Math.addExact(Math.addExact(Math.multiplyExact(2L, uvFloats), Math.multiplyExact(2L, indexInts)),
                        Math.multiplyExact(2L, arrayAllocationBytes(3L, 4L)))));
    }

    private static long arrayAllocationBytes(long count, long elementBytes) {
        long payload = Math.multiplyExact(count, elementBytes);
        return Math.addExact(16L, Math.addExact(payload, 7L)) & ~7L;
    }

    private static float[] generatedNormals(float[] positions, int[] indices, BoundedJson.Quota quota) throws IOException {
        int vertices = positions.length / 3;
        quota.charge(arrayAllocationBytes(positions.length, 8L));
        double[] accumulated = new double[positions.length];
        for (int triangle = 0; triangle < indices.length; triangle += 3) {
            int a = indices[triangle] * 3, b = indices[triangle + 1] * 3, c = indices[triangle + 2] * 3;
            double abx = (double) positions[b] - positions[a], aby = (double) positions[b + 1] - positions[a + 1], abz = (double) positions[b + 2] - positions[a + 2];
            double acx = (double) positions[c] - positions[a], acy = (double) positions[c + 1] - positions[a + 1], acz = (double) positions[c + 2] - positions[a + 2];
            double nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
            accumulated[a] += nx; accumulated[a + 1] += ny; accumulated[a + 2] += nz;
            accumulated[b] += nx; accumulated[b + 1] += ny; accumulated[b + 2] += nz;
            accumulated[c] += nx; accumulated[c + 1] += ny; accumulated[c + 2] += nz;
        }
        float[] normals = new float[positions.length];
        for (int vertex = 0; vertex < vertices; vertex++) {
            int i = vertex * 3;
            double length = Math.hypot(Math.hypot(accumulated[i], accumulated[i + 1]), accumulated[i + 2]);
            if (length == 0) { normals[i + 2] = 1; continue; }
            require(Double.isFinite(length), "Unrepresentable generated normal");
            normals[i] = (float) (accumulated[i] / length);
            normals[i + 1] = (float) (accumulated[i + 1] / length);
            normals[i + 2] = (float) (accumulated[i + 2] / length);
        }
        return normals;
    }

    private static float[] floatArray(JsonValue value, int expectedLength, String label) throws IOException {
        var list = numberArray(value, label);
        require(list.size() == expectedLength, label + " must have " + expectedLength + " numbers");
        float[] out = new float[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            float f = (float) number(list.get(i), label);
            require(Float.isFinite(f), "Non-finite " + label);
            out[i] = f;
        }
        return out;
    }

    private static String imageExt(String mime, byte[] data) {
        if ("image/png".equalsIgnoreCase(mime)) return "png";
        if ("image/jpeg".equalsIgnoreCase(mime)) return "jpg";
        if (data.length >= 4 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        if (data.length >= 2 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8) return "jpg";
        return "bin";
    }

    private static byte[] decodeImageDataUri(String uri, BoundedJson.Quota quota) throws IOException {
        int comma = uri.indexOf(',');
        require(comma > 5, "Malformed image data URI");
        String meta = uri.substring(5, comma);
        require(meta.equalsIgnoreCase("image/png;base64") || meta.equalsIgnoreCase("image/jpeg;base64"),
                "Unsupported image data URI media type");
        String payload = uri.substring(comma + 1);
        require(payload.length() <= quota.policy.maxInputBytes() * 2L, "Image data URI exceeds limit");
        try { return Base64.getDecoder().decode(payload); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed base64 image URI", malformed); }
    }

    private static byte[] decodeDataUri(String uri, int expected, BoundedJson.Quota quota) throws IOException {
        int comma = uri.indexOf(',');
        require(comma > 5, "Malformed data URI");
        String metadata = uri.substring(5, comma);
        require(metadata.equalsIgnoreCase("application/octet-stream;base64")
                || metadata.equalsIgnoreCase("application/gltf-buffer;base64"), "Unsupported data URI media type");
        String payload = uri.substring(comma + 1);
        long maximum = ((long) expected + 2) / 3 * 4;
        require(payload.length() <= maximum + 4, "Data URI exceeds declared buffer length");
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(payload); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed base64 data URI", malformed); }
        require(decoded.length == expected, "Data URI length differs from declared buffer length");
        return decoded;
    }

    static JsonObject accessor(List<JsonValue> values, int index) throws IOException {
        require(index >= 0 && index < values.size(), "Accessor index out of range");
        return obj(values.get(index), "accessor");
    }

    private static List<String> strings(JsonValue value) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonValue item : arrayOptional(value)) result.add(string(item, "extension"));
        return result;
    }

    private static List<JsonValue> jsonStrings(List<String> values) {
        List<JsonValue> out = new ArrayList<>();
        for (String value : values) out.add(new JsonString(value));
        return out;
    }

    static JsonValue optional(JsonValue value, int fallback) { return value == null ? new JsonNumber(fallback) : value; }
    static int integer(JsonValue value, String label) throws IOException {
        double d = number(value, label);
        require(d >= 0 && d <= Integer.MAX_VALUE && d == Math.rint(d), "Invalid " + label);
        return (int) d;
    }
    static double number(JsonValue value, String label) throws IOException {
        if (!(value instanceof JsonNumber n)) throw new IOException("Expected number: " + label);
        return n.value();
    }
    static String string(JsonValue value, String label) throws IOException {
        if (!(value instanceof JsonString s)) throw new IOException("Expected string: " + label);
        return s.value();
    }
    static String optionalString(JsonValue value, String fallback) throws IOException {
        return value == null ? fallback : string(value, "name");
    }
    static JsonObject obj(JsonValue value, String label) throws IOException {
        if (!(value instanceof JsonObject o)) throw new IOException("Expected object: " + label);
        return o;
    }
    static List<JsonValue> array(JsonValue value, String label) throws IOException {
        if (!(value instanceof JsonArray a)) throw new IOException("Expected array: " + label);
        return a.values();
    }
    static List<JsonValue> arrayOptional(JsonValue value) throws IOException {
        return value == null ? List.of() : array(value, "array");
    }
    static List<JsonValue> numberArray(JsonValue value, String label) throws IOException {
        if (value instanceof JsonVector3 v) return List.of(new JsonNumber(v.x()), new JsonNumber(v.y()), new JsonNumber(v.z()));
        return array(value, label);
    }

    private static final class SharedRetention {
        private List<ResourceBudget.Reservation> reservations;
        private int remaining;
        SharedRetention(List<ResourceBudget.Reservation> reservations, int owners) {
            this.reservations = List.copyOf(reservations);
            remaining = owners;
        }
        synchronized void releaseOne() {
            if (remaining <= 0) return;
            if (--remaining == 0) release();
        }
        synchronized void closeUnassigned(int count) {
            remaining -= count;
            if (remaining <= 0) release();
        }
        private void release() {
            if (reservations == null) return;
            for (int i = reservations.size() - 1; i >= 0; i--) reservations.get(i).close();
            reservations = null;
        }
    }

    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    static void requireSupported(boolean condition, String message) throws IOException {
        if (!condition) throw new UnsupportedFeatureException(message);
    }

    private static ModelImportException error(ImportRequest request, Exception failure) {
        ImportErrorCode code = failure instanceof java.util.concurrent.CancellationException ? ImportErrorCode.CANCELLED
                : failure instanceof DecodeLimitException limit ? limit.code()
                : failure instanceof IOException io && hasCause(io, ResourceBudgetExceededException.class) ? ImportErrorCode.BUDGET_EXCEEDED
                : failure instanceof UnsupportedFeatureException ? ImportErrorCode.UNSUPPORTED_FEATURE
                : failure instanceof IOException || failure instanceof IllegalArgumentException ? ImportErrorCode.MALFORMED_INPUT
                : ImportErrorCode.INTERNAL;
        return new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR, code,
                request.source().entry(), -1,
                failure.getMessage() == null ? "glTF import failed" : failure.getMessage()), failure);
    }

    private static boolean hasCause(Throwable value, Class<? extends Throwable> type) {
        for (Throwable cursor = value; cursor != null; cursor = cursor.getCause()) if (type.isInstance(cursor)) return true;
        return false;
    }

    private static final class UnsupportedFeatureException extends IOException {
        private static final long serialVersionUID = 1L;
        UnsupportedFeatureException(String message) { super(message); }
    }
}
