package dev.spm.core.nativejni;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.api.LayoutDescriptor;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Logical bone wire v1 extraction; no GPU layout or state is involved. */
public final class NativeBoneEvaluator {
    private static final int BONE_STRIDE=LayoutDescriptor.boneWireV1().stride();
    private NativeBoneEvaluator() { }
    public static BoneMatrices evaluate(Skeleton skeleton,BonePose pose,float[] root,Isa requested) {
        return extract(skeleton,pose,root,requested,-1);
    }
    public static BoneMatrices evaluateSubtree(Skeleton skeleton,BonePose pose,int root,Isa requested) {
        Objects.checkIndex(root,skeleton.boneCount());
        return extract(skeleton,pose,new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1},requested,root);
    }
    private static BoneMatrices extract(Skeleton skeleton,BonePose pose,float[] root,Isa requested,int subtree) {
        NativeTransforms.requireReady();
        Objects.requireNonNull(requested);
        int n=skeleton.boneCount();
        if(pose.boneCount()!=n || root.length!=16) throw new IllegalArgumentException("Bone request size mismatch");
        int parentBytes=Math.multiplyExact(n,4),pivotBytes=Math.multiplyExact(n,12),channelBytes=Math.multiplyExact(n,48),outputBytes=Math.multiplyExact(n,BONE_STRIDE);
        try(NativeArena arena=NativeArena.allocate(parentBytes,pivotBytes,channelBytes,64,outputBytes)) {
            ByteBuffer parents=arena.slice(parentBytes),pivots=arena.slice(pivotBytes),channels=arena.slice(channelBytes),outer=arena.slice(64),output=arena.slice(outputBytes);
            int[] parent=skeleton.parents(),glow=pose.glow(),colors=pose.colorsRgba();
            float[] pivot=skeleton.pivots(),r=pose.rotations(),t=pose.translations(),s=pose.scales();
            boolean[] own=pose.ownHidden(),children=pose.childrenHidden();
            for(int i=0;i<n;i++) {
                parents.putInt(parent[i]);
                for(int a=0;a<3;a++)pivots.putFloat(pivot[i*3+a]);
                for(int a=0;a<3;a++)channels.putFloat(r[i*3+a]);
                for(int a=0;a<3;a++)channels.putFloat(t[i*3+a]);
                for(int a=0;a<3;a++)channels.putFloat(s[i*3+a]);
                channels.putInt((own[i]?1:0)|(children[i]?2:0)).putInt(glow[i]).putInt(colors[i]);
            }
            for(float value:root)outer.putFloat(value);
            long outcome=nEvaluate(parents,pivots,channels,outer,output,n,requested.code(),subtree);
            if(outcome<0 || (int)outcome!=n) throw new IllegalArgumentException("Native bone computation rejected: "+outcome);
            float[] transforms=new float[Math.multiplyExact(n,16)],normals=new float[Math.multiplyExact(n,9)],locators=new float[Math.multiplyExact(n,3)];
            boolean[] visible=new boolean[n],descendants=new boolean[n];
            int[] lights=new int[n],outputColors=new int[n];
            for(int i=0;i<n;i++) {
                output.position(i*BONE_STRIDE);
                for(int j=0;j<16;j++)transforms[i*16+j]=output.getFloat();
                for(int j=0;j<9;j++)normals[i*9+j]=output.getFloat();
                for(int j=0;j<3;j++)locators[i*3+j]=output.getFloat();
                visible[i]=output.getInt()!=0;descendants[i]=output.getInt()!=0;lights[i]=output.getInt();outputColors[i]=output.getInt();
            }
            return new BoneMatrices(transforms,normals,visible,descendants,locators,lights,outputColors);
        }
    }
    private static native long nEvaluate(ByteBuffer parents,ByteBuffer pivots,ByteBuffer channels,ByteBuffer root,ByteBuffer output,int count,int isa,int subtree);
}
