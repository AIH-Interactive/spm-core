package dev.spm.core.integration;

import dev.spm.core.api.CancellationToken;
import dev.spm.core.api.DecodePolicy;
import dev.spm.core.api.ModelDocument;
import dev.spm.core.api.ResourceBudget;
import dev.spm.core.api.Skeleton;
import dev.spm.core.format.ysm.YsmArchiveCodec;
import dev.spm.core.format.ysm.YsmEnvelopeCodec;
import dev.spm.core.format.ysm.YsmHistoricalCodec;
import dev.spm.core.format.ysm.YsmImportResult;
import dev.spm.core.format.ysm.YsmModelImporter;
import dev.spm.core.format.ysm.YsmWirePayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Measures the real bone hierarchies of the community models in {@code D:\SparkleMorpher\TestModel}.
 *
 * <p>This exists to answer one question with data instead of assumption: how much of a real rig is
 * static from frame to frame? The design-C fast path caches each bone's local matrix and only
 * recomputes the ones whose parameters changed, so its benefit is exactly the fraction of bones
 * that do not move. Guessing that fraction would be guessing the whole design's value.
 *
 * <p>It reports, per model: bone count, tree depth, the widest level, and the distribution of
 * siblings per level — the last of which decides whether the 8-wide kernel can even be fed.
 *
 * <p>Run with:
 * <pre>
 * ./gradlew :integration-tests:test --tests '*RealModelHierarchyTest*' -Dspm.testModelDir=...
 * </pre>
 * The test is skipped when the directory is absent, so it never fails a machine without the models.
 */
public final class RealModelHierarchyTest {
    private static final Path DEFAULT_DIR = Path.of("D:\\SparkleMorpher\\TestModel");

    /** One model's measured shape. */
    record Shape(String name, int bones, int depth, int widestLevel, double meanLevelWidth,
                 int leaves, int maxChildren) {
        @Override public String toString() {
            return String.format(Locale.ROOT,
                    "%-34s bones=%4d depth=%3d widest=%4d meanWidth=%6.1f leaves=%4d maxChildren=%3d",
                    name, bones, depth, widestLevel, meanLevelWidth, leaves, maxChildren);
        }
    }

    @org.junit.jupiter.api.Test
    void reportRealModelHierarchies() throws IOException {
        Path dir = Path.of(System.getProperty("spm.testModelDir", DEFAULT_DIR.toString()));
        if (!Files.isDirectory(dir)) {
            System.out.println("[hierarchy] skipped: " + dir + " is not a directory");
            return;
        }
        List<Path> models;
        try (Stream<Path> walk = Files.walk(dir)) {
            models = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .sorted()
                    .toList();
        }
        if (models.isEmpty()) {
            System.out.println("[hierarchy] skipped: no .ysm files under " + dir);
            return;
        }
        System.out.println("[hierarchy] measuring " + models.size() + " model(s) under " + dir);
        List<Shape> shapes = new ArrayList<>();
        for (Path model : models) {
            try {
                shapes.addAll(measure(model));
            } catch (IOException | RuntimeException failure) {
                System.out.println("[hierarchy] " + model.getFileName() + " FAILED: " + failure);
            }
        }
        System.out.println();
        System.out.println("=== measured bone hierarchies ===");
        shapes.forEach(shape -> System.out.println(shape));
        System.out.println();
        summarise(shapes);
    }

    /** Imports one archive and measures every variant that lowered to a document. */
    private static List<Shape> measure(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        DecodePolicy policy = DecodePolicy.localModel();
        List<Shape> shapes = new ArrayList<>();
        ResourceBudget budget = new ResourceBudget(policy.maxExpandedBytes());
        // Two generations of container exist: the V3 encrypted envelope, and the older raw
        // archive. Try the envelope first and fall back, so both are covered without the caller
        // having to know which one a file is.
        YsmImportResult imported;
        try {
            YsmWirePayload wire = YsmEnvelopeCodec.decode(bytes, policy, budget, new CancellationToken());
            try (wire) {
                imported = YsmModelImporter.importPayload(wire, policy, budget, new CancellationToken());
            }
        } catch (IOException | RuntimeException notEnvelope) {
            // The raw archive path decodes to a historical model, which the importer lowers.
            var archive = YsmArchiveCodec.decode(bytes, policy, budget, new CancellationToken());
            try (archive) {
                var historical = YsmHistoricalCodec.decodeForImport(
                        archive.read(archive.entries().iterator().next()),
                        policy, budget, new CancellationToken());
                imported = YsmModelImporter.importPayload(historical, policy, budget, new CancellationToken());
            }
        }
        final YsmImportResult result = imported;
        try (result) {
            int variantIndex = 0;
            for (YsmImportResult.Variant variant : result.variants()) {
                if (variant.document().isEmpty()) {
                    continue;
                }
                ModelDocument document = variant.document().get();
                String label = file.getFileName() + (variantIndex == 0 ? "" : "#" + variantIndex);
                shapes.add(shapeOf(label, document.skeleton()));
                variantIndex++;
            }
        }
        return shapes;
    }

    /** Computes the shape metrics the fast path cares about. */
    static Shape shapeOf(String name, Skeleton skeleton) {
        int n = skeleton.boneCount();
        int[] parents = skeleton.parents();
        int[] depth = new int[n];
        int[] children = new int[n];
        for (int i = 0; i < n; i++) {
            if (parents[i] >= 0) {
                children[parents[i]]++;
            }
        }
        // Depth by repeated relaxation over the evaluation order, which is parent-before-child.
        int[] order = skeleton.evaluationOrder();
        for (int bone : order) {
            depth[bone] = parents[bone] < 0 ? 0 : depth[parents[bone]] + 1;
        }
        int maxDepth = 0;
        for (int d : depth) {
            maxDepth = Math.max(maxDepth, d);
        }
        int[] perLevel = new int[maxDepth + 1];
        for (int d : depth) {
            perLevel[d]++;
        }
        int widest = 0;
        for (int w : perLevel) {
            widest = Math.max(widest, w);
        }
        int leaves = 0;
        int maxChildren = 0;
        for (int i = 0; i < n; i++) {
            if (children[i] == 0) {
                leaves++;
            }
            maxChildren = Math.max(maxChildren, children[i]);
        }
        double meanWidth = maxDepth == 0 ? n : (double) n / (maxDepth + 1);
        return new Shape(name, n, maxDepth + 1, widest, meanWidth, leaves, maxChildren);
    }

    /** Prints the aggregate numbers the design decision rests on. */
    private static void summarise(List<Shape> shapes) {
        if (shapes.isEmpty()) {
            System.out.println("[hierarchy] no documents were produced");
            return;
        }
        int totalBones = 0;
        int maxBones = 0;
        int maxDepth = 0;
        double meanWidthSum = 0;
        for (Shape shape : shapes) {
            totalBones += shape.bones();
            maxBones = Math.max(maxBones, shape.bones());
            maxDepth = Math.max(maxDepth, shape.depth());
            meanWidthSum += shape.meanLevelWidth();
        }
        System.out.println("=== aggregate ===");
        System.out.printf(Locale.ROOT, "variants measured      : %d%n", shapes.size());
        System.out.printf(Locale.ROOT, "bones total / max      : %d / %d%n", totalBones, maxBones);
        System.out.printf(Locale.ROOT, "deepest tree           : %d levels%n", maxDepth);
        System.out.printf(Locale.ROOT, "mean level width       : %.1f bones%n", meanWidthSum / shapes.size());
        System.out.println();
        System.out.println("Interpretation: a level width of 8 or more means the 8-wide kernel can");
        System.out.println("fill its lanes from siblings alone; a narrow level falls back to scalar");
        System.out.println("lanes for the remainder.");
    }

    /**
     * Writes the largest variant of every model as a plain-text skeleton the Rust benchmark can
     * read, so the performance numbers are measured on real rigs rather than a synthetic fixture.
     *
     * <p>Format: one header line {@code bones depth}, then one line per bone
     * {@code parent pivotX pivotY pivotZ}. Deliberately trivial to parse — this is a measurement
     * input, not a product format.
     */
    @org.junit.jupiter.api.Test
    void dumpRealSkeletonsForBenchmark() throws IOException {
        Path dir = Path.of(System.getProperty("spm.testModelDir", DEFAULT_DIR.toString()));
        Path out = Path.of(System.getProperty("spm.skeletonOut", "build/real-skeletons"));
        if (!Files.isDirectory(dir)) {
            System.out.println("[dump] skipped: " + dir + " is not a directory");
            return;
        }
        Files.createDirectories(out);
        List<Path> models;
        try (Stream<Path> walk = Files.walk(dir)) {
            models = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .sorted()
                    .toList();
        }
        for (Path model : models) {
            try {
                Skeleton largest = largestSkeleton(model);
                if (largest == null) {
                    continue;
                }
                String base = model.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
                Path target = out.resolve(base + ".skeleton.txt");
                StringBuilder text = new StringBuilder();
                text.append(largest.boneCount()).append(' ').append(depthOf(largest)).append('\n');
                int[] parents = largest.parents();
                for (int i = 0; i < largest.boneCount(); i++) {
                    text.append(parents[i]).append(' ')
                            .append(largest.pivot(i, 0)).append(' ')
                            .append(largest.pivot(i, 1)).append(' ')
                            .append(largest.pivot(i, 2)).append('\n');
                }
                Files.writeString(target, text.toString());
                System.out.println("[dump] " + target + " (" + largest.boneCount() + " bones)");
            } catch (IOException | RuntimeException failure) {
                System.out.println("[dump] " + model.getFileName() + " FAILED: " + failure);
            }
        }
    }

    /** The variant with the most bones, which is the one the fast path must handle. */
    private static Skeleton largestSkeleton(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        DecodePolicy policy = DecodePolicy.localModel();
        ResourceBudget budget = new ResourceBudget(policy.maxExpandedBytes());
        YsmImportResult imported;
        try {
            YsmWirePayload wire = YsmEnvelopeCodec.decode(bytes, policy, budget, new CancellationToken());
            try (wire) {
                imported = YsmModelImporter.importPayload(wire, policy, budget, new CancellationToken());
            }
        } catch (IOException | RuntimeException notEnvelope) {
            var archive = YsmArchiveCodec.decode(bytes, policy, budget, new CancellationToken());
            try (archive) {
                var historical = YsmHistoricalCodec.decodeForImport(
                        archive.read(archive.entries().iterator().next()),
                        policy, budget, new CancellationToken());
                imported = YsmModelImporter.importPayload(historical, policy, budget, new CancellationToken());
            }
        }
        Skeleton best = null;
        final YsmImportResult owned = imported;
        try (owned) {
            for (YsmImportResult.Variant variant : owned.variants()) {
                if (variant.document().isEmpty()) {
                    continue;
                }
                Skeleton candidate = variant.document().get().skeleton();
                if (best == null || candidate.boneCount() > best.boneCount()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Tree depth of a skeleton, by relaxing over its parent-before-child evaluation order. */
    private static int depthOf(Skeleton skeleton) {
        int[] parents = skeleton.parents();
        int[] depth = new int[skeleton.boneCount()];
        int max = 0;
        for (int bone : skeleton.evaluationOrder()) {
            depth[bone] = parents[bone] < 0 ? 0 : depth[parents[bone]] + 1;
            max = Math.max(max, depth[bone]);
        }
        return max + 1;
    }
}
