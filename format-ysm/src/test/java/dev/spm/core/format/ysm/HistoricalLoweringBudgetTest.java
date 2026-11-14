package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

final class HistoricalLoweringBudgetTest {
    @Test void allocationByteArithmeticRejectsNegativeAndOverflowingCounts(){
        assertThrows(IllegalArgumentException.class,()->HistoricalLowering.arrayBytes(-1,4));
        assertThrows(ArithmeticException.class,()->HistoricalLowering.arrayBytes(Long.MAX_VALUE,4));
    }
    private static Struct s(Object... pairs){var fields=new java.util.LinkedHashMap<String,HistoricalValue>();for(int i=0;i<pairs.length;i+=2)fields.put((String)pairs[i],(HistoricalValue)pairs[i+1]);return new Struct(fields);}
    private static Text t(String value){return new Text(value);}
    private static Unsigned u(long value){return new Unsigned(32,value);}
    private static Float32 f(float value){return new Float32(Float.floatToRawIntBits(value));}
    private static Sequence seq(HistoricalValue...values){return new Sequence(List.of(values));}
    private static Mapping map(Entry...entries){return new Mapping(List.of(entries));}
    private static Struct model(boolean withQuad){return model(withQuad?1:0);}
    private static Struct model(int quadCount){
        var point0=s("position",seq(f(0),f(0),f(0)),"uv",seq(f(0),f(0)));
        var point1=s("position",seq(f(1),f(0),f(0)),"uv",seq(f(1),f(0)));
        var point2=s("position",seq(f(1),f(1),f(0)),"uv",seq(f(1),f(1)));
        var point3=s("position",seq(f(0),f(1),f(0)),"uv",seq(f(0),f(1)));
        var quad=s("normal",seq(f(0),f(0),f(1)),"vertices",seq(point0,point1,point2,point3));
        var bone=s("name",t("root"),"parent",t(""),"pivot",seq(f(0),f(0),f(0)),"rotation",seq(f(0),f(0),f(0)),
                "dont_render",new Bool(false),"hidden",new Bool(false),"cubes_hidden",new Bool(false),"hide_children",new Bool(false),"reset",new Bool(false),
                "quads",new Sequence(java.util.Collections.nCopies(quadCount,quad)));
        var geo=s("bones",seq(bone));var texture=s("width",u(16),"height",u(16),"bytes",new Binary(new byte[4]));
        return s("identity",t("fixture"),"textures",map(new Entry(t("default"),new Presence(true,texture))),
                "geos",map(new Entry(u(1),new Presence(true,geo))),"animations",map());
    }
    private static long importCharge(boolean quad)throws Exception{return importCharge(quad?1:0,1<<20);}
    private static long importCharge(int quads)throws Exception{return importCharge(quads,1<<20);}
    private static long importCharge(int quads,long limit)throws Exception{
        var budget=new ResourceBudget(limit);
        try(var source=new YsmHistoricalModel(1,model(quads),List.of());var result=YsmModelImporter.importPayload(source,DecodePolicy.localModel(),budget,new CancellationToken())){
            assertEquals(1,result.variants().size());assertTrue(result.variants().get(0).document().isPresent());return budget.used();
        }
    }
    @Test void retainedChargeRejectsOneByteBelowAndAdmitsTheExactMeasuredPayload()throws Exception{
        long exact=importCharge(1);
        var rejectedBudget=new ResourceBudget(exact-1);
        try(var source=new YsmHistoricalModel(1,model(1),List.of())){
            assertThrows(java.io.IOException.class,()->YsmModelImporter.importPayload(source,DecodePolicy.localModel(),rejectedBudget,new CancellationToken()));
        }
        assertEquals(0,rejectedBudget.used());
        var exactBudget=new ResourceBudget(exact);
        try(var source=new YsmHistoricalModel(1,model(1),List.of());var result=YsmModelImporter.importPayload(source,DecodePolicy.localModel(),exactBudget,new CancellationToken())){
            assertTrue(result.variants().get(0).document().isPresent());assertEquals(exact,exactBudget.used());
        }
        assertEquals(0,exactBudget.used());
    }
    private static long importCollectorCharge(int quadCount)throws Exception{
        var collector=new HistoricalMeshCollector();int bone=collector.bone("root","",new float[3],new float[3],false);
        for(int i=0;i<quadCount;i++)collector.quad(bone,0,0,1,new float[]{0,0,0,1,0,0,1,1,0,0,1,0},new float[]{0,0,1,0,1,1,0,1});
        var root=s("info",s("hash",t("fixture")),"common",s("functions",map()),
                "player",s("textures",map(),"geos",map(new Entry(u(1),new Presence(true,s("bones",seq())))),"animations",map(),"controllers",map()));
        var budget=new ResourceBudget(1<<20);var meshes=Map.of(1L,collector);
        try(var source=new YsmHistoricalModel(19,root,null,meshes,List.of());var result=YsmModelImporter.importPayload(source,DecodePolicy.localModel(),budget,new CancellationToken())){
            assertEquals(1,result.variants().size());assertTrue(result.variants().get(0).document().isEmpty());return budget.used();
        }
    }
    @Test void oneQuadChargeIncludesAllBackingArraysAndDefensiveCopies()throws Exception{
        long empty=importCharge(false),one=importCharge(true);
        long zeroArray=HistoricalLowering.arrayBytes(0,4),vertexFloat=HistoricalLowering.arrayBytes(12,4),uv=HistoricalLowering.arrayBytes(8,4),vertexInt=HistoricalLowering.arrayBytes(4,4),indices=HistoricalLowering.arrayBytes(6,4);
        long expected=2*(vertexFloat-zeroArray)+(uv-zeroArray)+3*(vertexInt-zeroArray)+(indices-zeroArray)+40;
        assertEquals(expected,one-empty,"retained positions/normals/UV/bones/glow/colors/indices and face; temporary defensive copies are a separate peak");
    }
    @Test void treeAndStreamingCollectorPathsChargeTheSameQuadOutput()throws Exception{
        for(int quads:new int[]{1,3}){
            long treeDelta=importCharge(quads)-importCharge(0);
            long collectorDelta=importCollectorCharge(quads)-importCollectorCharge(0);
            long vertices=quads*4L,zero=HistoricalLowering.arrayBytes(0,4);
            long expected=2*(HistoricalLowering.arrayBytes(vertices*3,4)-zero)
                    +(HistoricalLowering.arrayBytes(vertices*2,4)-zero)
                    +3*(HistoricalLowering.arrayBytes(vertices,4)-zero)
                    +(HistoricalLowering.arrayBytes(quads*6L,4)-zero)+40L*quads;
            assertEquals(expected,treeDelta);assertEquals(treeDelta,collectorDelta);
        }
    }
}
