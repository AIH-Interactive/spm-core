package dev.spm.core.scene;
import java.util.Objects;
/** Published immutable playback identity. */
public record CodedControllerSnapshot(String name,String selectedClip,double animationTimeSeconds,boolean contributing) {
    public CodedControllerSnapshot { Objects.requireNonNull(name);Objects.requireNonNull(selectedClip);if(!Double.isFinite(animationTimeSeconds)||animationTimeSeconds<0)throw new IllegalArgumentException("Invalid playback time"); }
}
