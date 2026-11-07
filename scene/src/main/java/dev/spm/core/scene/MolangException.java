package dev.spm.core.scene;

/** Explicit script rejection; cancellation remains CancellationException. */
public final class MolangException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Reason { SYNTAX, UNSUPPORTED, STEPS, DEPTH, CAPACITY, CONTROLLER_CYCLE, UNKNOWN_FUNCTION, ARITY, NON_FINITE }
    private final Reason reason;
    private final transient MolangBindings.Call bindingRequest;
    public MolangException(Reason reason, String message) { this(reason,message,null); }
    public MolangException(Reason reason,String message,MolangBindings.Call bindingRequest) {
        super(message); this.reason=java.util.Objects.requireNonNull(reason); this.bindingRequest=bindingRequest;
    }
    public Reason reason() { return reason; }
    public java.util.Optional<MolangBindings.Call> bindingRequest() { return java.util.Optional.ofNullable(bindingRequest); }
}
