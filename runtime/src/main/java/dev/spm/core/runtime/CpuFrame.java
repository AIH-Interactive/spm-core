package dev.spm.core.runtime;

import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.ModelAsset;
import dev.spm.core.api.VertexBatch;
import java.util.List;
import java.util.Objects;

/** Owned CPU frame; contains no GPU resources and does not imply a submitted draw. */
public final class CpuFrame implements AutoCloseable {
    public record Mesh(String name,VertexBatch vertices,float[] uvs,int[] indices,int materialIndex) {
        public Mesh {
            Objects.requireNonNull(name);Objects.requireNonNull(vertices);uvs=uvs.clone();indices=indices.clone();
            if(uvs.length!=Math.multiplyExact(vertices.vertexCount(),2)||indices.length%3!=0||materialIndex<0)throw new IllegalArgumentException("Invalid CPU mesh");
            for(float uv:uvs)if(!Float.isFinite(uv))throw new IllegalArgumentException("Nonfinite CPU UV");
            for(int index:indices)if(index<0||index>=vertices.vertexCount())throw new IllegalArgumentException("Invalid CPU mesh index");
        }
        @Override public float[] uvs(){return uvs.clone();}
        @Override public int[] indices(){return indices.clone();}
    }
    private final String identity;
    private final String backend;
    private final ResourceBudget.Reservation reservation;
    private final ModelAsset.Lease source;
    private List<Mesh> meshes;
    CpuFrame(String identity,String backend,List<Mesh> meshes,ResourceBudget.Reservation reservation,ModelAsset.Lease source){
        this.identity=identity;this.backend=backend;this.meshes=List.copyOf(meshes);this.reservation=reservation;this.source=source;
    }
    public String identity(){return identity;}
    public String backend(){return backend;}
    /** Borrowed immutable output: do not retain this list or its contents beyond frame close. */
    public synchronized List<Mesh> meshes(){if(meshes==null)throw new IllegalStateException("CPU frame closed");return meshes;}
    @Override public synchronized void close(){if(meshes==null)return;meshes=null;reservation.close();if(source!=null)source.close();}
}
