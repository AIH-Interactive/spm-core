package dev.spm.core.lua;

import dev.spm.core.lua.LuaException.Reason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/** Sandboxed Lua standard library (base, math, string, table, bit32, bounded require). */
final class LuaStdLib {
    private LuaStdLib() {}

    static void install(LuaVm vm, LuaTable globals, LuaTable stringMetatable,
                        LuaVm.ModuleResolver moduleResolver, Consumer<String> printSink) {
        globals.rawSet("_G", globals);
        globals.rawSet("_VERSION", LuaValue.of("Lua 5.3"));

        installBase(vm, globals, moduleResolver, printSink);
        installMath(vm, globals);
        LuaTable strLib = installString(vm, globals);
        stringMetatable.rawSet("__index", strLib);
        installTable(vm, globals);
        installBit32(globals);
    }

    private static void installBase(LuaVm vm, LuaTable g, LuaVm.ModuleResolver moduleResolver,
                                    Consumer<String> printSink) {
        g.rawSet("type", LuaFunction.nativeFn("type", (v, args) -> {
            if (args.length == 0) throw new LuaException(Reason.RUNTIME, "bad argument #1 to 'type' (value expected)");
            return LuaValues.single(LuaValue.of(args[0].typeName()));
        }));
        g.rawSet("tostring", LuaFunction.nativeFn("tostring", (v, args) ->
                LuaValues.single(LuaValue.of(v.evaluator().toLuaString(LuaValues.arg(args, 0))))));
        g.rawSet("tonumber", LuaFunction.nativeFn("tonumber", (v, args) -> {
            LuaValue val = LuaValues.arg(args, 0);
            LuaValue baseArg = LuaValues.arg(args, 1);
            if (baseArg != LuaValue.NIL) {
                int base = (int) baseArg.asDouble();
                if (base < 2 || base > 36) throw new LuaException(Reason.RUNTIME, "base out of range");
                String s = val.asString().trim();
                try {
                    return LuaValues.single(LuaValue.of((double) Long.parseLong(s, base)));
                } catch (NumberFormatException ignored) {
                    return LuaValues.single(LuaValue.NIL);
                }
            }
            if (val instanceof LuaValue.Num) return LuaValues.single(val);
            if (val instanceof LuaValue.Str s) {
                Double parsed = LuaValues.parseNumberOrNull(s.value());
                return LuaValues.single(parsed == null ? LuaValue.NIL : LuaValue.of(parsed));
            }
            return LuaValues.single(LuaValue.NIL);
        }));
        g.rawSet("assert", LuaFunction.nativeFn("assert", (v, args) -> {
            if (!LuaValues.arg(args, 0).isTruthy()) {
                LuaValue msg = LuaValues.arg(args, 1);
                String text = msg == LuaValue.NIL ? "assertion failed!" : v.evaluator().toLuaString(msg);
                throw new LuaException(msg == LuaValue.NIL ? LuaValue.of(text) : msg, text);
            }
            return args;
        }));
        g.rawSet("error", LuaFunction.nativeFn("error", (v, args) -> {
            LuaValue obj = LuaValues.arg(args, 0);
            throw new LuaException(obj, v.evaluator().toLuaString(obj));
        }));
        g.rawSet("pcall", LuaFunction.nativeFn("pcall", (v, args) -> {
            LuaValue fn = LuaValues.arg(args, 0);
            LuaValue[] callArgs = args.length <= 1 ? LuaValues.empty()
                    : java.util.Arrays.copyOfRange(args, 1, args.length);
            try {
                LuaValue[] res = v.evaluator().callValue(fn, callArgs, 1);
                LuaValue[] out = new LuaValue[res.length + 1];
                out[0] = LuaValue.TRUE;
                System.arraycopy(res, 0, out, 1, res.length);
                return out;
            } catch (LuaException ex) {
                if (ex.reason() == Reason.BUDGET || ex.reason() == Reason.MEMORY || ex.reason() == Reason.SECURITY) {
                    throw ex; // Sandbox limits cannot be swallowed by pcall.
                }
                LuaValue errVal = ex.errorObject() != null ? ex.errorObject() : LuaValue.of(ex.getMessage());
                return new LuaValue[] {LuaValue.FALSE, errVal};
            }
        }));
        g.rawSet("xpcall", LuaFunction.nativeFn("xpcall", (v, args) -> {
            LuaValue fn = LuaValues.arg(args, 0);
            LuaValue handler = LuaValues.arg(args, 1);
            LuaValue[] callArgs = args.length <= 2 ? LuaValues.empty()
                    : java.util.Arrays.copyOfRange(args, 2, args.length);
            try {
                LuaValue[] res = v.evaluator().callValue(fn, callArgs, 1);
                LuaValue[] out = new LuaValue[res.length + 1];
                out[0] = LuaValue.TRUE;
                System.arraycopy(res, 0, out, 1, res.length);
                return out;
            } catch (LuaException ex) {
                if (ex.reason() == Reason.BUDGET || ex.reason() == Reason.MEMORY || ex.reason() == Reason.SECURITY) {
                    throw ex;
                }
                LuaValue errVal = ex.errorObject() != null ? ex.errorObject() : LuaValue.of(ex.getMessage());
                LuaValue handled = LuaValues.first(v.evaluator().callValue(handler, new LuaValue[] {errVal}, 1));
                return new LuaValue[] {LuaValue.FALSE, handled};
            }
        }));
        g.rawSet("next", LuaFunction.nativeFn("next", (v, args) ->
                LuaValues.arg(args, 0).asTable().nextPair(LuaValues.arg(args, 1))));
        g.rawSet("pairs", LuaFunction.nativeFn("pairs", (v, args) -> {
            LuaValue target = LuaValues.arg(args, 0);
            LuaValue mm = v.evaluator().metamethod(target, "__pairs");
            if (mm != LuaValue.NIL) return v.evaluator().callValue(mm, new LuaValue[] {target}, 1);
            LuaTable t = target.asTable();
            return new LuaValue[] {g.rawGet("next"), t, LuaValue.NIL};
        }));
        LuaFunction ipairsNext = LuaFunction.nativeFn("ipairs_next", (v, args) -> {
            LuaValue t = LuaValues.arg(args, 0);
            long nextIdx = (long) LuaValues.arg(args, 1).asDouble() + 1L;
            LuaValue val = v.evaluator().getIndex(t, LuaValue.of(nextIdx), 1);
            if (val == LuaValue.NIL) return new LuaValue[] {LuaValue.NIL};
            return new LuaValue[] {LuaValue.of(nextIdx), val};
        });
        g.rawSet("ipairs", LuaFunction.nativeFn("ipairs", (v, args) -> {
            LuaValue target = LuaValues.arg(args, 0);
            LuaValue mm = v.evaluator().metamethod(target, "__ipairs");
            if (mm != LuaValue.NIL) return v.evaluator().callValue(mm, new LuaValue[] {target}, 1);
            return new LuaValue[] {ipairsNext, target, LuaValue.of(0L)};
        }));
        g.rawSet("select", LuaFunction.nativeFn("select", (v, args) -> {
            LuaValue idx = LuaValues.arg(args, 0);
            if (idx instanceof LuaValue.Str s && s.value().equals("#")) {
                return LuaValues.single(LuaValue.of(Math.max(0L, args.length - 1L)));
            }
            int i = (int) idx.asDouble();
            int n = Math.max(0, args.length - 1);
            int start = i < 0 ? Math.max(1, n + i + 1) : Math.max(1, i);
            if (start > n) return LuaValues.empty();
            return java.util.Arrays.copyOfRange(args, start, args.length);
        }));
        g.rawSet("getmetatable", LuaFunction.nativeFn("getmetatable", (v, args) -> {
            LuaTable mt = v.evaluator().metatableOf(LuaValues.arg(args, 0));
            if (mt == null) return LuaValues.single(LuaValue.NIL);
            LuaValue protectedMt = mt.rawGet("__metatable");
            return LuaValues.single(protectedMt != LuaValue.NIL ? protectedMt : mt);
        }));
        g.rawSet("setmetatable", LuaFunction.nativeFn("setmetatable", (v, args) -> {
            LuaTable t = LuaValues.arg(args, 0).asTable();
            LuaTable existing = t.metatable();
            if (existing != null && existing.rawGet("__metatable") != LuaValue.NIL) {
                throw new LuaException(Reason.RUNTIME, "cannot change a protected metatable");
            }
            LuaValue mtVal = LuaValues.arg(args, 1);
            t.setMetatable(mtVal == LuaValue.NIL ? null : mtVal.asTable());
            return LuaValues.single(t);
        }));
        g.rawSet("rawget", LuaFunction.nativeFn("rawget", (v, args) ->
                LuaValues.single(LuaValues.arg(args, 0).asTable().rawGet(LuaValues.arg(args, 1)))));
        g.rawSet("rawset", LuaFunction.nativeFn("rawset", (v, args) -> {
            LuaTable t = LuaValues.arg(args, 0).asTable();
            t.rawSet(LuaValues.arg(args, 1), LuaValues.arg(args, 2));
            return LuaValues.single(t);
        }));
        g.rawSet("rawequal", LuaFunction.nativeFn("rawequal", (v, args) -> {
            LuaValue a = LuaValues.arg(args, 0), b = LuaValues.arg(args, 1);
            boolean eq = (a == b && !(a instanceof LuaValue.Num n && Double.isNaN(n.value())))
                    || (a.getClass() == b.getClass() && !(a instanceof LuaTable) && !(a instanceof LuaUserdata) && a.equals(b));
            return LuaValues.single(LuaValue.of(eq));
        }));
        g.rawSet("rawlen", LuaFunction.nativeFn("rawlen", (v, args) -> {
            LuaValue val = LuaValues.arg(args, 0);
            if (val instanceof LuaValue.Str s) return LuaValues.single(LuaValue.of((long) s.value().length()));
            return LuaValues.single(LuaValue.of((long) val.asTable().rawLen()));
        }));
        g.rawSet("print", LuaFunction.nativeFn("print", (v, args) -> {
            if (printSink != null) {
                var sb = new StringBuilder();
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append('\t');
                    sb.append(v.evaluator().toLuaString(args[i]));
                }
                printSink.accept(sb.toString());
            }
            return LuaValues.empty();
        }));
        g.rawSet("load", LuaFunction.nativeFn("load", (v, args) -> {
            String src = LuaValues.arg(args, 0).asString();
            String name = LuaValues.arg(args, 1) == LuaValue.NIL ? "=(load)" : LuaValues.arg(args, 1).asString();
            String mode = LuaValues.arg(args, 2) == LuaValue.NIL ? "t" : LuaValues.arg(args, 2).asString();
            if (!mode.contains("t")) {
                return new LuaValue[] {LuaValue.NIL, LuaValue.of("binary Lua chunks are disabled")};
            }
            LuaTable env = LuaValues.arg(args, 3) instanceof LuaTable t ? t : null;
            try {
                return LuaValues.single(v.compile(name, src, env));
            } catch (LuaException ex) {
                return new LuaValue[] {LuaValue.NIL, LuaValue.of(ex.getMessage())};
            }
        }));

        LuaTable loaded = new LuaTable(vm.budget());
        LuaTable pkg = new LuaTable(vm.budget());
        pkg.rawSet("loaded", loaded);
        g.rawSet("package", pkg);

        Set<String> loading = new HashSet<>();
        g.rawSet("require", LuaFunction.nativeFn("require", (v, args) -> {
            String mod = LuaValues.arg(args, 0).asString();
            LuaValue existing = loaded.rawGet(mod);
            if (existing != LuaValue.NIL) return LuaValues.single(existing);
            if (!loading.add(mod)) {
                throw new LuaException(Reason.RUNTIME, "circular require for module '" + mod + "'");
            }
            try {
                String source = moduleResolver == null ? null : moduleResolver.resolve(mod);
                if (source == null) {
                    throw new LuaException(Reason.RUNTIME, "module '" + mod + "' not found");
                }
                LuaValue res = LuaValues.first(v.execute(mod, source));
                LuaValue stored = res == LuaValue.NIL ? LuaValue.TRUE : res;
                loaded.rawSet(mod, stored);
                return LuaValues.single(stored);
            } finally {
                loading.remove(mod);
            }
        }));
    }

    private static void installMath(LuaVm vm, LuaTable g) {
        LuaTable m = new LuaTable(vm.budget());
        m.rawSet("pi", LuaValue.of(Math.PI));
        m.rawSet("huge", LuaValue.of(Double.POSITIVE_INFINITY));
        m.rawSet("maxinteger", LuaValue.of((double) Long.MAX_VALUE));
        m.rawSet("mininteger", LuaValue.of((double) Long.MIN_VALUE));
        m.rawSet("abs", LuaFunction.nativeFn("math.abs", (v, a) -> LuaValues.single(LuaValue.of(Math.abs(a0(a))))));
        m.rawSet("acos", LuaFunction.nativeFn("math.acos", (v, a) -> LuaValues.single(LuaValue.of(Math.acos(a0(a))))));
        m.rawSet("asin", LuaFunction.nativeFn("math.asin", (v, a) -> LuaValues.single(LuaValue.of(Math.asin(a0(a))))));
        m.rawSet("atan", LuaFunction.nativeFn("math.atan", (v, a) -> LuaValues.single(LuaValue.of(
                a.length >= 2 ? Math.atan2(a0(a), a[1].asDouble()) : Math.atan(a0(a))))));
        m.rawSet("atan2", LuaFunction.nativeFn("math.atan2", (v, a) -> LuaValues.single(LuaValue.of(Math.atan2(a0(a), a1(a))))));
        m.rawSet("ceil", LuaFunction.nativeFn("math.ceil", (v, a) -> LuaValues.single(LuaValue.of(Math.ceil(a0(a))))));
        m.rawSet("cos", LuaFunction.nativeFn("math.cos", (v, a) -> LuaValues.single(LuaValue.of(Math.cos(a0(a))))));
        m.rawSet("cosh", LuaFunction.nativeFn("math.cosh", (v, a) -> LuaValues.single(LuaValue.of(Math.cosh(a0(a))))));
        m.rawSet("deg", LuaFunction.nativeFn("math.deg", (v, a) -> LuaValues.single(LuaValue.of(Math.toDegrees(a0(a))))));
        m.rawSet("exp", LuaFunction.nativeFn("math.exp", (v, a) -> LuaValues.single(LuaValue.of(Math.exp(a0(a))))));
        m.rawSet("floor", LuaFunction.nativeFn("math.floor", (v, a) -> LuaValues.single(LuaValue.of(Math.floor(a0(a))))));
        m.rawSet("fmod", LuaFunction.nativeFn("math.fmod", (v, a) -> LuaValues.single(LuaValue.of(a0(a) % a1(a)))));
        m.rawSet("log", LuaFunction.nativeFn("math.log", (v, a) -> {
            double x = Math.log(a0(a));
            if (a.length >= 2) x /= Math.log(a[1].asDouble());
            return LuaValues.single(LuaValue.of(x));
        }));
        m.rawSet("max", LuaFunction.nativeFn("math.max", (v, a) -> {
            if (a.length == 0) throw new LuaException(Reason.RUNTIME, "bad argument #1 to 'max'");
            double best = a[0].asDouble();
            for (int i = 1; i < a.length; i++) best = Math.max(best, a[i].asDouble());
            return LuaValues.single(LuaValue.of(best));
        }));
        m.rawSet("min", LuaFunction.nativeFn("math.min", (v, a) -> {
            if (a.length == 0) throw new LuaException(Reason.RUNTIME, "bad argument #1 to 'min'");
            double best = a[0].asDouble();
            for (int i = 1; i < a.length; i++) best = Math.min(best, a[i].asDouble());
            return LuaValues.single(LuaValue.of(best));
        }));
        m.rawSet("modf", LuaFunction.nativeFn("math.modf", (v, a) -> {
            double x = a0(a);
            double ip = x < 0 ? Math.ceil(x) : Math.floor(x);
            return new LuaValue[] {LuaValue.of(ip), LuaValue.of(x - ip)};
        }));
        m.rawSet("pow", LuaFunction.nativeFn("math.pow", (v, a) -> LuaValues.single(LuaValue.of(Math.pow(a0(a), a1(a))))));
        m.rawSet("rad", LuaFunction.nativeFn("math.rad", (v, a) -> LuaValues.single(LuaValue.of(Math.toRadians(a0(a))))));
        m.rawSet("sin", LuaFunction.nativeFn("math.sin", (v, a) -> LuaValues.single(LuaValue.of(Math.sin(a0(a))))));
        m.rawSet("sinh", LuaFunction.nativeFn("math.sinh", (v, a) -> LuaValues.single(LuaValue.of(Math.sinh(a0(a))))));
        m.rawSet("sqrt", LuaFunction.nativeFn("math.sqrt", (v, a) -> LuaValues.single(LuaValue.of(Math.sqrt(a0(a))))));
        m.rawSet("tan", LuaFunction.nativeFn("math.tan", (v, a) -> LuaValues.single(LuaValue.of(Math.tan(a0(a))))));
        m.rawSet("tanh", LuaFunction.nativeFn("math.tanh", (v, a) -> LuaValues.single(LuaValue.of(Math.tanh(a0(a))))));
        // Common Figura helpers
        m.rawSet("clamp", LuaFunction.nativeFn("math.clamp", (v, a) -> LuaValues.single(LuaValue.of(
                Math.max(a1(a), Math.min(LuaValues.arg(a, 2).asDouble(), a0(a)))))));
        m.rawSet("lerp", LuaFunction.nativeFn("math.lerp", (v, a) -> {
            double x = a0(a), y = a1(a), t = LuaValues.arg(a, 2).asDouble();
            return LuaValues.single(LuaValue.of(x + (y - x) * t));
        }));
        m.rawSet("round", LuaFunction.nativeFn("math.round", (v, a) -> LuaValues.single(LuaValue.of((double) Math.round(a0(a))))));
        m.rawSet("sign", LuaFunction.nativeFn("math.sign", (v, a) -> LuaValues.single(LuaValue.of(Math.signum(a0(a))))));

        final Random[] rng = {new Random(0x5EEDL)};
        m.rawSet("randomseed", LuaFunction.nativeFn("math.randomseed", (v, a) -> {
            rng[0] = new Random((long) a0(a));
            return LuaValues.empty();
        }));
        m.rawSet("random", LuaFunction.nativeFn("math.random", (v, a) -> {
            if (a.length == 0) return LuaValues.single(LuaValue.of(rng[0].nextDouble()));
            long lo = a.length == 1 ? 1L : (long) a0(a);
            long hi = a.length == 1 ? (long) a0(a) : (long) a1(a);
            if (hi < lo) throw new LuaException(Reason.RUNTIME, "bad argument to 'random' (interval is empty)");
            long bound = hi - lo + 1L;
            return LuaValues.single(LuaValue.of((double) (lo + (Math.abs(rng[0].nextLong()) % bound))));
        }));
        g.rawSet("math", m);
    }

    private static LuaTable installString(LuaVm vm, LuaTable g) {
        LuaTable s = new LuaTable(vm.budget());
        s.rawSet("len", LuaFunction.nativeFn("string.len", (v, a) -> LuaValues.single(LuaValue.of((long) s0(a).length()))));
        s.rawSet("lower", LuaFunction.nativeFn("string.lower", (v, a) -> LuaValues.single(LuaValue.of(s0(a).toLowerCase(Locale.ROOT)))));
        s.rawSet("upper", LuaFunction.nativeFn("string.upper", (v, a) -> LuaValues.single(LuaValue.of(s0(a).toUpperCase(Locale.ROOT)))));
        s.rawSet("reverse", LuaFunction.nativeFn("string.reverse", (v, a) ->
                LuaValues.single(LuaValue.of(new StringBuilder(s0(a)).reverse().toString()))));
        s.rawSet("rep", LuaFunction.nativeFn("string.rep", (v, a) -> {
            String base = s0(a);
            int n = Math.max(0, (int) a1(a));
            String sep = LuaValues.arg(a, 2) == LuaValue.NIL ? "" : LuaValues.arg(a, 2).asString();
            long est = Math.multiplyExact((long) n, (long) (base.length() + sep.length()));
            v.budget().chargeMemory(est * 2L);
            if (n == 0) return LuaValues.single(LuaValue.of(""));
            var sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                v.budget().step();
                if (i > 0) sb.append(sep);
                sb.append(base);
            }
            return LuaValues.single(LuaValue.of(sb.toString()));
        }));
        s.rawSet("sub", LuaFunction.nativeFn("string.sub", (v, a) -> {
            String str = s0(a);
            int len = str.length();
            int i = LuaPatterns.relativePos((int) a1(a), len);
            int j = LuaValues.arg(a, 2) == LuaValue.NIL ? len : (int) LuaValues.arg(a, 2).asDouble();
            int end = j >= 0 ? Math.min(len, j) : Math.max(0, len + j + 1);
            int start = Math.max(0, i);
            if (start >= end || start >= len) return LuaValues.single(LuaValue.of(""));
            return LuaValues.single(LuaValue.of(str.substring(start, end)));
        }));
        s.rawSet("byte", LuaFunction.nativeFn("string.byte", (v, a) -> {
            String str = s0(a);
            int len = str.length();
            int i = LuaValues.arg(a, 1) == LuaValue.NIL ? 0 : LuaPatterns.relativePos((int) a1(a), len);
            int jArg = LuaValues.arg(a, 2) == LuaValue.NIL ? i + 1 : (int) LuaValues.arg(a, 2).asDouble();
            int end = jArg >= 0 ? Math.min(len, jArg) : Math.max(0, len + jArg + 1);
            int start = Math.max(0, i);
            if (start >= end) return LuaValues.empty();
            var out = new LuaValue[end - start];
            for (int k = start; k < end; k++) out[k - start] = LuaValue.of((long) (str.charAt(k) & 0xFFFF));
            return out;
        }));
        s.rawSet("char", LuaFunction.nativeFn("string.char", (v, a) -> {
            var sb = new StringBuilder(a.length);
            for (LuaValue arg : a) sb.append((char) ((int) arg.asDouble() & 0xFF));
            return LuaValues.single(LuaValue.of(sb.toString()));
        }));
        s.rawSet("find", LuaFunction.nativeFn("string.find", (v, a) -> LuaPatterns.find(
                v, s0(a), LuaValues.arg(a, 1).asString(),
                LuaValues.arg(a, 2) == LuaValue.NIL ? 1 : (int) LuaValues.arg(a, 2).asDouble(),
                LuaValues.arg(a, 3).isTruthy())));
        s.rawSet("match", LuaFunction.nativeFn("string.match", (v, a) -> LuaPatterns.match(
                v, s0(a), LuaValues.arg(a, 1).asString(),
                LuaValues.arg(a, 2) == LuaValue.NIL ? 1 : (int) LuaValues.arg(a, 2).asDouble())));
        s.rawSet("gmatch", LuaFunction.nativeFn("string.gmatch", (v, a) ->
                LuaValues.single(LuaPatterns.gmatch(v, s0(a), LuaValues.arg(a, 1).asString()))));
        s.rawSet("gsub", LuaFunction.nativeFn("string.gsub", (v, a) -> LuaPatterns.gsub(
                v, s0(a), LuaValues.arg(a, 1).asString(), LuaValues.arg(a, 2),
                LuaValues.arg(a, 3) == LuaValue.NIL ? Integer.MAX_VALUE : (int) LuaValues.arg(a, 3).asDouble())));
        s.rawSet("format", LuaFunction.nativeFn("string.format", (v, a) ->
                LuaValues.single(LuaValue.of(LuaPatterns.format(v, s0(a), a)))));
        g.rawSet("string", s);
        return s;
    }

    private static void installTable(LuaVm vm, LuaTable g) {
        LuaTable t = new LuaTable(vm.budget());
        t.rawSet("insert", LuaFunction.nativeFn("table.insert", (v, a) -> {
            LuaTable tbl = a[0].asTable();
            int len = tbl.rawLen();
            if (a.length == 2) {
                tbl.rawSet(len + 1, a[1]);
                return LuaValues.empty();
            }
            int pos = (int) a[1].asDouble();
            LuaValue val = LuaValues.arg(a, 2);
            for (int i = len; i >= pos; i--) {
                v.budget().step();
                tbl.rawSet(i + 1, tbl.rawGet(i));
            }
            tbl.rawSet(pos, val);
            return LuaValues.empty();
        }));
        t.rawSet("remove", LuaFunction.nativeFn("table.remove", (v, a) -> {
            LuaTable tbl = a[0].asTable();
            int len = tbl.rawLen();
            int pos = a.length < 2 || a[1] == LuaValue.NIL ? len : (int) a[1].asDouble();
            if (len == 0 && (pos == 0 || pos == 1)) return LuaValues.single(LuaValue.NIL);
            LuaValue removed = tbl.rawGet(pos);
            for (int i = pos; i < len; i++) {
                v.budget().step();
                tbl.rawSet(i, tbl.rawGet(i + 1));
            }
            tbl.rawSet(len, LuaValue.NIL);
            return LuaValues.single(removed);
        }));
        t.rawSet("concat", LuaFunction.nativeFn("table.concat", (v, a) -> {
            LuaTable tbl = a[0].asTable();
            String sep = LuaValues.arg(a, 1) == LuaValue.NIL ? "" : LuaValues.arg(a, 1).asString();
            int i = LuaValues.arg(a, 2) == LuaValue.NIL ? 1 : (int) LuaValues.arg(a, 2).asDouble();
            int j = LuaValues.arg(a, 3) == LuaValue.NIL ? tbl.rawLen() : (int) LuaValues.arg(a, 3).asDouble();
            var sb = new StringBuilder();
            for (int k = i; k <= j; k++) {
                v.budget().step();
                if (k > i) sb.append(sep);
                LuaValue elem = tbl.rawGet(k);
                sb.append(elem.asString());
            }
            String out = sb.toString();
            v.budget().chargeMemory((long) out.length() * 2L);
            return LuaValues.single(LuaValue.of(out));
        }));
        t.rawSet("pack", LuaFunction.nativeFn("table.pack", (v, a) -> {
            LuaTable packed = new LuaTable(v.budget());
            for (int i = 0; i < a.length; i++) packed.rawSet(i + 1, a[i]);
            packed.rawSet("n", LuaValue.of((long) a.length));
            return LuaValues.single(packed);
        }));
        LuaFunction unpackFn = LuaFunction.nativeFn("unpack", (v, a) -> {
            LuaTable tbl = a[0].asTable();
            int i = LuaValues.arg(a, 1) == LuaValue.NIL ? 1 : (int) LuaValues.arg(a, 1).asDouble();
            int j = LuaValues.arg(a, 2) == LuaValue.NIL ? tbl.rawLen() : (int) LuaValues.arg(a, 2).asDouble();
            if (i > j) return LuaValues.empty();
            var list = new LuaValue[j - i + 1];
            for (int k = i; k <= j; k++) {
                v.budget().step();
                list[k - i] = tbl.rawGet(k);
            }
            return list;
        });
        t.rawSet("unpack", unpackFn);
        g.rawSet("unpack", unpackFn);
        t.rawSet("sort", LuaFunction.nativeFn("table.sort", (v, a) -> {
            LuaTable tbl = a[0].asTable();
            int len = tbl.rawLen();
            if (len <= 1) return LuaValues.empty();
            LuaFunction comp = a.length >= 2 && a[1] != LuaValue.NIL ? (LuaFunction) a[1] : null;
            var items = new ArrayList<LuaValue>(len);
            for (int i = 1; i <= len; i++) items.add(tbl.rawGet(i));
            Comparator<LuaValue> cmp = (x, y) -> {
                v.budget().step();
                if (comp != null) {
                    boolean lt = LuaValues.first(comp.call(v, new LuaValue[] {x, y})).isTruthy();
                    if (lt) return -1;
                    boolean gt = LuaValues.first(comp.call(v, new LuaValue[] {y, x})).isTruthy();
                    return gt ? 1 : 0;
                }
                if (v.evaluator().luaEquals(x, y, 1)) return 0;
                return v.evaluator().luaLessThan(x, y, 1) ? -1 : 1;
            };
            items.sort(cmp);
            for (int i = 1; i <= len; i++) tbl.rawSet(i, items.get(i - 1));
            return LuaValues.empty();
        }));
        g.rawSet("table", t);
    }

    private static void installBit32(LuaTable g) {
        LuaTable b = new LuaTable();
        b.rawSet("band", LuaFunction.nativeFn("bit32.band", (v, a) -> {
            int acc = -1;
            for (LuaValue val : a) acc &= u32(val);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(acc)));
        }));
        b.rawSet("bor", LuaFunction.nativeFn("bit32.bor", (v, a) -> {
            int acc = 0;
            for (LuaValue val : a) acc |= u32(val);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(acc)));
        }));
        b.rawSet("bxor", LuaFunction.nativeFn("bit32.bxor", (v, a) -> {
            int acc = 0;
            for (LuaValue val : a) acc ^= u32(val);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(acc)));
        }));
        b.rawSet("bnot", LuaFunction.nativeFn("bit32.bnot", (v, a) ->
                LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(~u32(a[0]))))));
        b.rawSet("lshift", LuaFunction.nativeFn("bit32.lshift", (v, a) -> {
            int x = u32(a[0]), disp = (int) a[1].asDouble();
            int r = Math.abs(disp) >= 32 ? 0 : (disp >= 0 ? x << disp : x >>> -disp);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(r)));
        }));
        b.rawSet("rshift", LuaFunction.nativeFn("bit32.rshift", (v, a) -> {
            int x = u32(a[0]), disp = (int) a[1].asDouble();
            int r = Math.abs(disp) >= 32 ? 0 : (disp >= 0 ? x >>> disp : x << -disp);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(r)));
        }));
        b.rawSet("arshift", LuaFunction.nativeFn("bit32.arshift", (v, a) -> {
            int x = u32(a[0]), disp = (int) a[1].asDouble();
            int r = disp >= 0 ? (disp >= 32 ? (x < 0 ? -1 : 0) : x >> disp) : (disp <= -32 ? 0 : x << -disp);
            return LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(r)));
        }));
        b.rawSet("btest", LuaFunction.nativeFn("bit32.btest", (v, a) -> {
            int acc = -1;
            for (LuaValue val : a) acc &= u32(val);
            return LuaValues.single(LuaValue.of(acc != 0));
        }));
        b.rawSet("lrotate", LuaFunction.nativeFn("bit32.lrotate", (v, a) ->
                LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(Integer.rotateLeft(u32(a[0]), (int) a[1].asDouble()))))));
        b.rawSet("rrotate", LuaFunction.nativeFn("bit32.rrotate", (v, a) ->
                LuaValues.single(LuaValue.of((double) Integer.toUnsignedLong(Integer.rotateRight(u32(a[0]), (int) a[1].asDouble()))))));
        g.rawSet("bit32", b);
    }

    private static int u32(LuaValue v) { return (int) (long) v.asDouble(); }
    private static double a0(LuaValue[] a) { return LuaValues.arg(a, 0).asDouble(); }
    private static double a1(LuaValue[] a) { return LuaValues.arg(a, 1).asDouble(); }
    private static String s0(LuaValue[] a) { return LuaValues.arg(a, 0).asString(); }
}
