package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import dev.spm.core.render.cpu.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class YsmModelImporterTest {
    private static byte[] sample(String name) throws IOException {
        try (var input = YsmModelImporterTest.class.getResourceAsStream("/ysm/historical/" + name + ".wire")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    @Test void importsOldAndNewPlayerVariantsWithoutConflatingWireAndBedrockCoordinates() throws Exception {
        for (String fixture : List.of("v1", "v5-face", "v32")) {
            var budget = new ResourceBudget(1 << 20);
            var policy = DecodePolicy.conservative();
            try (var historical = YsmHistoricalCodec.decode(sample(fixture), policy, budget, new CancellationToken());
                 var imported = YsmModelImporter.importPayload(historical, policy, budget, new CancellationToken())) {
                assertEquals(2, imported.variants().size());
                assertEquals(List.of("main", "arm"), imported.variants().stream().map(YsmImportResult.Variant::sourceKey).toList());
                ModelDocument main = imported.variants().get(0).document().orElseThrow();
                assertEquals("main", main.skeleton().names().get(0));
                assertEquals("ysm-v3-inner-" + historical.innerVersion(), main.sourceFormat());
                assertEquals("000102030405060708090a0b0c0d0e0f", main.identity());
                assertEquals(1, main.materials().size());
                ResourceKey texture = main.materials().get(0).resources().get(0);
                assertArrayEquals(new byte[] {16,32,48,(byte) 255}, imported.read(texture));
                // The historical tree is deliberately NOT retained. Lowered documents are Core values
                // that never alias a HistoricalValue (ModelDocument.JsonValue is a sealed interface
                // over JSON scalars), so keeping the generic tree alive would retain roughly five
                // times the heap of the data the caller can actually reach. This assertion keeps a
                // future change from quietly reintroducing that retention.
                assertThrows(IllegalStateException.class, historical::root,
                        "lowering must retire the tree it no longer needs");
                if (fixture.equals("v5-face")) {
                    ModelDocument.Mesh mesh = main.meshes().get(0);
                    assertEquals(4, mesh.vertices().vertexCount());
                    assertArrayEquals(new float[] {0,0,0,1,0,0,1,1,0,0,1,0}, mesh.vertices().positions());
                    assertArrayEquals(new float[] {0,0,1,0,0,1,0,0,1,0,0,1}, mesh.vertices().normals());
                    var matrices = BoneEvaluator.evaluate(main.skeleton(), main.bindPose());
                    assertArrayEquals(mesh.vertices().positions(), JavaCpuRenderer.transform(mesh.vertices(), matrices).positions());
                }
                assertTrue(budget.used() > 0);
            }
            assertEquals(0, budget.used());
        }
    }
}
