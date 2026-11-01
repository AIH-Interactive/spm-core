package dev.spm.core.scene;

/** Explicit script rejection; cancellation remains CancellationException. */
public final class MolangException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Reason { SYNTAX, UNSUPPORTED, STEPS, DEPTH, CAPACITY, CONTROLLER_CYCLE, UNKNOWN_FUNCTION, ARITY, NON_FINITE }
    private final Reason reason;
    public MolangException(Reason reason, String message) { super(message); this.reason = reason; }
    public Reason reason() { return reason; }
}
