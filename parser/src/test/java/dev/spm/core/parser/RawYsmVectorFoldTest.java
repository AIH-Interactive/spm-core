package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.ysm.*;
import dev.spm.core.scene.MolangContext;
import dev.spm.core.scene.PoseAnimationLibrary;
import dev.spm.core.scene.ScriptBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Vector folding on the raw V1/V2 archive path.
 *
 * <p>The fold is confined to the animation trees the raw parser assembles, because the shared
 * {@code BoundedJson} reader also feeds the bbmodel and bedrock importers, which require a literal
 * {@code JsonArray}. These tests fix both halves of that contract: a coordinate triple becomes
 * {@link JsonVector3}, and everything else keeps the array form the other readers demand.
 */
final class RawYsmVectorFoldTest {
    private static final DecodePolicy POLICY=DecodePolicy.conservative();
    private static final byte[] GEO=("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        +"\"description\":{\"identifier\":\"geometry.handmade\",\"texture_width\":16,\"texture_height\":16},"
        +"\"bones\":[{\"name\":\"root\",\"pivot\":[1.5,2.25,3.0],"
        +"\"cubes\":[{\"origin\":[-2.625,22.45335,-1.42286],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}")
        .getBytes(StandardCharsets.UTF_8);

    /** A triple that is exactly representable as three floats folds. */
    @Test void exactlyRepresentableTripleFoldsIntoAnInlineVector() throws Exception {
        var root=bone("{\"rotation\":[0,0,90],\"position\":[16,0,0],\"scale\":[1,1,1]}");
        assertEquals(new JsonVector3(0f,0f,90f),root.values().get("rotation"));
        assertEquals(new JsonVector3(16f,0f,0f),root.values().get("position"));
        assertEquals(new JsonVector3(1f,1f,1f),root.values().get("scale"));
    }

    /** The archival document and the executable definitions must share one folded subtree. */
    @Test void theFoldedSubtreeIsSharedByTheArchivedDocumentAndTheDefinitions() throws Exception {
        var metadata=importMetadata("{\"animations\":{\"idle\":{\"bones\":{\"root\":{\"rotation\":[0,0,90]}}}}}");
        var document=objectOf(objectOf(objectOf(metadata,"ysm"),"animation_sets"),"main");
        var archived=objectOf(objectOf(document,"document"),"animations");
        var definitions=objectOf(metadata,"animations");
        // The two top objects are distinct wrappers, but every node below them is one instance: that
        // is what keeps the fold from being retained twice.
        assertNotSame(archived,definitions);
        assertSame(objectOf(archived,"idle"),objectOf(definitions,"idle"));
        assertSame(boneOf(archived),boneOf(definitions));
        assertEquals(new JsonVector3(0f,0f,90f),boneOf(definitions).values().get("rotation"));
    }

    /** Anything that is not an exactly representable numeric triple keeps the array form. */
    @Test void nonTriplesNonNumbersAndInexactComponentsKeepTheArray() throws Exception {
        var root=bone("{\"two\":[0,90],\"four\":[0,0,0,1],\"molang\":[0,\"q.anim_time*90\",0],"
            +"\"strings\":[\"a\",\"b\",\"c\"],\"inexact\":[0.1,0,0],\"inexactMiddle\":[0,2.45335,0],"
            +"\"overflow\":[1e300,0,0],\"underflow\":[1e-300,0,0],\"mixed\":[0,true,0],\"nulled\":[0,null,0]}");
        for(String field:List.of("two","four","molang","strings","inexact","inexactMiddle","overflow","underflow","mixed","nulled"))
            assertInstanceOf(JsonArray.class,root.values().get(field),field+" must keep the array form");
        assertEquals(List.of(new JsonNumber(0),new JsonNumber(90)),((JsonArray)root.values().get("two")).values());
        assertEquals(4,((JsonArray)root.values().get("four")).values().size());
        // The inexact middle component is the point: 2.45335 needs more than 24 bits of mantissa.
        assertNotEquals(2.45335,(double)(float)2.45335);
    }

    /**
     * Negative zero is folded as it arrives, and the predicate is what keeps its sign.
     *
     * <p>The JSON reader cannot currently deliver {@code -0.0}: {@code BigDecimal("-0.0").doubleValue()}
     * is {@code 0.0}, so the sign is already gone before folding. The predicate is therefore asserted
     * directly, which is the only place the guarantee can be observed today; a future reader that
     * preserves the sign would still fold it correctly rather than turning it into {@code +0.0}.
     */
    @Test void theFoldPredicateKeepsTheSignOfNegativeZero() {
        JsonVector3 folded=RawYsmParser.vector(List.of(new JsonNumber(-0.0),new JsonNumber(-0.0),new JsonNumber(-0.0)));
        assertNotNull(folded);
        for(float component:new float[]{folded.x(),folded.y(),folded.z()})
            assertEquals(Double.doubleToRawLongBits(-0.0),Double.doubleToRawLongBits((double)component));
    }

    /**
     * The fold must never reach a list the raw parser reads back as a list.
     *
     * <p>{@code optionalArray} rejects anything that is not a {@code JsonArray}, so a folded
     * three-element {@code texture} or {@code match} list would fail the import with
     * "Expected raw list". Both are string lists, and this test is what keeps them that way.
     */
    @Test void threeElementStringListsTheParserReadsBackAreNeverFolded() throws Exception {
        var files=legacy();
        put(files,"ysm.json","""
            {"files":{"player":{"model":{"main":"main.json","arm":"arm.json"},
              "texture":["one.png","two.png","three.png"],
              "animation":{"main":"walk.animation.json"}},
              "projectiles":[{"model":"arrow.json","texture":"arrow.png","match":["a","b","c"]}]}}
            """);
        put(files,"walk.animation.json","{\"animations\":{\"walk\":{\"loop\":true}}}");
        files.put(new ResourceKey("one.png"),new byte[]{1});
        files.put(new ResourceKey("two.png"),new byte[]{2});
        files.put(new ResourceKey("three.png"),new byte[]{3});
        files.put(new ResourceKey("arrow.png"),new byte[]{4});
        files.put(new ResourceKey("arrow.json"),GEO);
        try(var result=importArchive(files)) {
            assertEquals(3,result.models().size());
            var playerYsm=objectOf(result.models().get(0).asset().document().metadata(),"ysm");
            assertEquals(3,objectOf(playerYsm,"texture_sets").size());
            var projectileYsm=objectOf(result.models().get(2).asset().document().metadata(),"ysm");
            var match=assertInstanceOf(JsonArray.class,projectileYsm.values().get("match"));
            assertEquals(3,match.values().size());
            assertEquals(new JsonString("c"),match.values().get(2));
        }
    }

    /** Legacy info names are read back as a list too, and must stay one. */
    @Test void threeLegacyExtraAnimationNamesStayAList() throws Exception {
        var files=legacy();
        remove(files,"ysm.json");
        put(files,"info.json","{\"extra_animation_names\":[\"One\",\"Two\",\"Three\"]}");
        put(files,"walk.animation.json","{\"animations\":{\"walk\":{\"loop\":true}}}");
        put(files,"main.animation.json","{\"animations\":{\"idle\":{\"loop\":true}}}");
        try(var result=importArchive(files)) {
            var manifest=objectOf(objectOf(result.models().get(0).asset().document().metadata(),"ysm"),"manifest");
            var extra=objectOf(objectOf(manifest,"properties"),"extra_animation");
            assertEquals(3,extra.size());
            assertEquals(new JsonString("Three"),extra.values().get("extra2"));
        }
    }

    /** Geometry is imported by the bedrock reader, which still demands real arrays. */
    @Test void geometryTriplesAreNotFolded() throws Exception {
        var geometry=assertInstanceOf(JsonArray.class,
            importMetadata("{\"animations\":{\"idle\":{\"loop\":true}}}").values().get("minecraft:geometry"));
        var entry=assertInstanceOf(JsonObject.class,geometry.values().get(0));
        var bones=assertInstanceOf(JsonArray.class,entry.values().get("bones"));
        var bone=assertInstanceOf(JsonObject.class,bones.values().get(0));
        // A bone pivot and a cube origin are exactly the triples the fold would otherwise claim.
        assertInstanceOf(JsonArray.class,bone.values().get("pivot"));
        var cubes=assertInstanceOf(JsonArray.class,bone.values().get("cubes"));
        var cube=assertInstanceOf(JsonObject.class,cubes.values().get(0));
        assertInstanceOf(JsonArray.class,cube.values().get("origin"));
        assertInstanceOf(JsonArray.class,cube.values().get("size"));
    }

    /** The executable decoder must accept the folded triple and produce the same pose. */
    @Test void theExecutableDecoderSamplesTheFoldedTripleExactlyLikeTheArray() throws Exception {
        var skeleton=new Skeleton(List.of("root"),new int[]{-1},new float[]{0,0,0});
        var budget=new ScriptBudget(1_000_000,128,new CancellationToken());
        var context=new MolangContext(Map.of(),Map.of(),Map.of(),budget);
        var bind=BonePose.identity(1);
        var inlineAnimation=animationsRoot("{\"animations\":{\"idle\":{\"bones\":{\"root\":{\"rotation\":[0,0,90]}}}}}");
        var inline=PoseAnimationLibrary.decode(inlineAnimation,skeleton,budget);
        assertEquals(Math.toRadians(90),inline.sample("idle",0,context,bind).rotations()[2],1e-5);
        // The inexact neighbour keeps the array form, decodes to the same authored angle, and must not
        // be narrowed: 2.45335-style components are common in real archives.
        var keptAnimation=animationsRoot("{\"animations\":{\"idle\":{\"bones\":{\"root\":{\"rotation\":[0,0,90.000001]}}}}}");
        var kept=PoseAnimationLibrary.decode(keptAnimation,skeleton,budget);
        assertEquals(Math.toRadians(90),kept.sample("idle",0,context,bind).rotations()[2],1e-3);
    }

    /** Direct fold predicate: the arms JSON cannot express, because it has no non-finite number. */
    @Test void theFoldPredicateRejectsEverythingItCannotRepresent() {
        assertEquals(new JsonVector3(1f,2f,3f),
            RawYsmParser.vector(List.of(new JsonNumber(1),new JsonNumber(2),new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1),new JsonNumber(2))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1),new JsonNumber(2),new JsonNumber(3),new JsonNumber(4))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1),new JsonString("2"),new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1),JsonNull.INSTANCE,new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1),new JsonBoolean(true),new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of(new JsonArray(List.of(new JsonNumber(1))),new JsonNumber(2),new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of()));
        // Finite as a double but infinite as a float: folding would destroy the value.
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1e300),new JsonNumber(2),new JsonNumber(3))));
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(-1e300),new JsonNumber(2),new JsonNumber(3))));
        // Denormal-as-float is fine only when it is genuinely exact; 1e-300 is not.
        assertNull(RawYsmParser.vector(List.of(new JsonNumber(1e-300),new JsonNumber(2),new JsonNumber(3))));
        // The extremes that are still exact must fold.
        assertEquals(new JsonVector3(Float.MAX_VALUE,0f,0f),
            RawYsmParser.vector(List.of(new JsonNumber(Float.MAX_VALUE),new JsonNumber(0),new JsonNumber(0))));
        assertEquals(new JsonVector3(0.1f,-2.5f,0.75f),
            RawYsmParser.vector(List.of(new JsonNumber(0.1f),new JsonNumber(-2.5f),new JsonNumber(0.75f))));
    }

    /** The geometry file itself must still parse through the bedrock reader with real arrays. */
    @Test void theBedrockGeometryReaderStillSeesLiterals() throws Exception {
        var asset=dev.spm.core.format.bedrock.BedrockJsonImporter.importAsset(GEO,POLICY,
            new ResourceBudget(32L<<20),new CancellationToken());
        try(asset){
            assertEquals(1,asset.document().meshes().size());
            assertEquals(24,asset.document().meshes().get(0).vertices().vertexCount());
        }
    }

    private static JsonObject bone(String channels) throws Exception {
        return boneOf(objectOf(importMetadata("{\"animations\":{\"idle\":{\"bones\":{\"root\":"+channels+"}}}}"),"animations"));
    }
    private static JsonObject boneOf(JsonObject animations){
        return objectOf(objectOf(objectOf(animations,"idle"),"bones"),"root");
    }
    private static JsonObject animationsRoot(String animationFile) throws Exception {
        return new JsonObject(Map.of("animations",importMetadata(animationFile).values().get("animations")));
    }
    private static JsonObject importMetadata(String animationFile) throws Exception {
        var files=legacy();
        put(files,"walk.animation.json",animationFile);
        try(var result=importArchive(files)){return result.models().get(0).asset().document().metadata();}
    }
    private static LinkedHashMap<ResourceKey,byte[]> legacy(){
        var files=new LinkedHashMap<ResourceKey,byte[]>();
        files.put(new ResourceKey("main.json"),GEO);files.put(new ResourceKey("arm.json"),GEO);
        files.put(new ResourceKey("skin.png"),new byte[]{1});
        put(files,"ysm.json","{\"files\":{\"player\":{\"model\":{\"main\":\"main.json\",\"arm\":\"arm.json\"},"
            +"\"texture\":[\"skin.png\"],\"animation\":{\"main\":\"walk.animation.json\"}}}}");
        return files;
    }
    private static void remove(Map<ResourceKey,byte[]> files,String name){files.remove(new ResourceKey(name));}
    private static void put(Map<ResourceKey,byte[]> files,String name,String value){
        files.put(new ResourceKey(name),value.getBytes(StandardCharsets.UTF_8));
    }
    private static ImportResult importArchive(Map<ResourceKey,byte[]> files) throws IOException {
        var budget=new ResourceBudget(64L<<20);
        var token=new CancellationToken();
        try(var encoded=YsmArchiveCodec.encode(2,files,POLICY,budget,token)) {
            ModelArchive archive=YsmArchiveCodec.decode(encoded.bytes(),POLICY,budget,token);
            var source=new ModelSource("handmade",new ResourceKey("model.ysm"),key->{throw new IOException("No external source");});
            var request=new ImportRequest(source,FormatHint.YSM,POLICY,budget,token);
            try(var builder=new ParserResultBuilder()) {
                builder.retain(archive);RawYsmParser.parse(archive,request,builder);return builder.finish();
            }
        }
    }
    private static JsonObject objectOf(JsonObject source,String field){
        return assertInstanceOf(JsonObject.class,source.values().get(field),field);
    }
    private static JsonObject objectOf(JsonValue value,String field){
        return assertInstanceOf(JsonObject.class,((JsonObject)value).values().get(field),field);
    }
}
