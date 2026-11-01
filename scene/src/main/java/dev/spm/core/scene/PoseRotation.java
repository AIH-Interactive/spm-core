package dev.spm.core.scene;

/** Final-local ZYX rotation interpolation. Initial rotation has already been included in both inputs. */
final class PoseRotation {
    private PoseRotation() { }
    static void axes(float[] start,float[] end,int offset,double alpha,boolean shortest,float[] result) {
        for (int axis=0;axis<3;axis++) {
            int index=offset+axis; double delta=(double)end[index]-start[index];
            if (shortest) delta-=Math.floor((delta+Math.PI)/(2*Math.PI))*2*Math.PI;
            result[index]=PoseAnimationLibrary.representable(start[index]+delta*alpha);
        }
    }
    static void blend(float[] start,float[] end,int offset,double alpha,float[] result) {
        if (alpha<=0) { System.arraycopy(start,offset,result,offset,3); return; }
        if (alpha>=1) { System.arraycopy(end,offset,result,offset,3); return; }
        double[] a=quaternion(start,offset),b=quaternion(end,offset);
        double dot=0;
        for (int i=0;i<4;i++) dot+=a[i]*b[i];
        double sign=dot<0?-1:1,norm=0;
        double[] q=new double[4];
        for (int i=0;i<4;i++) { q[i]=a[i]*(1-alpha)+b[i]*alpha*sign; norm+=q[i]*q[i]; }
        norm=Math.sqrt(norm);
        for (int i=0;i<4;i++) q[i]/=norm;
        double x=q[0],y=q[1],z=q[2],w=q[3];
        result[offset]=PoseAnimationLibrary.representable(Math.atan2(2*(y*z+w*x),1-2*(x*x+y*y)));
        result[offset+1]=PoseAnimationLibrary.representable(Math.asin(Math.max(-1,Math.min(1,2*(w*y-x*z)))));
        result[offset+2]=PoseAnimationLibrary.representable(Math.atan2(2*(x*y+w*z),1-2*(y*y+z*z)));
    }
    private static double[] quaternion(float[] euler,int offset) {
        double x=euler[offset]/2.0,y=euler[offset+1]/2.0,z=euler[offset+2]/2.0;
        double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
        return new double[]{cz*cy*sx-sz*sy*cx,cz*sy*cx+sz*cy*sx,sz*cy*cx-cz*sy*sx,cz*cy*cx+sz*sy*sx};
    }
}
