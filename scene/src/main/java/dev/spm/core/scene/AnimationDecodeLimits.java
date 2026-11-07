package dev.spm.core.scene;

/** Explicit resource policy for executable animation decoding; a format identifier grants no trust. */
public record AnimationDecodeLimits(int maximumKeys, int maximumExpressionTokens,
                                    int maximumExpressionCharacters, int maximumEventInstructions,
                                    int maximumExpressionDepth, int maximumTotalExpressionCharacters) {
    /** Existing callers retain the original four Mi-character aggregate source allowance. */
    public AnimationDecodeLimits(int keys, int tokens, int characters, int instructions, int depth) {
        this(keys,tokens,characters,instructions,depth,4_194_304);
    }
    public AnimationDecodeLimits {
        if (maximumKeys < 1 || maximumKeys > 1_048_576
                || maximumExpressionTokens < 1 || maximumExpressionTokens > 32_768
                || maximumExpressionCharacters < 1 || maximumExpressionCharacters > 131_072
                || maximumEventInstructions < 1 || maximumEventInstructions > 4096
                || maximumExpressionDepth < 1 || maximumExpressionDepth > 128
                || maximumTotalExpressionCharacters < 1 || maximumTotalExpressionCharacters > 33_554_432)
            throw new IllegalArgumentException("Animation decode limits exceed supported bounds");
    }
    public static AnimationDecodeLimits genericDefaults() {
        return new AnimationDecodeLimits(65_536,8192,65_536,128,128);
    }
    /** Compatibility policy used by the existing YSM fromModel entry point. */
    public static AnimationDecodeLimits historicalYsm() {
        return new AnimationDecodeLimits(131_072,8192,131_072,4096,128);
    }
    /** Explicit local policy: the real corpus contains a 30.7M-character authored animation set. */
    public static AnimationDecodeLimits localYsm() {
        return new AnimationDecodeLimits(1_048_576,32_768,131_072,4096,128,33_554_432);
    }
    /** Network-supplied animation policy; its parser depth is intentionally lower. */
    public static AnimationDecodeLimits peer() {
        return new AnimationDecodeLimits(65_536,16_384,65_536,128,32);
    }
}
