package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.bedrock.BedrockJsonImporter;
import dev.spm.core.format.bbmodel.BbmodelJsonImporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * F01 regression: a retained import must stay charged to the shared budget until its owner closes,
 * so repeated imports cannot consume unbounded memory under one fixed budget.
 */
final class RetainedAssetBudgetTest {
    private static final String BEDROCK="""
        {"format_version":"1.12.0","minecraft:geometry":[{"description":
        {"identifier":"geometry.retained","texture_width":16,"texture_height":16},
        "bones":[{"name":"body","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]}]}]}
        """;
    private static final String BBMODEL="""
        {"meta":{"format_version":"4.10","model_format":"free"},"name":"demo",
        "resolution":{"width":16,"height":16},
        "textures":[{"uuid":"tex","relative_path":"textures/skin.png","uv_width":16,"uv_height":16}],
        "elements":[{"uuid":"cube","type":"cube","name":"Box","from":[0,0,0],"to":[16,16,16],"origin":[0,0,0],
        "faces":{"east":{"uv":[0,0,16,16],"texture":0},"west":{"texture":null},"up":{"texture":null},
        "down":{"texture":null},"south":{"texture":null},"north":{"texture":null}}}],
        "outliner":["cube"]}
        """;
    private static byte[] bytes(String source) { return source.getBytes(StandardCharsets.UTF_8); }

    @Test void bedrockAssetStaysChargedAndBlocksASecondRetainedImport() throws Exception {
        var sizing=new ResourceBudget(1<<24);
        long charge;
        ModelAsset measured=BedrockJsonImporter.importAsset(bytes(BEDROCK),DecodePolicy.conservative(),sizing,new CancellationToken());
        try {
            charge=sizing.used();
            assertTrue(charge>0,"retained import must be charged while open");
            assertSame(measured.document(),measured.document());
        } finally { measured.close(); }
        assertEquals(0,sizing.used(),"close must release the retained charge");

        var tight=new ResourceBudget(charge);
        ModelAsset first=BedrockJsonImporter.importAsset(bytes(BEDROCK),DecodePolicy.conservative(),tight,new CancellationToken());
        assertEquals(charge,tight.used());
        assertThrows(IOException.class,()->BedrockJsonImporter.importAsset(bytes(BEDROCK),DecodePolicy.conservative(),tight,new CancellationToken()));
        assertEquals(charge,tight.used(),"a rejected import must not leak into the shared budget");
        first.close();first.close();
        assertEquals(0,tight.used(),"repeated close releases exactly once");
        assertThrows(IllegalStateException.class,first::document);
    }

    @Test void bbmodelAssetStaysChargedAndBlocksASecondRetainedImport() throws Exception {
        var sizing=new ResourceBudget(1<<24);
        long charge;
        ModelAsset measured=BbmodelJsonImporter.importAsset(bytes(BBMODEL),DecodePolicy.conservative(),sizing,new CancellationToken());
        try { charge=sizing.used(); assertTrue(charge>0); }
        finally { measured.close(); }
        assertEquals(0,sizing.used());

        var tight=new ResourceBudget(charge);
        ModelAsset first=BbmodelJsonImporter.importAsset(bytes(BBMODEL),DecodePolicy.conservative(),tight,new CancellationToken());
        assertThrows(IOException.class,()->BbmodelJsonImporter.importAsset(bytes(BBMODEL),DecodePolicy.conservative(),tight,new CancellationToken()));
        assertEquals(charge,tight.used());
        first.close();first.close();
        assertEquals(0,tight.used());
        assertThrows(IllegalStateException.class,first::document);
    }

    @Test void failingAssetImportReleasesConstructionCharges() {
        var budget=new ResourceBudget(1<<24);
        assertThrows(IOException.class,()->BedrockJsonImporter.importAsset(bytes("{}"),DecodePolicy.conservative(),budget,new CancellationToken()));
        assertEquals(0,budget.used());
        assertThrows(IOException.class,()->BbmodelJsonImporter.importAsset(bytes("{}"),DecodePolicy.conservative(),budget,new CancellationToken()));
        assertEquals(0,budget.used());
    }
}
