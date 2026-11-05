package dev.spm.core.scene;

import dev.spm.core.api.CancellationToken;
import java.util.Objects;

/**
 * Explicit Peer script limits and one aggregate meter per monotonically increasing frame.
 * The Host must share one policy across all models charged to that Peer frame, and supply
 * the same cancellation token on every request for that frame. Retrying cannot refund work.
 * This class does not sample game state or establish a game-side Peer adapter.
 */
public final class PeerScriptPolicy {
    public static final int MAX_SOURCE_CHARACTERS = 65_536;
    public static final int MAX_TOKENS = 16_384;
    public static final int MAX_NESTING = 32;
    public static final int MAX_FRAME_STEPS = 100_000;
    public static final int MAX_CALL_DEPTH = 64;

    private long currentFrame = -1;
    private CancellationToken currentCancellation;
    private ScriptBudget currentBudget;

    public static MolangProgram compile(String source) {
        return MolangProgram.compile(source, MAX_SOURCE_CHARACTERS, MAX_TOKENS, MAX_NESTING);
    }

    /** A strictly newer frame starts a meter; identical frame requests share the existing meter. */
    public synchronized ScriptBudget frame(long frameId, CancellationToken cancellation) {
        Objects.requireNonNull(cancellation);
        if (frameId < 0 || frameId < currentFrame) {
            throw new IllegalArgumentException("Peer frame must be nonnegative and cannot regress");
        }
        if (frameId == currentFrame) {
            if (cancellation != currentCancellation) {
                throw new IllegalArgumentException("One Peer frame must retain its cancellation token");
            }
            return currentBudget;
        }
        ScriptBudget next = new ScriptBudget(MAX_FRAME_STEPS, MAX_NESTING, MAX_CALL_DEPTH, cancellation);
        currentFrame = frameId;
        currentCancellation = cancellation;
        currentBudget = next;
        return next;
    }
}
