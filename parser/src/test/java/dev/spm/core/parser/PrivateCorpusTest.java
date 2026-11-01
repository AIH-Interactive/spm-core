package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.runtime.ModelFrameRenderer;
import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.acceleration.Isa;
import dev.spm.core.nativejni.NativeComputeProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in private inputs. Model bytes are never bundled, copied into test resources or published. */
@EnabledIfSystemProperty(named="spm.corpus.directory",matches=".+")
class PrivateCorpusTest {
    @Test void everyPrivateYsmImportsRendersAndReleasesItsBudgetWithoutAnExternalOracle() throws Exception {
        Path root=Path.of(System.getProperty("spm.corpus.directory")).toRealPath();
        var failures=new ArrayList<String>();int loaded=0;
        CpuComputeProvider nativeProvider=null;
        if(System.getProperty("spm.native.library")!=null){
            Path library=Path.of(System.getProperty("spm.native.library"));
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(library)));
            nativeProvider=NativeComputeProvider.load(library,digest,Isa.AUTO);
            System.out.println("CORPUS_NATIVE backend="+nativeProvider.backend());
        }
        try(var inputs=Files.walk(root)){
            var files=inputs.filter(Files::isRegularFile).filter(p->p.getFileName().toString().endsWith(".ysm")).sorted().toList();
            assertFalse(files.isEmpty(),"No private model inputs found");
            for(Path file:files){
                var budget=new ResourceBudget(512L<<20);String key=root.relativize(file).toString().replace('\\','/');
                ResourceResolver resources=resource->{
                    Path path=root.resolve(resource.path()).normalize().toRealPath();
                    if(!path.startsWith(root))throw new IOException("Resource escaped private corpus");
                    return Files.newInputStream(path);
                };
                var source=new ModelSource("private-corpus",new ResourceKey(key),resources);
                int variants=0,vertices=0;
                try(var result=new SpmModelParser().importModel(new ImportRequest(source,FormatHint.AUTO,DecodePolicy.localModel(),budget,new CancellationToken()))){
                    assertFalse(result.models().isEmpty(),"No executable variants");
                    for(var model:result.models()){
                        var document=model.asset().document();variants++;
                        try(var frame=ModelFrameRenderer.render(model.asset(),document.bindPose(),budget,new CancellationToken())){
                            for(var mesh:frame.meshes())vertices+=mesh.vertices().vertexCount();
                            if(nativeProvider!=null)try(var accelerated=ModelFrameRenderer.render(model.asset(),document.bindPose(),nativeProvider,budget,new CancellationToken())){
                                assertEquals(frame.meshes().size(),accelerated.meshes().size());
                                for(int i=0;i<frame.meshes().size();i++){
                                    var javaMesh=frame.meshes().get(i);var rustMesh=accelerated.meshes().get(i);
                                    assertArrayEquals(javaMesh.indices(),rustMesh.indices());assertArrayEquals(javaMesh.uvs(),rustMesh.uvs());
                                    assertEquals(javaMesh.materialIndex(),rustMesh.materialIndex());
                                    assertArrayEquals(javaMesh.vertices().boneIndices(),rustMesh.vertices().boneIndices());
                                    assertArrayEquals(javaMesh.vertices().glow(),rustMesh.vertices().glow());
                                    assertArrayEquals(javaMesh.vertices().colorsRgba(),rustMesh.vertices().colorsRgba());
                                    near(javaMesh.vertices().positions(),rustMesh.vertices().positions());
                                    near(javaMesh.vertices().normals(),rustMesh.vertices().normals());
                                }
                            }
                        }
                    }
                    loaded++;
                    System.out.println("CORPUS_OK\t"+key+"\tvariants="+variants+"\tvertices="+vertices);
                }catch(ModelImportException problem){
                    String failure=key+"\t"+problem.diagnostic().code()+"\t"+problem.getMessage();failures.add(failure);System.out.println("CORPUS_FAILED\t"+failure);
                }
                assertEquals(0,budget.used(),"Retained budget after "+key);
            }
            System.out.println("CORPUS_SUMMARY total="+files.size()+" loaded="+loaded+" failures="+failures.size());
        }
        assertTrue(failures.isEmpty(),()->String.join("\n",failures));
    }
    private static void near(float[] expected,float[] actual){
        assertEquals(expected.length,actual.length);
        for(int i=0;i<expected.length;i++){
            assertTrue(Float.isFinite(actual[i]));
            assertEquals(expected[i],actual[i],1e-5f+Math.abs(expected[i])*1e-5f,"component "+i);
        }
    }
}
