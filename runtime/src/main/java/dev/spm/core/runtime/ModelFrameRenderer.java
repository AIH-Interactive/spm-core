package dev.spm.core.runtime;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.api.*;
import dev.spm.core.render.cpu.JavaComputeProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Composes pure bone/vertex computation and keeps visible indices, UVs and materials aligned. */
public final class ModelFrameRenderer {
    private ModelFrameRenderer(){ }
    /** The returned frame pins the asset until the frame closes. */
    public static CpuFrame render(ModelAsset asset,BonePose pose,ResourceBudget budget,CancellationToken token){
        return render(asset,pose,new JavaComputeProvider(),budget,token);
    }
    public static CpuFrame render(ModelAsset asset,BonePose pose,CpuComputeProvider provider,ResourceBudget budget,CancellationToken token){
        var lease=Objects.requireNonNull(asset).acquire();
        try{return renderInternal(lease.document(),pose,provider,budget,token,lease);}
        catch(RuntimeException|Error failure){lease.close();throw failure;}
    }
    /** Borrowed-document path: the caller must keep its source owner alive until the frame closes. */
    public static CpuFrame render(ModelDocument document,BonePose pose,ResourceBudget budget,CancellationToken token){
        return render(document,pose,new JavaComputeProvider(),budget,token);
    }
    public static CpuFrame render(ModelDocument document,BonePose pose,CpuComputeProvider provider,ResourceBudget budget,CancellationToken token){
        return renderInternal(document,pose,provider,budget,token,null);
    }
    private static CpuFrame renderInternal(ModelDocument document,BonePose pose,CpuComputeProvider provider,ResourceBudget budget,CancellationToken token,ModelAsset.Lease lease){
        Objects.requireNonNull(document);Objects.requireNonNull(pose);Objects.requireNonNull(provider);Objects.requireNonNull(budget);Objects.requireNonNull(token);
        token.check();
        long working=Math.multiplyExact((long)document.skeleton().boneCount(),1024);
        for(var mesh:document.meshes())working=Math.addExact(working,Math.addExact(Math.multiplyExact((long)mesh.vertices().vertexCount(),256),Math.multiplyExact((long)mesh.indices().length,8)));
        ResourceBudget.Reservation reservation=budget.reserve(working);
        try {
            BoneMatrices matrices=provider.evaluateBones(document.skeleton(),pose);
            if(matrices.boneCount()!=document.skeleton().boneCount())throw new IllegalArgumentException("Provider bone count mismatch");
            boolean[] visible=matrices.visible();var meshes=new ArrayList<CpuFrame.Mesh>();
            for(var mesh:document.meshes()){
                token.check();VertexBatch original=mesh.vertices();int[] sourceBones=original.boneIndices();
                int[] remap=new int[sourceBones.length];Arrays.fill(remap,-1);int count=0;
                for(int i=0;i<sourceBones.length;i++){if((i&1023)==0)token.check();if(visible[sourceBones[i]])remap[i]=count++;}
                VertexBatch output=provider.renderVertices(original,matrices);
                if(output.vertexCount()!=count)throw new IllegalArgumentException("Provider visible vertex count mismatch");
                int[] outputBones=output.boneIndices();
                float[] sourceUv=mesh.uvs(),uv=new float[Math.multiplyExact(count,2)];
                for(int i=0;i<remap.length;i++)if(remap[i]>=0){
                    int out=remap[i];if(outputBones[out]!=sourceBones[i])throw new IllegalArgumentException("Provider vertex order mismatch");
                    uv[out*2]=sourceUv[i*2];uv[out*2+1]=sourceUv[i*2+1];
                }
                int[] sourceIndices=mesh.indices(),indices=new int[sourceIndices.length];int used=0;
                for(int i=0;i<sourceIndices.length;i+=3){
                    if((i&1023)==0)token.check();int a=remap[sourceIndices[i]],b=remap[sourceIndices[i+1]],c=remap[sourceIndices[i+2]];
                    if(a>=0&&b>=0&&c>=0){indices[used++]=a;indices[used++]=b;indices[used++]=c;}
                }
                meshes.add(new CpuFrame.Mesh(mesh.name(),output,uv,Arrays.copyOf(indices,used),mesh.materialIndex()));
            }
            token.check();return new CpuFrame(document.identity(),provider.backend(),meshes,reservation,lease);
        }catch(RuntimeException|Error failure){reservation.close();throw failure;}
    }
}
