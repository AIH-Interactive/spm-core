package dev.spm.core.acceleration;

/** Complete scalar implementation of this batch operation; no incubator module. */
public final class ScalarTransforms {
    private ScalarTransforms() { }
    public static float[] transform(float[] matrix,float[] positions) {
        validate(matrix,positions);
        float[] result=new float[positions.length];
        for(int i=0;i<positions.length;i+=3) {
            for(int row=0;row<3;row++) {
                float value=((matrix[row]*positions[i]+matrix[4+row]*positions[i+1])+matrix[8+row]*positions[i+2])+matrix[12+row];
                if(!Float.isFinite(value)) throw new IllegalArgumentException("Transform overflow");
                result[i+row]=value;
            }
        }
        return result;
    }
    public static void validate(float[] matrix,float[] positions) {
        if(matrix.length!=16 || positions.length%3!=0) throw new IllegalArgumentException("Invalid transform dimensions");
        for(float value:matrix) if(!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite matrix");
        for(float value:positions) if(!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite position");
    }
}
