package dev.spm.core.api;

import java.util.Objects;

/** Immutable logical column-major matrices, independent of any native ABI.
 * Normals are complete inverse transposes, not compact normalized approximations.
 * Invisible bones must not emit vertices or locators. descendantVisible controls child traversal. */
public record BoneMatrices(float[] transforms, float[] normals, boolean[] visible,
                           boolean[] descendantVisible, float[] locatorPositions, int[] glow, int[] colorsRgba) {
    public BoneMatrices(float[] transforms,float[] normals,boolean[] visible,boolean[] descendantVisible,float[] locatorPositions,int[] glow){
        this(transforms,normals,visible,descendantVisible,locatorPositions,glow,white(visible.length));
    }
    public BoneMatrices {
        transforms = transforms.clone(); normals = normals.clone(); visible = visible.clone();
        descendantVisible = descendantVisible.clone(); locatorPositions = locatorPositions.clone(); glow = glow.clone();
        colorsRgba=colorsRgba.clone();
        int n = visible.length;
        if (transforms.length != Math.multiplyExact(n, 16) || normals.length != Math.multiplyExact(n, 9)
                || locatorPositions.length != Math.multiplyExact(n, 3) || descendantVisible.length != n || glow.length != n || colorsRgba.length!=n)
            throw new IllegalArgumentException("Matrix sizes disagree");
        for (float[] values : new float[][] {transforms, normals, locatorPositions}) {
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite result");
        }
        for (int value : glow) if (value != 255 && (value < 0 || value > 15)) throw new IllegalArgumentException("Invalid glow");
    }
    public int boneCount() { return visible.length; }
    private static int[] white(int n){int[] values=new int[n];java.util.Arrays.fill(values,0xffffffff);return values;}
    public float matrix(int bone, int element) {
        Objects.checkIndex(bone, boneCount()); Objects.checkIndex(element, 16);
        return transforms[bone * 16 + element];
    }
    public float normal(int bone, int element) {
        Objects.checkIndex(bone, boneCount()); Objects.checkIndex(element, 9);
        return normals[bone * 9 + element];
    }
    @Override public float[] transforms() { return transforms.clone(); }
    @Override public float[] normals() { return normals.clone(); }
    @Override public boolean[] visible() { return visible.clone(); }
    @Override public boolean[] descendantVisible() { return descendantVisible.clone(); }
    @Override public float[] locatorPositions() { return locatorPositions.clone(); }
    @Override public int[] glow() { return glow.clone(); }
    @Override public int[] colorsRgba(){return colorsRgba.clone();}
}
