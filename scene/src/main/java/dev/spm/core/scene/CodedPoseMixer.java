package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.Arrays;
import java.util.List;

/** Candidate-owned ordered coded channels plus the model's three-tick undriven-channel reset. */
final class CodedPoseMixer {
    private final BonePose bind;
    private BonePose pose;
    private final boolean[] active;
    private final long[] lastDriven;
    private final float[][] anchors;
    CodedPoseMixer(BonePose bind) {
        this.bind=bind;pose=bind;active=new boolean[bind.boneCount()*3];lastDriven=new long[active.length];Arrays.fill(lastDriven,-1);
        anchors=new float[][]{bind.rotations(),bind.translations(),bind.scales()};
    }
    private CodedPoseMixer(CodedPoseMixer other) {
        bind=other.bind;pose=other.pose;active=other.active.clone();lastDriven=other.lastDriven.clone();
        anchors=new float[][]{other.anchors[0].clone(),other.anchors[1].clone(),other.anchors[2].clone()};
    }
    CodedPoseMixer copy(){return new CodedPoseMixer(this);}
    BonePose pose(){return pose;}
    BonePose relativeAnchor() {
        float[] r=pose.rotations(),p=pose.translations(),s=pose.scales(),br=bind.rotations(),bp=bind.translations(),bs=bind.scales();
        for(int i=0;i<r.length;i++){r[i]-=br[i];p[i]-=bp[i];s[i]=bs[i]==0?1:s[i]/bs[i];}
        return new BonePose(r,p,s,bind.ownHidden(),bind.childrenHidden(),bind.glow(),bind.colorsRgba());
    }
    void mix(List<CodedPlayback> members,long step,ScriptBudget budget) {
        float[][] target={bind.rotations(),bind.translations(),bind.scales()};
        float[][] neutral={bind.rotations(),bind.translations(),bind.scales()};
        boolean[] driven=new boolean[active.length];
        for(var member:members) {
            if(!member.contributing)continue;
            float[][] values={member.contribution.rotations(),member.contribution.translations(),member.contribution.scales()};
            for(int bone=0;bone<bind.boneCount();bone++) {
                budget.step();int offset=bone*3;
                for(int channel=0;channel<3;channel++) {
                    if(!member.mask[offset+channel])continue;
                    driven[offset+channel]=true;
                    float[] full=new float[3];
                    if (member.additive) {
                        for(int axis=0;axis<3;axis++){int i=offset+axis;full[axis]=PoseAnimationLibrary.representable(channel==2?target[channel][i]*values[channel][i]:target[channel][i]+values[channel][i]);}
                    } else {
                        for(int axis=0;axis<3;axis++){int i=offset+axis;full[axis]=PoseAnimationLibrary.representable(channel==2?neutral[channel][i]*values[channel][i]:neutral[channel][i]+values[channel][i]);}
                    }
                    if(channel==0) {
                        float[] lower={target[0][offset],target[0][offset+1],target[0][offset+2]};
                        PoseRotation.blend(full,lower,0,member.endingProgress,full);
                        System.arraycopy(full,0,target[0],offset,3);
                    } else for(int axis=0;axis<3;axis++){int i=offset+axis;target[channel][i]=PoseAnimationLibrary.representable(full[axis]*(1-member.endingProgress)+target[channel][i]*member.endingProgress);}
                }
            }
        }
        float[][] previous={pose.rotations(),pose.translations(),pose.scales()};
        for(int bone=0;bone<bind.boneCount();bone++) {
            budget.step();int offset=bone*3;
            for(int channel=0;channel<3;channel++) {
                int slot=offset+channel;
                if(driven[slot]){active[slot]=true;lastDriven[slot]=step;continue;}
                if(active[slot]){System.arraycopy(previous[channel],offset,anchors[channel],offset,3);active[slot]=false;}
                if(lastDriven[slot]<0)continue;
                double progress=Math.min(1,(step-lastDriven[slot])/9.0);
                if(channel==0)PoseRotation.blend(anchors[channel],neutral[channel],offset,progress,target[channel]);
                else for(int axis=0;axis<3;axis++){int i=offset+axis;target[channel][i]=PoseAnimationLibrary.representable(anchors[channel][i]*(1-progress)+neutral[channel][i]*progress);}
                if(progress>=1)lastDriven[slot]=-1;
            }
        }
        pose=new BonePose(target[0],target[1],target[2],bind.ownHidden(),bind.childrenHidden(),bind.glow(),bind.colorsRgba());
    }
}
