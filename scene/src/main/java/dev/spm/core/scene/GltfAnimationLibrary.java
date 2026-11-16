package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.ModelAsset;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.JsonArray;
import dev.spm.core.api.ModelDocument.JsonNumber;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.JsonString;
import dev.spm.core.api.ModelDocument.JsonValue;
import dev.spm.core.api.MorphWeights;
import dev.spm.core.api.SkinState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates glTF 2.0 animation clips stored in {@link ModelDocument#metadata()} under
 * {@code "gltfAnimations"} into immutable {@link BonePose} + {@link SkinState} frames.
 */
public final class GltfAnimationLibrary {
    public record EvaluatedFrame(BonePose pose, SkinState skinState) {
        public EvaluatedFrame {
            Objects.requireNonNull(pose);
            Objects.requireNonNull(skinState);
        }
    }

    private enum Path { TRANSLATION, ROTATION, SCALE, WEIGHTS }
    private enum Interpolation { LINEAR, STEP, CUBICSPLINE }

    private record Channel(int boneOrNode, Path path, Interpolation interpolation,
                           float[] times, float[] values, int stride) {}
    private record Clip(String name, double duration, List<Channel> channels) {}
    private record NodeMeshBinding(String meshName, int targetCount) {}

    private final Map<String, Clip> clips;
    private final Map<Integer, List<NodeMeshBinding>> nodeMeshes;

    private GltfAnimationLibrary(Map<String, Clip> clips, Map<Integer, List<NodeMeshBinding>> nodeMeshes) {
        this.clips = Collections.unmodifiableMap(clips);
        this.nodeMeshes = Collections.unmodifiableMap(nodeMeshes);
    }

    /** Extracts a glTF animation library from a {@link ModelDocument}. */
    public static GltfAnimationLibrary compile(ModelDocument document) {
        Objects.requireNonNull(document);
        var meta = document.metadata().values();
        var nodeMeshMap = parseNodeMeshes(meta.get("gltfNodeMeshes"));
        JsonValue rawAnims = meta.get("gltfAnimations");
        if (rawAnims == null) return new GltfAnimationLibrary(Map.of(), nodeMeshMap);
        if (!(rawAnims instanceof JsonArray animArray)) {
            throw new IllegalArgumentException("gltfAnimations metadata must be a JSON array");
        }
        int boneCount = document.skeleton().boneCount();
        var compiled = new LinkedHashMap<String, Clip>();
        for (int i = 0; i < animArray.values().size(); i++) {
            var clipObj = requireObject(animArray.values().get(i), "animation");
            String name = stringField(clipObj, "name", "animation-" + i);
            double duration = numberField(clipObj, "duration", 0.0);
            if (!Double.isFinite(duration) || duration < 0.0) {
                throw new IllegalArgumentException("Invalid glTF animation duration for " + name);
            }
            var channelsVal = clipObj.values().get("channels");
            var channels = new ArrayList<Channel>();
            if (channelsVal instanceof JsonArray chArr) {
                for (JsonValue chElem : chArr.values()) {
                    var chObj = requireObject(chElem, "channel");
                    int nodeIdx = (int) numberField(chObj, "node", -1);
                    if (nodeIdx < 0) throw new IllegalArgumentException("Invalid glTF animation node index");
                    Path path = switch (stringField(chObj, "path", "")) {
                        case "translation" -> Path.TRANSLATION;
                        case "rotation" -> Path.ROTATION;
                        case "scale" -> Path.SCALE;
                        case "weights" -> Path.WEIGHTS;
                        default -> throw new IllegalArgumentException("Unknown glTF animation path");
                    };
                    if (path != Path.WEIGHTS && nodeIdx >= boneCount) {
                        throw new IllegalArgumentException("glTF animation bone index out of range: " + nodeIdx);
                    }
                    Interpolation interp = switch (stringField(chObj, "interpolation", "LINEAR")) {
                        case "LINEAR" -> Interpolation.LINEAR;
                        case "STEP" -> Interpolation.STEP;
                        case "CUBICSPLINE" -> Interpolation.CUBICSPLINE;
                        default -> throw new IllegalArgumentException("Unknown glTF interpolation");
                    };
                    float[] times = floatArrayField(chObj, "times");
                    float[] values = floatArrayField(chObj, "values");
                    for (int t = 1; t < times.length; t++) {
                        if (times[t] < times[t - 1]) {
                            throw new IllegalArgumentException("Non-monotonic glTF keyframe timestamps");
                        }
                    }
                    int keyMult = interp == Interpolation.CUBICSPLINE ? 3 : 1;
                    int stride = switch (path) {
                        case TRANSLATION, SCALE -> 3;
                        case ROTATION -> 4;
                        case WEIGHTS -> times.length == 0 ? 0 : values.length / Math.multiplyExact(times.length, keyMult);
                    };
                    if (times.length > 0 && stride <= 0) {
                        throw new IllegalArgumentException("Invalid glTF animation channel stride");
                    }
                    if (values.length != Math.multiplyExact(Math.multiplyExact(times.length, keyMult), stride)) {
                        throw new IllegalArgumentException("glTF animation channel values length mismatch");
                    }
                    if (times.length > 0) {
                        duration = Math.max(duration, times[times.length - 1]);
                    }
                    channels.add(new Channel(nodeIdx, path, interp, times, values, stride));
                }
            }
            compiled.put(name, new Clip(name, duration, List.copyOf(channels)));
        }
        return new GltfAnimationLibrary(compiled, nodeMeshMap);
    }

    public boolean hasClips() { return !clips.isEmpty(); }

    public List<String> clipNames() { return List.copyOf(clips.keySet()); }

    public double durationSeconds(String clipName) {
        Clip clip = clips.get(Objects.requireNonNull(clipName));
        if (clip == null) throw new IllegalArgumentException("Unknown glTF clip: " + clipName);
        return clip.duration();
    }

    public EvaluatedFrame evaluate(ModelAsset asset, String clipName, double timeSeconds, boolean looping,
                                   ScriptBudget budget) {
        Objects.requireNonNull(asset);
        return evaluate(asset.document(), asset.defaultSkinState(), clipName, timeSeconds, looping, budget);
    }

    public EvaluatedFrame evaluate(ModelDocument document, SkinState baseSkinState, String clipName,
                                   double timeSeconds, boolean looping, ScriptBudget budget) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(baseSkinState);
        Objects.requireNonNull(budget);
        if (!Double.isFinite(timeSeconds)) throw new IllegalArgumentException("Non-finite glTF animation time");
        Clip clip = clips.get(Objects.requireNonNull(clipName));
        if (clip == null) throw new IllegalArgumentException("Unknown glTF clip: " + clipName);
        double t = timeSeconds;
        if (clip.duration() > 0.0) {
            if (looping) {
                t = ((t % clip.duration()) + clip.duration()) % clip.duration();
            } else {
                t = Math.max(0.0, Math.min(clip.duration(), t));
            }
        } else {
            t = 0.0;
        }
        BonePose bind = document.bindPose();
        float[] rotations = bind.rotations();
        float[] translations = bind.translations();
        float[] scales = bind.scales();
        var morphMap = seedMorphMap(nodeMeshes, baseSkinState);

        for (Channel ch : clip.channels()) {
            budget.step();
            if (ch.times().length == 0) continue;
            switch (ch.path()) {
                case TRANSLATION -> {
                    float[] v = sampleChannel(ch, t, false, budget);
                    int base = ch.boneOrNode() * 3;
                    System.arraycopy(v, 0, translations, base, 3);
                }
                case SCALE -> {
                    float[] v = sampleChannel(ch, t, false, budget);
                    int base = ch.boneOrNode() * 3;
                    System.arraycopy(v, 0, scales, base, 3);
                }
                case ROTATION -> {
                    float[] q = sampleChannel(ch, t, true, budget);
                    float[] euler = quaternionToEulerZyx(q[0], q[1], q[2], q[3]);
                    int base = ch.boneOrNode() * 3;
                    System.arraycopy(euler, 0, rotations, base, 3);
                }
                case WEIGHTS -> {
                    float[] w = sampleChannel(ch, t, false, budget);
                    for (int i = 0; i < w.length; i++) {
                        w[i] = Math.max(-MorphWeights.MAX_MAGNITUDE, Math.min(MorphWeights.MAX_MAGNITUDE, w[i]));
                    }
                    List<NodeMeshBinding> bound = nodeMeshes.getOrDefault(ch.boneOrNode(), List.of());
                    for (NodeMeshBinding nm : bound) {
                        if (nm.targetCount() == w.length) {
                            morphMap.put(nm.meshName(), new MorphWeights(w));
                        }
                    }
                }
            }
        }
        BonePose pose = new BonePose(rotations, translations, scales, bind.ownHidden(), bind.childrenHidden(),
                bind.glow(), bind.colorsRgba());
        SkinState skinState = morphMap.isEmpty() ? SkinState.rest() : SkinState.of(morphMap);
        return new EvaluatedFrame(pose, skinState);
    }

    private static Map<String, MorphWeights> seedMorphMap(Map<Integer, List<NodeMeshBinding>> nodeMeshes,
                                                          SkinState baseSkinState) {
        var map = new LinkedHashMap<String, MorphWeights>();
        for (List<NodeMeshBinding> list : nodeMeshes.values()) {
            for (NodeMeshBinding nm : list) {
                if (nm.targetCount() > 0) {
                    map.put(nm.meshName(), baseSkinState.morph(nm.meshName(), nm.targetCount()));
                }
            }
        }
        return map;
    }

    private static float[] sampleChannel(Channel ch, double time, boolean isQuaternion, ScriptBudget budget) {
        float[] times = ch.times();
        float[] vals = ch.values();
        int stride = ch.stride();
        int n = times.length;
        int mult = ch.interpolation() == Interpolation.CUBICSPLINE ? 3 : 1;
        if (time <= times[0] || n == 1) {
            return extractKey(vals, 0, stride, mult, 1, isQuaternion);
        }
        int last = n - 1;
        if (time >= times[last]) {
            return extractKey(vals, last, stride, mult, 1, isQuaternion);
        }
        int low = 0, high = n;
        while (low < high) {
            budget.step();
            int mid = (low + high) >>> 1;
            if (times[mid] <= time) low = mid + 1;
            else high = mid;
        }
        int left = Math.max(0, low - 1);
        int right = Math.min(last, left + 1);
        if (left == right || ch.interpolation() == Interpolation.STEP || times[right] == times[left]) {
            return extractKey(vals, left, stride, mult, 1, isQuaternion);
        }
        float dt = times[right] - times[left];
        float u = (float) ((time - times[left]) / dt);
        if (ch.interpolation() == Interpolation.CUBICSPLINE) {
            float u2 = u * u, u3 = u2 * u;
            float h00 = 2f * u3 - 3f * u2 + 1f;
            float h10 = u3 - 2f * u2 + u;
            float h01 = -2f * u3 + 3f * u2;
            float h11 = u3 - u2;
            int leftBase = left * 3 * stride;
            int rightBase = right * 3 * stride;
            float[] out = new float[stride];
            for (int c = 0; c < stride; c++) {
                float p0 = vals[leftBase + stride + c];
                float b0 = vals[leftBase + 2 * stride + c];
                float a1 = vals[rightBase + c];
                float p1 = vals[rightBase + stride + c];
                out[c] = h00 * p0 + h10 * dt * b0 + h01 * p1 + h11 * dt * a1;
            }
            return isQuaternion ? normalizeQuat(out) : out;
        }
        // LINEAR
        float[] a = extractKey(vals, left, stride, 1, 0, false);
        float[] b = extractKey(vals, right, stride, 1, 0, false);
        if (isQuaternion) {
            return slerpQuat(a, b, u);
        }
        float[] out = new float[stride];
        for (int c = 0; c < stride; c++) {
            out[c] = a[c] + (b[c] - a[c]) * u;
        }
        return out;
    }

    private static float[] extractKey(float[] vals, int keyIndex, int stride, int mult, int cubicSubIndex,
                                      boolean normalize) {
        int sub = mult == 3 ? cubicSubIndex : 0;
        int offset = (keyIndex * mult + sub) * stride;
        float[] out = new float[stride];
        System.arraycopy(vals, offset, out, 0, stride);
        return normalize ? normalizeQuat(out) : out;
    }

    private static float[] normalizeQuat(float[] q) {
        double len = Math.sqrt((double) q[0] * q[0] + (double) q[1] * q[1] + (double) q[2] * q[2] + (double) q[3] * q[3]);
        if (!(len > 0.0) || !Double.isFinite(len)) return new float[] {0f, 0f, 0f, 1f};
        return new float[] {(float) (q[0] / len), (float) (q[1] / len), (float) (q[2] / len), (float) (q[3] / len)};
    }

    private static float[] slerpQuat(float[] rawA, float[] rawB, float u) {
        float[] a = normalizeQuat(rawA);
        float[] b = normalizeQuat(rawB);
        double dot = (double) a[0] * b[0] + (double) a[1] * b[1] + (double) a[2] * b[2] + (double) a[3] * b[3];
        if (dot < 0.0) {
            dot = -dot;
            b = new float[] {-b[0], -b[1], -b[2], -b[3]};
        }
        if (dot > 0.9995) {
            return normalizeQuat(new float[] {
                    a[0] + (b[0] - a[0]) * u,
                    a[1] + (b[1] - a[1]) * u,
                    a[2] + (b[2] - a[2]) * u,
                    a[3] + (b[3] - a[3]) * u
            });
        }
        double theta0 = Math.acos(Math.min(1.0, dot));
        double theta = theta0 * u;
        double sin0 = Math.sin(theta0);
        double s1 = Math.sin(theta) / sin0;
        double s0 = Math.cos(theta) - dot * s1;
        return normalizeQuat(new float[] {
                (float) (s0 * a[0] + s1 * b[0]),
                (float) (s0 * a[1] + s1 * b[1]),
                (float) (s0 * a[2] + s1 * b[2]),
                (float) (s0 * a[3] + s1 * b[3])
        });
    }

    private static float[] quaternionToEulerZyx(double x, double y, double z, double w) {
        double m00 = 1 - 2 * (y * y + z * z);
        double m10 = 2 * (x * y + z * w);
        double m20 = 2 * (x * z - y * w);
        double m01 = 2 * (x * y - z * w);
        double m11 = 1 - 2 * (x * x + z * z);
        double m21 = 2 * (y * z + x * w);
        double m22 = 1 - 2 * (x * x + y * y);
        double sy = Math.max(-1.0, Math.min(1.0, -m20));
        double ry = Math.asin(sy);
        double rx, rz;
        if (Math.abs(sy) < 1.0 - 1e-6) {
            rx = Math.atan2(m21, m22);
            rz = Math.atan2(m10, m00);
        } else {
            rz = 0.0;
            rx = sy > 0 ? Math.atan2(m01, m11) : Math.atan2(-m01, m11);
        }
        return new float[] {(float) rx, (float) ry, (float) rz};
    }

    private static Map<Integer, List<NodeMeshBinding>> parseNodeMeshes(JsonValue raw) {
        var map = new LinkedHashMap<Integer, List<NodeMeshBinding>>();
        if (!(raw instanceof JsonArray arr)) return map;
        for (JsonValue elem : arr.values()) {
            var obj = requireObject(elem, "gltfNodeMesh");
            String mesh = stringField(obj, "mesh", "");
            int nodeIdx = (int) numberField(obj, "nodeIndex", -1);
            int targetCount = (int) numberField(obj, "targetCount", 0);
            if (!mesh.isBlank() && nodeIdx >= 0) {
                map.computeIfAbsent(nodeIdx, ignored -> new ArrayList<>())
                        .add(new NodeMeshBinding(mesh, targetCount));
            }
        }
        return map;
    }

    private static JsonObject requireObject(JsonValue v, String label) {
        if (!(v instanceof JsonObject o)) throw new IllegalArgumentException("Expected JSON object for " + label);
        return o;
    }

    private static String stringField(JsonObject obj, String key, String fallback) {
        JsonValue v = obj.values().get(key);
        if (v == null) return fallback;
        if (!(v instanceof JsonString s)) throw new IllegalArgumentException("Expected string for " + key);
        return s.value();
    }

    private static double numberField(JsonObject obj, String key, double fallback) {
        JsonValue v = obj.values().get(key);
        if (v == null) return fallback;
        if (!(v instanceof JsonNumber n)) throw new IllegalArgumentException("Expected number for " + key);
        return n.value();
    }

    private static float[] floatArrayField(JsonObject obj, String key) {
        JsonValue v = obj.values().get(key);
        if (!(v instanceof JsonArray arr)) throw new IllegalArgumentException("Expected array for " + key);
        float[] out = new float[arr.values().size()];
        for (int i = 0; i < out.length; i++) {
            if (!(arr.values().get(i) instanceof JsonNumber n)) {
                throw new IllegalArgumentException("Expected numeric array element in " + key);
            }
            float f = (float) n.value();
            if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite float in " + key);
            out[i] = f;
        }
        return out;
    }
}
