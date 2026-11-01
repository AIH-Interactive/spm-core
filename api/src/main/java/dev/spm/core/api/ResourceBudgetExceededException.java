package dev.spm.core.api;

/** Distinguishes allocation-credit exhaustion from malformed input or an unrelated state error. */
public final class ResourceBudgetExceededException extends IllegalStateException {
    private static final long serialVersionUID=1L;
    public ResourceBudgetExceededException(){super("Core resource budget exceeded");}
}
