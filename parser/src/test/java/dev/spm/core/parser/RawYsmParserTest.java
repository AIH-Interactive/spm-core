package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import dev.spm.core.format.ysm.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class RawYsmParserTest {
    private static final DecodePolicy POLICY=DecodePolicy.conservative();
    private static final byte[] GEO=("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{"
        +"\"description\":{\"identifier\":\"geometry.handmade\",\"texture_width\":16,\"texture_height\":16},"
        +"\"bones\":[{\"name\":\"root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}")
        .getBytes(StandardCharsets.UTF_8);

    @Test void importsBothRawArchiveVersionsAndRetainsTheirResourceDomain() throws Exception {
        for(int version:new int[]{1,2}) {
            var budget=new ResourceBudget(32L<<20);
            try(var result=importFiles(version,legacy(),budget)) {
                assertEquals(java.util.List.of("player/main","player/arm"),result.models().stream().map(ImportResult.Model::key).toList());
                var document=result.models().get(0).asset().document();
                assertEquals(24,document.meshes().get(0).vertices().vertexCount());
                assertEquals("skin",document.materials().get(0).name());
                try(var input=result.resources().open(new ResourceKey("skin.png"))){assertArrayEquals(new byte[]{1,2,3},input.readAllBytes());}
                assertTrue(budget.used()>0);
            }
            assertEquals(0,budget.used());
        }
    }

    @Test void manifestPreservesSkinRolesNamedAnimationSourcesControllersAndReplacementMatches() throws Exception {
        var files=legacy();
        put(files,"ysm.json","""
            {"properties":{"default_texture":"second"},"files":{"player":{
              "model":{"main":"main.json","arm":"arm.json"},
              "texture":["skin.png",{"uv":"second.png","normal":"second_n.png","specular":"second_s.png"}],
              "animation":{"main":"walk.animation.json","extra":"extra.animation.json"},
              "animation_controllers":["controller.json"]},
              "projectiles":{"minecraft:arrow":{"model":"arrow.json","texture":"arrow.png"}}}}
            """);
        files.put(new ResourceKey("second.png"),new byte[]{4});files.put(new ResourceKey("second_n.png"),new byte[]{5});
        files.put(new ResourceKey("second_s.png"),new byte[]{6});files.put(new ResourceKey("arrow.png"),new byte[]{7});
        files.put(new ResourceKey("arrow.json"),GEO);
        put(files,"walk.animation.json","{\"animations\":{\"walk\":{\"loop\":true}}}");
        put(files,"extra.animation.json","{\"animations\":{\"wave\":{\"animation_length\":1}}}");
        put(files,"controller.json","{\"animation_controllers\":{\"controller.test\":{\"states\":{\"default\":{}}}}}");
        var budget=new ResourceBudget(32L<<20);
        try(var result=importFiles(2,files,budget)) {
            assertEquals(3,result.models().size());
            var doc=result.models().get(0).asset().document();
            assertEquals(1,doc.meshes().get(0).materialIndex());
            assertEquals(java.util.List.of(new ResourceKey("second.png"),new ResourceKey("second_n.png"),new ResourceKey("second_s.png")),doc.materials().get(1).resources());
            assertEquals(2,((JsonObject)doc.metadata().values().get("animations")).values().size());
            assertTrue(((JsonObject)doc.metadata().values().get("animation_controllers")).values().containsKey("controller.test"));
            var ysm=(JsonObject)doc.metadata().values().get("ysm");
            assertEquals(2,((JsonObject)ysm.values().get("animation_sets")).values().size());
            var arrow=(JsonObject)result.models().get(2).asset().document().metadata().values().get("ysm");
            assertEquals(new JsonArray(java.util.List.of(new JsonString("minecraft:arrow"))),arrow.values().get("match"));
        }
        assertEquals(0,budget.used());
    }

    @Test void missingDeclaredResourcesAndLegacyArrowPairsFailWithoutLeakingBudget() throws Exception {
        var missingArm=legacy();missingArm.remove(new ResourceKey("arm.json"));
        var orphanArrow=legacy();orphanArrow.put(new ResourceKey("arrow.png"),new byte[]{1});
        var missingNormal=legacy();put(missingNormal,"ysm.json","{\"files\":{\"player\":{\"model\":{\"main\":\"main.json\",\"arm\":\"arm.json\"},\"texture\":[{\"uv\":\"skin.png\",\"normal\":\"absent.png\"}]}}}");
        for(var files:java.util.List.of(missingArm,orphanArrow,missingNormal)) {
            var budget=new ResourceBudget(32L<<20);
            assertThrows(IOException.class,()->importFiles(1,files,budget));
            assertEquals(0,budget.used());
        }
    }

    @Test void rejectsDuplicateAnimationNamesInsteadOfLosingASet() throws Exception {
        var files=legacy();
        put(files,"ysm.json","{\"files\":{\"player\":{\"model\":{\"main\":\"main.json\",\"arm\":\"arm.json\"},\"texture\":[\"skin.png\"],\"animation\":{\"main\":\"one.json\",\"extra\":\"two.json\"}}}}");
        put(files,"one.json","{\"animations\":{\"same\":{}}}");put(files,"two.json","{\"animations\":{\"same\":{\"loop\":true}}}");
        var budget=new ResourceBudget(32L<<20);
        assertThrows(IOException.class,()->importFiles(2,files,budget));assertEquals(0,budget.used());
    }

    @Test void legacyInfoIsPreservedAndNormalizedIntoManifestSettings() throws Exception {
        var files=legacy();put(files,"info.json","{\"name\":\"Handmade\",\"free\":true,\"extra_animation_names\":[\"Wave\"]}");
        var budget=new ResourceBudget(32L<<20);
        try(var result=importFiles(1,files,budget)) {
            var ysm=(JsonObject)result.models().get(0).asset().document().metadata().values().get("ysm");
            var manifest=(JsonObject)ysm.values().get("manifest");
            var properties=(JsonObject)manifest.values().get("properties");
            assertEquals(new JsonBoolean(true),properties.values().get("free"));
            assertEquals(new JsonString("Wave"),((JsonObject)properties.values().get("extra_animation")).values().get("extra0"));
            assertEquals(new JsonString("Handmade"),((JsonObject)manifest.values().get("legacy_info")).values().get("name"));
        }
        assertEquals(0,budget.used());
    }

    @Test void openResourceStreamKeepsArchiveAndGeometryReservationsAliveAfterResultClose() throws Exception {
        var budget=new ResourceBudget(32L<<20);var result=importFiles(2,legacy(),budget);
        try(var stream=result.resources().open(new ResourceKey("skin.png"))) {
            result.close();assertTrue(budget.used()>0);assertArrayEquals(new byte[]{1,2,3},stream.readAllBytes());
        } finally {result.close();}
        assertEquals(0,budget.used());
    }

    @Test void missingAnimationAndUnsafeManifestPathsCannotEscapeToAnotherResolver() throws Exception {
        for(String animation:new String[]{"missing.json","../outside.json","walk.txt"}) {
            var files=legacy();put(files,"ysm.json","{\"files\":{\"player\":{\"model\":{\"main\":\"main.json\",\"arm\":\"arm.json\"},\"texture\":[\"skin.png\"],\"animation\":{\"main\":\""+animation+"\"}}}}");
            var budget=new ResourceBudget(32L<<20);assertThrows(IOException.class,()->importFiles(1,files,budget));assertEquals(0,budget.used());
        }
    }

    @Test void rawYsmAcceptsSourceCommentsWithoutChangingStringsOrArchivedBytes() throws Exception {
        var files=legacy();
        String geometry=new String(GEO,StandardCharsets.UTF_8)
            .replace("\"description\":{","\"description\":{/* authoring comment */")
            .replace("\"texture_height\":16","\"texture_height\":16,\"note\":\"https://example.test/*literal*/\"")
            .replace("\"bones\":","// bone list\r\n\"bones\":");
        byte[] original=geometry.getBytes(StandardCharsets.UTF_8);files.put(new ResourceKey("main.json"),original);
        put(files,"main.animation.json","{/* library */\"animations\":{\"idle\":{\"loop\":true}}}// EOF comment");
        var budget=new ResourceBudget(32L<<20);
        try(var result=importFiles(2,files,budget)) {
            var metadata=result.models().get(0).asset().document().metadata();
            var geos=(JsonArray)metadata.values().get("minecraft:geometry");
            var description=(JsonObject)((JsonObject)geos.values().get(0)).values().get("description");
            assertEquals(new JsonString("https://example.test/*literal*/"),description.values().get("note"));
            assertTrue(((JsonObject)metadata.values().get("animations")).values().containsKey("idle"));
            try(var stream=result.resources().open(new ResourceKey("main.json"))){assertArrayEquals(original,stream.readAllBytes());}
        }
        assertEquals(0,budget.used());
    }

    @Test void rawCommentsDoNotEnableOtherLenientJsonFormsOrHideDuplicateKeys() throws Exception {
        for(String invalid:new String[]{
            "{/* unterminated", "{unquoted:1}", "{\"x\":NaN}", "{\"x\":1,}",
            "{\"x\":[1,]}", "{/*a*/\"x\":1,/*b*/\"x\":2}", "{'x':1}", "{# comment\n\"x\":1}"
        }) {
            var files=legacy();put(files,"info.json",invalid);var budget=new ResourceBudget(32L<<20);
            assertThrows(IOException.class,()->importFiles(2,files,budget));assertEquals(0,budget.used());
        }
    }

    @Test void malformedUtf8InsideACommentIsStillRejectedAndReleased() throws Exception {
        var files=legacy();files.put(new ResourceKey("info.json"),new byte[]{'{','/','/',(byte)0xc0,(byte)0x80,'\n','}'});
        var budget=new ResourceBudget(32L<<20);
        assertThrows(IOException.class,()->importFiles(1,files,budget));assertEquals(0,budget.used());
    }

    private static LinkedHashMap<ResourceKey,byte[]> legacy(){
        var files=new LinkedHashMap<ResourceKey,byte[]>();files.put(new ResourceKey("main.json"),GEO);
        files.put(new ResourceKey("arm.json"),GEO);files.put(new ResourceKey("skin.png"),new byte[]{1,2,3});return files;
    }
    private static void put(Map<ResourceKey,byte[]> files,String name,String value){files.put(new ResourceKey(name),value.getBytes(StandardCharsets.UTF_8));}
    private static ImportResult importFiles(int version,Map<ResourceKey,byte[]> files,ResourceBudget budget)throws IOException {
        var token=new CancellationToken();
        try(var encoded=YsmArchiveCodec.encode(version,files,POLICY,budget,token)) {
            ModelArchive archive=YsmArchiveCodec.decode(encoded.bytes(),POLICY,budget,token);
            var source=new ModelSource("handmade",new ResourceKey("model.ysm"),key->{throw new IOException("Not an external source");});
            var request=new ImportRequest(source,FormatHint.YSM,POLICY,budget,token);
            try(var result=new ParserResultBuilder()) {
                result.retain(archive);RawYsmParser.parse(archive,request,result);return result.finish();
            }
        }
    }
}
