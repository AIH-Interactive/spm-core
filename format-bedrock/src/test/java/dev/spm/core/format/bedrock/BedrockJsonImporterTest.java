package dev.spm.core.format.bedrock;

import dev.spm.core.api.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module-local coverage for the Bedrock geometry importer. The cross-module behaviour tests live in
 * {@code verification}; this class pins the importer's own limits and its two retention contracts so
 * the {@code format-bedrock} module is no longer a zero-test module.
 */
final class BedrockJsonImporterTest {
    private static final String PREFIX = """
            {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.unit","texture_width":64,"texture_height":32},"bones":
            """;

    private static byte[] document(String bones) {
        return (PREFIX + bones + "}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static final String ONE_CUBE =
            "[{\"name\":\"root\",\"pivot\":[0,0,0],\"cubes\":[{\"origin\":[0,0,0],\"size\":[16,16,16],\"uv\":[0,0]}]}]";

    private static ResourceBudget budget() { return new ResourceBudget(64L << 20); }
    private static DecodePolicy policy() { return DecodePolicy.conservative(); }
    private static CancellationToken token() { return new CancellationToken(); }

    @Test void theRetainedEntryPointKeepsCreditsUntilTheAssetCloses() throws Exception {
        var budget = budget();
        var asset = BedrockJsonImporter.importAsset(document(ONE_CUBE), policy(), budget, token());
        long retained = budget.used();
        try (asset) {
            assertTrue(retained > 0, "a retained import must keep its credits charged");
            assertNotNull(asset.document());
            assertEquals("geometry.unit", asset.document().identity());
        }
        assertEquals(0, budget.used(), "closing the asset releases every retained credit");
    }

    @Test void theLegacyEntryPointReleasesCreditsImmediately() throws Exception {
        var budget = budget();
        var model = BedrockJsonImporter.importJson(document(ONE_CUBE), policy(), budget, token());
        assertNotNull(model);
        assertEquals(0, budget.used(), "the construction-only path must not retain credits");
    }

    @Test void aFailedRetainedImportLeaksNoCredits() {
        var budget = budget();
        assertThrows(IOException.class,
                () -> BedrockJsonImporter.importAsset(document("[{\"name\":\"\"}]"), policy(), budget, token()));
        assertEquals(0, budget.used(), "a rejected import must release everything it charged");
    }

    @Test void unsupportedFormatVersionsAreRejected() {
        var wrong = ("{\"format_version\":\"9.9.9\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.x\","
                + "\"texture_width\":64,\"texture_height\":32},\"bones\":[]}]}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BedrockJsonImporter.importJson(wrong, policy(), budget(), token()));
    }

    @Test void exactlyOneGeometryIsRequiredPerImport() {
        var two = ("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":["
                + "{\"description\":{\"identifier\":\"geometry.a\",\"texture_width\":64,\"texture_height\":32},\"bones\":[]},"
                + "{\"description\":{\"identifier\":\"geometry.b\",\"texture_width\":64,\"texture_height\":32},\"bones\":[]}]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BedrockJsonImporter.importJson(two, policy(), budget(), token()));
    }

    @Test void duplicateAndBlankBoneNamesAreRejected() {
        assertThrows(IOException.class, () -> BedrockJsonImporter.importJson(
                document("[{\"name\":\"a\"},{\"name\":\"a\"}]"), policy(), budget(), token()));
        assertThrows(IOException.class, () -> BedrockJsonImporter.importJson(
                document("[{\"name\":\"\"}]"), policy(), budget(), token()));
    }

    @Test void oversizedTextureDimensionsAreRejectedBeforeBoneWork() {
        var huge = ("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.h\","
                + "\"texture_width\":999999,\"texture_height\":999999},\"bones\":[]}]}").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BedrockJsonImporter.importJson(huge, new DecodePolicy(64L << 20, 1024, 16, 1 << 10, 8),
                        budget(), token()));
    }

    @Test void aTightBudgetIsRejectedAndLeaksNothing() {
        var tight = new ResourceBudget(64);
        assertThrows(IOException.class,
                () -> BedrockJsonImporter.importAsset(document(ONE_CUBE), policy(), tight, token()));
        assertEquals(0, tight.used(), "a budget rejection must not retain credits");
    }

    @Test void anAlreadyCancelledTokenIsRejectedAndLeaksNothing() {
        var cancelled = new CancellationToken();
        cancelled.cancel();
        var budget = budget();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> BedrockJsonImporter.importAsset(document(ONE_CUBE), policy(), budget, cancelled));
        assertEquals(0, budget.used());
    }

    @Test void nonJsonInputIsRejected() {
        assertThrows(IOException.class, () -> BedrockJsonImporter.importJson(
                "not json at all".getBytes(StandardCharsets.UTF_8), policy(), budget(), token()));
        assertThrows(IOException.class, () -> BedrockJsonImporter.importJson(
                new byte[0], policy(), budget(), token()));
    }

    @Test void theIdenticalInputProducesAnIdenticalDocument() throws Exception {
        // Determinism matters because a Host may cache on identity plus a digest.
        var first = BedrockJsonImporter.importJson(document(ONE_CUBE), policy(), budget(), token());
        var second = BedrockJsonImporter.importJson(document(ONE_CUBE), policy(), budget(), token());
        assertEquals(first.identity(), second.identity());
        assertEquals(first.meshes().size(), second.meshes().size());
        assertArrayEquals(first.skeleton().pivots(), second.skeleton().pivots(), 0f);
        assertEquals(first.bindPose().boneCount(), second.bindPose().boneCount());
    }
}
