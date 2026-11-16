package dev.spm.core.lua;

import java.util.Objects;

/** Controlled Lua compilation, runtime, budget, memory, or sandbox exception. */
public final class LuaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        SYNTAX,
        RUNTIME,
        BUDGET,
        MEMORY,
        SECURITY
    }

    private final Reason reason;
    private final transient LuaValue errorObject;

    public LuaException(Reason reason, String message) {
        super(Objects.requireNonNull(message));
        this.reason = Objects.requireNonNull(reason);
        this.errorObject = null;
    }

    public LuaException(Reason reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message), cause);
        this.reason = Objects.requireNonNull(reason);
        this.errorObject = null;
    }

    public LuaException(LuaValue errorObject, String message) {
        super(Objects.requireNonNull(message));
        this.reason = Reason.RUNTIME;
        this.errorObject = errorObject;
    }

    public Reason reason() { return reason; }

    public LuaValue errorObject() { return errorObject; }
}
