package dev.spm.core.format.figura;

import dev.spm.core.api.*;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FiguraAssetImporterTest {
    private static final byte[] MODEL="""
        {"meta":{"format_version":"4.10","model_format":"free"},"name":"owned-cube","textures":[{"uuid":"skin","relative_path":"texture.png"}],
         "elements":[{"name":"cube","uuid":"cube","type":"cube","from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],"faces":{"east":{"uv":[0,0,16,16],"texture":0}}}],
         "outliner":[{"name":"part","uuid":"group","origin":[8,0,0],"rotation":[0,0,90],"children":["cube"]}]}
        """.getBytes(StandardCharsets.UTF_8);
    private static byte[] zip(Map<String,byte[]> files)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(var entry:files.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    private static Map<String,byte[]> files(){var files=new LinkedHashMap<String,byte[]>();files.put("avatar.json",new byte[0]);files.put("model.bbmodel",MODEL);files.put("texture.png",new byte[]{1,2,3});return files;}
    private static ImportRequest request(byte[] source,ResourceBudget budget,CancellationToken token){return new ImportRequest(new ModelSource("owned-fixture",new ResourceKey("owned.figura.zip"),k->new ByteArrayInputStream(source)),FormatHint.FIGURA,DecodePolicy.conservative(),budget,token);}
    @Test void staticModelMatchesExistingHierarchyAndPoseAndStreamsPinTheResourceDomain()throws Exception {
        var budget=new ResourceBudget(1<<20);var importer=new FiguraAssetImporter();var result=importer.importModel(request(zip(files()),budget,new CancellationToken()));
        var asset=result.models().get(0).asset();var lease=asset.acquire();var resolver=result.resources();var stream=resolver.open(new ResourceKey("texture.png"));
        try(var expected=BbmodelJsonImporter.importAsset(MODEL,DecodePolicy.conservative(),new ResourceBudget(1<<20),new CancellationToken())) {
            var actual=asset.document();assertEquals(expected.document().skeleton().names(),actual.skeleton().names());
            assertArrayEquals(expected.document().bindPose().rotations(),actual.bindPose().rotations());
            assertArrayEquals(expected.document().meshes().get(0).vertices().positions(),actual.meshes().get(0).vertices().positions());
            assertTrue(actual.meshes().get(0).vertices().vertexCount()>0);
            assertEquals(List.of(new ResourceKey("texture.png")),actual.materials().get(actual.meshes().get(0).materialIndex()).resources());
        }
        result.close();assertTrue(budget.used()>0);assertNotNull(lease.document());assertThrows(IOException.class,()->resolver.open(new ResourceKey("texture.png")));
        lease.close();assertTrue(budget.used()>0);assertArrayEquals(new byte[]{1,2,3},stream.readAllBytes());stream.close();stream.close();result.close();assertEquals(0,budget.used());
    }
    @Test void rejectsNestedArchivesTraversalAndCollidingPathsWithoutLeaking()throws Exception {
        for(String name:List.of("nested.zip","../escape.png","%2e%2e/escape.png","/outside.png","C:/outside.png","AVATAR.JSON","payload/")) {
            var files=files();files.put(name,new byte[]{1});var budget=new ResourceBudget(1<<20);
            assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(zip(files),budget,new CancellationToken())),name);
            assertEquals(0,budget.used(),name);
        }
    }
    @Test void truncatedCentralDirectoryIsRejectedAndReleasesCredits()throws Exception {
        byte[] valid=zip(files());var budget=new ResourceBudget(1<<20);
        assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(Arrays.copyOf(valid,valid.length-10),budget,new CancellationToken())));
        assertEquals(0,budget.used());
    }
    @Test void inconsistentCentralCrcIsRejectedAndCleanlyFails()throws Exception {
        byte[] corrupt=zip(files());
        for(int p=0;p<corrupt.length-20;p++)if(corrupt[p]==0x50&&corrupt[p+1]==0x4b&&corrupt[p+2]==1&&corrupt[p+3]==2){corrupt[p+16]^=1;break;}
        var budget=new ResourceBudget(1<<20);
        assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(corrupt,budget,new CancellationToken())));assertEquals(0,budget.used());
    }
    @Test void importsAnimationsEmbeddedTexturesLuaAndNbtAndRunsLuaAvatarRuntime()throws Exception {
        String pngB64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X2XkAAAAASUVORK5CYII=";
        byte[] animBbmodel=("""
            {"meta":{"format_version":"4.10","model_format":"free"},
             "name":"avatar","textures":[{"uuid":"emb","source":"data:image/png;base64,%s"}],
             "elements":[{"name":"cube","uuid":"cube","type":"cube","from":[0,0,0],"to":[8,8,8],"origin":[0,0,0],"faces":{"east":{"uv":[0,0,8,8],"texture":0}}}],
             "outliner":[{"name":"part","uuid":"group","origin":[0,0,0],"rotation":[0,0,0],"children":["cube"]}],
             "animations":[{"name":"wave","loop":"loop","length":1.0,"animators":{"group":{"name":"part","type":"bone","keyframes":[{"channel":"rotation","time":0.0,"data_points":[{"x":0,"y":0,"z":0}]},{"channel":"rotation","time":1.0,"data_points":[{"x":0,"y":0,"z":90}]}]}}}]}
            """.formatted(pngB64)).getBytes(StandardCharsets.UTF_8);
        var files=new LinkedHashMap<String,byte[]>();
        files.put("avatar.json","{\"name\":\"SparkleFigura\",\"autoScripts\":[\"main\"]}".getBytes(StandardCharsets.UTF_8));
        files.put("model.bbmodel",animBbmodel);
        files.put("helper.lua","return { angle = 45 }".getBytes(StandardCharsets.UTF_8));
        files.put("main.lua","""
            local h = require("helper")
            vanilla_model.HEAD:setVisible(false)
            animations.model.wave:play()
            function pings.greet(n)
                sounds:playSound("minecraft:entity.experience_orb.pickup", player:getPos(), 0.8, 1.2)
            end
            function events.tick()
                if player:isCrouching() then
                    pings.greet(1)
                end
            end
            function events.render(delta)
                models.model.part:setRot(0, h.angle, 0)
            end
            """.getBytes(StandardCharsets.UTF_8));

        var budget=new ResourceBudget(2<<20);
        try(var result=new FiguraAssetImporter().importModel(request(zip(files),budget,new CancellationToken()))) {
            var doc=result.models().get(0).asset().document();
            var texKey=doc.materials().get(1).resources().get(0);
            try(var tex=result.resources().open(texKey)) {
                assertTrue(tex.readAllBytes().length>0);
            }
            var runtime=FiguraAvatarRuntime.create(result,dev.spm.core.lua.LuaBudget.avatarDefault(new CancellationToken()),FiguraAvatarRuntime.HostSnapshot.idle("Player"));
            runtime.initialize();
            var crouchSnap=new FiguraAvatarRuntime.HostSnapshot(1,64,2,0,0,0,0,0,0,20,20,true,false,false,false,true,false,"Player","uuid",true,100,0,60,"minecraft:air","minecraft:air");
            for(int t=0;t<10;t++)runtime.tick(crouchSnap); // +0.5s -> wave rotation Z = 45 deg
            var frame=runtime.render(0.0f,crouchSnap);
            var pose=frame.modelPoses().get("model.bbmodel");
            assertEquals(Math.toRadians(45.0),pose.rotations()[1],1e-4);
            assertEquals(Math.toRadians(45.0),pose.rotations()[2],1e-4);
            assertFalse(frame.vanillaOverrides().get("HEAD").visible());
            assertTrue(frame.effects().stream().anyMatch(e->e.kind().equals("SOUND")));
            assertTrue(frame.effects().stream().anyMatch(e->e.kind().equals("PING")));
        }
        assertEquals(0,budget.used());
    }
    @Test void budgetFailureAndPreCancellationReleaseEveryPartialOwner()throws Exception {
        byte[] source=zip(files());var budget=new ResourceBudget(1);
        assertEquals(ImportErrorCode.BUDGET_EXCEEDED,assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(source,budget,new CancellationToken()))).diagnostic().code());assertEquals(0,budget.used());
        var token=new CancellationToken();token.cancel();var cancelledBudget=new ResourceBudget(1<<20);
        assertEquals(ImportErrorCode.CANCELLED,assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(source,cancelledBudget,token))).diagnostic().code());assertEquals(0,cancelledBudget.used());
    }
    @Test void inputLimitsAndSourceErrorsKeepTheirStableDiagnosticCategories()throws Exception {
        byte[] source=zip(files());var budget=new ResourceBudget(1<<20);var base=request(source,budget,new CancellationToken());
        var limited=new ImportRequest(base.source(),FormatHint.FIGURA,new DecodePolicy(source.length-1,1<<20,1000,1000,100),budget,new CancellationToken());
        assertEquals(ImportErrorCode.INPUT_LIMIT,assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(limited)).diagnostic().code());assertEquals(0,budget.used());
        var failed=new ImportRequest(new ModelSource("broken",new ResourceKey("broken.figura.zip"),key->{throw new IOException("source unavailable");}),FormatHint.FIGURA,DecodePolicy.conservative(),budget,new CancellationToken());
        assertEquals(ImportErrorCode.SOURCE_IO,assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(failed)).diagnostic().code());assertEquals(0,budget.used());
    }
    @Test void importsCompressedAvatarNbtWithScriptsAndModels()throws Exception {
        var nbtBytes=new ByteArrayOutputStream();
        try(var gz=new GZIPOutputStream(nbtBytes);var dos=new DataOutputStream(gz)) {
            dos.writeByte(10);dos.writeUTF("");
            // metadata compound
            dos.writeByte(10);dos.writeUTF("metadata");
            dos.writeByte(8);dos.writeUTF("name");dos.writeUTF("NbtAvatar");
            dos.writeByte(0);
            // scripts compound
            dos.writeByte(10);dos.writeUTF("scripts");
            dos.writeByte(7);dos.writeUTF("avatar");
            byte[] script="function events.render() models.avatar.head:setRot(10, 20, 30) end".getBytes(StandardCharsets.UTF_8);
            dos.writeInt(script.length);dos.write(script);
            dos.writeByte(0);
            // models compound -> models.chld[0] = "avatar" -> avatar.chld[0] = "head"
            dos.writeByte(10);dos.writeUTF("models");
            dos.writeByte(8);dos.writeUTF("name");dos.writeUTF("models");
            dos.writeByte(9);dos.writeUTF("chld");dos.writeByte(10);dos.writeInt(1);
            dos.writeByte(8);dos.writeUTF("name");dos.writeUTF("avatar");
            dos.writeByte(9);dos.writeUTF("chld");dos.writeByte(10);dos.writeInt(1);
            dos.writeByte(8);dos.writeUTF("name");dos.writeUTF("head");
            dos.writeByte(10);dos.writeUTF("cube_data");
            dos.writeByte(9);dos.writeUTF("f");dos.writeByte(5);dos.writeInt(3);dos.writeFloat(-4);dos.writeFloat(0);dos.writeFloat(-4);
            dos.writeByte(9);dos.writeUTF("t");dos.writeByte(5);dos.writeInt(3);dos.writeFloat(4);dos.writeFloat(8);dos.writeFloat(4);
            dos.writeByte(0); // end cube_data
            dos.writeByte(0); // end head
            dos.writeByte(0); // end avatar
            dos.writeByte(0); // end models
            dos.writeByte(0); // end root
        }
        var budget=new ResourceBudget(1<<20);
        var req=new ImportRequest(new ModelSource("nbt-fixture",new ResourceKey("avatar.nbt"),k->new ByteArrayInputStream(nbtBytes.toByteArray())),FormatHint.AUTO,DecodePolicy.conservative(),budget,new CancellationToken());
        try(var result=new FiguraAssetImporter().importModel(req)) {
            assertEquals("figura-nbt",result.models().get(0).asset().document().sourceFormat());
            var runtime=FiguraAvatarRuntime.create(result,dev.spm.core.lua.LuaBudget.avatarDefault(new CancellationToken()),FiguraAvatarRuntime.HostSnapshot.idle("Alex"));
            var frame=runtime.render(0f,FiguraAvatarRuntime.HostSnapshot.idle("Alex"));
            var pose=frame.modelPoses().get("avatar");
            assertEquals(Math.toRadians(10.0),pose.rotations()[3],1e-4);
            assertEquals(Math.toRadians(20.0),pose.rotations()[4],1e-4);
            assertEquals(Math.toRadians(30.0),pose.rotations()[5],1e-4);
        }
        assertEquals(0,budget.used());
    }
}
