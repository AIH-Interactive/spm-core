package dev.spm.core.verification;

import dev.spm.core.api.BonePose;
import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.FormatHint;
import dev.spm.core.api.ImportRequest;
import dev.spm.core.api.MeshSkin;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ModelSource;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.ResourceKey;
import dev.spm.core.api.ResourceResolver;
import dev.spm.core.api.SkinWeights;
import dev.spm.core.api.SkinningMethod;
import dev.spm.core.api.SkinningRig;
import dev.spm.core.api.SkinState;
import dev.spm.core.api.VertexBatch;
import dev.spm.core.parser.SpmModelParser;
import dev.spm.core.render.cpu.BindMatrices;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import dev.spm.core.scene.MolangContext;
import dev.spm.core.scene.PoseAnimationLibrary;
import dev.spm.core.scene.ScriptBudget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Weighted skinning over the whole private corpus — the first real-content exercise of the weighted
 * path.
 *
 * <p>Every earlier skinning test built a synthetic two- or three-bone rig by hand; nothing had ever
 * skinned a real model. This test imports each real {@code .ysm} model, derives weights from the
 * model's <em>own</em> cube-to-bone assignment through {@link SkinningRig#fromVertexBones}, and
 * renders real geometry through {@code WeightedFrameRenderer}.</p>
 *
 * <p>Two claims are asserted at deliberately different strengths:</p>
 *
 * <ul>
 *   <li><b>Bit equality</b> is claimed only where it is provable. The rigid renderer applies a bone's
 *       bind transform straight to bone-local vertices; the weighted renderer applies
 *       {@code pose * inverseBind}. Those coincide in exact float arithmetic only when every bind
 *       transform is the identity, because the inverse is then exact and the product is exactly the
 *       pose. With a non-identity bind the product needs an inversion and a 4x4 multiply that both
 *       round, so bit equality there would be an overclaim. The test <em>checks</em> identity and
 *       asserts raw-bit equality (via {@link Float#floatToRawIntBits}) only for the variants where
 *       it holds, then asserts that the set of such variants is exactly the identity-bind set.</li>
 *   <li><b>Completion, finiteness and budget release</b> are asserted for every real variant, and a
 *       posed frame is exercised for every variant whose pose is not the bind pose.</li>
 * </ul>
 *
 * <p>Posed frames are asserted to <em>complete</em>, not to reproduce the rigid path: under a
 * non-identity bind pose the two vertex-space conventions genuinely differ, so an equality assertion
 * would be asserting something false about the renderers rather than about the model. Every variant
 * gets a posed frame; when a document's own clips cannot be decoded against its geometry skeleton the
 * pose falls back to a deterministic tilt of the authored channels, and the split is reported.</p>
 *
 * <p>Both tests are gated on {@code spm.corpus.directory}; the default {@code check} run skips them
 * and never touches the private corpus.</p>
 */
@EnabledIfSystemProperty(named = "spm.corpus.directory", matches = ".+")
class RealCorpusSkinningTest {
    /** Model files expected in the private corpus; a shrinking corpus must fail, not silently pass. */
    private static final int EXPECTED_MODELS = 18;

    @Test
    void everyCorpusModelBindsFromItsOwnVertexBonesAndSkinsTheRealGeometry() throws Exception {
        Run run = runCorpus(false);
        assertEquals(EXPECTED_MODELS, run.files, "Corpus file count changed");
        assertEquals(EXPECTED_MODELS, run.models, "Corpus model count changed");
        // Nothing may be silently skipped: the whole point is that every variant is bound and rendered.
        assertEquals(run.variants, run.boundVariants, "Every variant must carry a derived weighted rig");
        assertEquals(run.meshes, run.weightedMeshes, "Every mesh must be weighted, not left rigid");
        assertEquals(run.variants, run.renderedVariants, "Every variant must render through the weighted path");
        assertTrue(run.totalVertices > 0, "No vertices were skinned");
        // The strong claim, scoped to exactly the variants where it is true.
        assertEquals(run.identityBindVariants, run.bitIdenticalVariants,
                "Every variant with an identity bind pose must match the rigid path bit for bit");
        System.out.println("REAL_SKINNING_SUMMARY models=" + run.models + " variants=" + run.variants
                + " meshes=" + run.meshes + " vertices=" + run.totalVertices
                + " identityBindVariants=" + run.identityBindVariants
                + " bitIdenticalBindVariants=" + run.bitIdenticalVariants
                + " posedRenderedVariants=" + run.posedRenderedVariants
                + " posedFromOwnClip=" + run.posedFromOwnClip
                + " weightedPeakMiB=" + String.format("%.1f", run.peakWorking / 1048576.0));
    }

    @Test
    void identityBindReproducesTheRigidPathBitForBit() throws Exception {
        Run run = runCorpus(true);
        assertEquals(EXPECTED_MODELS, run.models, "Corpus model count changed");
        assertEquals(run.variants, run.boundVariants, "Every variant must carry a derived weighted rig");
        assertEquals(run.variants, run.identityBindVariants, "The identity bind pose must evaluate to identity");
        assertEquals(run.variants, run.bitIdenticalVariants,
                "Weighted and rigid output must match bit for bit under an identity bind pose");
        assertEquals(run.variants, run.posedRenderedVariants, "Every variant must complete a posed frame");
        assertTrue(run.totalVertices > 0, "No vertices were skinned");
        System.out.println("REAL_SKINNING_BITS variants=" + run.variants
                + " vertices=" + run.totalVertices
                + " bitIdentical=" + run.bitIdenticalVariants
                + " posedCompleted=" + run.posedRenderedVariants
                + " posedFromOwnClip=" + run.posedFromOwnClip);
    }

    /** Frame of run accounting, so the tests assert on measured coverage rather than on silence. */
    private static final class Run {
        int files, models, variants, boundVariants, renderedVariants, meshes, weightedMeshes;
        int identityBindVariants, bitIdenticalVariants, posedRenderedVariants, posedFromOwnClip;
        long totalVertices, peakWorking;
        final List<String> nonIdentityBindModels = new ArrayList<>();
    }

    /**
     * @param forceIdentityBind when true the document is rebuilt with an identity bind pose, so the
     *                          rigid and weighted conventions provably coincide and bit equality is a
     *                          real claim rather than an assumption
     */
    private Run runCorpus(boolean forceIdentityBind) throws Exception {
        Path root = corpusDirectory();
        List<Path> files = corpusFiles(root);
        long budgetMiB = Long.parseLong(System.getProperty("spm.corpus.budgetMiB", "512"));
        long budgetBytes = Math.multiplyExact(budgetMiB, 1L << 20);
        var run = new Run();
        run.files = files.size();
        for (Path file : files) {
            String key = root.relativize(file).toString().replace('\\', '/');
            var importBudget = new ResourceBudget(budgetBytes);
            var source = new ModelSource("private-corpus", new ResourceKey(key), resolver(root));
            try (var result = new SpmModelParser().importModel(new ImportRequest(source, FormatHint.AUTO,
                    DecodePolicy.localModel(), importBudget, new CancellationToken()))) {
                run.models++;
                for (var model : result.models()) {
                    run.variants++;
                    ModelDocument imported = model.asset().document();
                    String label = key + " / " + model.key();
                    ModelDocument document = forceIdentityBind ? withBindPose(imported, BonePose.identity(
                            imported.skeleton().boneCount())) : imported;
                    // The equality claim is conditional on an identity bind pose, so check it rather
                    // than assume it; that is also what makes the reported count honest.
                    boolean identityBind = bindIsIdentity(document);
                    if (identityBind) run.identityBindVariants++;
                    else run.nonIdentityBindModels.add(label);

                    SkinningRig rig = SkinningRig.fromVertexBones(document);
                    assertTrue(rig.hasWeights(), label + " derived no weighted meshes");
                    assertEquals(document.meshes().size(), rig.weightedMeshes().size(),
                            label + " left a mesh rigid");
                    run.boundVariants++;
                    for (ModelDocument.Mesh mesh : document.meshes()) {
                        run.meshes++;
                        MeshSkin skin = rig.skin(mesh.name());
                        assertTrue(skin instanceof MeshSkin.Weighted,
                                label + " mesh " + mesh.name() + " was not bound as weighted");
                        var weighted = (MeshSkin.Weighted) skin;
                        assertEquals(SkinningMethod.BDEF, weighted.method(), label + " unexpected method");
                        assertEquals(0, weighted.targetCount(), label + " invented morph targets");
                        assertFalse(weighted.sdef().hasCentres(), label + " invented SDEF centres");
                        run.weightedMeshes++;
                        run.totalVertices += mesh.vertices().vertexCount();
                        assertWeightsMirrorVertexBones(weighted.weights(), mesh.vertices(), document,
                                label + " mesh " + mesh.name());
                    }

                    BonePose bindPose = document.bindPose();
                    var frameBudget = new ResourceBudget(budgetBytes);
                    CpuFrame rigid = ModelFrameRenderer.render(document, bindPose, frameBudget,
                            new CancellationToken());
                    CpuFrame skinned = ModelFrameRenderer.render(document, bindPose, rig, SkinState.rest(),
                            frameBudget, new CancellationToken());
                    try {
                        run.renderedVariants++;
                        assertEquals(rigid.meshes().size(), skinned.meshes().size(), label + " mesh count");
                        for (int mesh = 0; mesh < rigid.meshes().size(); mesh++) {
                            assertMeshEquivalent(label + " mesh " + mesh, rigid.meshes().get(mesh),
                                    skinned.meshes().get(mesh), identityBind);
                        }
                        if (identityBind) run.bitIdenticalVariants++;
                        run.peakWorking = Math.max(run.peakWorking, frameBudget.peakUsed());
                    } finally {
                        skinned.close();
                        rigid.close();
                    }
                    assertEquals(0, frameBudget.used(), label + " frame budget not released");

                    // A posed frame: asserted to complete with finite output and a released budget,
                    // not to reproduce the rigid path — see the class javadoc for why.
                    Posed posedFrame = posedFrame(document);
                    if (posedFrame.fromClip()) run.posedFromOwnClip++;
                    BonePose pose = posedFrame.pose();
                    assertEquals(document.skeleton().boneCount(), pose.boneCount(), label + " pose size");
                    var posedBudget = new ResourceBudget(budgetBytes);
                    CpuFrame posed = ModelFrameRenderer.render(document, pose, rig, SkinState.rest(),
                            posedBudget, new CancellationToken());
                    try {
                        assertEquals(document.meshes().size(), posed.meshes().size(), label + " posed meshes");
                        for (CpuFrame.Mesh mesh : posed.meshes()) {
                            assertFinite(label + " posed positions", mesh.vertices().positions());
                            assertFinite(label + " posed normals", mesh.vertices().normals());
                            assertEquals(Math.multiplyExact(mesh.vertices().vertexCount(), 2),
                                    mesh.uvs().length, label + " posed uv count");
                            assertFinite(label + " posed uvs", mesh.uvs());
                        }
                        run.posedRenderedVariants++;
                    } finally {
                        posed.close();
                    }
                    assertEquals(0, posedBudget.used(), label + " posed frame budget not released");
                }
            } finally {
                assertEquals(0, importBudget.used(), "Retained import budget after " + key);
            }
        }
        if (!run.nonIdentityBindModels.isEmpty()) {
            System.out.println("REAL_SKINNING_NON_IDENTITY_BIND count=" + run.nonIdentityBindModels.size());
            for (String label : run.nonIdentityBindModels)
                System.out.println("REAL_SKINNING_NON_IDENTITY_BIND " + label);
        }
        return run;
    }

    /**
     * Structural equivalence of a rigid and a weighted mesh. Everything here is exact by construction
     * for single-influence weights: the same visibility remap produces the same vertex count, the same
     * dominant bone, the same tint, and the same filtered triangle list. Positions and normals are
     * compared only when the bind pose is the identity, since only then is their equality provable.
     */
    private static void assertMeshEquivalent(String label, CpuFrame.Mesh expected, CpuFrame.Mesh actual,
                                             boolean identityBind) {
        assertEquals(expected.name(), actual.name(), label + " name");
        assertEquals(expected.materialIndex(), actual.materialIndex(), label + " material");
        assertEquals(expected.vertices().vertexCount(), actual.vertices().vertexCount(),
                label + " visible vertex count");
        assertIntArrayEquals(expected.vertices().boneIndices(), actual.vertices().boneIndices(),
                label + " bone indices");
        assertIntArrayEquals(expected.vertices().glow(), actual.vertices().glow(),
                label + " glow");
        assertIntArrayEquals(expected.vertices().colorsRgba(), actual.vertices().colorsRgba(),
                label + " colours");
        assertIntArrayEquals(expected.indices(), actual.indices(), label + " indices");
        assertSameBits(expected.uvs(), actual.uvs(), label + " uvs");
        assertFinite(label + " positions", actual.vertices().positions());
        assertFinite(label + " normals", actual.vertices().normals());
        for (int component : actual.vertices().glow()) {
            assertTrue(component == 255 || component >= 0 && component <= 15, label + " glow value");
        }
        if (identityBind) {
            assertSameBits(expected.vertices().positions(), actual.vertices().positions(),
                    label + " bind-pose positions");
            assertSameBits(expected.vertices().normals(), actual.vertices().normals(),
                    label + " bind-pose normals");
        }
    }

    /**
     * The derived weights must be exactly the model's own assignment: one influence, full weight, and
     * that influence is the vertex's recorded bone. Asserted per vertex, because this is the property
     * that makes the rig legitimate rather than invented.
     */
    private static void assertWeightsMirrorVertexBones(SkinWeights weights, VertexBatch batch,
                                                       ModelDocument document, String label) {
        int vertices = batch.vertexCount();
        assertEquals(vertices, weights.vertexCount(), label + " weight count");
        assertEquals(document.skeleton().boneCount(), weights.boneCount(), label + " skeleton mismatch");
        int[] bones = batch.boneIndices();
        for (int vertex = 0; vertex < vertices; vertex++) {
            assertEquals(1, weights.influenceCount(vertex), label + " influence count at " + vertex);
            assertEquals(bones[vertex], weights.boneIndex(vertex, 0), label + " bone at " + vertex);
            assertEquals(1f, weights.weight(vertex, 0), 0f, label + " weight at " + vertex);
            assertEquals(bones[vertex], weights.dominantBone(vertex), label + " dominant bone at " + vertex);
            for (int slot = 1; slot < SkinWeights.MAX_INFLUENCES; slot++) {
                assertEquals(0f, weights.weight(vertex, slot), 0f,
                        label + " padding weight at " + vertex);
                assertEquals(0, weights.boneIndex(vertex, slot), label + " padding bone at " + vertex);
            }
        }
    }

    /** A posed frame plus where it came from, so the report can state the split honestly. */
    private record Posed(BonePose pose, boolean fromClip) { }

    /** A posed frame: the document's own first clip when it decodes, otherwise a deterministic tilt. */
    private static Posed posedFrame(ModelDocument document) {
        var budget = new ScriptBudget(50_000_000L, 64, new CancellationToken());
        try {
            var library = PoseAnimationLibrary.fromModel(document, budget);
            var names = new TreeSet<>(library.names());
            if (!names.isEmpty()) {
                var context = new MolangContext(Map.of(), Map.of(), Map.of(), budget);
                BonePose sampled = library.sample(names.first(), 0.4, context, document.bindPose());
                if (sampled.boneCount() == document.skeleton().boneCount())
                    return new Posed(sampled, true);
            }
        } catch (RuntimeException undecodableClips) {
            // A model whose clips cannot be decoded still needs a posed frame; fall through.
        }
        return new Posed(tilted(document.bindPose()), false);
    }

    /**
     * Deterministic pose offset from the authored channels: every bone moves a little, so no weighted
     * vertex is trivially left at its bind position. Both renderers receive the same pose.
     */
    private static BonePose tilted(BonePose bind) {
        int boneCount = bind.boneCount();
        float[] rotations = bind.rotations(), translations = bind.translations(), scales = bind.scales();
        for (int bone = 0; bone < boneCount; bone++) {
            int offset = bone * 3;
            rotations[offset] += 0.05f * (bone % 7);
            rotations[offset + 1] += 0.03f * (bone % 5);
            rotations[offset + 2] += 0.02f * (bone % 3);
            translations[offset] += 0.01f * (bone % 4);
            translations[offset + 1] += 0.01f * (bone % 3);
            translations[offset + 2] += 0.01f * (bone % 2);
            scales[offset] *= 1 + 0.01f * (bone % 2);
        }
        return new BonePose(rotations, translations, scales, bind.ownHidden(), bind.childrenHidden(),
                bind.glow(), bind.colorsRgba());
    }

    private static ModelDocument withBindPose(ModelDocument document, BonePose bindPose) {
        return new ModelDocument(document.identity(), document.sourceFormat(), document.skeleton(),
                document.meshes(), bindPose, document.materials(), document.locators(), document.metadata());
    }

    private static boolean bindIsIdentity(ModelDocument document) {
        var bind = BindMatrices.evaluate(document.skeleton(), document.bindPose());
        for (int bone = 0; bone < document.skeleton().boneCount(); bone++) {
            for (int element = 0; element < 16; element++) {
                if (bind.matrix(bone, element) != (element % 5 == 0 ? 1f : 0f)) return false;
            }
        }
        return true;
    }

    private static Path corpusDirectory() {
        try {
            return Path.of(System.getProperty("spm.corpus.directory")).toRealPath();
        } catch (IOException failure) {
            throw new AssertionError("Private corpus directory is not readable", failure);
        }
    }

    private static List<Path> corpusFiles(Path root) {
        var files = new ArrayList<Path>();
        try (var inputs = Files.walk(root)) {
            inputs.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ysm"))
                    .sorted().forEach(files::add);
        } catch (IOException failure) {
            throw new AssertionError("Cannot enumerate the private corpus", failure);
        }
        assertFalse(files.isEmpty(), "No private model inputs found");
        return files;
    }

    /** A resolver confined to the corpus root: a resource escaping it fails instead of being read. */
    private static ResourceResolver resolver(Path root) {
        return resource -> {
            Path path = root.resolve(resource.path()).normalize().toRealPath();
            if (!path.startsWith(root)) throw new IOException("Resource escaped the private corpus");
            return Files.newInputStream(path);
        };
    }

    private static void assertFinite(String label, float[] values) {
        for (int index = 0; index < values.length; index++)
            assertTrue(Float.isFinite(values[index]),
                    label + "[" + index + "] is not finite: " + values[index]);
    }

    private static void assertIntArrayEquals(int[] expected, int[] actual, String label) {
        assertEquals(expected.length, actual.length, label + " length");
        for (int index = 0; index < expected.length; index++)
            assertEquals(expected[index], actual[index], label + " at " + index);
    }

    /** Raw-bit comparison: the claim is exact equality, so an epsilon would weaken it. */
    private static void assertSameBits(float[] expected, float[] actual, String label) {
        assertEquals(expected.length, actual.length, label + " length");
        for (int index = 0; index < expected.length; index++)
            assertEquals(Float.floatToRawIntBits(expected[index]),
                    Float.floatToRawIntBits(actual[index]),
                    label + " at " + index + " (" + expected[index] + " vs " + actual[index] + ")");
    }
}
