package dev.spm.core.format.ysm;

import dev.spm.core.api.ResourceBudget;
import java.util.*;

/**
 * Owns a decoded historical model.
 *
 * <p>Animation clips are <em>not</em> part of {@link #root()}. They are the overwhelming majority of
 * a real model's node mass (measured at 97.4% of 10.6 million nodes for a 5.6 MiB community model),
 * so the decoder projects each clip into its final scene JSON as soon as it has been parsed and drops
 * the tree representation immediately. The projected result lives in {@link #animationCollector()}.
 */
public final class YsmHistoricalModel implements AutoCloseable {
    private final int version;
    private HistoricalValue.Struct root;
    private HistoricalSceneProjection.Collector animationCollector;
    private final List<ResourceBudget.Reservation> reservations;
    YsmHistoricalModel(int version, HistoricalValue.Struct root, List<ResourceBudget.Reservation> reservations) {
        this(version, root, null, reservations);
    }
    YsmHistoricalModel(int version, HistoricalValue.Struct root, HistoricalSceneProjection.Collector animationCollector,
                       List<ResourceBudget.Reservation> reservations) {
        this.version = version; this.root = root; this.animationCollector = animationCollector;
        this.reservations = new ArrayList<>(reservations);
    }
    public int innerVersion() { return version; }
    public synchronized HistoricalValue.Struct root() {
        if (root == null) throw new IllegalStateException("Historical model is closed");
        return root;
    }
    /** Streamed animation sets, or null when the source carried none. */
    synchronized HistoricalSceneProjection.Collector animationCollector() {
        if (root == null) throw new IllegalStateException("Historical model is closed");
        return animationCollector;
    }
    /** Hands the collector to its next owner so a later close cannot invalidate an in-flight use. */
    synchronized HistoricalSceneProjection.Collector takeAnimationCollector() {
        if (root == null) throw new IllegalStateException("Historical model is closed");
        var taken = animationCollector; animationCollector = null; return taken;
    }
    @Override public synchronized void close() {
        root = null; animationCollector = null;
        reservations.forEach(ResourceBudget.Reservation::close); reservations.clear();
    }
}
