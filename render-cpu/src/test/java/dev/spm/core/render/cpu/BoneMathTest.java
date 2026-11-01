package dev.spm.core.render.cpu;

import dev.spm.core.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoneMathTest {
    private static final float HALF_PI = (float)(Math.PI / 2);
    private static final float EPS = 0.000002f;
    private static Skeleton one(float x, float y, float z) {
        return new Skeleton(List.of("bone"), new int[] {-1}, new float[] {x,y,z});
    }
    private static BonePose pose(float[] r, float[] d, float[] s) {
        return new BonePose(r,d,s,new boolean[r.length/3],new boolean[r.length/3],BonePose.identity(r.length/3).glow());
    }
    @Test void pivotScaleRotationAndLocatorMatchHandCalculatedCoordinates() {
        var state = BoneEvaluator.evaluate(one(1,0,0),pose(new float[] {0,0,HALF_PI},new float[] {0,1,0},new float[] {2,1,1}));
        var output = JavaCpuRenderer.transform(new VertexBatch(new float[] {2,0,0},new float[] {1,1,0},new int[] {0}),state);
        assertArrayEquals(new float[] {1,3,0},output.positions(),EPS);
        assertArrayEquals(new float[] {1,1,0},state.locatorPositions(),EPS);
        assertArrayEquals(new float[] {-0.89442719f,0.4472136f,0},output.normals(),EPS);
    }
    @Test void rotationsAreAppliedXThenYThenZWithoutUnitOrAxisConversion() {
        var state=BoneEvaluator.evaluate(one(0,0,0),pose(new float[] {HALF_PI,HALF_PI,0},new float[] {2,3,4},new float[] {1,1,1}));
        var output=JavaCpuRenderer.transform(new VertexBatch(new float[] {1,0,0,0,1,0,0,0,1},new float[9],new int[] {0,0,0}),state);
        assertArrayEquals(new float[] {2,3,3,3,3,4,2,2,4},output.positions(),EPS);
    }
    @Test void parentOrderAndLocalSubtreeSelectionAreDistinct() {
        var skeleton=new Skeleton(List.of("child","parent"),new int[] {1,-1},new float[6]);
        var pose=pose(new float[6],new float[] {0,2,0,3,0,0},new float[] {1,1,1,1,1,1});
        var source=new VertexBatch(new float[] {1,0,0},new float[] {0,1,0},new int[] {0});
        assertArrayEquals(new float[] {4,2,0},JavaCpuRenderer.transform(source,BoneEvaluator.evaluate(skeleton,pose)).positions(),EPS);
        assertArrayEquals(new float[] {1,2,0},JavaCpuRenderer.transform(source,BoneEvaluator.evaluateSubtree(skeleton,pose,0)).positions(),EPS);
        assertArrayEquals(new boolean[] {true,false},BoneEvaluator.evaluateSubtree(skeleton,pose,0).visible());
    }
    @Test void ownHiddenDoesNotHideDescendantsAndGlowDoesNotInherit() {
        var skeleton=new Skeleton(List.of("root","child"),new int[] {-1,0},new float[6]);
        var identity=BonePose.identity(2);
        var pose=new BonePose(identity.rotations(),identity.translations(),identity.scales(),new boolean[] {true,false},new boolean[2],new int[] {7,255});
        var state=BoneEvaluator.evaluate(skeleton,pose);
        assertArrayEquals(new boolean[] {false,true},state.visible());
        assertArrayEquals(new boolean[] {true,true},state.descendantVisible());
        var output=JavaCpuRenderer.transform(new VertexBatch(new float[6],new float[6],new int[] {0,1}),state);
        assertEquals(1,output.vertexCount()); assertArrayEquals(new int[] {1},output.boneIndices());
        assertArrayEquals(new int[] {255},output.glow());
    }
    @Test void childrenHiddenAndZeroScaleSuppressOnlyAffectedSubtrees() {
        var skeleton=new Skeleton(List.of("root","child","sibling"),new int[] {-1,0,-1},new float[9]);
        var identity=BonePose.identity(3);
        var hidden=new BonePose(identity.rotations(),identity.translations(),identity.scales(),new boolean[3],new boolean[] {true,false,false},identity.glow());
        assertArrayEquals(new boolean[] {true,false,true},BoneEvaluator.evaluate(skeleton,hidden).visible());
        float[] scale=identity.scales(); scale[0]=0;
        assertArrayEquals(new boolean[] {false,false,true},BoneEvaluator.evaluate(skeleton,pose(identity.rotations(),identity.translations(),scale)).visible());
    }
    @Test void explicitRootTransformAndNegativeScaleHaveCorrectNormals() {
        float[] root={1,0,0,0,0,1,0,0,0,0,1,0,4,5,6,1};
        var state=BoneEvaluator.evaluate(one(0,0,0),pose(new float[3],new float[3],new float[] {-2,1,1}),root);
        var output=JavaCpuRenderer.transform(new VertexBatch(new float[] {1,2,3},new float[] {1,1,0},new int[] {0}),state);
        assertArrayEquals(new float[] {2,7,9},output.positions(),EPS);
        assertArrayEquals(new float[] {-0.4472136f,0.89442719f,0},output.normals(),EPS);
    }
    @Test void inputAndOutputArraysAreIsolated() {
        var identity=BonePose.identity(1); var translations=identity.translations(); translations[0]=100;
        var state=BoneEvaluator.evaluate(one(0,0,0),identity); state.transforms()[12]=100; state.visible()[0]=false;
        assertEquals(0,state.matrix(0,12)); assertTrue(state.visible()[0]);
    }
    @Test void nonfiniteInputOverflowAndInvalidIndicesFailExplicitly() {
        assertThrows(IllegalArgumentException.class,()->pose(new float[] {Float.NaN,0,0},new float[3],new float[] {1,1,1}));
        var skeleton=new Skeleton(List.of("root","child"),new int[] {-1,0},new float[6]);
        var huge=pose(new float[6],new float[6],new float[] {Float.MAX_VALUE,1,1,2,1,1});
        assertThrows(IllegalArgumentException.class,()->BoneEvaluator.evaluate(skeleton,huge));
        assertThrows(IllegalArgumentException.class,()->BoneEvaluator.evaluate(one(0,0,0),BonePose.identity(2)));
        assertThrows(IndexOutOfBoundsException.class,()->BoneEvaluator.evaluateSubtree(one(0,0,0),BonePose.identity(1),-1));
        var state=BoneEvaluator.evaluate(one(0,0,0),BonePose.identity(1));
        assertThrows(IllegalArgumentException.class,()->JavaCpuRenderer.transform(new VertexBatch(new float[3],new float[3],new int[] {1}),state));
        assertThrows(IllegalArgumentException.class,()->BoneEvaluator.evaluate(one(0,0,0),BonePose.identity(1),new float[16]));
    }
    @Test void emptyGeometryAndZeroNormalAreValid() {
        var empty=BoneEvaluator.evaluate(new Skeleton(List.of(),new int[0],new float[0]),BonePose.identity(0));
        assertEquals(0,JavaCpuRenderer.transform(new VertexBatch(new float[0],new float[0],new int[0]),empty).vertexCount());
        var state=BoneEvaluator.evaluate(one(0,0,0),BonePose.identity(1));
        assertArrayEquals(new float[3],JavaCpuRenderer.transform(new VertexBatch(new float[3],new float[3],new int[] {0}),state).normals());
    }
}
