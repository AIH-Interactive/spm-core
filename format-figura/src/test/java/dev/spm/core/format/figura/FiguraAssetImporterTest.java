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
    @Test void rejectsScriptsNestedArchivesTraversalAndCollidingPathsWithoutLeaking()throws Exception {
        for(String name:List.of("script.lua","nested.zip","../escape.png","%2e%2e/escape.png","/outside.png","C:/outside.png","AVATAR.JSON","payload/")) {
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
    @Test void inconsistentCentralCrcAndBehavioralMetadataAreRejected()throws Exception {
        byte[] corrupt=zip(files());
        for(int p=0;p<corrupt.length-20;p++)if(corrupt[p]==0x50&&corrupt[p+1]==0x4b&&corrupt[p+2]==1&&corrupt[p+3]==2){corrupt[p+16]^=1;break;}
        var budget=new ResourceBudget(1<<20);
        assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(corrupt,budget,new CancellationToken())));assertEquals(0,budget.used());
        var files=files();files.put("avatar.json","{\"autoScripts\":true}".getBytes(StandardCharsets.UTF_8));
        assertEquals(ImportErrorCode.UNSUPPORTED_FEATURE,assertThrows(ModelImportException.class,()->new FiguraAssetImporter().importModel(request(zip(files),budget,new CancellationToken()))).diagnostic().code());assertEquals(0,budget.used());
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
}
