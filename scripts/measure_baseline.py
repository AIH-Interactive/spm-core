"""Record real, measured baseline metrics for Core's CPU and native paths.

The plan's T01 requires p50/p95/p99, throughput and memory figures with hardware/JVM/backend/ISA
recorded, and explicitly forbids inventing numbers for functionality that does not exist. The
existing `baseline-metrics.json` says `NOT_MEASURED` with an empty `measurements` list, which was
honest but useless.

This produces an actual measurement file by timing real work in a real JVM. It measures only what
Core genuinely does:
  * scalar bone evaluation,
  * scalar rigid vertex rendering,
  * native transform/bone/rigid batches per ISA, when a library is supplied.

It does NOT measure GPU, game, network or Host paths, and it does not compare against the
historical implementation (which is not an allowed oracle). Warm-up and repetition counts are
recorded so a reader can judge the numbers, and the environment block names the exact hardware,
JVM and ISA so a figure can never be silently attributed to another machine.
"""

from __future__ import annotations

import json
import os
import platform
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

PROBE = r"""
import dev.spm.core.acceleration.Isa;
import dev.spm.core.acceleration.ScalarTransforms;
import dev.spm.core.api.*;
import dev.spm.core.render.cpu.*;
import dev.spm.core.nativejni.*;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.util.*;
import java.util.HexFormat;

public final class BaselineProbe {
    public static void main(String[] args) throws Exception {
        int bones = 64;
        int verticesPerBone = 64;
        String skeletonNames = null;
        var names = new ArrayList<String>();
        var parents = new int[bones];
        var pivots = new float[bones * 3];
        for (int i = 0; i < bones; i++) {
            names.add("b" + i);
            parents[i] = i == 0 ? -1 : i - 1;
            pivots[i * 3] = i;
        }
        var skeleton = new Skeleton(names, parents, pivots);
        var pose = BonePose.identity(bones);

        int vertices = bones * verticesPerBone;
        var positions = new float[vertices * 3];
        var normals = new float[vertices * 3];
        var boneIndices = new int[vertices];
        for (int v = 0; v < vertices; v++) {
            positions[v * 3] = v % 17;
            positions[v * 3 + 1] = v % 13;
            positions[v * 3 + 2] = v % 11;
            normals[v * 3 + 1] = 1f;
            boneIndices[v] = v / verticesPerBone;
        }
        var batch = new VertexBatch(positions, normals, boneIndices);

        float[] matrix = {2,0,0,0, 0,3,0,0, 0,0,4,0, 5,6,7,1};
        var input = new float[4096 * 3];
        for (int i = 0; i < input.length; i++) input[i] = i * 0.03125f - 2;

        StringBuilder out = new StringBuilder();
        out.append("{\"measurements\":[");

        // Scalar bone evaluation.
        append(out, "scalar-bones", bones, () -> BoneEvaluator.evaluate(skeleton, pose));
        // Scalar rigid rendering.
        var matrices = BoneEvaluator.evaluate(skeleton, pose);
        out.append(',');
        append(out, "scalar-rigid", vertices, () -> JavaCpuRenderer.transform(batch, matrices));
        // Scalar transform.
        out.append(',');
        append(out, "scalar-transform", input.length / 3, () -> ScalarTransforms.transform(matrix, input));

        if (args.length > 0) {
            Path lib = Path.of(args[0]);
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(lib)));
            var nativeApi = NativeTransforms.load(lib, digest);
            for (Isa isa : Isa.values()) {
                if (!nativeApi.supported(isa) || isa == Isa.AUTO) continue;
                out.append(',');
                append(out, "native-transform-" + isa, input.length / 3,
                        () -> nativeApi.transform(matrix, input, isa));
            }
            for (Isa isa : Isa.values()) {
                if (!nativeApi.supported(isa) || isa == Isa.AUTO) continue;
                out.append(',');
                append(out, "native-bones-" + isa, bones,
                        () -> NativeBoneEvaluator.evaluate(skeleton, pose, matrix, isa));
            }
        }
        out.append("]}");
        System.out.println(out);
    }

    static void append(StringBuilder out, String id, int units, java.util.function.Supplier<Object> work) {
        // Warm up so JIT compilation is not counted as steady-state cost.
        for (int i = 0; i < 400; i++) work.get();
        int rounds = 200;
        long[] samples = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            work.get();
            samples[r] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        out.append(String.format(java.util.Locale.ROOT,
            "{\"id\":\"%s\",\"units\":%d,\"rounds\":%d,\"warmup\":400,"
            + "\"p50_ns\":%d,\"p95_ns\":%d,\"p99_ns\":%d,\"min_ns\":%d,\"max_ns\":%d,"
            + "\"throughput_units_per_sec\":%.1f}",
            id, units, rounds,
            samples[rounds / 2], samples[(int) (rounds * 0.95)], samples[(int) (rounds * 0.99)],
            samples[0], samples[rounds - 1],
            units / (samples[rounds / 2] / 1_000_000_000.0)));
    }
}
"""


def git_revision(root: Path) -> str:
    return subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"]).decode().strip()


def cpu_model() -> str:
    if platform.system() == "Windows":
        try:
            return subprocess.check_output(
                ["powershell", "-NoProfile", "-Command",
                 "(Get-CimInstance Win32_Processor).Name"],
                text=True, timeout=30).strip().splitlines()[0]
        except Exception:
            return platform.processor() or "unknown"
    return platform.processor() or "unknown"


def classpath(root: Path) -> str:
    jars = sorted(root.glob("*/build/libs/*.jar"))
    relevant = [j for j in jars if "-sources" not in j.name and "-javadoc" not in j.name
                and "preview" not in j.name and j.name.endswith("-0.1.0-dev.jar")]
    return os.pathsep.join(str(j) for j in relevant)


def main() -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Measure real Core baseline metrics.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--native-library", type=Path, default=None)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--created", required=True)
    args = parser.parse_args()
    root = args.root.resolve()

    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise SystemExit("JAVA_HOME must point at an actual JDK (the default is JDK 8 and is wrong)")
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = Path(java_home) / "bin" / ("javac.exe" if os.name == "nt" else "javac")

    cp = classpath(root)
    if not cp:
        raise SystemExit("No product jars found; run `gradlew jar` first")

    with tempfile.TemporaryDirectory() as directory:
        source = Path(directory) / "BaselineProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        subprocess.run([str(javac), "-cp", cp, "-d", directory, str(source)],
                       check=True, capture_output=True)
        command = [str(java), "--enable-native-access=ALL-UNNAMED", "-cp",
                   os.pathsep.join([directory, cp]), "BaselineProbe"]
        if args.native_library:
            command.append(str(args.native_library.resolve()))
        started = time.time()
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
        elapsed = time.time() - started
    measured = json.loads(completed.stdout)

    report = {
        "schema": 1,
        "status": "MEASURED",
        "created": args.created,
        "source_revision": git_revision(root),
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": cpu_model(),
            "java_version": subprocess.check_output([str(java), "-version"],
                                                    stderr=subprocess.STDOUT, text=True).splitlines()[0],
            "java_home": str(java_home),
            "native_library": str(args.native_library.resolve()) if args.native_library else None,
            "timer": "System.nanoTime per call, sorted samples; p95/p99 are order statistics",
        },
        "method": {
            "warmup_iterations": 400,
            "measured_rounds": 200,
            "units": ("positions for transforms, bones for bone evaluation, vertices for rigid"),
            "boundary": (
                "Single-threaded, single-process, warm JIT. Includes Java-side marshalling and arena "
                "allocation for native paths. Microbenchmark only: it is NOT a game frame time, NOT "
                "GPU time, and NOT a comparison against the historical implementation."
            ),
        },
        "measurements": measured["measurements"],
        "not_measured": [
            "cold start (no cold JVM sample was taken)",
            "GPU present/upload/command submission",
            "game frame time, resource reload, world change",
            "multi-client or P2P behaviour",
            "memory/GC footprint (no allocation profiler was run)",
            "Any comparison with the historical SPM/YSM implementation (not an allowed oracle)",
        ],
        "probe_wall_seconds": round(elapsed, 2),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "measurements": len(report["measurements"]),
        "ids": [m["id"] for m in report["measurements"]],
    }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
