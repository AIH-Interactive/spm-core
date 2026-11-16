package dev.spm.core.format.figura;

import dev.spm.core.lua.LuaException;
import dev.spm.core.lua.LuaFunction;
import dev.spm.core.lua.LuaTable;
import dev.spm.core.lua.LuaUserdata;
import dev.spm.core.lua.LuaValue;
import dev.spm.core.lua.LuaValues;
import dev.spm.core.lua.LuaVm;

/** Figura-compatible {@code vec}, {@code vectors}, and {@code matrices} Lua bindings. */
final class FiguraVectorApi {
    static final class Vec {
        double x, y, z, w;
        final int dims;

        Vec(int dims, double x, double y, double z, double w) {
            this.dims = dims;
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
        }

        Vec copy() { return new Vec(dims, x, y, z, w); }

        double getComp(int idx) {
            return switch (idx) {
                case 0 -> x;
                case 1 -> y;
                case 2 -> z;
                default -> w;
            };
        }

        void setComp(int idx, double v) {
            switch (idx) {
                case 0 -> x = v;
                case 1 -> y = v;
                case 2 -> z = v;
                case 3 -> w = v;
                default -> {}
            }
        }
    }

    static final class Mat4 {
        final double[] m = new double[16];

        Mat4() {
            m[0] = 1; m[5] = 1; m[10] = 1; m[15] = 1;
        }

        Mat4 copy() {
            var c = new Mat4();
            System.arraycopy(m, 0, c.m, 0, 16);
            return c;
        }

        void mul(Mat4 o) {
            double[] r = new double[16];
            for (int c = 0; c < 4; c++) {
                for (int row = 0; row < 4; row++) {
                    double s = 0;
                    for (int k = 0; k < 4; k++) s += m[k * 4 + row] * o.m[c * 4 + k];
                    r[c * 4 + row] = s;
                }
            }
            System.arraycopy(r, 0, m, 0, 16);
        }
    }

    private FiguraVectorApi() {}

    static void install(LuaVm vm, LuaTable globals) {
        LuaTable vecMeta = buildVecMetatable(vm);
        LuaTable matMeta = buildMatMetatable(vm);

        LuaFunction vecFn = LuaFunction.nativeFn("vec", (v, a) -> LuaValues.single(newUserVec(vecMeta, a)));
        globals.rawSet("vec", vecFn);

        LuaTable vectors = new LuaTable(vm.budget());
        vectors.rawSet("vec", vecFn);
        vectors.rawSet("vec2", LuaFunction.nativeFn("vectors.vec2", (v, a) -> LuaValues.single(
                new LuaUserdata(new Vec(2, d(a, 0), d(a, 1), 0, 0), vecMeta))));
        vectors.rawSet("vec3", LuaFunction.nativeFn("vectors.vec3", (v, a) -> LuaValues.single(
                new LuaUserdata(new Vec(3, d(a, 0), d(a, 1), d(a, 2), 0), vecMeta))));
        vectors.rawSet("vec4", LuaFunction.nativeFn("vectors.vec4", (v, a) -> LuaValues.single(
                new LuaUserdata(new Vec(4, d(a, 0), d(a, 1), d(a, 2), d(a, 3)), vecMeta))));
        vectors.rawSet("hexToRGB", LuaFunction.nativeFn("vectors.hexToRGB", (v, a) -> {
            String s = LuaValues.arg(a, 0).asString().trim();
            if (s.startsWith("#")) s = s.substring(1);
            int rgb = (int) Long.parseLong(s, 16);
            double r = ((rgb >> 16) & 0xFF) / 255.0;
            double g = ((rgb >> 8) & 0xFF) / 255.0;
            double b = (rgb & 0xFF) / 255.0;
            return LuaValues.single(new LuaUserdata(new Vec(3, r, g, b, 0), vecMeta));
        }));
        vectors.rawSet("rgbToInt", LuaFunction.nativeFn("vectors.rgbToInt", (v, a) -> {
            Vec c = asVec(a, 0);
            int r = clampByte(c.x * 255.0), g = clampByte(c.y * 255.0), b = clampByte(c.z * 255.0);
            return LuaValues.single(LuaValue.of((double) ((r << 16) | (g << 8) | b)));
        }));
        vectors.rawSet("intToRGB", LuaFunction.nativeFn("vectors.intToRGB", (v, a) -> {
            int rgb = (int) LuaValues.arg(a, 0).asDouble();
            return LuaValues.single(new LuaUserdata(new Vec(3,
                    ((rgb >> 16) & 0xFF) / 255.0,
                    ((rgb >> 8) & 0xFF) / 255.0,
                    (rgb & 0xFF) / 255.0, 0), vecMeta));
        }));
        globals.rawSet("vectors", vectors);

        LuaTable matrices = new LuaTable(vm.budget());
        matrices.rawSet("mat4", LuaFunction.nativeFn("matrices.mat4", (v, a) ->
                LuaValues.single(new LuaUserdata(new Mat4(), matMeta))));
        matrices.rawSet("translate4", LuaFunction.nativeFn("matrices.translate4", (v, a) -> {
            Mat4 m = new Mat4();
            Vec tr = extractVec3(a, 0);
            m.m[12] = tr.x; m.m[13] = tr.y; m.m[14] = tr.z;
            return LuaValues.single(new LuaUserdata(m, matMeta));
        }));
        matrices.rawSet("scale4", LuaFunction.nativeFn("matrices.scale4", (v, a) -> {
            Mat4 m = new Mat4();
            Vec sc = a.length == 1 && LuaValues.arg(a, 0) instanceof LuaValue.Num n
                    ? new Vec(3, n.value(), n.value(), n.value(), 0)
                    : extractVec3(a, 0);
            m.m[0] = sc.x; m.m[5] = sc.y; m.m[10] = sc.z;
            return LuaValues.single(new LuaUserdata(m, matMeta));
        }));
        matrices.rawSet("xRotation4", LuaFunction.nativeFn("matrices.xRotation4", (v, a) -> {
            Mat4 m = new Mat4();
            double rad = Math.toRadians(LuaValues.arg(a, 0).asDouble());
            double c = Math.cos(rad), s = Math.sin(rad);
            m.m[5] = c; m.m[6] = s; m.m[9] = -s; m.m[10] = c;
            return LuaValues.single(new LuaUserdata(m, matMeta));
        }));
        matrices.rawSet("yRotation4", LuaFunction.nativeFn("matrices.yRotation4", (v, a) -> {
            Mat4 m = new Mat4();
            double rad = Math.toRadians(LuaValues.arg(a, 0).asDouble());
            double c = Math.cos(rad), s = Math.sin(rad);
            m.m[0] = c; m.m[2] = -s; m.m[8] = s; m.m[10] = c;
            return LuaValues.single(new LuaUserdata(m, matMeta));
        }));
        matrices.rawSet("zRotation4", LuaFunction.nativeFn("matrices.zRotation4", (v, a) -> {
            Mat4 m = new Mat4();
            double rad = Math.toRadians(LuaValues.arg(a, 0).asDouble());
            double c = Math.cos(rad), s = Math.sin(rad);
            m.m[0] = c; m.m[1] = s; m.m[4] = -s; m.m[5] = c;
            return LuaValues.single(new LuaUserdata(m, matMeta));
        }));
        globals.rawSet("matrices", matrices);
    }

    static Vec extractVec3(LuaValue[] args, int startIdx) {
        LuaValue first = LuaValues.arg(args, startIdx);
        if (first instanceof LuaUserdata ud && ud.value() instanceof Vec v) return v;
        return new Vec(3, d(args, startIdx), d(args, startIdx + 1), d(args, startIdx + 2), 0);
    }

    private static LuaUserdata newUserVec(LuaTable meta, LuaValue[] args) {
        if (args.length <= 2) return new LuaUserdata(new Vec(2, d(args, 0), d(args, 1), 0, 0), meta);
        if (args.length == 3) return new LuaUserdata(new Vec(3, d(args, 0), d(args, 1), d(args, 2), 0), meta);
        return new LuaUserdata(new Vec(4, d(args, 0), d(args, 1), d(args, 2), d(args, 3)), meta);
    }

    private static LuaTable buildVecMetatable(LuaVm vm) {
        LuaTable methods = new LuaTable(vm.budget());
        LuaTable mt = new LuaTable(vm.budget());

        methods.rawSet("copy", LuaFunction.nativeFn("vec:copy", (v, a) ->
                LuaValues.single(new LuaUserdata(asVec(a, 0).copy(), mt))));
        methods.rawSet("lengthSquared", LuaFunction.nativeFn("vec:lengthSquared", (v, a) -> {
            Vec u = asVec(a, 0);
            double s = u.x * u.x + u.y * u.y + (u.dims >= 3 ? u.z * u.z : 0) + (u.dims >= 4 ? u.w * u.w : 0);
            return LuaValues.single(LuaValue.of(s));
        }));
        methods.rawSet("length", LuaFunction.nativeFn("vec:length", (v, a) -> {
            Vec u = asVec(a, 0);
            double s = u.x * u.x + u.y * u.y + (u.dims >= 3 ? u.z * u.z : 0) + (u.dims >= 4 ? u.w * u.w : 0);
            return LuaValues.single(LuaValue.of(Math.sqrt(s)));
        }));
        methods.rawSet("normalized", LuaFunction.nativeFn("vec:normalized", (v, a) -> {
            Vec u = asVec(a, 0).copy();
            double len = Math.sqrt(u.x * u.x + u.y * u.y + u.z * u.z + u.w * u.w);
            if (len > 0) { u.x /= len; u.y /= len; u.z /= len; u.w /= len; }
            return LuaValues.single(new LuaUserdata(u, mt));
        }));
        methods.rawSet("dot", LuaFunction.nativeFn("vec:dot", (v, a) -> {
            Vec u = asVec(a, 0), w = asVec(a, 1);
            return LuaValues.single(LuaValue.of(u.x * w.x + u.y * w.y + u.z * w.z + u.w * w.w));
        }));
        methods.rawSet("crossed", LuaFunction.nativeFn("vec:crossed", (v, a) -> {
            Vec u = asVec(a, 0), w = asVec(a, 1);
            return LuaValues.single(new LuaUserdata(new Vec(3,
                    u.y * w.z - u.z * w.y, u.z * w.x - u.x * w.z, u.x * w.y - u.y * w.x, 0), mt));
        }));
        methods.rawSet("lerp", LuaFunction.nativeFn("vec:lerp", (v, a) -> {
            Vec u = asVec(a, 0), w = asVec(a, 1);
            double t = a[2].asDouble();
            return LuaValues.single(new LuaUserdata(new Vec(u.dims,
                    u.x + (w.x - u.x) * t, u.y + (w.y - u.y) * t,
                    u.z + (w.z - u.z) * t, u.w + (w.w - u.w) * t), mt));
        }));
        methods.rawSet("set", LuaFunction.nativeFn("vec:set", (v, a) -> {
            Vec u = asVec(a, 0);
            Vec s = a.length >= 2 && a[1] instanceof LuaUserdata ud && ud.value() instanceof Vec ov
                    ? ov : new Vec(u.dims, d(a, 1), d(a, 2), d(a, 3), d(a, 4));
            u.x = s.x; u.y = s.y; if (u.dims >= 3) u.z = s.z; if (u.dims >= 4) u.w = s.w;
            return LuaValues.single(a[0]);
        }));
        methods.rawSet("add", LuaFunction.nativeFn("vec:add", (v, a) -> {
            Vec u = asVec(a, 0);
            Vec s = a.length >= 2 && a[1] instanceof LuaUserdata ud && ud.value() instanceof Vec ov
                    ? ov : new Vec(u.dims, d(a, 1), d(a, 2), d(a, 3), d(a, 4));
            u.x += s.x; u.y += s.y; if (u.dims >= 3) u.z += s.z; if (u.dims >= 4) u.w += s.w;
            return LuaValues.single(a[0]);
        }));
        methods.rawSet("scale", LuaFunction.nativeFn("vec:scale", (v, a) -> {
            Vec u = asVec(a, 0);
            double s = a[1].asDouble();
            u.x *= s; u.y *= s; u.z *= s; u.w *= s;
            return LuaValues.single(a[0]);
        }));
        methods.rawSet("unpack", LuaFunction.nativeFn("vec:unpack", (v, a) -> {
            Vec u = asVec(a, 0);
            LuaValue[] out = new LuaValue[u.dims];
            for (int i = 0; i < u.dims; i++) out[i] = LuaValue.of(u.getComp(i));
            return out;
        }));
        methods.rawSet("toRad", LuaFunction.nativeFn("vec:toRad", (v, a) -> {
            Vec u = asVec(a, 0);
            return LuaValues.single(new LuaUserdata(new Vec(u.dims,
                    Math.toRadians(u.x), Math.toRadians(u.y), Math.toRadians(u.z), Math.toRadians(u.w)), mt));
        }));
        methods.rawSet("toDeg", LuaFunction.nativeFn("vec:toDeg", (v, a) -> {
            Vec u = asVec(a, 0);
            return LuaValues.single(new LuaUserdata(new Vec(u.dims,
                    Math.toDegrees(u.x), Math.toDegrees(u.y), Math.toDegrees(u.z), Math.toDegrees(u.w)), mt));
        }));

        mt.rawSet("__index", LuaFunction.nativeFn("vec.__index", (v, a) -> {
            Vec u = asVec(a, 0);
            if (a[1] instanceof LuaValue.Num n) {
                int idx = (int) n.value() - 1;
                return LuaValues.single(idx >= 0 && idx < u.dims ? LuaValue.of(u.getComp(idx)) : LuaValue.NIL);
            }
            String k = a[1].asString();
            LuaValue method = methods.rawGet(k);
            if (method != LuaValue.NIL) return LuaValues.single(method);
            if (k.length() == 1) {
                int ci = compIndex(k.charAt(0));
                return LuaValues.single(ci >= 0 && ci < u.dims ? LuaValue.of(u.getComp(ci)) : LuaValue.NIL);
            }
            if (k.length() >= 2 && k.length() <= 4 && isSwizzle(k)) {
                double[] c = new double[4];
                for (int i = 0; i < k.length(); i++) {
                    int ci = compIndex(k.charAt(i));
                    c[i] = ci >= 0 ? u.getComp(ci) : 0.0;
                }
                return LuaValues.single(new LuaUserdata(new Vec(k.length(), c[0], c[1], c[2], c[3]), mt));
            }
            return LuaValues.single(LuaValue.NIL);
        }));
        mt.rawSet("__newindex", LuaFunction.nativeFn("vec.__newindex", (v, a) -> {
            Vec u = asVec(a, 0);
            if (a[1] instanceof LuaValue.Num n) {
                int idx = (int) n.value() - 1;
                if (idx >= 0 && idx < u.dims) u.setComp(idx, a[2].asDouble());
                return LuaValues.empty();
            }
            String k = a[1].asString();
            if (k.length() == 1) {
                int ci = compIndex(k.charAt(0));
                if (ci >= 0 && ci < u.dims) u.setComp(ci, a[2].asDouble());
            }
            return LuaValues.empty();
        }));
        mt.rawSet("__add", LuaFunction.nativeFn("vec.__add", (v, a) -> binVec(a[0], a[1], mt, Double::sum)));
        mt.rawSet("__sub", LuaFunction.nativeFn("vec.__sub", (v, a) -> binVec(a[0], a[1], mt, (x, y) -> x - y)));
        mt.rawSet("__mul", LuaFunction.nativeFn("vec.__mul", (v, a) -> binVec(a[0], a[1], mt, (x, y) -> x * y)));
        mt.rawSet("__div", LuaFunction.nativeFn("vec.__div", (v, a) -> binVec(a[0], a[1], mt, (x, y) -> x / y)));
        mt.rawSet("__unm", LuaFunction.nativeFn("vec.__unm", (v, a) -> {
            Vec u = asVec(a, 0);
            return LuaValues.single(new LuaUserdata(new Vec(u.dims, -u.x, -u.y, -u.z, -u.w), mt));
        }));
        mt.rawSet("__tostring", LuaFunction.nativeFn("vec.__tostring", (v, a) -> {
            Vec u = asVec(a, 0);
            String s = "{" + LuaValues.formatNumber(u.x) + ", " + LuaValues.formatNumber(u.y)
                    + (u.dims >= 3 ? ", " + LuaValues.formatNumber(u.z) : "")
                    + (u.dims >= 4 ? ", " + LuaValues.formatNumber(u.w) : "") + "}";
            return LuaValues.single(LuaValue.of(s));
        }));
        mt.rawSet("__eq", LuaFunction.nativeFn("vec.__eq", (v, a) -> {
            Vec u = asVec(a, 0), w = asVec(a, 1);
            return LuaValues.single(LuaValue.of(u.dims == w.dims && u.x == w.x && u.y == w.y && u.z == w.z && u.w == w.w));
        }));
        return mt;
    }

    private static LuaTable buildMatMetatable(LuaVm vm) {
        LuaTable methods = new LuaTable(vm.budget());
        LuaTable mt = new LuaTable(vm.budget());
        methods.rawSet("copy", LuaFunction.nativeFn("mat:copy", (v, a) ->
                LuaValues.single(new LuaUserdata(((Mat4) ((LuaUserdata) a[0]).value()).copy(), mt))));
        methods.rawSet("translate", LuaFunction.nativeFn("mat:translate", (v, a) -> {
            Mat4 m = ((Mat4) ((LuaUserdata) a[0]).value()).copy();
            Vec tr = extractVec3(a, 1);
            Mat4 t = new Mat4(); t.m[12] = tr.x; t.m[13] = tr.y; t.m[14] = tr.z;
            m.mul(t);
            return LuaValues.single(new LuaUserdata(m, mt));
        }));
        methods.rawSet("scale", LuaFunction.nativeFn("mat:scale", (v, a) -> {
            Mat4 m = ((Mat4) ((LuaUserdata) a[0]).value()).copy();
            Vec sc = extractVec3(a, 1);
            Mat4 s = new Mat4(); s.m[0] = sc.x; s.m[5] = sc.y; s.m[10] = sc.z;
            m.mul(s);
            return LuaValues.single(new LuaUserdata(m, mt));
        }));
        mt.rawSet("__index", methods);
        mt.rawSet("__mul", LuaFunction.nativeFn("mat.__mul", (v, a) -> {
            Mat4 left = (Mat4) ((LuaUserdata) a[0]).value();
            Mat4 right = (Mat4) ((LuaUserdata) a[1]).value();
            Mat4 copy = left.copy();
            copy.mul(right);
            return LuaValues.single(new LuaUserdata(copy, mt));
        }));
        return mt;
    }

    @FunctionalInterface private interface ScalarOp { double apply(double a, double b); }

    private static LuaValue[] binVec(LuaValue lhs, LuaValue rhs, LuaTable mt, ScalarOp op) {
        if (lhs instanceof LuaUserdata l && l.value() instanceof Vec v1
                && rhs instanceof LuaUserdata r && r.value() instanceof Vec v2) {
            return LuaValues.single(new LuaUserdata(new Vec(Math.max(v1.dims, v2.dims),
                    op.apply(v1.x, v2.x), op.apply(v1.y, v2.y),
                    op.apply(v1.z, v2.z), op.apply(v1.w, v2.w)), mt));
        }
        if (lhs instanceof LuaUserdata l && l.value() instanceof Vec v1) {
            double s = rhs.asDouble();
            return LuaValues.single(new LuaUserdata(new Vec(v1.dims,
                    op.apply(v1.x, s), op.apply(v1.y, s), op.apply(v1.z, s), op.apply(v1.w, s)), mt));
        }
        if (rhs instanceof LuaUserdata r && r.value() instanceof Vec v2) {
            double s = lhs.asDouble();
            return LuaValues.single(new LuaUserdata(new Vec(v2.dims,
                    op.apply(s, v2.x), op.apply(s, v2.y), op.apply(s, v2.z), op.apply(s, v2.w)), mt));
        }
        throw new LuaException(LuaException.Reason.RUNTIME, "invalid vector arithmetic operands");
    }

    static Vec asVec(LuaValue[] a, int i) {
        LuaValue v = LuaValues.arg(a, i);
        if (v instanceof LuaUserdata ud && ud.value() instanceof Vec vec) return vec;
        throw new LuaException(LuaException.Reason.RUNTIME, "expected vector value");
    }

    private static int compIndex(char c) {
        return switch (c) {
            case 'x', 'r' -> 0;
            case 'y', 'g' -> 1;
            case 'z', 'b' -> 2;
            case 'w', 'a' -> 3;
            default -> -1;
        };
    }

    private static boolean isSwizzle(String k) {
        for (int i = 0; i < k.length(); i++) {
            if (compIndex(k.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static double d(LuaValue[] a, int i) {
        LuaValue v = LuaValues.arg(a, i);
        return v == LuaValue.NIL ? 0.0 : v.asDouble();
    }

    private static int clampByte(double v) {
        return Math.max(0, Math.min(255, (int) Math.round(v)));
    }
}
