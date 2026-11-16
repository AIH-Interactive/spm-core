package dev.spm.core.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owned model: an immutable {@link ModelDocument} whose retained allocations stay charged to the
 * shared {@link ResourceBudget} until this owner closes.
 *
 * <p>The document itself is a value object and may be copied freely while the asset is open. It is
 * deliberately <em>not</em> a replacement for the asset: closing the asset retires it and releases
 * reservations after all leases return. A document captured from {@link #document()} must not
 * outlive the owner; longer asynchronous use requires {@link #acquire()}.</p>
 */
public final class ModelAsset implements AutoCloseable {
    private ModelDocument document;
    private SkinningRig skinningRig;
    private SkinState defaultSkinState;
    private List<ResourceBudget.Reservation> reservations;
    private boolean closed;
    private int borrowers;
    private Runnable releaseDependency;

    public ModelAsset(ModelDocument document, List<ResourceBudget.Reservation> reservations) {
        this(document, null, SkinState.rest(), reservations, () -> {});
    }

    /** releaseDependency is invoked once, after retirement and the last frame/asset lease return. */
    public ModelAsset(ModelDocument document, List<ResourceBudget.Reservation> reservations, Runnable releaseDependency) {
        this(document, null, SkinState.rest(), reservations, releaseDependency);
    }

    /** Owned model with an optional authored {@link SkinningRig} and initial {@link SkinState}. */
    public ModelAsset(ModelDocument document, SkinningRig skinningRig, SkinState defaultSkinState,
                      List<ResourceBudget.Reservation> reservations, Runnable releaseDependency) {
        this.document = Objects.requireNonNull(document);
        this.skinningRig = skinningRig;
        this.defaultSkinState = Objects.requireNonNull(defaultSkinState);
        if (skinningRig != null) {
            for (var entry : skinningRig.weightedMeshes()) {
                boolean found = false;
                for (var mesh : document.meshes()) {
                    if (mesh.name().equals(entry)) {
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("Skinning rig does not belong to document: " + entry);
            }
            this.defaultSkinState.validate(skinningRig);
        } else if (!this.defaultSkinState.isEmpty()) {
            throw new IllegalArgumentException("Default skin state requires a skinning rig");
        }
        this.reservations = List.copyOf(Objects.requireNonNull(reservations));
        this.releaseDependency = Objects.requireNonNull(releaseDependency);
    }

    /** The retained document; fails once the asset is closed. */
    public synchronized ModelDocument document() {
        if (closed) throw new IllegalStateException("Model asset closed");
        return document;
    }

    /** Optional authored weighted skinning rig; empty for strictly rigid assets. */
    public synchronized Optional<SkinningRig> skinningRig() {
        if (closed) throw new IllegalStateException("Model asset closed");
        return Optional.ofNullable(skinningRig);
    }

    /** Initial morph/skin state authored by the imported asset. */
    public synchronized SkinState defaultSkinState() {
        if (closed) throw new IllegalStateException("Model asset closed");
        return defaultSkinState;
    }

    public synchronized boolean isOpen() { return !closed; }

    /** Pins this exact asset. Retirement forbids new leases; existing leases remain valid. */
    public synchronized Lease acquire() {
        if (closed) throw new IllegalStateException("Model asset closed");
        if (borrowers == Integer.MAX_VALUE) throw new IllegalStateException("Too many asset leases");
        var lease = new Lease();
        borrowers++;
        return lease;
    }

    /** A borrowed document must not outlive this lease. */
    public final class Lease implements AutoCloseable {
        private boolean returned;
        private Lease() { }
        public ModelDocument document() {
            synchronized (ModelAsset.this) {
                if (returned) throw new IllegalStateException("Model asset lease closed");
                return document;
            }
        }
        @Override public void close() {
            synchronized (ModelAsset.this) {
                if (returned) return;
                returned = true;
                borrowers--;
                releaseIfUnused();
            }
        }
    }

    /** Retires the asset; releases its credits after the last borrower returns. Idempotent. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        releaseIfUnused();
    }
    private void releaseIfUnused() {
        if (!closed || borrowers != 0) return;
        for (int i = reservations.size() - 1; i >= 0; i--) reservations.get(i).close();
        reservations = List.of();
        document = null;
        skinningRig = null;
        defaultSkinState = null;
        var dependency = releaseDependency;
        releaseDependency = null;
        if (dependency != null) dependency.run();
    }
}
