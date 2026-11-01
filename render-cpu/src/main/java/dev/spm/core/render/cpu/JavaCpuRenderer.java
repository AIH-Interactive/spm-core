package dev.spm.core.render.cpu;

import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.VertexBatch;

/** Scalar rigid vertex transformation; output is independent of Minecraft. */
public final class JavaCpuRenderer {
    private JavaCpuRenderer() {}
    /** Compacts visible vertices in source order and applies each bone's own glow. */
    public static VertexBatch transform(VertexBatch batch, BoneMatrices matrices) {
        int[] sourceBones=batch.boneIndices();
        boolean[] visible=matrices.visible();
        int count=0;
        for (int bone : sourceBones) {
            if (bone >= matrices.boneCount()) throw new IllegalArgumentException("Vertex bone index out of range");
            if (visible[bone]) count++;
        }
        float[] positions=new float[Math.multiplyExact(count,3)], normals=new float[positions.length];
        int[] bones=new int[count], glow=new int[count], boneGlow=matrices.glow();
        int[] colors=new int[count],boneColors=matrices.colorsRgba(),vertexColors=batch.colorsRgba();
        float[] sourcePositions=batch.positions(), sourceNormals=batch.normals();
        int output=0;
        for (int input=0;input<sourceBones.length;input++) {
            int bone=sourceBones[input];
            if (!visible[bone]) continue;
            int src=input*3, dst=output*3;
            double[] direction=new double[3];
            for (int row=0;row<3;row++) {
                double point=matrices.matrix(bone,12+row), normal=0;
                for (int axis=0;axis<3;axis++) {
                    point += (double)matrices.matrix(bone,axis*4+row)*sourcePositions[src+axis];
                    normal += (double)matrices.normal(bone,axis*3+row)*sourceNormals[src+axis];
                }
                positions[dst+row]=finite(point);
                direction[row]=normal;
            }
            double length=Math.hypot(Math.hypot(direction[0],direction[1]),direction[2]);
            if (!Double.isFinite(length)) throw new IllegalArgumentException("Unrepresentable normal");
            if (length != 0) for (int row=0;row<3;row++) normals[dst+row]=finite(direction[row]/length);
            bones[output]=bone; glow[output]=boneGlow[bone];colors[output]=tint(vertexColors[input],boneColors[bone]); output++;
        }
        return new VertexBatch(positions,normals,bones,glow,colors);
    }
    /** Logical normalized RGBA multiplication, rounded to the nearest 8-bit channel. */
    private static int tint(int vertex,int bone){
        if(vertex==0xffffffff)return bone;if(bone==0xffffffff)return vertex;
        int color=0;for(int shift=0;shift<32;shift+=8){int value=(((vertex>>>shift)&255)*((bone>>>shift)&255)+127)/255;color|=value<<shift;}return color;
    }

    private static float finite(double value) {
        float result=(float)value;
        if (!Float.isFinite(result)) throw new IllegalArgumentException("Unrepresentable transformed vertex");
        return result;
    }
}
