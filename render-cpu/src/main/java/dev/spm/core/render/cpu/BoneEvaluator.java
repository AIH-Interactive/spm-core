package dev.spm.core.render.cpu;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.Skeleton;
import java.util.Objects;

/** Scalar bone extraction in normalized Core coordinates. */
public final class BoneEvaluator {
    /**
     * Working credits charged per evaluated bone before allocation: 16 matrix floats, 9 normal
     * floats, 3 locator floats, plus the visibility and selection flags and the per-bone temporaries.
     * It is a modelled upper bound, deliberately larger than the exact array sizes, so a budget
     * rejection happens before any large allocation rather than after it.
     */
    public static final long WORKING_BYTES_PER_BONE = 256;
    private BoneEvaluator() {}
    public static BoneMatrices evaluate(Skeleton skeleton, BonePose pose) {
        return evaluate(skeleton, pose, identity());
    }

    /** rootPose is an explicit nonsingular affine column-major transform. */
    public static BoneMatrices evaluate(Skeleton skeleton, BonePose pose, float[] rootPose) {
        return extract(skeleton, pose, rootPose, -1);
    }

    /**
     * Budgeted evaluation: reserves the modelled working credits up front, so an oversized request
     * fails with {@link dev.spm.core.api.ResourceBudgetExceededException} before allocating, and
     * releases them on every path including failure. Callers that already reserve a larger frame
     * working set (for example {@code ModelFrameRenderer}) should keep using the unbudgeted overload
     * to avoid double charging.
     */
    public static BoneMatrices evaluate(Skeleton skeleton, BonePose pose, float[] rootPose,
                                        ResourceBudget budget, CancellationToken token) {
        Objects.requireNonNull(budget);
        Objects.requireNonNull(token);
        token.check();
        long working = Math.multiplyExact((long) Objects.requireNonNull(skeleton).boneCount(),
                WORKING_BYTES_PER_BONE);
        var reservation = budget.reserve(working);
        try {
            token.check();
            BoneMatrices result = extract(skeleton, pose, rootPose, -1);
            token.check();
            return result;
        } finally {
            reservation.close();
        }
    }

    /** Extract one local subtree, ignoring all transforms and hiding above root. */
    public static BoneMatrices evaluateSubtree(Skeleton skeleton, BonePose pose, int root) {
        Objects.checkIndex(root, skeleton.boneCount());
        return extract(skeleton, pose, identity(), root);
    }

    private static BoneMatrices extract(Skeleton skeleton, BonePose pose, float[] rootPose, int root) {
        int n = skeleton.boneCount();
        if (pose.boneCount() != n) throw new IllegalArgumentException("Pose does not match skeleton");
        float[] outer = rootPose.clone();
        if (outer.length != 16) throw new IllegalArgumentException("Root matrix must have 16 elements");
        for (float value : outer) finite(value);
        if (outer[3] != 0 || outer[7] != 0 || outer[11] != 0 || outer[15] != 1)
            throw new IllegalArgumentException("Root matrix must be affine");
        normal(outer); // Reject a singular or unrepresentable root before traversal.
        float[] rotations = pose.rotations(), translations = pose.translations(), scales = pose.scales();
        boolean[] ownHidden = pose.ownHidden(), childrenHidden = pose.childrenHidden();
        float[] matrices = new float[Math.multiplyExact(n,16)], normals = new float[Math.multiplyExact(n,9)];
        float[] locators = new float[Math.multiplyExact(n,3)];
        boolean[] visible = new boolean[n], descendants = new boolean[n], selected = new boolean[n];
        for (int bone : skeleton.evaluationOrder()) {
            int parent = skeleton.parent(bone);
            selected[bone] = root == -1 || bone == root || parent >= 0 && selected[parent];
            if (!selected[bone]) continue;
            boolean localRoot = bone == root;
            if (!localRoot && parent >= 0 && !descendants[parent]) continue;
            int v = bone * 3;
            if (scales[v] == 0 || scales[v+1] == 0 || scales[v+2] == 0) continue;
            float[] local = local(skeleton, bone, rotations, translations, scales);
            float[] world = multiply(localRoot || parent < 0 ? outer : matrices,
                    localRoot || parent < 0 ? 0 : parent * 16, local);
            System.arraycopy(world,0,matrices,bone*16,16);
            System.arraycopy(normal(world),0,normals,bone*9,9);
            visible[bone] = !ownHidden[bone];
            descendants[bone] = !childrenHidden[bone];
            for (int row=0;row<3;row++) {
                double coordinate=world[12+row];
                for (int axis=0;axis<3;axis++) coordinate += (double)world[axis*4+row]*skeleton.pivot(bone,axis);
                locators[v+row]=finite(coordinate);
            }
        }
        return new BoneMatrices(matrices,normals,visible,descendants,locators,pose.glow(),pose.colorsRgba());
    }

    private static float[] local(Skeleton skeleton, int bone, float[] r, float[] d, float[] s) {
        int v=bone*3;
        double cx=Math.cos(r[v]), sx=Math.sin(r[v]);
        double cy=Math.cos(r[v+1]), sy=Math.sin(r[v+1]);
        double cz=Math.cos(r[v+2]), sz=Math.sin(r[v+2]);
        double[] linear={cz*cy,sz*cy,-sy,
                cz*sy*sx-sz*cx,sz*sy*sx+cz*cx,cy*sx,
                cz*sy*cx+sz*sx,sz*sy*cx-cz*sx,cy*cx};
        float[] result=identity();
        for (int column=0;column<3;column++) {
            for (int row=0;row<3;row++) result[column*4+row]=finite(linear[column*3+row]*s[v+column]);
        }
        for (int row=0;row<3;row++) {
            double translation=(double)skeleton.pivot(bone,row)+d[v+row];
            for (int axis=0;axis<3;axis++) translation -= (double)result[axis*4+row]*skeleton.pivot(bone,axis);
            result[12+row]=finite(translation);
        }
        return result;
    }

    private static float[] multiply(float[] parent, int offset, float[] local) {
        float[] result=new float[16];
        for (int column=0;column<4;column++) {
            for (int row=0;row<4;row++) {
                double value=0;
                for (int k=0;k<4;k++) value += (double)parent[offset+k*4+row]*local[column*4+k];
                result[column*4+row]=finite(value);
            }
        }
        return result;
    }

    private static float[] normal(float[] matrix) {
        double a=matrix[0],b=matrix[4],c=matrix[8],d=matrix[1],e=matrix[5],f=matrix[9];
        double g=matrix[2],h=matrix[6],i=matrix[10];
        double determinant=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        if (determinant == 0 || !Double.isFinite(determinant))
            throw new IllegalArgumentException("Singular normal transform");
        double[] cofactor={e*i-f*h,c*h-b*i,b*f-c*e,
                f*g-d*i,a*i-c*g,c*d-a*f,d*h-e*g,b*g-a*h,a*e-b*d};
        float[] result=new float[9];
        for (int k=0;k<9;k++) result[k]=finite(cofactor[k]/determinant);
        return result;
    }

    private static float finite(double value) {
        float result=(float)value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Unrepresentable matrix result");
        return result;
    }

    private static float[] identity() {
        return new float[] {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    }
}
