package dev.spm.core.api;

import java.util.Arrays;

/** Immutable positions, normals and one rigid bone index per vertex. No skinning is implied.
 * colorsRgba uses numeric 0xAABBGGRR, as specified by {@link BonePose}. */
public record VertexBatch(float[] positions, float[] normals, int[] boneIndices, int[] glow,int[] colorsRgba) {
    public VertexBatch(float[] positions,float[] normals,int[] boneIndices,int[] glow){this(positions,normals,boneIndices,glow,white(boneIndices.length));}
    public VertexBatch(float[] positions, float[] normals, int[] boneIndices) {
        this(positions, normals, boneIndices, hostLight(boneIndices.length));
    }
    public VertexBatch {
        positions = positions.clone(); normals = normals.clone(); boneIndices = boneIndices.clone(); glow = glow.clone();
        colorsRgba=colorsRgba.clone();
        int n = boneIndices.length;
        if (positions.length != Math.multiplyExact(n, 3) || normals.length != positions.length || glow.length != n || colorsRgba.length!=n)
            throw new IllegalArgumentException("Vertex sizes disagree");
        for (float[] values : new float[][] {positions, normals}) {
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vertex");
        }
        for (int value : boneIndices) if (value < 0) throw new IllegalArgumentException("Negative bone index");
        for (int value : glow) if (value != 255 && (value < 0 || value > 15)) throw new IllegalArgumentException("Invalid glow");
    }
    private static int[] hostLight(int n) { int[] values = new int[n]; Arrays.fill(values, 255); return values; }
    private static int[] white(int n){int[] values=new int[n];Arrays.fill(values,0xffffffff);return values;}
    public int vertexCount() { return boneIndices.length; }
    @Override public float[] positions() { return positions.clone(); }
    @Override public float[] normals() { return normals.clone(); }
    @Override public int[] boneIndices() { return boneIndices.clone(); }
    @Override public int[] glow() { return glow.clone(); }
    @Override public int[] colorsRgba(){return colorsRgba.clone();}
}
