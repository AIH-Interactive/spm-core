package dev.spm.core.lua;

import java.util.HashMap;
import java.util.Map;

/** Lexical environment frame holding mutable local variable slots for closures. */
final class LuaEnv {
    static final class Slot {
        LuaValue value;
        Slot(LuaValue value) { this.value = value; }
    }

    private final LuaEnv parent;
    private final Map<String, Slot> locals = new HashMap<>();
    private LuaValue[] varargs;

    LuaEnv(LuaEnv parent, LuaValue[] varargs) {
        this.parent = parent;
        this.varargs = varargs;
    }

    void define(String name, LuaValue value) {
        locals.put(name, new Slot(value));
    }

    Slot findLocal(String name) {
        for (LuaEnv env = this; env != null; env = env.parent) {
            Slot s = env.locals.get(name);
            if (s != null) return s;
        }
        return null;
    }

    LuaValue[] varargs() {
        for (LuaEnv env = this; env != null; env = env.parent) {
            if (env.varargs != null) return env.varargs;
        }
        return LuaValues.empty();
    }
}
