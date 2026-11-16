package dev.spm.core.lua;

import dev.spm.core.lua.LuaAst.*;
import java.util.ArrayList;
import java.util.List;

/** Tree-walking bounded Lua AST evaluator with closures, multi-return, and metamethods. */
final class LuaEvaluator {
    private static final int MAX_METATABLE_CHAIN = 64;

    private static final class BreakSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final BreakSignal INSTANCE = new BreakSignal();
        private BreakSignal() { super(null, null, false, false); }
    }

    private static final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient LuaValue[] values;
        ReturnSignal(LuaValue[] values) {
            super(null, null, false, false);
            this.values = values;
        }
    }

    private final LuaVm vm;
    private final LuaBudget budget;

    LuaEvaluator(LuaVm vm, LuaBudget budget) {
        this.vm = vm;
        this.budget = budget;
    }

    LuaValue[] executeChunk(Chunk chunk, LuaTable envOverride) {
        LuaEnv root = new LuaEnv(null, LuaValues.empty());
        if (envOverride != null) root.define("_ENV", envOverride);
        budget.enterCall();
        try {
            execBlock(chunk.statements(), root);
            return LuaValues.empty();
        } catch (ReturnSignal ret) {
            return ret.values;
        } finally {
            budget.leaveCall();
        }
    }

    LuaValue[] invokeClosure(LuaClosure closure, LuaValue[] args) {
        FunctionExpr fn = closure.ast();
        LuaEnv frame = new LuaEnv(closure.closureEnv(), extraVarargs(fn, args));
        List<String> params = fn.params();
        for (int i = 0; i < params.size(); i++) {
            frame.define(params.get(i), LuaValues.arg(args, i));
        }
        budget.enterCall();
        try {
            execBlock(fn.body(), frame);
            return LuaValues.empty();
        } catch (ReturnSignal ret) {
            return ret.values;
        } finally {
            budget.leaveCall();
        }
    }

    LuaValue[] callValue(LuaValue callee, LuaValue[] args, int line) {
        if (callee instanceof LuaFunction fn) {
            return fn.call(vm, args);
        }
        LuaValue mm = metamethod(callee, "__call");
        if (mm != LuaValue.NIL) {
            LuaValue[] shifted = new LuaValue[args.length + 1];
            shifted[0] = callee;
            System.arraycopy(args, 0, shifted, 1, args.length);
            return callValue(mm, shifted, line);
        }
        throw runtimeError(line, "attempt to call a " + callee.typeName() + " value");
    }

    LuaValue getIndex(LuaValue target, LuaValue key, int line) {
        LuaValue cur = target;
        for (int depth = 0; depth < MAX_METATABLE_CHAIN; depth++) {
            budget.step();
            if (cur instanceof LuaTable t) {
                LuaValue direct = t.rawGet(key);
                if (direct != LuaValue.NIL) return direct;
            }
            LuaValue mm = metamethod(cur, "__index");
            if (mm == LuaValue.NIL) {
                if (cur instanceof LuaTable) return LuaValue.NIL;
                throw runtimeError(line, "attempt to index a " + cur.typeName() + " value");
            }
            if (mm instanceof LuaFunction fn) {
                return LuaValues.first(fn.call(vm, new LuaValue[] {cur, key}));
            }
            cur = mm;
        }
        throw runtimeError(line, "'__index' chain too long; possible loop");
    }

    void setIndex(LuaValue target, LuaValue key, LuaValue value, int line) {
        LuaValue cur = target;
        for (int depth = 0; depth < MAX_METATABLE_CHAIN; depth++) {
            budget.step();
            if (cur instanceof LuaTable t) {
                if (t.rawGet(key) != LuaValue.NIL) {
                    t.rawSet(key, value);
                    return;
                }
                LuaValue mm = metamethod(cur, "__newindex");
                if (mm == LuaValue.NIL) {
                    t.rawSet(key, value);
                    return;
                }
                if (mm instanceof LuaFunction fn) {
                    fn.call(vm, new LuaValue[] {cur, key, value});
                    return;
                }
                cur = mm;
            } else {
                LuaValue mm = metamethod(cur, "__newindex");
                if (mm == LuaValue.NIL) {
                    throw runtimeError(line, "attempt to index a " + cur.typeName() + " value");
                }
                if (mm instanceof LuaFunction fn) {
                    fn.call(vm, new LuaValue[] {cur, key, value});
                    return;
                }
                cur = mm;
            }
        }
        throw runtimeError(line, "'__newindex' chain too long; possible loop");
    }

    String toLuaString(LuaValue v) {
        LuaValue mm = metamethod(v, "__tostring");
        if (mm instanceof LuaFunction fn) {
            LuaValue res = LuaValues.first(fn.call(vm, new LuaValue[] {v}));
            return res instanceof LuaValue.Str s ? s.value() : res.toString();
        }
        if (v instanceof LuaValue.Nil) return "nil";
        if (v instanceof LuaValue.Bool b) return b.value() ? "true" : "false";
        if (v instanceof LuaValue.Num n) return LuaValues.formatNumber(n.value());
        if (v instanceof LuaValue.Str s) return s.value();
        return v.toString();
    }

    private void execBlock(List<Stmt> stmts, LuaEnv parentEnv) {
        LuaEnv scope = new LuaEnv(parentEnv, null);
        for (Stmt s : stmts) {
            execStmt(s, scope);
        }
    }

    private void execStmt(Stmt stmt, LuaEnv env) {
        budget.step();
        if (stmt instanceof EmptyStmt) return;
        if (stmt instanceof LocalStmt loc) execLocal(loc, env);
        else if (stmt instanceof LocalFuncStmt lf) {
            env.define(lf.name(), LuaValue.NIL);
            budget.chargeMemory(32L);
            env.findLocal(lf.name()).value = new LuaClosure(lf.function(), env);
        }
        else if (stmt instanceof AssignStmt assign) execAssign(assign, env);
        else if (stmt instanceof ExprStmt e) evalExprMulti(e.expr(), env);
        else if (stmt instanceof DoStmt d) execBlock(d.body(), env);
        else if (stmt instanceof WhileStmt w) execWhile(w, env);
        else if (stmt instanceof RepeatStmt r) execRepeat(r, env);
        else if (stmt instanceof IfStmt i) execIf(i, env);
        else if (stmt instanceof NumericForStmt nf) execNumericFor(nf, env);
        else if (stmt instanceof GenericForStmt gf) execGenericFor(gf, env);
        else if (stmt instanceof BreakStmt) throw BreakSignal.INSTANCE;
        else if (stmt instanceof ReturnStmt ret) throw new ReturnSignal(evalExprList(ret.values(), env));
    }

    private void execLocal(LocalStmt loc, LuaEnv env) {
        LuaValue[] vals = evalExprList(loc.values(), env);
        List<String> names = loc.names();
        for (int i = 0; i < names.size(); i++) {
            env.define(names.get(i), LuaValues.arg(vals, i));
        }
    }

    private void execAssign(AssignStmt assign, LuaEnv env) {
        List<LValue> targets = assign.targets();
        // Evaluate target containers before RHS per Lua spec, then evaluate RHS, then assign right-to-left.
        var resolvedTables = new LuaValue[targets.size()];
        var resolvedKeys = new LuaValue[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) instanceof IndexTarget idx) {
                resolvedTables[i] = evalSingle(idx.table(), env);
                resolvedKeys[i] = evalSingle(idx.key(), env);
            }
        }
        LuaValue[] rhs = evalExprList(assign.values(), env);
        for (int i = targets.size() - 1; i >= 0; i--) {
            LuaValue val = LuaValues.arg(rhs, i);
            LValue target = targets.get(i);
            if (target instanceof VarTarget v) {
                assignVar(v.name(), val, env, v.line());
            } else {
                IndexTarget idx = (IndexTarget) target;
                setIndex(resolvedTables[i], resolvedKeys[i], val, idx.line());
            }
        }
    }

    private void execWhile(WhileStmt w, LuaEnv env) {
        try {
            while (evalSingle(w.condition(), env).isTruthy()) {
                budget.step();
                execBlock(w.body(), env);
            }
        } catch (BreakSignal ignored) {}
    }

    private void execRepeat(RepeatStmt r, LuaEnv env) {
        try {
            while (true) {
                budget.step();
                LuaEnv loopScope = new LuaEnv(env, null);
                for (Stmt s : r.body()) execStmt(s, loopScope);
                if (evalSingle(r.condition(), loopScope).isTruthy()) break;
            }
        } catch (BreakSignal ignored) {}
    }

    private void execIf(IfStmt ifs, LuaEnv env) {
        for (IfBranch b : ifs.branches()) {
            if (evalSingle(b.condition(), env).isTruthy()) {
                execBlock(b.body(), env);
                return;
            }
        }
        if (!ifs.elseBody().isEmpty()) {
            execBlock(ifs.elseBody(), env);
        }
    }

    private void execNumericFor(NumericForStmt nf, LuaEnv env) {
        double init = toLoopNumber(evalSingle(nf.start(), env), "initial value", nf.line());
        double limit = toLoopNumber(evalSingle(nf.limit(), env), "limit", nf.line());
        double step = toLoopNumber(evalSingle(nf.step(), env), "step", nf.line());
        if (step == 0.0) throw runtimeError(nf.line(), "'for' step is zero");
        try {
            double v = init;
            while ((step > 0.0 && v <= limit) || (step < 0.0 && v >= limit)) {
                budget.step();
                LuaEnv iterEnv = new LuaEnv(env, null);
                iterEnv.define(nf.var(), LuaValue.of(v));
                execBlock(nf.body(), iterEnv);
                v += step;
            }
        } catch (BreakSignal ignored) {}
    }

    private void execGenericFor(GenericForStmt gf, LuaEnv env) {
        LuaValue[] state = evalExprList(gf.iterators(), env);
        LuaValue iterFn = LuaValues.arg(state, 0);
        LuaValue invariant = LuaValues.arg(state, 1);
        LuaValue control = LuaValues.arg(state, 2);
        List<String> vars = gf.vars();
        try {
            while (true) {
                budget.step();
                LuaValue[] res = callValue(iterFn, new LuaValue[] {invariant, control}, gf.line());
                control = LuaValues.arg(res, 0);
                if (control == LuaValue.NIL) break;
                LuaEnv iterEnv = new LuaEnv(env, null);
                for (int i = 0; i < vars.size(); i++) {
                    iterEnv.define(vars.get(i), LuaValues.arg(res, i));
                }
                execBlock(gf.body(), iterEnv);
            }
        } catch (BreakSignal ignored) {}
    }

    private LuaValue[] evalExprList(List<Expr> exprs, LuaEnv env) {
        if (exprs.isEmpty()) return LuaValues.empty();
        var out = new ArrayList<LuaValue>(exprs.size());
        for (int i = 0; i < exprs.size() - 1; i++) {
            out.add(evalSingle(exprs.get(i), env));
        }
        LuaValue[] tail = evalExprMulti(exprs.get(exprs.size() - 1), env);
        for (LuaValue v : tail) out.add(v);
        return out.toArray(LuaValue[]::new);
    }

    private LuaValue evalSingle(Expr expr, LuaEnv env) {
        return LuaValues.first(evalExprMulti(expr, env));
    }

    private LuaValue[] evalExprMulti(Expr expr, LuaEnv env) {
        budget.step();
        if (expr instanceof LiteralExpr lit) return LuaValues.single(lit.value());
        if (expr instanceof VarargExpr) return env.varargs();
        if (expr instanceof VarExpr v) return LuaValues.single(lookupVar(v.name(), env, v.line()));
        if (expr instanceof FieldExpr f) return LuaValues.single(getIndex(evalSingle(f.table(), env), evalSingle(f.key(), env), f.line()));
        if (expr instanceof TableExpr t) return LuaValues.single(buildTable(t, env));
        if (expr instanceof FunctionExpr fn) {
            budget.chargeMemory(32L);
            return LuaValues.single(new LuaClosure(fn, env));
        }
        if (expr instanceof UnaryExpr u) return LuaValues.single(evalUnary(u, env));
        if (expr instanceof BinaryExpr b) return LuaValues.single(evalBinary(b, env));
        if (expr instanceof CallExpr c) {
            LuaValue callee = evalSingle(c.callee(), env);
            LuaValue[] args = evalExprList(c.args(), env);
            return callValue(callee, args, c.line());
        }
        if (expr instanceof MethodCallExpr m) {
            LuaValue self = evalSingle(m.target(), env);
            LuaValue method = getIndex(self, LuaValue.of(m.method()), m.line());
            LuaValue[] rawArgs = evalExprList(m.args(), env);
            LuaValue[] withSelf = new LuaValue[rawArgs.length + 1];
            withSelf[0] = self;
            System.arraycopy(rawArgs, 0, withSelf, 1, rawArgs.length);
            return callValue(method, withSelf, m.line());
        }
        if (expr instanceof ParenExpr p) return LuaValues.single(evalSingle(p.inner(), env));
        throw new IllegalStateException();
    }

    private LuaTable buildTable(TableExpr tex, LuaEnv env) {
        var table = new LuaTable(budget);
        int arrayIndex = 1;
        List<TableField> fields = tex.fields();
        for (int i = 0; i < fields.size(); i++) {
            budget.step();
            TableField f = fields.get(i);
            if (f.key() != null) {
                LuaValue k = evalSingle(f.key(), env);
                LuaValue v = evalSingle(f.value(), env);
                if (k == LuaValue.NIL) throw runtimeError(tex.line(), "table index is nil");
                table.rawSet(k, v);
            } else if (i == fields.size() - 1) {
                LuaValue[] vals = evalExprMulti(f.value(), env);
                for (LuaValue v : vals) {
                    table.rawSet(arrayIndex++, v);
                }
            } else {
                table.rawSet(arrayIndex++, evalSingle(f.value(), env));
            }
        }
        return table;
    }

    private LuaValue evalUnary(UnaryExpr u, LuaEnv env) {
        LuaValue v = evalSingle(u.right(), env);
        return switch (u.op()) {
            case NOT -> LuaValue.of(!v.isTruthy());
            case NEG -> {
                Double num = coerceNumber(v);
                if (num != null) yield LuaValue.of(-num);
                yield callUnaryMetamethod(v, "__unm", u.line());
            }
            case LEN -> {
                if (v instanceof LuaValue.Str s) yield LuaValue.of((long) s.value().length());
                LuaValue mm = metamethod(v, "__len");
                if (mm != LuaValue.NIL) yield LuaValues.first(callValue(mm, new LuaValue[] {v}, u.line()));
                if (v instanceof LuaTable t) yield LuaValue.of((long) t.rawLen());
                throw runtimeError(u.line(), "attempt to get length of a " + v.typeName() + " value");
            }
            case BNOT -> {
                Long iv = coerceInt(v);
                if (iv != null) yield LuaValue.of(~iv);
                yield callUnaryMetamethod(v, "__bnot", u.line());
            }
        };
    }

    private LuaValue evalBinary(BinaryExpr b, LuaEnv env) {
        if (b.op() == BinaryOp.AND) {
            LuaValue lhs = evalSingle(b.left(), env);
            return lhs.isTruthy() ? evalSingle(b.right(), env) : lhs;
        }
        if (b.op() == BinaryOp.OR) {
            LuaValue lhs = evalSingle(b.left(), env);
            return lhs.isTruthy() ? lhs : evalSingle(b.right(), env);
        }
        LuaValue left = evalSingle(b.left(), env);
        LuaValue right = evalSingle(b.right(), env);
        return switch (b.op()) {
            case ADD -> numericOrMeta(left, right, "__add", b.line(), (x, y) -> x + y);
            case SUB -> numericOrMeta(left, right, "__sub", b.line(), (x, y) -> x - y);
            case MUL -> numericOrMeta(left, right, "__mul", b.line(), (x, y) -> x * y);
            case DIV -> numericOrMeta(left, right, "__div", b.line(), (x, y) -> x / y);
            case IDIV -> numericOrMeta(left, right, "__idiv", b.line(), (x, y) -> {
                if (y == 0.0) throw runtimeError(b.line(), "attempt to divide by zero");
                return Math.floor(x / y);
            });
            case MOD -> numericOrMeta(left, right, "__mod", b.line(), (x, y) -> x - Math.floor(x / y) * y);
            case POW -> numericOrMeta(left, right, "__pow", b.line(), Math::pow);
            case BAND -> bitwiseOrMeta(left, right, "__band", b.line(), (x, y) -> x & y);
            case BOR -> bitwiseOrMeta(left, right, "__bor", b.line(), (x, y) -> x | y);
            case BXOR -> bitwiseOrMeta(left, right, "__bxor", b.line(), (x, y) -> x ^ y);
            case SHL -> bitwiseOrMeta(left, right, "__shl", b.line(), (x, y) -> shiftLeft(x, y));
            case SHR -> bitwiseOrMeta(left, right, "__shr", b.line(), (x, y) -> shiftRight(x, y));
            case CONCAT -> concat(left, right, b.line());
            case EQ -> LuaValue.of(luaEquals(left, right, b.line()));
            case NEQ -> LuaValue.of(!luaEquals(left, right, b.line()));
            case LT -> LuaValue.of(luaLessThan(left, right, b.line()));
            case LE -> LuaValue.of(luaLessEqual(left, right, b.line()));
            case GT -> LuaValue.of(luaLessThan(right, left, b.line()));
            case GE -> LuaValue.of(luaLessEqual(right, left, b.line()));
            default -> throw new IllegalStateException();
        };
    }

    @FunctionalInterface private interface DoubleOp { double apply(double a, double b); }
    @FunctionalInterface private interface LongOp { long apply(long a, long b); }

    private LuaValue numericOrMeta(LuaValue a, LuaValue b, String mmName, int line, DoubleOp op) {
        Double x = coerceNumber(a);
        Double y = coerceNumber(b);
        if (x != null && y != null) return LuaValue.of(op.apply(x, y));
        return callBinaryMetamethod(a, b, mmName, line);
    }

    private LuaValue bitwiseOrMeta(LuaValue a, LuaValue b, String mmName, int line, LongOp op) {
        Long x = coerceInt(a);
        Long y = coerceInt(b);
        if (x != null && y != null) return LuaValue.of(op.apply(x, y));
        return callBinaryMetamethod(a, b, mmName, line);
    }

    private LuaValue concat(LuaValue a, LuaValue b, int line) {
        if ((a instanceof LuaValue.Str || a instanceof LuaValue.Num)
                && (b instanceof LuaValue.Str || b instanceof LuaValue.Num)) {
            String sa = toLuaString(a);
            String sb = toLuaString(b);
            String combined = sa + sb;
            budget.chargeMemory((long) combined.length() * 2L);
            return LuaValue.of(combined);
        }
        return callBinaryMetamethod(a, b, "__concat", line);
    }

    boolean luaEquals(LuaValue a, LuaValue b, int line) {
        if (a == b) {
            return !(a instanceof LuaValue.Num n && Double.isNaN(n.value()));
        }
        if (a.getClass() == b.getClass() && !(a instanceof LuaTable) && !(a instanceof LuaUserdata)) {
            return a.equals(b);
        }
        if ((a instanceof LuaTable || a instanceof LuaUserdata) && a.getClass() == b.getClass()) {
            LuaValue mmA = metamethod(a, "__eq");
            LuaValue mmB = metamethod(b, "__eq");
            if (mmA != LuaValue.NIL && mmA.equals(mmB)) {
                return LuaValues.first(callValue(mmA, new LuaValue[] {a, b}, line)).isTruthy();
            }
            return a == b;
        }
        return false;
    }

    boolean luaLessThan(LuaValue a, LuaValue b, int line) {
        if (a instanceof LuaValue.Num na && b instanceof LuaValue.Num nb) {
            return na.value() < nb.value();
        }
        if (a instanceof LuaValue.Str sa && b instanceof LuaValue.Str sb) {
            return sa.value().compareTo(sb.value()) < 0;
        }
        LuaValue mm = metamethod(a, "__lt");
        if (mm == LuaValue.NIL) mm = metamethod(b, "__lt");
        if (mm != LuaValue.NIL) {
            return LuaValues.first(callValue(mm, new LuaValue[] {a, b}, line)).isTruthy();
        }
        throw runtimeError(line, "attempt to compare " + a.typeName() + " with " + b.typeName());
    }

    private boolean luaLessEqual(LuaValue a, LuaValue b, int line) {
        if (a instanceof LuaValue.Num na && b instanceof LuaValue.Num nb) {
            return na.value() <= nb.value();
        }
        if (a instanceof LuaValue.Str sa && b instanceof LuaValue.Str sb) {
            return sa.value().compareTo(sb.value()) <= 0;
        }
        LuaValue mm = metamethod(a, "__le");
        if (mm == LuaValue.NIL) mm = metamethod(b, "__le");
        if (mm != LuaValue.NIL) {
            return LuaValues.first(callValue(mm, new LuaValue[] {a, b}, line)).isTruthy();
        }
        LuaValue lt = metamethod(a, "__lt");
        if (lt == LuaValue.NIL) lt = metamethod(b, "__lt");
        if (lt != LuaValue.NIL) {
            return !LuaValues.first(callValue(lt, new LuaValue[] {b, a}, line)).isTruthy();
        }
        throw runtimeError(line, "attempt to compare " + a.typeName() + " with " + b.typeName());
    }

    private LuaValue callUnaryMetamethod(LuaValue a, String mmName, int line) {
        LuaValue mm = metamethod(a, mmName);
        if (mm == LuaValue.NIL) throw runtimeError(line, "attempt to perform arithmetic on a " + a.typeName() + " value");
        return LuaValues.first(callValue(mm, new LuaValue[] {a}, line));
    }

    private LuaValue callBinaryMetamethod(LuaValue a, LuaValue b, String mmName, int line) {
        LuaValue mm = metamethod(a, mmName);
        if (mm == LuaValue.NIL) mm = metamethod(b, mmName);
        if (mm == LuaValue.NIL) {
            throw runtimeError(line, "attempt to perform " + mmName + " on " + a.typeName() + " and " + b.typeName());
        }
        return LuaValues.first(callValue(mm, new LuaValue[] {a, b}, line));
    }

    LuaTable metatableOf(LuaValue v) {
        if (v instanceof LuaTable t) return t.metatable();
        if (v instanceof LuaUserdata u) return u.metatable();
        if (v instanceof LuaValue.Str) return vm.stringMetatable();
        return null;
    }

    LuaValue metamethod(LuaValue v, String key) {
        LuaTable mt = metatableOf(v);
        return mt == null ? LuaValue.NIL : mt.rawGet(key);
    }

    private LuaValue lookupVar(String name, LuaEnv env, int line) {
        LuaEnv.Slot local = env.findLocal(name);
        if (local != null) return local.value;
        LuaValue globalEnv = envTable(env);
        return getIndex(globalEnv, LuaValue.of(name), line);
    }

    private void assignVar(String name, LuaValue value, LuaEnv env, int line) {
        LuaEnv.Slot local = env.findLocal(name);
        if (local != null) {
            local.value = value;
            return;
        }
        LuaValue globalEnv = envTable(env);
        setIndex(globalEnv, LuaValue.of(name), value, line);
    }

    private LuaValue envTable(LuaEnv env) {
        LuaEnv.Slot customEnv = env.findLocal("_ENV");
        return customEnv != null ? customEnv.value : vm.globals();
    }

    private static LuaValue[] extraVarargs(FunctionExpr fn, LuaValue[] args) {
        if (!fn.hasVararg() || args.length <= fn.params().size()) return LuaValues.empty();
        int extra = args.length - fn.params().size();
        LuaValue[] out = new LuaValue[extra];
        System.arraycopy(args, fn.params().size(), out, 0, extra);
        return out;
    }

    private static Double coerceNumber(LuaValue v) {
        if (v instanceof LuaValue.Num n) return n.value();
        if (v instanceof LuaValue.Str s) return LuaValues.parseNumberOrNull(s.value());
        return null;
    }

    private static Long coerceInt(LuaValue v) {
        Double d = coerceNumber(v);
        if (d == null || !Double.isFinite(d) || d != Math.rint(d)) return null;
        return (long) d.doubleValue();
    }

    private double toLoopNumber(LuaValue v, String role, int line) {
        Double d = coerceNumber(v);
        if (d == null) throw runtimeError(line, "'for' " + role + " must be a number");
        return d;
    }

    private static long shiftLeft(long x, long y) {
        if (y < 0) return shiftRight(x, -y);
        return y >= 64 ? 0L : (x << (int) y);
    }

    private static long shiftRight(long x, long y) {
        if (y < 0) return shiftLeft(x, -y);
        return y >= 64 ? 0L : (x >>> (int) y);
    }

    private LuaException runtimeError(int line, String msg) {
        return new LuaException(LuaException.Reason.RUNTIME, "line " + line + ": " + msg);
    }
}
