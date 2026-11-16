package dev.spm.core.lua;

import dev.spm.core.lua.LuaAst.FunctionExpr;

/** Compiled Lua function closure capturing its defining lexical environment. */
final class LuaClosure implements LuaFunction {
    private final FunctionExpr ast;
    private final LuaEnv closureEnv;

    LuaClosure(FunctionExpr ast, LuaEnv closureEnv) {
        this.ast = ast;
        this.closureEnv = closureEnv;
    }

    @Override public String debugName() { return ast.debugName(); }

    @Override public LuaValue[] call(LuaVm vm, LuaValue[] args) {
        return vm.evaluator().invokeClosure(this, args);
    }

    FunctionExpr ast() { return ast; }
    LuaEnv closureEnv() { return closureEnv; }

    @Override public String toString() { return "function: " + ast.debugName(); }
}
