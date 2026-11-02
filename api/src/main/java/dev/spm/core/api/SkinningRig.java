package dev.spm.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable static skinning binding for one {@link ModelDocument}: which meshes are weighted, with
 * which weights and method, plus their authored morph targets.
 *
 * <p>Every binding is validated against the document before work starts: a mesh name must exist,
 * a weighted mesh's weights must address exactly that mesh's vertices, and every referenced bone
 * must exist in the document skeleton. Meshes absent from this rig stay rigid.</p>
 */
public final class SkinningRig {
    private final Map<String, MeshSkin> skins;

    private SkinningRig(Map<String, MeshSkin> skins) { this.skins = skins; }

    /** Binds each entry to the identically named document mesh; unlisted meshes remain rigid. */
    public static SkinningRig of(ModelDocument document, Map<String, MeshSkin> skins) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(skins);
        int boneCount = document.skeleton().boneCount();
        var copy = new LinkedHashMap<String, MeshSkin>();
        for (var entry : skins.entrySet()) {
            String name = Objects.requireNonNull(entry.getKey());
            MeshSkin skin = Objects.requireNonNull(entry.getValue());
            var mesh = document.meshes().stream().filter(m -> m.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Skin rig names an unknown mesh: " + name));
            if (skin instanceof MeshSkin.Weighted weighted) {
                if (weighted.weights().boneCount() != boneCount)
                    throw new IllegalArgumentException("Skin weights address an unknown skeleton");
                if (weighted.weights().vertexCount() != mesh.vertices().vertexCount())
                    throw new IllegalArgumentException("Skin weights do not match the mesh vertex count");
                if (weighted.method() == SkinningMethod.SDEF && !weighted.sdef().hasCentres())
                    throw new IllegalArgumentException("SDEF requires authored sphere centres");
            }
            copy.put(name, skin);
        }
        // Keep declaration order: callers derive per-mesh work order from weightedMeshes().
        return new SkinningRig(Collections.unmodifiableMap(copy));
    }

    /** Every mesh explicitly rigid. */
    public static SkinningRig rigid(ModelDocument document) { return of(document, Map.of()); }

    /**
     * Binds every mesh of {@code document} with linear blend skinning derived from the document's own
     * per-vertex bone assignment, in the default {@link SkinningMethod#BDEF} method.
     *
     * @see #fromVertexBones(ModelDocument, SkinningMethod)
     */
    public static SkinningRig fromVertexBones(ModelDocument document) {
        return fromVertexBones(document, SkinningMethod.BDEF);
    }

    /**
     * Derives a weighted rig from the document's own rigid cube-to-bone assignment.
     *
     * <p>Every {@link VertexBatch} in this runtime already carries exactly one bone index per vertex,
     * and the importers fill it from authored structure rather than from inference: a Bedrock cube
     * belongs to the bone object that declares it ({@code BedrockJsonImporter.MeshBuilder.face}),
     * and a historical quad belongs to the bone whose {@code quads} array contains it
     * ({@code HistoricalMeshCollector}). {@link ModelDocument.Mesh} even refuses to construct a face
     * whose recorded bone disagrees with its four vertices, so that assignment is checked data, not
     * an assumption. This method re-expresses that same assignment as skin weights: one influence per
     * vertex at weight 1, which is exactly what the source authored.</p>
     *
     * <p>The weights are therefore <em>never invented</em>. No influence is synthesized, spread over
     * neighbouring bones, smoothed, or inferred from geometry; a vertex the source bound to one bone
     * stays bound to that bone with full weight, and a source that authored several influences per
     * vertex cannot be represented by this factory at all — it has no data here to average, so it
     * must supply its own {@link SkinWeights} through {@link #of}. The reason to derive weights at
     * all is reach, not deformation: this is the binding the rigid path already renders with, exposed
     * through the weighted contract so that real content can use morph targets, the QDEF/SDEF methods
     * and the {@code WeightedFrameRenderer} charge model.</p>
     *
     * <p>Because a single influence at weight 1 is exactly the rigid rule, a bind-pose frame of the
     * returned rig reproduces the rigid path byte for byte whenever the document's bind transforms are
     * the identity (which is the case for every model in the private corpus, asserted by
     * {@code RealCorpusSkinningTest}). Skinning a posed frame is only equal to the rigid path under
     * that same identity precondition, because the rigid renderer applies each bone's bind transform to
     * bone-local vertices while the weighted renderer applies {@code pose * inverseBind}; the two
     * conventions coincide exactly when the bind transform is the identity.</p>
     *
     * @param document the document whose meshes and per-vertex bones are bound
     * @param method the blend method for every bound mesh; {@link SkinningMethod#SDEF} is refused
     *               here because the derivation carries no sphere centres to place
     * @return a rig binding every mesh, in document declaration order
     * @throws IllegalArgumentException if {@code method} is SDEF, which needs authored centres
     */
    public static SkinningRig fromVertexBones(ModelDocument document, SkinningMethod method) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(method);
        if (method == SkinningMethod.SDEF)
            throw new IllegalArgumentException("SDEF needs authored sphere centres; derive from real data instead");
        int boneCount = document.skeleton().boneCount();
        var skins = new LinkedHashMap<String, MeshSkin>();
        for (ModelDocument.Mesh mesh : document.meshes()) {
            // ModelDocument has already rejected a vertex bone outside the skeleton, so this cannot
            // silently clamp: SkinWeights.rigid would refuse an out-of-range index anyway.
            SkinWeights weights = SkinWeights.rigid(boneCount, mesh.vertices().boneIndices());
            skins.put(mesh.name(), new MeshSkin.Weighted(weights, method));
        }
        return of(document, skins);
    }

    /** Skin data of one mesh, or {@link MeshSkin.Rigid#INSTANCE} when the rig does not list it. */
    public MeshSkin skin(String meshName) {
        return skins.getOrDefault(Objects.requireNonNull(meshName), MeshSkin.Rigid.INSTANCE);
    }

    public boolean isEmpty() { return skins.isEmpty(); }

    /** Mesh names carrying weighted skin data, in the caller's binding order. */
    public List<String> weightedMeshes() {
        var names = new ArrayList<String>();
        skins.forEach((name, skin) -> { if (skin instanceof MeshSkin.Weighted) names.add(name); });
        return List.copyOf(names);
    }

    /** True when at least one mesh is weighted, so the weighted renderer must run. */
    public boolean hasWeights() { return !weightedMeshes().isEmpty(); }
}
