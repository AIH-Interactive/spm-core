package dev.spm.core.render.cpu;

import dev.spm.core.api.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoneMaterialTest {
    @Test void rgbaAndGlowArePerBoneAndDoNotInheritFromParent() {
        Skeleton skeleton=new Skeleton(List.of("root","child"),new int[]{-1,0},new float[6]);
        BonePose identity=BonePose.identity(2);
        BonePose pose=new BonePose(identity.rotations(),identity.translations(),identity.scales(),identity.ownHidden(),identity.childrenHidden(),new int[]{7,255},new int[]{0x80563412,0xffffffff});
        BoneMatrices matrices=BoneEvaluator.evaluate(skeleton,pose);
        assertArrayEquals(new int[]{0x80563412,0xffffffff},matrices.colorsRgba());
        VertexBatch output=JavaCpuRenderer.transform(new VertexBatch(new float[]{1,0,0,1,0,0},new float[]{0,1,0,0,1,0},new int[]{0,1}),matrices);
        assertArrayEquals(new int[]{0x80563412,0xffffffff},output.colorsRgba());
        assertArrayEquals(new int[]{7,255},output.glow());
        int[] borrowed=pose.colorsRgba();borrowed[0]=0;
        assertEquals(0x80563412,pose.colorsRgba()[0]);
    }
}
