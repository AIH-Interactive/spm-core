package dev.spm.core.lua;

import java.util.Objects;

/** Callable Lua or native function. */
public non-sealed interface LuaFunction extends LuaValue {
    @FunctionalInterface
    interface NativeBody {
        LuaValue[] invoke(LuaVm vm, LuaValue[] args);
    }

    String debugName();

    LuaValue[] call(LuaVm vm, LuaValue[] args);

    static LuaFunction nativeFn(String name, NativeBody body) {
        Objects.requireNonNull(name);
        Objects.requireNonNull(body);
        return new LuaFunction() {
            @Override public String debugName() { return name; }
            @Override public LuaValue[] call(LuaVm vm, LuaValue[] args) { return body.invoke(vm, args); }
            @Override public String toString() { return "function: " + name; }
        };
    }
}
