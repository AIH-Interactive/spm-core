package dev.spm.core.nativejni;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BoneMatrices;
import dev.spm.core.api.VertexBatch;
import dev.spm.core.api.LayoutDescriptor;
import java.nio.ByteBuffer;
import java.util.Objects;

/** Optional Rust-only rigid vertex, normal and material computation. */
public final class NativeRigidRenderer {
    private static final int BONE_STRIDE=LayoutDescriptor.boneWireV1().stride();
    private NativeRigidRenderer() {}
    public static VertexBatch transform(VertexBatch batch,BoneMatrices bones,Isa requested) {
        NativeTransforms.requireReady();
        Objects.requireNonNull(batch);Objects.requireNonNull(bones);Objects.requireNonNull(requested);
        int boneCount=bones.boneCount(),count=batch.vertexCount();
        int boneBytes=Math.multiplyExact(boneCount,BONE_STRIDE),inputBytes=Math.multiplyExact(count,32),outputBytes=Math.multiplyExact(count,40);
        try(NativeArena arena=NativeArena.allocate(boneBytes,inputBytes,outputBytes)) {
            ByteBuffer boneData=arena.slice(boneBytes),input=arena.slice(inputBytes),output=arena.slice(outputBytes);
            float[] transforms=bones.transforms(),normals=bones.normals(),locators=bones.locatorPositions();
            int[] glow=bones.glow(),boneColors=bones.colorsRgba(); boolean[] visible=bones.visible(),children=bones.descendantVisible();
            for(int bone=0;bone<boneCount;bone++) {
                for(int i=0;i<16;i++)boneData.putFloat(transforms[bone*16+i]);
                for(int i=0;i<9;i++)boneData.putFloat(normals[bone*9+i]);
                for(int i=0;i<3;i++)boneData.putFloat(locators[bone*3+i]);
                boneData.putInt(visible[bone]?1:0).putInt(children[bone]?1:0).putInt(glow[bone]).putInt(boneColors[bone]);
            }
            float[] points=batch.positions(),directions=batch.normals();int[] indices=batch.boneIndices(),colors=batch.colorsRgba();
            for(int vertex=0;vertex<count;vertex++) {
                for(int axis=0;axis<3;axis++)input.putFloat(points[vertex*3+axis]);
                for(int axis=0;axis<3;axis++)input.putFloat(directions[vertex*3+axis]);
                input.putInt(indices[vertex]).putInt(colors[vertex]);
            }
            long outcome=nTransform(boneData,input,output,boneCount,count,requested.code());
            if(outcome<0)throw new IllegalArgumentException("Native rigid rendering rejected, status="+outcome);
            long completed=outcome&0xffffffffL;
            Isa used=Isa.fromCode((int)(outcome>>>32));
            if(completed>count || used==Isa.AUTO || requested!=Isa.AUTO&&used!=requested)
                throw new IllegalStateException("Invalid native rigid outcome");
            int n=(int)completed;
            float[] resultPositions=new float[Math.multiplyExact(n,3)],resultNormals=new float[resultPositions.length];
            int[] resultBones=new int[n],resultGlow=new int[n],resultColors=new int[n];
            for(int vertex=0;vertex<n;vertex++) {
                output.position(vertex*40);
                for(int axis=0;axis<3;axis++)resultPositions[vertex*3+axis]=output.getFloat();
                for(int axis=0;axis<3;axis++)resultNormals[vertex*3+axis]=output.getFloat();
                int bone=output.getInt();
                if(bone<0||bone>=boneCount||!visible[bone])throw new IllegalStateException("Invalid native rigid bone index");
                resultBones[vertex]=bone;resultGlow[vertex]=output.getInt();resultColors[vertex]=output.getInt();
            }
            return new VertexBatch(resultPositions,resultNormals,resultBones,resultGlow,resultColors);
        }
    }
    private static native long nTransform(ByteBuffer bones,ByteBuffer input,ByteBuffer output,int boneCount,int vertexCount,int isa);
}
