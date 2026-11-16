package dev.spm.core.format.figura;

import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelDocument.JsonArray;
import dev.spm.core.api.ModelDocument.JsonNumber;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.JsonString;
import dev.spm.core.api.ModelDocument.JsonValue;
import dev.spm.core.scene.MolangContext;
import dev.spm.core.scene.MolangProgram;
import dev.spm.core.scene.ScriptBudget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses and plays Blockbench / Figura animation clips attached to a Figura model. */
final class FiguraAnimationSupport {
    enum LoopMode { ONCE, LOOP, HOLD }
    enum Interp { LINEAR, STEP, CATMULL_ROM }
    enum Channel { ROTATION, POSITION, SCALE }

    private record ScalarExpr(double constant, MolangProgram program) {
        double eval(double animTime, ScriptBudget budget) {
            if (program == null) return constant;
            var ctx = new MolangContext(Map.of("anim_time", animTime), Map.of(), Map.of(), budget);
            return program.evaluate(ctx);
        }
    }

    private record Keyframe(double time, Interp interp, ScalarExpr x, ScalarExpr y, ScalarExpr z) {}
    private record BoneTracks(int boneIndex, List<Keyframe> rot, List<Keyframe> pos, List<Keyframe> scale) {}

    static final class ClipInstance {
        final String name;
        final double length;
        final List<BoneTracks> tracks;
        LoopMode loopMode;
        double time;
        double speed = 1.0;
        double blend = 1.0;
        int priority;
        boolean playing;
        boolean paused;
        boolean override;

        ClipInstance(String name, double length, LoopMode loopMode, List<BoneTracks> tracks) {
            this.name = name;
            this.length = length;
            this.loopMode = loopMode;
            this.tracks = tracks;
        }

        void play() { playing = true; paused = false; }
        void stop() { playing = false; paused = false; time = 0.0; }
        void pause() { paused = true; }
        void restart() { playing = true; paused = false; time = 0.0; }

        void advance(double dt) {
            if (!playing || paused) return;
            time += dt * speed;
            if (length <= 0.0) {
                if (loopMode == LoopMode.ONCE) { playing = false; time = 0.0; }
                return;
            }
            if (time >= length || time < 0.0) {
                switch (loopMode) {
                    case LOOP -> time = ((time % length) + length) % length;
                    case HOLD -> time = Math.max(0.0, Math.min(length, time));
                    case ONCE -> { playing = false; time = 0.0; }
                }
            }
        }
    }

    private FiguraAnimationSupport() {}

    static Map<String, ClipInstance> compile(ModelDocument document) {
        var boneIndexByName = new HashMap<String, Integer>();
        var names = document.skeleton().names();
        for (int i = 0; i < names.size(); i++) {
            boneIndexByName.put(names.get(i), i);
        }
        var result = new LinkedHashMap<String, ClipInstance>();

        JsonValue bbAnims = document.metadata().values().get("animations");
        if (bbAnims instanceof JsonArray arr) {
            for (JsonValue elem : arr.values()) {
                if (!(elem instanceof JsonObject animObj)) continue;
                String name = str(animObj.values().get("name"), "animation_" + result.size());
                double length = num(animObj.values().get("length"), 0.0);
                LoopMode loop = parseLoop(str(animObj.values().get("loop"), "once"));
                boolean override = animObj.values().get("override") instanceof ModelDocument.JsonBoolean b && b.value();
                var tracks = parseBbAnimators(animObj.values().get("animators"), boneIndexByName);
                double maxKey = length;
                for (BoneTracks bt : tracks) {
                    maxKey = Math.max(maxKey, Math.max(maxTime(bt.rot()), Math.max(maxTime(bt.pos()), maxTime(bt.scale()))));
                }
                var clip = new ClipInstance(name, maxKey, loop, tracks);
                clip.override = override;
                result.put(name, clip);
            }
        }

        JsonValue nbtAnims = document.metadata().values().get("figuraNbtAnimations");
        if (nbtAnims instanceof JsonArray arr) {
            for (JsonValue elem : arr.values()) {
                if (!(elem instanceof JsonObject animObj)) continue;
                String name = str(animObj.values().get("name"), "anim_" + result.size());
                double length = num(animObj.values().get("len"), 0.0);
                LoopMode loop = parseLoop(str(animObj.values().get("loop"), "once"));
                var tracks = parseNbtCodeTracks(animObj.values().get("code"), boneIndexByName);
                var clip = new ClipInstance(name, length, loop, tracks);
                clip.override = animObj.values().get("ovr") instanceof JsonNumber n && n.value() != 0;
                result.putIfAbsent(name, clip);
            }
        }
        return result;
    }

    static void evaluateAll(java.util.Collection<ClipInstance> clips, float[] rotations, float[] translations,
                            float[] scales, ScriptBudget budget) {
        var active = new ArrayList<ClipInstance>();
        for (ClipInstance clip : clips) {
            if (clip.playing || (clip.loopMode == LoopMode.HOLD && clip.time > 0.0)) {
                active.add(clip);
            }
        }
        active.sort(Comparator.comparingInt(c -> c.priority));
        for (ClipInstance clip : active) {
            double t = clip.time;
            float weight = (float) Math.max(0.0, clip.blend);
            for (BoneTracks bt : clip.tracks) {
                budget.step();
                int b3 = bt.boneIndex() * 3;
                if (!bt.rot().isEmpty()) {
                    double[] deg = sampleTrack(bt.rot(), t, 0.0, budget);
                    for (int a = 0; a < 3; a++) {
                        float delta = (float) Math.toRadians(deg[a]) * weight;
                        rotations[b3 + a] = clip.override ? delta : rotations[b3 + a] + delta;
                    }
                }
                if (!bt.pos().isEmpty()) {
                    double[] px = sampleTrack(bt.pos(), t, 0.0, budget);
                    for (int a = 0; a < 3; a++) {
                        float delta = (float) (px[a] / 16.0) * weight;
                        translations[b3 + a] = clip.override ? delta : translations[b3 + a] + delta;
                    }
                }
                if (!bt.scale().isEmpty()) {
                    double[] sc = sampleTrack(bt.scale(), t, 1.0, budget);
                    for (int a = 0; a < 3; a++) {
                        double blended = 1.0 + (sc[a] - 1.0) * weight;
                        scales[b3 + a] = clip.override ? (float) blended : (float) (scales[b3 + a] * blended);
                    }
                }
            }
        }
    }

    private static List<BoneTracks> parseBbAnimators(JsonValue raw, Map<String, Integer> boneIndexByName) {
        if (!(raw instanceof JsonObject obj)) return List.of();
        var list = new ArrayList<BoneTracks>();
        for (JsonValue val : obj.values().values()) {
            if (!(val instanceof JsonObject anim)) continue;
            String boneName = str(anim.values().get("name"), "");
            Integer boneIdx = boneIndexByName.get(boneName);
            if (boneIdx == null) continue;
            var rot = new ArrayList<Keyframe>();
            var pos = new ArrayList<Keyframe>();
            var scale = new ArrayList<Keyframe>();
            if (anim.values().get("keyframes") instanceof JsonArray kfArr) {
                for (JsonValue kfVal : kfArr.values()) {
                    if (!(kfVal instanceof JsonObject kf)) continue;
                    String chStr = str(kf.values().get("channel"), "");
                    double time = num(kf.values().get("time"), 0.0);
                    Interp interp = switch (str(kf.values().get("interpolation"), "linear").toLowerCase(Locale.ROOT)) {
                        case "step" -> Interp.STEP;
                        case "catmullrom" -> Interp.CATMULL_ROM;
                        default -> Interp.LINEAR;
                    };
                    double def = chStr.equals("scale") ? 1.0 : 0.0;
                    ScalarExpr x = new ScalarExpr(def, null), y = new ScalarExpr(def, null), z = new ScalarExpr(def, null);
                    if (kf.values().get("data_points") instanceof JsonArray pts && !pts.values().isEmpty()
                            && pts.values().get(0) instanceof JsonObject pt) {
                        x = parseScalar(pt.values().get("x"), def);
                        y = parseScalar(pt.values().get("y"), def);
                        z = parseScalar(pt.values().get("z"), def);
                    }
                    var parsed = new Keyframe(time, interp, x, y, z);
                    switch (chStr) {
                        case "rotation" -> rot.add(parsed);
                        case "position" -> pos.add(parsed);
                        case "scale" -> scale.add(parsed);
                        default -> {}
                    }
                }
            }
            rot.sort(Comparator.comparingDouble(Keyframe::time));
            pos.sort(Comparator.comparingDouble(Keyframe::time));
            scale.sort(Comparator.comparingDouble(Keyframe::time));
            if (!rot.isEmpty() || !pos.isEmpty() || !scale.isEmpty()) {
                list.add(new BoneTracks(boneIdx, List.copyOf(rot), List.copyOf(pos), List.copyOf(scale)));
            }
        }
        return list;
    }

    private static List<BoneTracks> parseNbtCodeTracks(JsonValue raw, Map<String, Integer> boneIndexByName) {
        if (!(raw instanceof JsonObject obj)) return List.of();
        var list = new ArrayList<BoneTracks>();
        for (var e : obj.values().entrySet()) {
            Integer boneIdx = boneIndexByName.get(e.getKey());
            if (boneIdx == null || !(e.getValue() instanceof JsonObject channels)) continue;
            var rot = parseNbtKeyList(channels.values().get("rot"), 0.0);
            var pos = parseNbtKeyList(channels.values().get("pos"), 0.0);
            var scale = parseNbtKeyList(channels.values().get("scl"), 1.0);
            if (!rot.isEmpty() || !pos.isEmpty() || !scale.isEmpty()) {
                list.add(new BoneTracks(boneIdx, rot, pos, scale));
            }
        }
        return list;
    }

    private static List<Keyframe> parseNbtKeyList(JsonValue raw, double def) {
        if (!(raw instanceof JsonArray arr)) return List.of();
        var out = new ArrayList<Keyframe>();
        for (JsonValue val : arr.values()) {
            if (!(val instanceof JsonObject kf)) continue;
            double t = num(kf.values().get("time"), 0.0);
            Interp interp = switch (str(kf.values().get("int"), "linear").toLowerCase(Locale.ROOT)) {
                case "step" -> Interp.STEP;
                case "catmullrom" -> Interp.CATMULL_ROM;
                default -> Interp.LINEAR;
            };
            if (kf.values().get("end") instanceof JsonArray v && v.values().size() >= 3) {
                out.add(new Keyframe(t, interp,
                        parseScalar(v.values().get(0), def),
                        parseScalar(v.values().get(1), def),
                        parseScalar(v.values().get(2), def)));
            }
        }
        out.sort(Comparator.comparingDouble(Keyframe::time));
        return List.copyOf(out);
    }

    private static double[] sampleTrack(List<Keyframe> keys, double time, double animTimeDef, ScriptBudget budget) {
        if (keys.isEmpty()) return new double[] {animTimeDef, animTimeDef, animTimeDef};
        Keyframe first = keys.get(0);
        if (time <= first.time() || keys.size() == 1) {
            return evalKey(first, time, budget);
        }
        Keyframe last = keys.get(keys.size() - 1);
        if (time >= last.time()) {
            return evalKey(last, time, budget);
        }
        int leftIdx = 0;
        for (int i = 0; i < keys.size() - 1; i++) {
            if (keys.get(i + 1).time() >= time) {
                leftIdx = i;
                break;
            }
        }
        Keyframe k1 = keys.get(leftIdx);
        Keyframe k2 = keys.get(leftIdx + 1);
        double[] v1 = evalKey(k1, time, budget);
        if (k1.interp() == Interp.STEP || k2.time() == k1.time()) return v1;
        double[] v2 = evalKey(k2, time, budget);
        double u = (time - k1.time()) / (k2.time() - k1.time());
        if (k1.interp() == Interp.CATMULL_ROM || k2.interp() == Interp.CATMULL_ROM) {
            double[] v0 = leftIdx > 0 ? evalKey(keys.get(leftIdx - 1), time, budget) : v1;
            double[] v3 = leftIdx + 2 < keys.size() ? evalKey(keys.get(leftIdx + 2), time, budget) : v2;
            double u2 = u * u, u3 = u2 * u;
            return new double[] {
                    catmull(v0[0], v1[0], v2[0], v3[0], u, u2, u3),
                    catmull(v0[1], v1[1], v2[1], v3[1], u, u2, u3),
                    catmull(v0[2], v1[2], v2[2], v3[2], u, u2, u3)
            };
        }
        return new double[] {
                v1[0] + (v2[0] - v1[0]) * u,
                v1[1] + (v2[1] - v1[1]) * u,
                v1[2] + (v2[2] - v1[2]) * u
        };
    }

    private static double catmull(double p0, double p1, double p2, double p3, double u, double u2, double u3) {
        return 0.5 * ((2.0 * p1) + (-p0 + p2) * u
                + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * u2
                + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * u3);
    }

    private static double[] evalKey(Keyframe k, double t, ScriptBudget budget) {
        return new double[] {k.x().eval(t, budget), k.y().eval(t, budget), k.z().eval(t, budget)};
    }

    private static ScalarExpr parseScalar(JsonValue val, double def) {
        if (val == null) return new ScalarExpr(def, null);
        if (val instanceof JsonNumber n) return new ScalarExpr(n.value(), null);
        if (val instanceof JsonString s) {
            String trimmed = s.value().trim();
            if (trimmed.isEmpty()) return new ScalarExpr(def, null);
            try {
                return new ScalarExpr(Double.parseDouble(trimmed), null);
            } catch (NumberFormatException ignored) {
                return new ScalarExpr(def, MolangProgram.compile(trimmed));
            }
        }
        return new ScalarExpr(def, null);
    }

    static LoopMode parseLoop(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "loop" -> LoopMode.LOOP;
            case "hold" -> LoopMode.HOLD;
            default -> LoopMode.ONCE;
        };
    }

    private static double maxTime(List<Keyframe> keys) {
        return keys.isEmpty() ? 0.0 : keys.get(keys.size() - 1).time();
    }

    private static String str(JsonValue v, String def) {
        return v instanceof JsonString s ? s.value() : def;
    }

    private static double num(JsonValue v, double def) {
        return v instanceof JsonNumber n ? n.value() : def;
    }
}
