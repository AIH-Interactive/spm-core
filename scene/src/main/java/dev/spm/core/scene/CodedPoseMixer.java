package dev.spm.core.scene;

import dev.spm.core.api.BonePose;
import java.util.Arrays;
import java.util.List;

/** Candidate-owned ordered coded channels plus the model's three-tick undriven-channel reset. */
final class CodedPoseMixer {
    private final int boneCount;
    private final float[] bindRot;
    private final float[] bindPos;
    private final float[] bindScale;
    private final boolean[] bindOwnHidden;
    private final boolean[] bindChildrenHidden;
    private final int[] bindGlow;
    private final int[] bindColors;
    private BonePose pose;
    private float[] poseRot;
    private float[] posePos;
    private float[] poseScale;
    private float[][] cachedRelativeAnchor;
    private final boolean[] active;
    private final long[] lastDriven;
    private final float[][] anchors;
    CodedPoseMixer(BonePose bind) {
        this.boneCount=bind.boneCount();
        this.bindRot=bind.rotations();
        this.bindPos=bind.translations();
        this.bindScale=bind.scales();
        this.bindOwnHidden=bind.ownHidden();
        this.bindChildrenHidden=bind.childrenHidden();
        this.bindGlow=bind.glow();
        this.bindColors=bind.colorsRgba();
        pose=bind;
        poseRot=bindRot;posePos=bindPos;poseScale=bindScale;
        active=new boolean[boneCount*3];lastDriven=new long[active.length];Arrays.fill(lastDriven,-1);
        anchors=new float[][]{bindRot.clone(),bindPos.clone(),bindScale.clone()};
    }
    private CodedPoseMixer(CodedPoseMixer other) {
        boneCount=other.boneCount;
        bindRot=other.bindRot;bindPos=other.bindPos;bindScale=other.bindScale;
        bindOwnHidden=other.bindOwnHidden;bindChildrenHidden=other.bindChildrenHidden;
        bindGlow=other.bindGlow;bindColors=other.bindColors;
        pose=other.pose;poseRot=other.poseRot;posePos=other.posePos;poseScale=other.poseScale;
        active=other.active.clone();lastDriven=other.lastDriven.clone();
        anchors=new float[][]{other.anchors[0].clone(),other.anchors[1].clone(),other.anchors[2].clone()};
    }
    CodedPoseMixer copy(){return new CodedPoseMixer(this);}
    BonePose pose(){return pose;}
    void beginPulse(){cachedRelativeAnchor=null;}
    float[][] relativeAnchorArrays() {
        if (cachedRelativeAnchor != null) return cachedRelativeAnchor;
        int len = bindRot.length;
        float[] r = new float[len], p = new float[len], s = new float[len];
        for (int i = 0; i < len; i++) {
            r[i] = poseRot[i] - bindRot[i];
            p[i] = posePos[i] - bindPos[i];
            float bs = bindScale[i];
            s[i] = bs == 0 ? 1 : poseScale[i] / bs;
        }
        cachedRelativeAnchor = new float[][]{r, p, s};
        return cachedRelativeAnchor;
    }
    void mix(List<CodedPlayback> members,long step,ScriptBudget budget) {
        cachedRelativeAnchor = null;
        float[] targetRot = bindRot.clone();
        float[] targetPos = bindPos.clone();
        float[] targetScale = bindScale.clone();
        boolean[] driven = new boolean[active.length];
        float[] fullScratch = null;
        float[] lowerScratch = null;
        for (int m = 0, mc = members.size(); m < mc; m++) {
            CodedPlayback member = members.get(m);
            if (!member.contributing) continue;
            budget.steps(boneCount);
            boolean additive = member.additive && !member.jsonActive;
            double endProg = member.endingProgress;
            PoseAnimationLibrary.SparseSample sp = member.sparse;
            if (sp != null) {
                int[] bIndices = sp.boneIndices;
                byte[] chs = sp.channels;
                float[] mRot = sp.rotations;
                float[] mPos = sp.translations;
                float[] mScale = sp.scales;
                int kCount = bIndices.length;
                if (endProg <= 0.0) {
                    for (int k = 0; k < kCount; k++) {
                        int offset = bIndices[k] * 3;
                        int kOff = k * 3;
                        byte ch = chs[k];
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_ROT) != 0) {
                            driven[offset] = true;
                            float[] base = additive ? targetRot : bindRot;
                            targetRot[offset]     = PoseAnimationLibrary.representable(base[offset]     + mRot[kOff]);
                            targetRot[offset + 1] = PoseAnimationLibrary.representable(base[offset + 1] + mRot[kOff + 1]);
                            targetRot[offset + 2] = PoseAnimationLibrary.representable(base[offset + 2] + mRot[kOff + 2]);
                        }
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_POS) != 0) {
                            driven[offset + 1] = true;
                            // Legacy YSM deprecatedMode (parallel_*) is rotation-only additive; position always overwrites.
                            targetPos[offset]     = PoseAnimationLibrary.representable(bindPos[offset]     + mPos[kOff]);
                            targetPos[offset + 1] = PoseAnimationLibrary.representable(bindPos[offset + 1] + mPos[kOff + 1]);
                            targetPos[offset + 2] = PoseAnimationLibrary.representable(bindPos[offset + 2] + mPos[kOff + 2]);
                        }
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                            driven[offset + 2] = true;
                            // Legacy YSM deprecatedMode (parallel_*) is rotation-only additive; scale always overwrites
                            // so parallel clips can unhide (scale 1) bones hidden (scale 0) by pre_parallel clips.
                            targetScale[offset]     = PoseAnimationLibrary.representable(bindScale[offset]     * mScale[kOff]);
                            targetScale[offset + 1] = PoseAnimationLibrary.representable(bindScale[offset + 1] * mScale[kOff + 1]);
                            targetScale[offset + 2] = PoseAnimationLibrary.representable(bindScale[offset + 2] * mScale[kOff + 2]);
                        }
                    }
                } else {
                    if (fullScratch == null) {
                        fullScratch = new float[3];
                        lowerScratch = new float[3];
                    }
                    double oneMinusEnd = 1.0 - endProg;
                    for (int k = 0; k < kCount; k++) {
                        int offset = bIndices[k] * 3;
                        int kOff = k * 3;
                        byte ch = chs[k];
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_ROT) != 0) {
                            driven[offset] = true;
                            float[] base = additive ? targetRot : bindRot;
                            for (int axis = 0; axis < 3; axis++) {
                                fullScratch[axis] = PoseAnimationLibrary.representable(base[offset + axis] + mRot[kOff + axis]);
                                lowerScratch[axis] = targetRot[offset + axis];
                            }
                            PoseRotation.blend(fullScratch, lowerScratch, 0, endProg, fullScratch);
                            targetRot[offset]     = fullScratch[0];
                            targetRot[offset + 1] = fullScratch[1];
                            targetRot[offset + 2] = fullScratch[2];
                        }
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_POS) != 0) {
                            driven[offset + 1] = true;
                            for (int axis = 0; axis < 3; axis++) {
                                float full = PoseAnimationLibrary.representable(bindPos[offset + axis] + mPos[kOff + axis]);
                                targetPos[offset + axis] = PoseAnimationLibrary.representable(full * oneMinusEnd + targetPos[offset + axis] * endProg);
                            }
                        }
                        if ((ch & PoseAnimationLibrary.SparseSample.HAS_SCALE) != 0) {
                            driven[offset + 2] = true;
                            for (int axis = 0; axis < 3; axis++) {
                                float full = PoseAnimationLibrary.representable(bindScale[offset + axis] * mScale[kOff + axis]);
                                targetScale[offset + axis] = PoseAnimationLibrary.representable(full * oneMinusEnd + targetScale[offset + axis] * endProg);
                            }
                        }
                    }
                }
                continue;
            }
            float[] mRot = member.contribRot;
            float[] mPos = member.contribPos;
            float[] mScale = member.contribScale;
            boolean[] mask = member.mask;
            if (endProg <= 0.0) {
                for (int bone = 0; bone < boneCount; bone++) {
                    int offset = bone * 3;
                    if (mask[offset]) {
                        driven[offset] = true;
                        float[] base = additive ? targetRot : bindRot;
                        targetRot[offset]     = PoseAnimationLibrary.representable(base[offset]     + mRot[offset]);
                        targetRot[offset + 1] = PoseAnimationLibrary.representable(base[offset + 1] + mRot[offset + 1]);
                        targetRot[offset + 2] = PoseAnimationLibrary.representable(base[offset + 2] + mRot[offset + 2]);
                    }
                    if (mask[offset + 1]) {
                        driven[offset + 1] = true;
                        targetPos[offset]     = PoseAnimationLibrary.representable(bindPos[offset]     + mPos[offset]);
                        targetPos[offset + 1] = PoseAnimationLibrary.representable(bindPos[offset + 1] + mPos[offset + 1]);
                        targetPos[offset + 2] = PoseAnimationLibrary.representable(bindPos[offset + 2] + mPos[offset + 2]);
                    }
                    if (mask[offset + 2]) {
                        driven[offset + 2] = true;
                        targetScale[offset]     = PoseAnimationLibrary.representable(bindScale[offset]     * mScale[offset]);
                        targetScale[offset + 1] = PoseAnimationLibrary.representable(bindScale[offset + 1] * mScale[offset + 1]);
                        targetScale[offset + 2] = PoseAnimationLibrary.representable(bindScale[offset + 2] * mScale[offset + 2]);
                    }
                }
            } else {
                if (fullScratch == null) {
                    fullScratch = new float[3];
                    lowerScratch = new float[3];
                }
                double oneMinusEnd = 1.0 - endProg;
                for (int bone = 0; bone < boneCount; bone++) {
                    int offset = bone * 3;
                    if (mask[offset]) {
                        driven[offset] = true;
                        float[] base = additive ? targetRot : bindRot;
                        for (int axis = 0; axis < 3; axis++) {
                            int i = offset + axis;
                            fullScratch[axis] = PoseAnimationLibrary.representable(base[i] + mRot[i]);
                            lowerScratch[axis] = targetRot[i];
                        }
                        PoseRotation.blend(fullScratch, lowerScratch, 0, endProg, fullScratch);
                        targetRot[offset]     = fullScratch[0];
                        targetRot[offset + 1] = fullScratch[1];
                        targetRot[offset + 2] = fullScratch[2];
                    }
                    if (mask[offset + 1]) {
                        driven[offset + 1] = true;
                        for (int axis = 0; axis < 3; axis++) {
                            int i = offset + axis;
                            float full = PoseAnimationLibrary.representable(bindPos[i] + mPos[i]);
                            targetPos[i] = PoseAnimationLibrary.representable(full * oneMinusEnd + targetPos[i] * endProg);
                        }
                    }
                    if (mask[offset + 2]) {
                        driven[offset + 2] = true;
                        for (int axis = 0; axis < 3; axis++) {
                            int i = offset + axis;
                            float full = PoseAnimationLibrary.representable(bindScale[i] * mScale[i]);
                            targetScale[i] = PoseAnimationLibrary.representable(full * oneMinusEnd + targetScale[i] * endProg);
                        }
                    }
                }
            }
        }
        float[][] target = {targetRot, targetPos, targetScale};
        float[][] neutral = {bindRot, bindPos, bindScale};
        float[][] previous = {poseRot, posePos, poseScale};
        budget.steps(boneCount);
        for (int bone = 0; bone < boneCount; bone++) {
            int offset = bone * 3;
            for (int channel = 0; channel < 3; channel++) {
                int slot = offset + channel;
                if (driven[slot]) { active[slot] = true; lastDriven[slot] = step; continue; }
                if (active[slot]) { System.arraycopy(previous[channel], offset, anchors[channel], offset, 3); active[slot] = false; }
                if (lastDriven[slot] < 0) continue;
                double progress = Math.min(1, (step - lastDriven[slot]) / 9.0);
                if (channel == 0) PoseRotation.blend(anchors[channel], neutral[channel], offset, progress, target[channel]);
                else {
                    double oneMinus = 1 - progress;
                    for (int axis = 0; axis < 3; axis++) {
                        int i = offset + axis;
                        target[channel][i] = PoseAnimationLibrary.representable(anchors[channel][i] * oneMinus + neutral[channel][i] * progress);
                    }
                }
                if (progress >= 1) lastDriven[slot] = -1;
            }
        }
        poseRot = targetRot;
        posePos = targetPos;
        poseScale = targetScale;
        pose = new BonePose(targetRot, targetPos, targetScale, bindOwnHidden, bindChildrenHidden, bindGlow, bindColors);
    }
}
