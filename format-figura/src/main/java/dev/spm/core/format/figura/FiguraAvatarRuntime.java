package dev.spm.core.format.figura;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.JsonArray;
import dev.spm.core.api.ModelDocument.JsonObject;
import dev.spm.core.api.ModelDocument.JsonString;
import dev.spm.core.api.ModelDocument.JsonValue;
import dev.spm.core.format.figura.FiguraAnimationSupport.ClipInstance;
import dev.spm.core.format.figura.FiguraVectorApi.Vec;
import dev.spm.core.lua.LuaBudget;
import dev.spm.core.lua.LuaFunction;
import dev.spm.core.lua.LuaTable;
import dev.spm.core.lua.LuaValue;
import dev.spm.core.lua.LuaValues;
import dev.spm.core.lua.LuaVm;
import dev.spm.core.scene.ScriptBudget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Stateful, bounded Pure-Java Figura avatar Lua &amp; animation runtime bound to an imported Figura avatar.
 * Produces immutable per-frame {@link BonePose}s, vanilla part overrides, and queued host effects.
 */
public final class FiguraAvatarRuntime {
    public record HostSnapshot(
            double x, double y, double z,
            double velX, double velY, double velZ,
            float yaw, float pitch, float bodyYaw,
            float health, float maxHealth,
            boolean crouching, boolean sprinting, boolean swimming, boolean flying,
            boolean onGround, boolean swingingArm,
            String playerName, String playerUuid, boolean isHost,
            double worldTime, float rainGradient, float fps,
            String mainHandItem, String offHandItem) {

        public static HostSnapshot idle(String playerName) {
            return new HostSnapshot(
                    0, 0, 0, 0, 0, 0,
                    0f, 0f, 0f,
                    20f, 20f,
                    false, false, false, false, true, false,
                    Objects.requireNonNull(playerName), "00000000-0000-0000-0000-000000000000", true,
                    0.0, 0f, 60f, "minecraft:air", "minecraft:air");
        }
    }

    public record VanillaPartState(boolean visible, float[] rotDeg, float[] posPixels, float[] scale) {
        public VanillaPartState {
            rotDeg = rotDeg.clone();
            posPixels = posPixels.clone();
            scale = scale.clone();
        }
    }

    public record EffectCommand(String kind, Map<String, Object> arguments) {
        public EffectCommand {
            Objects.requireNonNull(kind);
            arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }
    }

    public record FiguraFrame(
            Map<String, BonePose> modelPoses,
            Map<String, VanillaPartState> vanillaOverrides,
            List<EffectCommand> effects,
            List<String> logs) {}

    private static final class MutableBoneOverride {
        final float[] rotDeg = new float[3];
        final float[] posPx = new float[3];
        final float[] scale = {1f, 1f, 1f};
        boolean hasRot;
        boolean hasPos;
        boolean hasScale;
        Boolean visible;
        int colorRgba = 0xFFFFFFFF;
        String renderType;
        String parentType;
    }

    private static final class ModelState {
        final String modelKey;
        final String shortName;
        final ModelDocument document;
        final MutableBoneOverride[] boneOverrides;
        final Map<String, ClipInstance> animations;

        ModelState(String modelKey, String shortName, ModelDocument document) {
            this.modelKey = modelKey;
            this.shortName = shortName;
            this.document = document;
            int n = document.skeleton().boneCount();
            this.boneOverrides = new MutableBoneOverride[n];
            for (int i = 0; i < n; i++) this.boneOverrides[i] = new MutableBoneOverride();
            this.animations = FiguraAnimationSupport.compile(document);
        }
    }

    private static final class MutableVanillaPart {
        boolean visible = true;
        final float[] rotDeg = new float[3];
        final float[] posPx = new float[3];
        final float[] scale = {1f, 1f, 1f};

        VanillaPartState snapshot() {
            return new VanillaPartState(visible, rotDeg, posPx, scale);
        }
    }

    private static final List<String> VANILLA_PART_NAMES = List.of(
            "ALL", "PLAYER", "HEAD", "BODY", "LEFT_ARM", "RIGHT_ARM", "LEFT_LEG", "RIGHT_LEG",
            "HAT", "JACKET", "LEFT_SLEEVE", "RIGHT_SLEEVE", "LEFT_PANTS", "RIGHT_PANTS",
            "CAPE", "ELYTRA", "ARMOR", "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");

    private final LuaBudget budget;
    private final ScriptBudget animBudget;
    private final LuaVm vm;
    private final Map<String, String> scripts;
    private final List<String> autoScripts;
    private final List<ModelState> models;
    private final Map<String, MutableVanillaPart> vanillaParts = new LinkedHashMap<>();
    private final Map<String, List<LuaFunction>> events = new LinkedHashMap<>();
    private final Map<String, LuaFunction> pingHandlers = new LinkedHashMap<>();
    private final List<EffectCommand> pendingEffects = new ArrayList<>();
    private final List<String> pendingLogs = new ArrayList<>();
    private HostSnapshot currentSnapshot;
    private boolean initialized;

    private FiguraAvatarRuntime(
            LuaBudget budget,
            ScriptBudget animBudget,
            Map<String, String> scripts,
            List<String> autoScripts,
            List<ModelState> models,
            HostSnapshot initialSnapshot) {
        this.budget = Objects.requireNonNull(budget);
        this.animBudget = Objects.requireNonNull(animBudget);
        this.scripts = Map.copyOf(scripts);
        this.autoScripts = List.copyOf(autoScripts);
        this.models = List.copyOf(models);
        this.currentSnapshot = Objects.requireNonNull(initialSnapshot);
        this.vm = new LuaVm(budget, this::resolveModule, pendingLogs::add);
        installBindings();
    }

    public static FiguraAvatarRuntime create(ImportResult imported, LuaBudget budget, HostSnapshot initialSnapshot)
            throws IOException {
        Objects.requireNonNull(imported);
        Objects.requireNonNull(budget);
        Objects.requireNonNull(initialSnapshot);

        var scripts = new LinkedHashMap<String, String>();
        var modelStates = new ArrayList<ModelState>();
        List<String> explicitAuto = null;

        if (imported.resources() instanceof ResourceCatalog catalog) {
            var sorted = new ArrayList<>(catalog.entries());
            sorted.sort(Comparator.comparing(ResourceKey::path));
            for (ResourceKey key : sorted) {
                if (key.path().toLowerCase(Locale.ROOT).endsWith(".lua")) {
                    try (InputStream in = catalog.open(key)) {
                        String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        scripts.put(normalizeScriptKey(key.path()), src);
                    }
                }
            }
        }

        for (ImportResult.Model m : imported.models()) {
            ModelDocument doc = m.asset().document();
            String shortName = stripExtension(m.key());
            modelStates.add(new ModelState(m.key(), shortName, doc));
            if (explicitAuto == null && doc.metadata().values().get("figuraAvatar") instanceof JsonObject av) {
                if (av.values().get("autoScripts") instanceof JsonArray arr) {
                    explicitAuto = new ArrayList<>();
                    for (JsonValue v : arr.values()) {
                        if (v instanceof JsonString s) explicitAuto.add(normalizeScriptKey(s.value()));
                    }
                }
            }
        }

        List<String> toRun;
        if (explicitAuto != null) {
            toRun = explicitAuto;
        } else {
            toRun = new ArrayList<>(scripts.keySet());
        }
        var animBudget = new ScriptBudget(100_000L, 32, CancellationTokenHolder.from(budget));
        return new FiguraAvatarRuntime(budget, animBudget, scripts, toRun, modelStates, initialSnapshot);
    }

    private static final class CancellationTokenHolder {
        static CancellationToken from(LuaBudget budget) {
            budget.checkCancelled();
            return new CancellationToken();
        }
    }

    public void initialize() {
        if (initialized) return;
        initialized = true;
        for (String name : autoScripts) {
            String src = scripts.get(name);
            if (src != null) {
                vm.execute(name + ".lua", src);
            }
        }
        fireEvent("ENTITY_INIT");
    }

    public void tick(HostSnapshot snapshot) {
        if (!initialized) initialize();
        this.currentSnapshot = Objects.requireNonNull(snapshot);
        for (ModelState m : models) {
            for (ClipInstance clip : m.animations.values()) {
                clip.advance(1.0 / 20.0);
            }
        }
        fireEvent("WORLD_TICK");
        fireEvent("TICK");
    }

    public FiguraFrame render(float tickDelta, HostSnapshot snapshot) {
        if (!initialized) initialize();
        this.currentSnapshot = Objects.requireNonNull(snapshot);
        LuaValue[] renderArgs = {LuaValue.of(tickDelta), LuaValue.of("RENDER")};
        fireEvent("WORLD_RENDER", renderArgs);
        fireEvent("RENDER", renderArgs);

        var modelPoses = new LinkedHashMap<String, BonePose>();
        for (ModelState m : models) {
            modelPoses.put(m.modelKey, buildBonePose(m));
        }

        fireEvent("POST_RENDER", renderArgs);

        var vanillaSnap = new LinkedHashMap<String, VanillaPartState>();
        vanillaParts.forEach((k, v) -> vanillaSnap.put(k, v.snapshot()));

        var effectsSnap = List.copyOf(pendingEffects);
        pendingEffects.clear();
        var logsSnap = List.copyOf(pendingLogs);
        pendingLogs.clear();
        return new FiguraFrame(Collections.unmodifiableMap(modelPoses),
                Collections.unmodifiableMap(vanillaSnap), effectsSnap, logsSnap);
    }

    public void dispatchEvent(String eventName, LuaValue... args) {
        if (!initialized) initialize();
        fireEvent(eventName.toUpperCase(Locale.ROOT), args);
    }

    public LuaVm vm() { return vm; }

    private BonePose buildBonePose(ModelState m) {
        BonePose bind = m.document.bindPose();
        int n = m.document.skeleton().boneCount();
        float[] rots = bind.rotations();
        float[] trans = bind.translations();
        float[] scales = bind.scales();
        boolean[] ownHidden = bind.ownHidden();
        boolean[] childHidden = bind.childrenHidden();
        int[] glow = bind.glow();
        int[] colors = bind.colorsRgba();

        FiguraAnimationSupport.evaluateAll(m.animations.values(), rots, trans, scales, animBudget);

        for (int i = 0; i < n; i++) {
            MutableBoneOverride ov = m.boneOverrides[i];
            int i3 = i * 3;
            if (ov.hasRot) {
                rots[i3] += (float) Math.toRadians(ov.rotDeg[0]);
                rots[i3 + 1] += (float) Math.toRadians(ov.rotDeg[1]);
                rots[i3 + 2] += (float) Math.toRadians(ov.rotDeg[2]);
            }
            if (ov.hasPos) {
                trans[i3] += ov.posPx[0] / 16f;
                trans[i3 + 1] += ov.posPx[1] / 16f;
                trans[i3 + 2] += ov.posPx[2] / 16f;
            }
            if (ov.hasScale) {
                scales[i3] *= ov.scale[0];
                scales[i3 + 1] *= ov.scale[1];
                scales[i3 + 2] *= ov.scale[2];
            }
            if (ov.visible != null) {
                ownHidden[i] = !ov.visible;
                childHidden[i] = !ov.visible;
            }
            if (ov.colorRgba != 0xFFFFFFFF) {
                colors[i] = ov.colorRgba;
            }
        }
        return new BonePose(rots, trans, scales, ownHidden, childHidden, glow, colors);
    }

    private void fireEvent(String name, LuaValue... args) {
        List<LuaFunction> listeners = events.get(name);
        if (listeners == null || listeners.isEmpty()) return;
        for (LuaFunction fn : List.copyOf(listeners)) {
            budget.step();
            vm.invoke(fn, args);
        }
    }

    private String resolveModule(String modName) {
        String key = normalizeScriptKey(modName);
        if (scripts.containsKey(key)) return scripts.get(key);
        String slash = normalizeScriptKey(modName.replace('.', '/'));
        return scripts.get(slash);
    }

    private void installBindings() {
        LuaTable g = vm.globals();
        FiguraVectorApi.install(vm, g);
        installEvents(g);
        installModelsAndAnimations(g);
        installVanillaModel(g);
        installPlayerWorldClientRendererSoundsParticlesPings(g);
    }

    private void installEvents(LuaTable g) {
        LuaTable eventsTbl = new LuaTable(budget);
        LuaTable eventsMeta = new LuaTable(budget);
        eventsMeta.rawSet("__index", LuaFunction.nativeFn("events.__index", (v, a) -> {
            String key = a[1].asString().toUpperCase(Locale.ROOT);
            return LuaValues.single(createEventProxy(key));
        }));
        eventsMeta.rawSet("__newindex", LuaFunction.nativeFn("events.__newindex", (v, a) -> {
            String key = a[1].asString().toUpperCase(Locale.ROOT);
            if (a[2] instanceof LuaFunction fn) {
                events.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fn);
            }
            return LuaValues.empty();
        }));
        eventsTbl.setMetatable(eventsMeta);
        g.rawSet("events", eventsTbl);
    }

    private LuaTable createEventProxy(String eventName) {
        LuaTable proxy = new LuaTable(budget);
        proxy.rawSet("register", LuaFunction.nativeFn("event:register", (v, a) -> {
            if (a.length >= 2 && a[1] instanceof LuaFunction fn) {
                events.computeIfAbsent(eventName, ignored -> new ArrayList<>()).add(fn);
            }
            return LuaValues.empty();
        }));
        proxy.rawSet("remove", LuaFunction.nativeFn("event:remove", (v, a) -> {
            events.remove(eventName);
            return LuaValues.single(LuaValue.of(0L));
        }));
        proxy.rawSet("getRegisteredCount", LuaFunction.nativeFn("event:count", (v, a) -> {
            var list = events.get(eventName);
            return LuaValues.single(LuaValue.of((long) (list == null ? 0 : list.size())));
        }));
        return proxy;
    }

    private void installModelsAndAnimations(LuaTable g) {
        LuaTable modelsTbl = new LuaTable(budget);
        LuaTable animsTbl = new LuaTable(budget);
        for (ModelState ms : models) {
            LuaTable rootParts = buildModelPartTree(ms);
            modelsTbl.rawSet(ms.shortName, rootParts);
            if (!ms.modelKey.equals(ms.shortName)) modelsTbl.rawSet(ms.modelKey, rootParts);

            LuaTable clipGroup = new LuaTable(budget);
            for (var e : ms.animations.entrySet()) {
                clipGroup.rawSet(e.getKey(), buildClipProxy(e.getValue()));
            }
            animsTbl.rawSet(ms.shortName, clipGroup);
            if (!ms.modelKey.equals(ms.shortName)) animsTbl.rawSet(ms.modelKey, clipGroup);
        }
        g.rawSet("models", modelsTbl);
        g.rawSet("animations", animsTbl);
    }

    private LuaTable buildModelPartTree(ModelState ms) {
        Skeleton skel = ms.document.skeleton();
        int n = skel.boneCount();
        LuaTable[] partTables = new LuaTable[n];
        for (int i = 0; i < n; i++) {
            partTables[i] = buildBonePartProxy(ms, i, skel.names().get(i));
        }
        LuaTable container = new LuaTable(budget);
        for (int i = 0; i < n; i++) {
            int parent = skel.parent(i);
            String name = skel.names().get(i);
            if (parent < 0) {
                container.rawSet(name, partTables[i]);
            } else {
                partTables[parent].rawSet(name, partTables[i]);
            }
            container.rawSet(name, partTables[i]); // allow direct lookup too if root-skipped
        }
        return container;
    }

    private LuaTable buildBonePartProxy(ModelState ms, int boneIdx, String name) {
        LuaTable t = new LuaTable(budget);
        MutableBoneOverride ov = ms.boneOverrides[boneIdx];
        t.rawSet("getName", LuaFunction.nativeFn("part:getName", (v, a) -> LuaValues.single(LuaValue.of(name))));
        t.rawSet("setRot", LuaFunction.nativeFn("part:setRot", (v, a) -> {
            Vec r = FiguraVectorApi.extractVec3(a, 1);
            ov.rotDeg[0] = (float) r.x; ov.rotDeg[1] = (float) r.y; ov.rotDeg[2] = (float) r.z;
            ov.hasRot = true;
            return LuaValues.single(a[0]);
        }));
        t.rawSet("getRot", LuaFunction.nativeFn("part:getRot", (v, a) -> LuaValues.single(vecTuple(ov.rotDeg))));
        t.rawSet("setPos", LuaFunction.nativeFn("part:setPos", (v, a) -> {
            Vec p = FiguraVectorApi.extractVec3(a, 1);
            ov.posPx[0] = (float) p.x; ov.posPx[1] = (float) p.y; ov.posPx[2] = (float) p.z;
            ov.hasPos = true;
            return LuaValues.single(a[0]);
        }));
        t.rawSet("getPos", LuaFunction.nativeFn("part:getPos", (v, a) -> LuaValues.single(vecTuple(ov.posPx))));
        t.rawSet("setScale", LuaFunction.nativeFn("part:setScale", (v, a) -> {
            Vec s = a.length == 2 && a[1] instanceof LuaValue.Num num
                    ? new Vec(3, num.value(), num.value(), num.value(), 0)
                    : FiguraVectorApi.extractVec3(a, 1);
            ov.scale[0] = (float) s.x; ov.scale[1] = (float) s.y; ov.scale[2] = (float) s.z;
            ov.hasScale = true;
            return LuaValues.single(a[0]);
        }));
        t.rawSet("getScale", LuaFunction.nativeFn("part:getScale", (v, a) -> LuaValues.single(vecTuple(ov.scale))));
        t.rawSet("setVisible", LuaFunction.nativeFn("part:setVisible", (v, a) -> {
            ov.visible = LuaValues.arg(a, 1).isTruthy();
            return LuaValues.single(a[0]);
        }));
        t.rawSet("getVisible", LuaFunction.nativeFn("part:getVisible", (v, a) ->
                LuaValues.single(LuaValue.of(ov.visible == null || ov.visible))));
        t.rawSet("setColor", LuaFunction.nativeFn("part:setColor", (v, a) -> {
            Vec c = FiguraVectorApi.extractVec3(a, 1);
            int r = clampByte(c.x * 255.0), gg = clampByte(c.y * 255.0), b = clampByte(c.z * 255.0);
            int alpha = (ov.colorRgba >>> 24) & 0xFF;
            ov.colorRgba = (alpha << 24) | (b << 16) | (gg << 8) | r;
            return LuaValues.single(a[0]);
        }));
        t.rawSet("setOpacity", LuaFunction.nativeFn("part:setOpacity", (v, a) -> {
            int alpha = clampByte(LuaValues.arg(a, 1).asDouble() * 255.0);
            ov.colorRgba = (alpha << 24) | (ov.colorRgba & 0x00FFFFFF);
            return LuaValues.single(a[0]);
        }));
        t.rawSet("setPrimaryRenderType", LuaFunction.nativeFn("part:setPrimaryRenderType", (v, a) -> {
            ov.renderType = LuaValues.arg(a, 1) == LuaValue.NIL ? null : LuaValues.arg(a, 1).asString();
            return LuaValues.single(a[0]);
        }));
        t.rawSet("setParentType", LuaFunction.nativeFn("part:setParentType", (v, a) -> {
            ov.parentType = LuaValues.arg(a, 1) == LuaValue.NIL ? null : LuaValues.arg(a, 1).asString();
            return LuaValues.single(a[0]);
        }));
        return t;
    }

    private LuaTable buildClipProxy(ClipInstance clip) {
        LuaTable t = new LuaTable(budget);
        t.rawSet("play", LuaFunction.nativeFn("anim:play", (v, a) -> { clip.play(); return LuaValues.single(a[0]); }));
        t.rawSet("stop", LuaFunction.nativeFn("anim:stop", (v, a) -> { clip.stop(); return LuaValues.single(a[0]); }));
        t.rawSet("pause", LuaFunction.nativeFn("anim:pause", (v, a) -> { clip.pause(); return LuaValues.single(a[0]); }));
        t.rawSet("restart", LuaFunction.nativeFn("anim:restart", (v, a) -> { clip.restart(); return LuaValues.single(a[0]); }));
        t.rawSet("isPlaying", LuaFunction.nativeFn("anim:isPlaying", (v, a) -> LuaValues.single(LuaValue.of(clip.playing && !clip.paused))));
        t.rawSet("isPaused", LuaFunction.nativeFn("anim:isPaused", (v, a) -> LuaValues.single(LuaValue.of(clip.paused))));
        t.rawSet("setSpeed", LuaFunction.nativeFn("anim:setSpeed", (v, a) -> { clip.speed = LuaValues.arg(a, 1).asDouble(); return LuaValues.single(a[0]); }));
        t.rawSet("getSpeed", LuaFunction.nativeFn("anim:getSpeed", (v, a) -> LuaValues.single(LuaValue.of(clip.speed))));
        t.rawSet("setBlend", LuaFunction.nativeFn("anim:setBlend", (v, a) -> { clip.blend = LuaValues.arg(a, 1).asDouble(); return LuaValues.single(a[0]); }));
        t.rawSet("getBlend", LuaFunction.nativeFn("anim:getBlend", (v, a) -> LuaValues.single(LuaValue.of(clip.blend))));
        t.rawSet("setPriority", LuaFunction.nativeFn("anim:setPriority", (v, a) -> { clip.priority = (int) LuaValues.arg(a, 1).asDouble(); return LuaValues.single(a[0]); }));
        t.rawSet("getPriority", LuaFunction.nativeFn("anim:getPriority", (v, a) -> LuaValues.single(LuaValue.of((long) clip.priority))));
        t.rawSet("setLoop", LuaFunction.nativeFn("anim:setLoop", (v, a) -> { clip.loopMode = FiguraAnimationSupport.parseLoop(LuaValues.arg(a, 1).asString()); return LuaValues.single(a[0]); }));
        t.rawSet("getLoop", LuaFunction.nativeFn("anim:getLoop", (v, a) -> LuaValues.single(LuaValue.of(clip.loopMode.name()))));
        t.rawSet("setTime", LuaFunction.nativeFn("anim:setTime", (v, a) -> { clip.time = LuaValues.arg(a, 1).asDouble(); return LuaValues.single(a[0]); }));
        t.rawSet("getTime", LuaFunction.nativeFn("anim:getTime", (v, a) -> LuaValues.single(LuaValue.of(clip.time))));
        t.rawSet("getLength", LuaFunction.nativeFn("anim:getLength", (v, a) -> LuaValues.single(LuaValue.of(clip.length))));
        return t;
    }

    private void installVanillaModel(LuaTable g) {
        LuaTable vmTbl = new LuaTable(budget);
        for (String name : VANILLA_PART_NAMES) {
            var state = new MutableVanillaPart();
            vanillaParts.put(name, state);
            LuaTable pt = new LuaTable(budget);
            pt.rawSet("setVisible", LuaFunction.nativeFn("vanilla:setVisible", (v, a) -> { state.visible = LuaValues.arg(a, 1).isTruthy(); return LuaValues.single(a[0]); }));
            pt.rawSet("getVisible", LuaFunction.nativeFn("vanilla:getVisible", (v, a) -> LuaValues.single(LuaValue.of(state.visible))));
            pt.rawSet("setRot", LuaFunction.nativeFn("vanilla:setRot", (v, a) -> {
                Vec r = FiguraVectorApi.extractVec3(a, 1);
                state.rotDeg[0] = (float) r.x; state.rotDeg[1] = (float) r.y; state.rotDeg[2] = (float) r.z;
                return LuaValues.single(a[0]);
            }));
            pt.rawSet("getRot", LuaFunction.nativeFn("vanilla:getRot", (v, a) -> LuaValues.single(vecTuple(state.rotDeg))));
            pt.rawSet("setPos", LuaFunction.nativeFn("vanilla:setPos", (v, a) -> {
                Vec p = FiguraVectorApi.extractVec3(a, 1);
                state.posPx[0] = (float) p.x; state.posPx[1] = (float) p.y; state.posPx[2] = (float) p.z;
                return LuaValues.single(a[0]);
            }));
            pt.rawSet("getPos", LuaFunction.nativeFn("vanilla:getPos", (v, a) -> LuaValues.single(vecTuple(state.posPx))));
            pt.rawSet("setScale", LuaFunction.nativeFn("vanilla:setScale", (v, a) -> {
                Vec s = FiguraVectorApi.extractVec3(a, 1);
                state.scale[0] = (float) s.x; state.scale[1] = (float) s.y; state.scale[2] = (float) s.z;
                return LuaValues.single(a[0]);
            }));
            pt.rawSet("getScale", LuaFunction.nativeFn("vanilla:getScale", (v, a) -> LuaValues.single(vecTuple(state.scale))));
            vmTbl.rawSet(name, pt);
        }
        g.rawSet("vanilla_model", vmTbl);
    }

    private void installPlayerWorldClientRendererSoundsParticlesPings(LuaTable g) {
        LuaTable player = new LuaTable(budget);
        player.rawSet("isLoaded", LuaFunction.nativeFn("player:isLoaded", (v, a) -> LuaValues.single(LuaValue.TRUE)));
        player.rawSet("getName", LuaFunction.nativeFn("player:getName", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.playerName()))));
        player.rawSet("getUUID", LuaFunction.nativeFn("player:getUUID", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.playerUuid()))));
        player.rawSet("getPos", LuaFunction.nativeFn("player:getPos", (v, a) -> LuaValues.single(makeVec(currentSnapshot.x(), currentSnapshot.y(), currentSnapshot.z()))));
        player.rawSet("getVelocity", LuaFunction.nativeFn("player:getVelocity", (v, a) -> LuaValues.single(makeVec(currentSnapshot.velX(), currentSnapshot.velY(), currentSnapshot.velZ()))));
        player.rawSet("getRot", LuaFunction.nativeFn("player:getRot", (v, a) -> LuaValues.single(makeVec(currentSnapshot.pitch(), currentSnapshot.yaw()))));
        player.rawSet("getBodyYaw", LuaFunction.nativeFn("player:getBodyYaw", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.bodyYaw()))));
        player.rawSet("getHealth", LuaFunction.nativeFn("player:getHealth", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.health()))));
        player.rawSet("getMaxHealth", LuaFunction.nativeFn("player:getMaxHealth", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.maxHealth()))));
        player.rawSet("isCrouching", LuaFunction.nativeFn("player:isCrouching", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.crouching()))));
        player.rawSet("isSprinting", LuaFunction.nativeFn("player:isSprinting", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.sprinting()))));
        player.rawSet("isSwimming", LuaFunction.nativeFn("player:isSwimming", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.swimming()))));
        player.rawSet("isFlying", LuaFunction.nativeFn("player:isFlying", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.flying()))));
        player.rawSet("isOnGround", LuaFunction.nativeFn("player:isOnGround", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.onGround()))));
        player.rawSet("isSwingingArm", LuaFunction.nativeFn("player:isSwingingArm", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.swingingArm()))));
        player.rawSet("getHeldItem", LuaFunction.nativeFn("player:getHeldItem", (v, a) -> {
            boolean offhand = LuaValues.arg(a, 1).isTruthy();
            String id = offhand ? currentSnapshot.offHandItem() : currentSnapshot.mainHandItem();
            LuaTable item = new LuaTable(budget);
            item.rawSet("id", LuaValue.of(id));
            item.rawSet("getName", LuaFunction.nativeFn("item:getName", (vv, aa) -> LuaValues.single(LuaValue.of(id))));
            return LuaValues.single(item);
        }));
        g.rawSet("player", player);

        LuaTable world = new LuaTable(budget);
        world.rawSet("getTime", LuaFunction.nativeFn("world:getTime", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.worldTime()))));
        world.rawSet("getRainGradient", LuaFunction.nativeFn("world:getRainGradient", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.rainGradient()))));
        g.rawSet("world", world);

        LuaTable client = new LuaTable(budget);
        client.rawSet("getFPS", LuaFunction.nativeFn("client:getFPS", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.fps()))));
        g.rawSet("client", client);

        LuaTable host = new LuaTable(budget);
        host.rawSet("isHost", LuaFunction.nativeFn("host:isHost", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.isHost()))));
        host.rawSet("setActionbar", LuaFunction.nativeFn("host:setActionbar", (v, a) -> {
            pendingEffects.add(new EffectCommand("ACTIONBAR", Map.of("text", LuaValues.arg(a, 1).asString())));
            return LuaValues.single(a[0]);
        }));
        g.rawSet("host", host);

        LuaTable renderer = new LuaTable(budget);
        renderer.rawSet("setShadowRadius", LuaFunction.nativeFn("renderer:setShadowRadius", (v, a) -> {
            pendingEffects.add(new EffectCommand("RENDERER", Map.of("shadowRadius", LuaValues.arg(a, 1).asDouble())));
            return LuaValues.single(a[0]);
        }));
        renderer.rawSet("setRenderHUD", LuaFunction.nativeFn("renderer:setRenderHUD", (v, a) -> {
            pendingEffects.add(new EffectCommand("RENDERER", Map.of("renderHud", LuaValues.arg(a, 1).isTruthy())));
            return LuaValues.single(a[0]);
        }));
        g.rawSet("renderer", renderer);

        LuaTable sounds = new LuaTable(budget);
        sounds.rawSet("playSound", LuaFunction.nativeFn("sounds:playSound", (v, a) -> {
            String id = LuaValues.arg(a, 1).asString();
            Vec pos = a.length >= 3 && a[2] != LuaValue.NIL ? FiguraVectorApi.extractVec3(a, 2)
                    : new Vec(3, currentSnapshot.x(), currentSnapshot.y(), currentSnapshot.z(), 0);
            double vol = LuaValues.arg(a, 3) == LuaValue.NIL ? 1.0 : LuaValues.arg(a, 3).asDouble();
            double pitch = LuaValues.arg(a, 4) == LuaValue.NIL ? 1.0 : LuaValues.arg(a, 4).asDouble();
            pendingEffects.add(new EffectCommand("SOUND", Map.of(
                    "id", id, "x", pos.x, "y", pos.y, "z", pos.z, "volume", vol, "pitch", pitch)));
            return LuaValues.single(a[0]);
        }));
        sounds.rawSet("stopSound", LuaFunction.nativeFn("sounds:stopSound", (v, a) -> {
            String id = LuaValues.arg(a, 1) == LuaValue.NIL ? "" : LuaValues.arg(a, 1).asString();
            pendingEffects.add(new EffectCommand("STOP_SOUND", Map.of("id", id)));
            return LuaValues.single(a[0]);
        }));
        g.rawSet("sounds", sounds);

        LuaTable particles = new LuaTable(budget);
        particles.rawSet("newParticle", LuaFunction.nativeFn("particles:newParticle", (v, a) -> {
            String id = LuaValues.arg(a, 1).asString();
            Vec pos = a.length >= 3 && a[2] != LuaValue.NIL ? FiguraVectorApi.extractVec3(a, 2) : new Vec(3, 0, 0, 0, 0);
            pendingEffects.add(new EffectCommand("PARTICLE", Map.of("id", id, "x", pos.x, "y", pos.y, "z", pos.z)));
            return LuaValues.single(a[0]);
        }));
        g.rawSet("particles", particles);

        LuaTable pings = new LuaTable(budget);
        LuaTable pingsMeta = new LuaTable(budget);
        pingsMeta.rawSet("__newindex", LuaFunction.nativeFn("pings.__newindex", (v, a) -> {
            String name = a[1].asString();
            if (a[2] instanceof LuaFunction fn) {
                pingHandlers.put(name, fn);
            }
            return LuaValues.empty();
        }));
        pingsMeta.rawSet("__index", LuaFunction.nativeFn("pings.__index", (v, a) -> {
            String name = a[1].asString();
            return LuaValues.single(LuaFunction.nativeFn("ping:" + name, (vv, aa) -> {
                pendingEffects.add(new EffectCommand("PING", Map.of("name", name, "argCount", aa.length)));
                LuaFunction handler = pingHandlers.get(name);
                return handler != null ? vm.invoke(handler, aa) : LuaValues.empty();
            }));
        }));
        pings.setMetatable(pingsMeta);
        g.rawSet("pings", pings);

        LuaTable avatar = new LuaTable(budget);
        avatar.rawSet("getEntityName", LuaFunction.nativeFn("avatar:getEntityName", (v, a) -> LuaValues.single(LuaValue.of(currentSnapshot.playerName()))));
        g.rawSet("avatar", avatar);
    }

    private LuaValue makeVec(double x, double y, double z) {
        LuaValue fn = vm.globals().rawGet("vec");
        return LuaValues.first(vm.invoke(fn, LuaValue.of(x), LuaValue.of(y), LuaValue.of(z)));
    }

    private LuaValue makeVec(double x, double y) {
        LuaValue fn = vm.globals().rawGet("vec");
        return LuaValues.first(vm.invoke(fn, LuaValue.of(x), LuaValue.of(y)));
    }

    private LuaValue vecTuple(float[] arr) {
        return makeVec(arr[0], arr[1], arr[2]);
    }

    private static String normalizeScriptKey(String path) {
        String s = path.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        if (s.toLowerCase(Locale.ROOT).endsWith(".lua")) s = s.substring(0, s.length() - 4);
        return s;
    }

    private static String stripExtension(String path) {
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static int clampByte(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }
}
