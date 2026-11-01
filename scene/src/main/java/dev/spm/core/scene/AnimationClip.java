package dev.spm.core.scene;

import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Immutable scalar clip; bindings to translation/rotation/scale are a consumer decision. */
public record AnimationClip(String name, double durationSeconds, boolean looping,
                            Map<String, KeyframeCurve> curves, List<Cue> cues) {
    public record Cue(double timeSeconds, String kind, String resource, Map<String, Double> parameters) {
        public Cue {
            if (!Double.isFinite(timeSeconds) || timeSeconds < 0) throw new IllegalArgumentException("Invalid cue time");
            if (Objects.requireNonNull(kind).isBlank() || Objects.requireNonNull(resource).isBlank()) throw new IllegalArgumentException("Cue kind and resource required");
            parameters = Map.copyOf(MolangContext.numericCopy(parameters));
            if (parameters.size() > 64) throw new IllegalArgumentException("At most 64 effect parameters");
        }
    }
    public AnimationClip {
        Objects.requireNonNull(name);
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.000001 || durationSeconds > 1_000_000) {
            throw new IllegalArgumentException("Clip duration must be 1 microsecond..1000000 seconds");
        }
        curves = Map.copyOf(curves);
        cues = cues.stream().sorted(Comparator.comparingDouble(Cue::timeSeconds)).toList();
        if (curves.size() > 4096 || cues.size() > 1024) throw new IllegalArgumentException("Clip channel/cue limit exceeded");
        for (Cue cue : cues) {
            if (cue.timeSeconds > durationSeconds || (looping && cue.timeSeconds == durationSeconds)) {
                throw new IllegalArgumentException("Cue outside clip; looping endpoint belongs to next cycle");
            }
        }
    }
    public double localTime(double timeSeconds) {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0) throw new IllegalArgumentException("Invalid clip sample time");
        return looping ? timeSeconds % durationSeconds : Math.min(timeSeconds, durationSeconds);
    }
}
