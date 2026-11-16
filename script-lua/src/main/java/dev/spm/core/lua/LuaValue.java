package dev.spm.core.lua;

import java.util.Objects;

/** Dynamically typed Lua value. */
public sealed interface LuaValue permits LuaValue.Nil, LuaValue.Bool, LuaValue.Num, LuaValue.Str,
        LuaTable, LuaFunction, LuaUserdata {

    Nil NIL = Nil.INSTANCE;
    Bool TRUE = Bool.TRUE;
    Bool FALSE = Bool.FALSE;

    default String typeName() {
        if (this instanceof Nil) return "nil";
        if (this instanceof Bool) return "boolean";
        if (this instanceof Num) return "number";
        if (this instanceof Str) return "string";
        if (this instanceof LuaTable) return "table";
        if (this instanceof LuaFunction) return "function";
        return "userdata";
    }

    default boolean isTruthy() {
        return !(this instanceof Nil || (this instanceof Bool b && !b.value()));
    }

    default boolean isNil() { return this instanceof Nil; }

    static Bool of(boolean b) { return b ? Bool.TRUE : Bool.FALSE; }
    static Num of(double d) { return new Num(d); }
    static Num of(long l) { return new Num((double) l); }
    static Str of(String s) { return new Str(Objects.requireNonNull(s)); }
    static LuaValue ofNullable(String s) { return s == null ? NIL : of(s); }

    default double asDouble() {
        if (this instanceof Num n) return n.value();
        if (this instanceof Str s) {
            Double parsed = LuaValues.parseNumberOrNull(s.value());
            if (parsed != null) return parsed;
        }
        throw new LuaException(LuaException.Reason.RUNTIME, "attempt to perform arithmetic on a " + typeName() + " value");
    }

    default long asInt64() {
        double d = asDouble();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < Long.MIN_VALUE || d >= 9223372036854775808.0) {
            throw new LuaException(LuaException.Reason.RUNTIME, "number has no integer representation");
        }
        return (long) d;
    }

    default String asString() {
        if (this instanceof Str s) return s.value();
        if (this instanceof Num n) return LuaValues.formatNumber(n.value());
        throw new LuaException(LuaException.Reason.RUNTIME, "expected string, got " + typeName());
    }

    default LuaTable asTable() {
        if (this instanceof LuaTable t) return t;
        throw new LuaException(LuaException.Reason.RUNTIME, "expected table, got " + typeName());
    }

    enum Nil implements LuaValue {
        INSTANCE;
        @Override public String toString() { return "nil"; }
    }

    enum Bool implements LuaValue {
        TRUE(true), FALSE(false);
        private final boolean value;
        Bool(boolean value) { this.value = value; }
        public boolean value() { return value; }
        @Override public String toString() { return value ? "true" : "false"; }
    }

    record Num(double value) implements LuaValue {
        @Override public boolean equals(Object o) {
            return o instanceof Num other && value == other.value;
        }
        @Override public int hashCode() {
            double normalized = value == 0.0 ? 0.0 : value;
            return Double.hashCode(normalized);
        }
        @Override public String toString() { return LuaValues.formatNumber(value); }
    }

    record Str(String value) implements LuaValue {
        public Str { Objects.requireNonNull(value); }
        @Override public String toString() { return value; }
    }
}
