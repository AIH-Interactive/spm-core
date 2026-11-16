package dev.spm.core.lua;

import java.util.Objects;

/** Opaque host-provided userdata object with an optional metatable for methods and operators. */
public final class LuaUserdata implements LuaValue {
    private final Object value;
    private LuaTable metatable;

    public LuaUserdata(Object value, LuaTable metatable) {
        this.value = Objects.requireNonNull(value);
        this.metatable = metatable;
    }

    public Object value() { return value; }

    public LuaTable metatable() { return metatable; }

    public void setMetatable(LuaTable metatable) { this.metatable = metatable; }

    @Override public String toString() { return "userdata: " + value.getClass().getSimpleName(); }
}
