package dev.spm.core.scene;

import java.util.Map;

/** Host-sampled numeric query snapshot. It must be safe to use without a game thread. */
public record SceneInputs(Map<String, Double> queries,MolangBindings bindings,boolean effectsAllowed) {
    public SceneInputs(Map<String,Double> queries) { this(queries,MolangBindings.empty(),true); }
    public SceneInputs(Map<String,Double> queries,MolangBindings bindings) { this(queries,bindings,true); }
    public SceneInputs {
        java.util.Objects.requireNonNull(bindings);
        queries = Map.copyOf(MolangContext.numericCopy(queries));
        if (queries.size() > 4096) throw new IllegalArgumentException("At most 4096 query inputs");
    }
}
