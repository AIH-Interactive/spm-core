package dev.spm.core.scene;

import java.util.Map;

/** Host-sampled numeric query snapshot. It must be safe to use without a game thread. */
public record SceneInputs(Map<String, Double> queries) {
    public SceneInputs {
        queries = Map.copyOf(MolangContext.numericCopy(queries));
        if (queries.size() > 4096) throw new IllegalArgumentException("At most 4096 query inputs");
    }
}
