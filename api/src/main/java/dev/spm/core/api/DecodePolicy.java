package dev.spm.core.api;

/** Explicit per-operation limits; hosts select stricter limits for remote/untrusted input.
 * Input bounds encoded request bytes. Expanded bounds decoded allocation credits (including
 * container/node charges), not only inflated wire length. Entries bound resources, objects bound
 * decoded nodes, collection entries bound an individual collection. String bytes bound encoded
 * string length; depth bounds nesting. The five-argument constructor preserves its former strict
 * shared entries/objects/collection limit; local callers can explicitly select localModel().
 * The separate shared ResourceBudget caps concurrent live allocation credits. Credits model
 * bounded allocations conservatively; they are not an exact JVM heap-size measurement.
 * These defaults are not an authenticated Peer policy.
 */
public record DecodePolicy(long maxInputBytes, long maxExpandedBytes, int maxEntries, int maxStringBytes, int maxDepth,
                           int maxObjects, int maxCollectionEntries) {
    public DecodePolicy(long maxInputBytes,long maxExpandedBytes,int maxEntries,int maxStringBytes,int maxDepth) {
        this(maxInputBytes,maxExpandedBytes,maxEntries,maxStringBytes,maxDepth,maxEntries,maxEntries);
    }
    public DecodePolicy {
        if (maxInputBytes < 0 || maxExpandedBytes < 0 || maxEntries < 0 || maxStringBytes < 0 || maxDepth < 1 || maxObjects<0 || maxCollectionEntries<0) {
            throw new IllegalArgumentException("Invalid decode limits");
        }
    }
    public static DecodePolicy conservative() { return new DecodePolicy(64L << 20, 256L << 20, 16384, 1 << 20, 32); }
    /**
     * Explicit local-file workload limits; this does not confer trust or replace a remote policy.
     *
     * <p>{@code maxExpandedBytes} is 3 GiB because the historical YSM tree is charged at its measured
     * retention cost, not at its wire size: a real 5.6 MiB community model expands to about 10.6
     * million nodes, 97% of them animation keyframes, and legitimately exceeds 1.4 GiB of credits.
     * Charging those nodes honestly is what makes the limit meaningful; lowering the charge to fit a
     * smaller cap would restore the previous behaviour where the budget under-estimated real memory
     * by a factor of three. Untrusted input must still use {@link #conservative()} or a stricter Host
     * policy.
     */
    public static DecodePolicy localModel() { return new DecodePolicy(128L<<20,3L<<30,16384,16<<20,64,16_777_216,1_048_576); }
}
