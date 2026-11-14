package dev.spm.core.scene;
import dev.spm.core.api.BonePose;
import java.util.List;
import java.util.Map;
import java.util.Objects;
/** One completely published coded collection candidate. */
public record CodedPoseFrame(long instanceId,long generation,long frameId,long step,double timeSeconds,BonePose pose,
                             Map<String,Double> variables,List<CodedControllerSnapshot> controllers,List<EffectCommand> effects) {
    public CodedPoseFrame { Objects.requireNonNull(pose);variables=Map.copyOf(variables);controllers=List.copyOf(controllers);effects=List.copyOf(effects); }
}
