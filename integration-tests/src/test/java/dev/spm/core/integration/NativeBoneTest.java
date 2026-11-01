package dev.spm.core.integration;

import dev.spm.core.acceleration.Isa;
import dev.spm.core.api.BonePose;
import dev.spm.core.api.Skeleton;
import dev.spm.core.nativejni.NativeBoneEvaluator;
import dev.spm.core.nativejni.NativeTransforms;
import dev.spm.core.render.cpu.BoneEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="spm.native.library",matches=".+")
class NativeBoneTest {
    @Test void localSubtreeIgnoresHiddenAndZeroScaleAncestors() throws Exception {
        Path path=Path.of(System.getProperty("spm.native.library"));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        NativeTransforms.load(path,hash);
        Skeleton skeleton=new Skeleton(List.of("root","arm","hand"),new int[]{-1,0,1},new float[9]);
        BonePose base=BonePose.identity(3);float[] scale=base.scales();scale[0]=0;
        BonePose pose=new BonePose(base.rotations(),new float[]{9,9,9,1,2,3,2,3,4},scale,new boolean[]{true,false,false},new boolean[]{true,false,false},base.glow());
        var expected=BoneEvaluator.evaluateSubtree(skeleton,pose,1);
        var actual=NativeBoneEvaluator.evaluateSubtree(skeleton,pose,1,Isa.AUTO);
        assertArrayEquals(expected.transforms(),actual.transforms(),0.00001f);
        assertArrayEquals(expected.visible(),actual.visible());
        assertArrayEquals(expected.locatorPositions(),actual.locatorPositions(),0.00001f);
    }
    @Test void rustBonesMatchAllowedSourceJavaOracleWithHierarchyHiddenFlagsAndNormalMatrices() throws Exception {
        Path path=Path.of(System.getProperty("spm.native.library"));
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        var provider=NativeTransforms.load(path,hash);
        var skeleton=new Skeleton(List.of("child","root","leaf"),new int[]{1,-1,0},new float[]{1,2,3,0,1,0,4,5,6});
        float[] r={0.1f,0.2f,0.3f, -0.2f,0.4f,-0.1f, 0.5f,0.2f,-0.3f};
        float[] t={1,2,3, -1,-2,-3, 0,0,0};
        float[] s={-1,2,3, 2,3,4, 1,1,1};
        float[] root={1,0,0,0,0,2,0,0,0,0,3,0,5,6,7,1};
        for(int scenario=0;scenario<4;scenario++) {
            boolean[] own={scenario==1,false,false},children={scenario==2,false,false};
            float[] scales=s.clone(); if(scenario==3)scales[0]=0;
            var pose=new BonePose(r,t,scales,own,children,new int[]{15,255,3},new int[]{0x80563412,0xffffffff,0x01020304});
            var expected=BoneEvaluator.evaluate(skeleton,pose,root);
            for(Isa isa:Isa.values()) if(provider.supported(isa)) {
                var actual=NativeBoneEvaluator.evaluate(skeleton,pose,root,isa);
                assertArrayEquals(expected.transforms(),actual.transforms(),0.0001f);
                assertArrayEquals(expected.normals(),actual.normals(),0.0001f);
                assertArrayEquals(expected.locatorPositions(),actual.locatorPositions(),0.0001f);
                assertArrayEquals(expected.visible(),actual.visible());
                assertArrayEquals(expected.descendantVisible(),actual.descendantVisible());
                assertArrayEquals(expected.glow(),actual.glow());
                assertArrayEquals(expected.colorsRgba(),actual.colorsRgba());
            }
        }
    }
}
