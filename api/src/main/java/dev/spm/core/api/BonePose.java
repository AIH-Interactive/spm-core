package dev.spm.core.api;

import java.util.Arrays;

/** Immutable local channels. Coordinates and deltas are already in Core units and orientation.
 * Rotations are final local Euler radians, including any model initial rotation.
 * Glow 255 preserves host light; levels 0..15 override it.
 * colorsRgba packs red in bits 0..7, green 8..15, blue 16..23 and alpha 24..31
 * (numeric 0xAABBGGRR, RGBA bytes in little-endian memory). */
public record BonePose(float[] rotations, float[] translations, float[] scales,
                       boolean[] ownHidden, boolean[] childrenHidden, int[] glow, int[] colorsRgba) {
    public BonePose(float[] rotations,float[] translations,float[] scales,boolean[] ownHidden,boolean[] childrenHidden,int[] glow) {
        this(rotations,translations,scales,ownHidden,childrenHidden,glow,white(ownHidden.length));
    }
    public BonePose {
        rotations = rotations.clone(); translations = translations.clone(); scales = scales.clone();
        ownHidden = ownHidden.clone(); childrenHidden = childrenHidden.clone(); glow = glow.clone();
        colorsRgba=colorsRgba.clone();
        int n = ownHidden.length;
        int components = Math.multiplyExact(n, 3);
        if (rotations.length != components || translations.length != components || scales.length != components
                || childrenHidden.length != n || glow.length != n || colorsRgba.length!=n) throw new IllegalArgumentException("Pose sizes disagree");
        for (float[] values : new float[][] {rotations, translations, scales}) {
            for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite channel");
        }
        for (int value : glow) if (value != 255 && (value < 0 || value > 15)) throw new IllegalArgumentException("Invalid glow");
    }
    public static BonePose identity(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative bone count");
        int components = Math.multiplyExact(count, 3);
        float[] scale = new float[components]; Arrays.fill(scale, 1);
        int[] glow = new int[count]; Arrays.fill(glow, 255);
        return new BonePose(new float[components], new float[components], scale,
                new boolean[count], new boolean[count], glow);
    }
    public int boneCount() { return ownHidden.length; }
    private static int[] white(int n){int[] values=new int[n];Arrays.fill(values,0xffffffff);return values;}
    @Override public float[] rotations() { return rotations.clone(); }
    @Override public float[] translations() { return translations.clone(); }
    @Override public float[] scales() { return scales.clone(); }
    @Override public boolean[] ownHidden() { return ownHidden.clone(); }
    @Override public boolean[] childrenHidden() { return childrenHidden.clone(); }
    @Override public int[] glow() { return glow.clone(); }
    @Override public int[] colorsRgba(){return colorsRgba.clone();}
}
