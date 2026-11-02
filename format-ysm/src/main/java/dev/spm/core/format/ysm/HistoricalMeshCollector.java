package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.IOException;
import java.util.*;

/**
 * Builds the runtime skeleton, mesh and bind pose directly from geometry, one bone at a time.
 *
 * <p>Geometry is the second half of the historical tree's cost and all of it is overhead. Measured on
 * the community corpus, a 395,608-vertex model holds 3,876,325 {@code HistoricalValue} nodes under
 * {@code player.geos} — 65.4% of the entire tree and about 428 MiB of charged retention — and every
 * one of those nodes exists only to be read once and turned into {@code float[]} arrays.
 *
 * <p>The decoder parses one bone, hands its nineteen numbers per quad to this collector, and drops that
 * bone's tree immediately. Only bone <em>metadata</em> is kept until {@link #finish}, because a parent
 * name can only be resolved to an index against the complete name table; that metadata is a handful of
 * values per bone, not per quad. The peak therefore becomes one bone instead of all of them, and the
 * resulting mesh is identical: bone order still defines both the vertex bone index and the face order.
 */
final class HistoricalMeshCollector {
    /** Bone metadata, kept until {@link #finish} because parent names resolve against the full set. */
    private final List<String> names = new ArrayList<>();
    private final List<String> parents = new ArrayList<>();
    private final List<float[]> pivots = new ArrayList<>();
    private final List<float[]> rotations = new ArrayList<>();
    private boolean authoredBoneFlags;

    private float[] positions = new float[3 * 512];
    private float[] normals = new float[3 * 512];
    private float[] uvs = new float[2 * 512];
    private int[] vertexBones = new int[512];
    private int[] quadBones = new int[128];
    private int[] quadCube = new int[128];
    private int vertices;
    private int quads;
    private int cubeInBone;

    /** Registers one bone's metadata and returns the index its quads will be attributed to. */
    int bone(String name,String parent,float[] pivot,float[] rotation,boolean flags) {
        if(names.size()>=65536)throw new IllegalArgumentException("Historical bone limit");
        names.add(name);
        parents.add(parent);
        pivots.add(pivot);
        rotations.add(rotation);
        if(flags)authoredBoneFlags=true;
        cubeInBone=0;
        return names.size()-1;
    }

    /**
     * Appends one quad.
     *
     * @param corners   four positions, xyz each, in corner order
     * @param texCoords four UV pairs, uv each, in the same order
     */
    void quad(int boneIndex,float nx,float ny,float nz,float[] corners,float[] texCoords) {
        ensureVertexCapacity(vertices+4);
        ensureQuadCapacity(quads+1);
        for(int corner=0;corner<4;corner++) {
            int v=vertices+corner;
            positions[v*3]=corners[corner*3];
            positions[v*3+1]=corners[corner*3+1];
            positions[v*3+2]=corners[corner*3+2];
            normals[v*3]=nx;
            normals[v*3+1]=ny;
            normals[v*3+2]=nz;
            uvs[v*2]=texCoords[corner*2];
            uvs[v*2+1]=texCoords[corner*2+1];
            vertexBones[v]=boneIndex;
        }
        vertices+=4;
        quadBones[quads]=boneIndex;
        quadCube[quads]=cubeInBone++;
        quads++;
    }

    private void ensureVertexCapacity(int needed) {
        if(needed<=vertexBones.length)return;
        int capacity=Math.max(needed,vertexBones.length+vertexBones.length/2);
        positions=Arrays.copyOf(positions,capacity*3);
        normals=Arrays.copyOf(normals,capacity*3);
        uvs=Arrays.copyOf(uvs,capacity*2);
        vertexBones=Arrays.copyOf(vertexBones,capacity);
    }

    private void ensureQuadCapacity(int needed) {
        if(needed<=quadBones.length)return;
        int capacity=Math.max(needed,quadBones.length+quadBones.length/2);
        quadBones=Arrays.copyOf(quadBones,capacity);
        quadCube=Arrays.copyOf(quadCube,capacity);
    }

    int boneCount() { return names.size(); }
    int quadCount() { return quads; }
    boolean authoredBoneFlags() { return authoredBoneFlags; }

    /** Builds the immutable runtime objects, trimming every buffer to its exact retained size. */
    Result finish(String meshKey)throws IOException {
        int n=names.size();
        var byName=new HashMap<String,Integer>(Math.max(16,n*2));
        for(int i=0;i<n;i++)if(byName.put(names.get(i),i)!=null)throw new IOException("Duplicate historical bone");
        int[] parentIndex=new int[n];
        for(int i=0;i<n;i++) {
            String parent=parents.get(i);
            if(parent.isEmpty())parentIndex[i]=-1;
            else {
                Integer index=byName.get(parent);
                if(index==null)throw new IOException("Unknown historical bone parent");
                parentIndex[i]=index;
            }
        }
        float[] pivotArray=new float[n*3],rotationArray=new float[n*3];
        float[] translation=new float[n*3],scale=new float[n*3];
        boolean[] ownHidden=new boolean[n],childrenHidden=new boolean[n];
        int[] glow=new int[n];
        Arrays.fill(scale,1);
        Arrays.fill(glow,255);
        for(int i=0;i<n;i++) {
            float[] pivot=pivots.get(i),rotation=rotations.get(i);
            for(int axis=0;axis<3;axis++) {
                pivotArray[i*3+axis]=pivot[axis]/16;
                rotationArray[i*3+axis]=rotation[axis];
            }
            // The historical glow convention: a bone whose name carries the marker emits full glow.
            if(names.get(i).startsWith("ysmGlow"))glow[i]=15;
        }
        Skeleton skeleton;
        try { skeleton=new Skeleton(names,parentIndex,pivotArray); }
        catch(IllegalArgumentException bad) { throw new IOException("Invalid historical skeleton",bad); }

        float[] min={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        float[] max={Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int v=0;v<vertices;v++)for(int axis=0;axis<3;axis++) {
            float value=positions[v*3+axis];
            if(value<min[axis])min[axis]=value;
            if(value>max[axis])max[axis]=value;
        }
        if(vertices==0){Arrays.fill(min,0);Arrays.fill(max,0);}

        int[] indices=new int[quads*6];
        var faces=new ArrayList<ModelDocument.Face>(quads);
        for(int quad=0;quad<quads;quad++) {
            int start=quad*4;
            indices[quad*6]=start;
            indices[quad*6+1]=start+1;
            indices[quad*6+2]=start+2;
            indices[quad*6+3]=start;
            indices[quad*6+4]=start+2;
            indices[quad*6+5]=start+3;
            faces.add(new ModelDocument.Face(quadBones[quad],quadCube[quad],start,"historical-quad",false));
        }
        var batch=new VertexBatch(
                Arrays.copyOf(positions,vertices*3),
                Arrays.copyOf(normals,vertices*3),
                Arrays.copyOf(vertexBones,vertices));
        var mesh=new ModelDocument.Mesh(meshKey,batch,Arrays.copyOf(uvs,vertices*2),indices,faces,
                new ModelDocument.Bounds(min[0],min[1],min[2],max[0],max[1],max[2]),0);
        var pose=new BonePose(rotationArray,translation,scale,ownHidden,childrenHidden,glow);
        return new Result(skeleton,mesh,pose,authoredBoneFlags);
    }

    /** The lowered geometry, ready to be attached to a variant. */
    record Result(Skeleton skeleton,ModelDocument.Mesh mesh,BonePose pose,boolean authoredBoneFlags) { }
}
