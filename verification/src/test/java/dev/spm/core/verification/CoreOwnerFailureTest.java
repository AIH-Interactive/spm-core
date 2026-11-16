package dev.spm.core.verification;

import dev.spm.core.acceleration.CpuComputeProvider;
import dev.spm.core.api.*;
import dev.spm.core.parser.SpmModelParser;
import dev.spm.core.render.cpu.JavaComputeProvider;
import dev.spm.core.runtime.CpuFrame;
import dev.spm.core.runtime.ModelFrameRenderer;
import dev.spm.core.runtime.ResourceRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * R07 pure-Core lifecycle fault injection: concurrent retirement/cancellation races, provider/frame
 * failures, ResourceRegistry generation races and importer stream/budget cleanup.
 */
final class CoreOwnerFailureTest {

    private static ModelDocument twoBoneDocument() {
        var vertices = new VertexBatch(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new float[] {0, 0, 1, 0, 0, 1, 0, 0, 1},
                new int[] {0, 1, 1});
        var mesh = new ModelDocument.Mesh("mesh", vertices, new float[] {0, 0, 1, 0, 0, 1},
                new int[] {0, 1, 2}, List.of(), new ModelDocument.Bounds(0, 0, 0, 1, 1, 0), 0);
        return new ModelDocument("owner-fault", "synthetic",
                new Skeleton(List.of("root", "child"), new int[] {-1, 0}, new float[6]),
                List.of(mesh), BonePose.identity(2),
                List.of(new ModelDocument.Material("mat", 1, 1, List.of())), List.of(),
                new ModelDocument.JsonObject(Map.of()));
    }

    private static SkinningRig weightedRig(ModelDocument document) {
        var weights = SkinWeights.of(2,
                new int[][] {{0}, {0, 1}, {1}},
                new float[][] {{1f}, {0.5f, 0.5f}, {1f}});
        return SkinningRig.of(document, Map.of("mesh", new MeshSkin.Weighted(weights, SkinningMethod.BDEF)));
    }

    @Test
    void concurrentAssetRetireWhileRenderingKeepsAssetAliveOnSuccessAndReleasesOnCancel() throws Exception {
        for (boolean weighted : new boolean[] {false, true}) {
            for (boolean cancelMidFlight : new boolean[] {false, true}) {
                var budget = new ResourceBudget(64L << 20);
                var document = twoBoneDocument();
                var released = new AtomicInteger();
                var asset = new ModelAsset(document, List.of(budget.reserve(4096)), released::incrementAndGet);
                var enteredProvider = new CountDownLatch(1);
                var proceed = new CountDownLatch(1);
                var token = new CancellationToken();
                var java = new JavaComputeProvider();
                CpuComputeProvider latching = new CpuComputeProvider() {
                    @Override public String backend() { return java.backend(); }
                    @Override public BoneMatrices evaluateBones(Skeleton skeleton, BonePose pose) {
                        enteredProvider.countDown();
                        try {
                            assertTrue(proceed.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return java.evaluateBones(skeleton, pose);
                    }
                    @Override public VertexBatch renderVertices(VertexBatch vertices, BoneMatrices matrices) {
                        return java.renderVertices(vertices, matrices);
                    }
                };
                var pool = Executors.newSingleThreadExecutor();
                try {
                    Future<CpuFrame> future = pool.submit(() -> weighted
                            ? ModelFrameRenderer.render(asset, BonePose.identity(2), weightedRig(document),
                                    SkinState.rest(), latching, budget, token)
                            : ModelFrameRenderer.render(asset, BonePose.identity(2), latching, budget, token));
                    assertTrue(enteredProvider.await(5, TimeUnit.SECONDS));
                    asset.close();
                    assertEquals(0, released.get(), "active render lease must keep retired asset alive");
                    assertTrue(budget.used() >= 4096);
                    if (cancelMidFlight) token.cancel();
                    proceed.countDown();
                    if (cancelMidFlight) {
                        var ex = assertThrows(java.util.concurrent.ExecutionException.class,
                                () -> future.get(5, TimeUnit.SECONDS));
                        assertInstanceOf(CancellationException.class, ex.getCause());
                        assertEquals(1, released.get(), "cancelled render must release retired asset once");
                        assertEquals(0, budget.used(), "cancelled render must leave budget at zero");
                    } else {
                        try (CpuFrame frame = future.get(5, TimeUnit.SECONDS)) {
                            assertFalse(frame.meshes().isEmpty());
                            assertEquals(0, released.get(), "live CpuFrame keeps retired asset open");
                            assertTrue(budget.used() > 4096);
                        }
                        assertEquals(1, released.get(), "closing frame releases retired asset once");
                        assertEquals(0, budget.used());
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    @Test
    void providerFailuresOnRigidAndWeightedPathsReleaseWorkingCreditsAndKeepAssetLeaseClean() {
        var document = twoBoneDocument();
        var rig = weightedRig(document);
        var java = new JavaComputeProvider();
        CpuComputeProvider failingEvaluate = new CpuComputeProvider() {
            @Override public String backend() { return "FAIL_BONES"; }
            @Override public BoneMatrices evaluateBones(Skeleton s, BonePose p) {
                throw new IllegalStateException("injected bone failure");
            }
            @Override public VertexBatch renderVertices(VertexBatch v, BoneMatrices m) {
                return java.renderVertices(v, m);
            }
        };
        CpuComputeProvider scrambledOrder = new CpuComputeProvider() {
            @Override public String backend() { return "SCRAMBLED"; }
            @Override public BoneMatrices evaluateBones(Skeleton s, BonePose p) {
                return java.evaluateBones(s, p);
            }
            @Override public VertexBatch renderVertices(VertexBatch v, BoneMatrices m) {
                VertexBatch ok = java.renderVertices(v, m);
                int[] wrongBones = ok.boneIndices();
                wrongBones[0] = 1; // swaps bone 0 -> 1 on vertex 0
                return new VertexBatch(ok.positions(), ok.normals(), wrongBones, ok.glow(), ok.colorsRgba());
            }
        };
        var budget = new ResourceBudget(64L << 20);
        var released = new AtomicInteger();
        var asset = new ModelAsset(document, List.of(budget.reserve(2048)), released::incrementAndGet);
        try (asset) {
            assertThrows(IllegalStateException.class, () -> ModelFrameRenderer.render(
                    asset, BonePose.identity(2), failingEvaluate, budget, new CancellationToken()));
            assertEquals(2048, budget.used(), "failed rigid evaluateBones must release only working budget");
            assertThrows(IllegalStateException.class, () -> ModelFrameRenderer.render(
                    asset, BonePose.identity(2), rig, SkinState.rest(), failingEvaluate, budget, new CancellationToken()));
            assertEquals(2048, budget.used(), "failed weighted evaluateBones must release only working budget");
            assertThrows(IllegalArgumentException.class, () -> ModelFrameRenderer.render(
                    asset, BonePose.identity(2), scrambledOrder, budget, new CancellationToken()));
            assertEquals(2048, budget.used(), "rejected vertex order must release only working budget");
            assertEquals(0, released.get(), "asset remains open while not closed");
        }
        assertEquals(1, released.get(), "all temporary leases were returned so asset closes cleanly");
        assertEquals(0, budget.used());
    }

    @Test
    void modelAssetReleasesBudgetEvenIfReleaseDependencyThrowsAndConcurrentCloseIsIdempotent() throws Exception {
        var budget = new ResourceBudget(4096);
        var calls = new AtomicInteger();
        var asset = new ModelAsset(twoBoneDocument(), List.of(budget.reserve(1024)), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("cleanup boom");
        });
        assertThrows(IllegalStateException.class, asset::close);
        assertEquals(0, budget.used(), "reservations must close before dependency callback runs");
        assertThrows(IllegalStateException.class, asset::document);
        asset.close(); // idempotent and does not re-invoke dependency
        assertEquals(1, calls.get());

        // Concurrent closes run dependency at most once.
        var concurrentCalls = new AtomicInteger();
        var shared = new ModelAsset(twoBoneDocument(), List.of(budget.reserve(512)), concurrentCalls::incrementAndGet);
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    shared.close();
                }));
            }
            gate.countDown();
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, concurrentCalls.get());
        assertEquals(0, budget.used());
    }

    @Test
    void registryHandlesStaleTicketsReplacedLeasesAndThrowingCleanupCallbacks() {
        var cleanupFailures = new AtomicInteger();
        var closedObjects = new java.util.ArrayList<String>();
        var registry = new ResourceRegistry<String>();
        long t1 = registry.begin("avatar");
        long t2 = registry.begin("avatar");
        assertFalse(registry.publishIfCurrent("avatar", t1, "stale", () -> closedObjects.add("stale")));
        assertEquals(List.of("stale"), closedObjects);
        assertTrue(registry.publishIfCurrent("avatar", t2, "v1", () -> closedObjects.add("v1")));
        var leaseV1 = registry.acquire("avatar").orElseThrow();
        assertEquals("v1", leaseV1.value());

        // Replace while leased, with a failing cleanup on v2 when later closed.
        registry.publish("avatar", "v2", () -> {
            closedObjects.add("v2");
            cleanupFailures.incrementAndGet();
            throw new IllegalStateException("v2 cleanup failed");
        });
        assertEquals("v1", leaseV1.value(), "old lease stays readable until returned");
        assertFalse(closedObjects.contains("v1"));
        assertEquals(List.of("avatar"), registry.leakedLeases());

        leaseV1.close();
        leaseV1.close(); // idempotent
        assertTrue(closedObjects.contains("v1"));
        assertTrue(registry.leakedLeases().isEmpty());

        registry.close();
        assertTrue(closedObjects.contains("v2"));
        assertEquals(1, cleanupFailures.get());
        assertEquals(1, registry.cleanupFailures().size());
        assertFalse(registry.publishIfCurrent("avatar", t2, "after-close", () -> closedObjects.add("after-close")));
        assertTrue(closedObjects.contains("after-close"));
    }

    @Test
    void importerFailuresCloseOpenedStreamsAndReleaseBudget() throws Exception {
        byte[] bedrock = """
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.fault","texture_width":16,"texture_height":16},"bones":[{"name":"root","cubes":[{"origin":[0,0,0],"size":[16,16,16],"uv":[0,0]}]}]}]}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] figura = figuraZip(
                "{\"name\":\"fault\"}",
                "{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\"},\"name\":\"box\",\"resolution\":{\"width\":16,\"height\":16},\"textures\":[],\"elements\":[{\"uuid\":\"c\",\"type\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16],\"origin\":[0,0,0],\"faces\":{\"north\":{\"uv\":[0,0,16,16]}}}],\"outliner\":[\"c\"]}");

        var parser = new SpmModelParser();
        for (var entry : List.of(
                Map.entry("model.geo.json", bedrock),
                Map.entry("avatar.figura.zip", figura))) {
            var openStreams = new AtomicInteger();
            ResourceResolver failingResolver = key -> {
                openStreams.incrementAndGet();
                return new FilterInputStream(new ByteArrayInputStream(entry.getValue())) {
                    private boolean closed;
                    private int readCount;
                    @Override public int read() throws IOException {
                        if (++readCount > 24) throw new IOException("injected read failure");
                        return super.read();
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        int chunk = Math.min(len, 16);
                        int n = super.read(b, off, chunk);
                        if (n > 0) {
                            readCount += n;
                            if (readCount > 24) throw new IOException("injected read failure");
                        }
                        return n;
                    }
                    @Override public void close() throws IOException {
                        if (!closed) {
                            closed = true;
                            openStreams.decrementAndGet();
                        }
                        super.close();
                    }
                };
            };
            var budget = new ResourceBudget(64L << 20);
            assertThrows(IOException.class, () -> parser.importModel(new ImportRequest(
                    new ModelSource("fault", new ResourceKey(entry.getKey()), failingResolver),
                    FormatHint.AUTO, DecodePolicy.conservative(), budget, new CancellationToken())));
            assertEquals(0, openStreams.get(), "failed import must close opened stream for " + entry.getKey());
            assertEquals(0, budget.used(), "failed import must release budget for " + entry.getKey());

            // Budget exhaustion must also close every stream and release reservations.
            ResourceResolver trackingOkResolver = key -> {
                openStreams.incrementAndGet();
                return new FilterInputStream(new ByteArrayInputStream(entry.getValue())) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (!closed) {
                            closed = true;
                            openStreams.decrementAndGet();
                        }
                        super.close();
                    }
                };
            };
            var starved = new ResourceBudget(1);
            assertThrows(IOException.class, () -> parser.importModel(new ImportRequest(
                    new ModelSource("starved", new ResourceKey(entry.getKey()), trackingOkResolver),
                    FormatHint.AUTO, DecodePolicy.conservative(), starved, new CancellationToken())));
            assertEquals(0, openStreams.get(), "budget-exhausted import must close stream for " + entry.getKey());
            assertEquals(0, starved.used());
        }
    }

    private static byte[] figuraZip(String avatarJson, String bbmodelJson) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("avatar.json"));
            zip.write(avatarJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("model.bbmodel"));
            zip.write(bbmodelJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
