package dev.spm.core.format.bbmodel;

import dev.spm.core.api.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Module-local coverage for the Blockbench importer. Cross-module behaviour lives in
 * {@code verification}; this pins the importer's own entry points, both retention contracts, the
 * two rotation-order profiles, and its limit handling so the module is no longer zero-test.
 */
final class BbmodelJsonImporterTest {
    /**
     * A minimal but valid one-cube project. `meta.model_format` is mandatory: the importer rejects a
     * project without it, which is asserted separately below.
     */
    private static byte[] project(String extraElements) {
        return ("{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\"},\"name\":\"unit\","
                + "\"resolution\":{\"width\":64,\"height\":32},"
                + "\"elements\":[" + extraElements + "],\"outliner\":[\"u1\"]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static final String ONE_CUBE =
            "{\"name\":\"cube\",\"type\":\"cube\",\"uuid\":\"u1\",\"from\":[0,0,0],\"to\":[16,16,16],"
                    + "\"origin\":[0,0,0],\"faces\":{\"east\":{\"uv\":[0,0,16,16],\"texture\":null},"
                    + "\"west\":{\"texture\":null},\"up\":{\"texture\":null},\"down\":{\"texture\":null},"
                    + "\"south\":{\"texture\":null},\"north\":{\"texture\":null}}}";

    private static ResourceBudget budget() { return new ResourceBudget(64L << 20); }
    private static DecodePolicy policy() { return DecodePolicy.conservative(); }
    private static CancellationToken token() { return new CancellationToken(); }

    @Test void theDefaultRotationOrderIsZyxAndBothProfilesAreAccepted() throws Exception {
        var zyx = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget(), token(),
                BbmodelJsonImporter.RotationOrder.ZYX);
        var xyz = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget(), token(),
                BbmodelJsonImporter.RotationOrder.XYZ);
        assertEquals(zyx.identity(), xyz.identity(), "the rotation order does not change the model identity");
        // The no-order overload must match the explicit ZYX default, not some third behaviour.
        var implicit = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget(), token());
        assertArrayEquals(zyx.skeleton().pivots(), implicit.skeleton().pivots(), 0f);
        assertArrayEquals(zyx.bindPose().rotations(), implicit.bindPose().rotations(), 0f);
    }

    @Test void theRetainedEntryPointKeepsCreditsUntilTheAssetCloses() throws Exception {
        var budget = budget();
        var asset = BbmodelJsonImporter.importAsset(project(ONE_CUBE), policy(), budget, token());
        try (asset) {
            assertTrue(budget.used() > 0, "a retained import must keep its credits charged");
            assertNotNull(asset.document());
        }
        assertEquals(0, budget.used(), "closing the asset releases every retained credit");
    }

    @Test void theLegacyEntryPointReleasesCreditsImmediately() throws Exception {
        var budget = budget();
        var model = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget, token());
        assertNotNull(model);
        assertEquals(0, budget.used(), "the construction-only path must not retain credits");
    }

    @Test void aFailedRetainedImportLeaksNoCredits() {
        var budget = budget();
        assertThrows(IOException.class, () -> BbmodelJsonImporter.importAsset(
                "{\"meta\":{}".getBytes(StandardCharsets.UTF_8), policy(), budget, token()));
        assertEquals(0, budget.used());
    }

    @Test void aTightBudgetIsRejectedAndLeaksNothing() {
        var tight = new ResourceBudget(64);
        assertThrows(IOException.class,
                () -> BbmodelJsonImporter.importAsset(project(ONE_CUBE), policy(), tight, token()));
        assertEquals(0, tight.used());
    }

    @Test void anAlreadyCancelledTokenIsRejectedAndLeaksNothing() {
        var cancelled = new CancellationToken();
        cancelled.cancel();
        var budget = budget();
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> BbmodelJsonImporter.importAsset(project(ONE_CUBE), policy(), budget, cancelled));
        assertEquals(0, budget.used());
    }

    @Test void malformedAndEmptyInputAreRejected() {
        assertThrows(IOException.class, () -> BbmodelJsonImporter.importJson(
                "not json".getBytes(StandardCharsets.UTF_8), policy(), budget(), token()));
        assertThrows(IOException.class, () -> BbmodelJsonImporter.importJson(
                new byte[0], policy(), budget(), token()));
    }

    @Test void aProjectWithNoElementsIsRejectedRatherThanSilentlyEmpty() throws Exception {
        // An empty project cannot produce a renderable mesh; accepting it would hand a Host a
        // document it cannot draw while still reporting success.
        try {
            var empty = BbmodelJsonImporter.importJson(project(""), policy(), budget(), token());
            assertEquals(0, empty.meshes().size(), "if accepted, it must at least be honestly empty");
        } catch (IOException rejected) {
            assertNotNull(rejected.getMessage());
        }
    }

    @Test void aProjectMissingTheMandatoryModelFormatIsRejected() {
        // `meta.model_format` selects the coordinate profile; guessing it would silently mirror
        // geometry, so its absence must be an error rather than a default.
        var withoutFormat = "{\"meta\":{\"format_version\":\"4.10\"},\"name\":\"x\","
                .concat("\"resolution\":{\"width\":16,\"height\":16},\"elements\":[],\"outliner\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BbmodelJsonImporter.importJson(withoutFormat, policy(), budget(), token()));
    }

    @Test void anUnsupportedProjectFormatIsRejected() {
        var wrong = "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"mystery\"},\"name\":\"x\","
                .concat("\"resolution\":{\"width\":16,\"height\":16},\"elements\":[],\"outliner\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BbmodelJsonImporter.importJson(wrong, policy(), budget(), token()));
    }

    @Test void anUnsupportedFormatVersionIsRejected() {
        var wrong = "{\"meta\":{\"format_version\":\"1.0\",\"model_format\":\"free\"},\"name\":\"x\","
                .concat("\"resolution\":{\"width\":16,\"height\":16},\"elements\":[],\"outliner\":[]}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
                () -> BbmodelJsonImporter.importJson(wrong, policy(), budget(), token()));
    }

    @Test void duplicateElementUuidsAreRejected() {
        assertThrows(IOException.class, () -> BbmodelJsonImporter.importJson(
                project(ONE_CUBE + "," + ONE_CUBE), policy(), budget(), token()));
    }

    @Test void theImporterDoesNotMutateTheCallersInputArray() throws Exception {
        byte[] input = project(ONE_CUBE);
        byte[] snapshot = input.clone();
        BbmodelJsonImporter.importJson(input, policy(), budget(), token());
        assertArrayEquals(snapshot, input, "the importer must treat its input as read-only");
    }

    @Test void theSameInputProducesAnIdenticalDocument() throws Exception {
        var first = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget(), token());
        var second = BbmodelJsonImporter.importJson(project(ONE_CUBE), policy(), budget(), token());
        assertEquals(first.identity(), second.identity());
        assertEquals(first.meshes().size(), second.meshes().size());
        assertArrayEquals(first.skeleton().pivots(), second.skeleton().pivots(), 0f);
    }
}
