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
