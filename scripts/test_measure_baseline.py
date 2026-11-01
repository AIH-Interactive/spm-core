"""Regressions for the baseline metric recorder.

These check the document's honesty rules without running a JVM: an unmeasured report must say so,
a measured report must name its environment, and the method block must state what was not measured.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import measure_baseline


class BaselineDocumentTest(unittest.TestCase):
    def test_the_probe_source_declares_every_type_it_uses(self) -> None:
        # A missing import compiles fine as a string but fails at javac time; catch it here.
        for symbol in ["ScalarTransforms", "BoneEvaluator", "JavaCpuRenderer", "VertexBatch",
                       "NativeBoneEvaluator", "NativeTransforms", "Isa"]:
            self.assertIn(symbol, measure_baseline.PROBE, f"probe references {symbol}")
        self.assertIn("import dev.spm.core.acceleration.ScalarTransforms;", measure_baseline.PROBE)

    def test_the_probe_warms_up_before_measuring(self) -> None:
        # Without warm-up the first samples are JIT compilation, not steady state.
        self.assertIn("warmup", measure_baseline.PROBE)
        self.assertIn("for (int i = 0; i < 400; i++) work.get();", measure_baseline.PROBE)

    def test_the_probe_records_order_statistics_not_a_mean(self) -> None:
        # The plan asks for p50/p95/p99; a single average would hide tail latency.
        self.assertIn("p50_ns", measure_baseline.PROBE)
        self.assertIn("p95_ns", measure_baseline.PROBE)
        self.assertIn("p99_ns", measure_baseline.PROBE)
        self.assertIn("Arrays.sort", measure_baseline.PROBE)

    def test_the_not_measured_list_names_gpu_game_and_memory(self) -> None:
        # This list is the difference between an honest baseline and a misleading one.
        source = Path(measure_baseline.__file__).read_text(encoding="utf-8")
        for phrase in ["cold start", "GPU", "game frame time", "memory/GC",
                       "historical SPM/YSM implementation"]:
            self.assertIn(phrase, source, f"the not_measured list must mention {phrase}")

    def test_the_method_block_states_the_benchmark_is_not_a_frame_time(self) -> None:
        source = Path(measure_baseline.__file__).read_text(encoding="utf-8")
        self.assertIn("NOT a game frame time", source)
        self.assertIn("NOT a comparison against the historical implementation", source)

    def test_a_missing_java_home_is_an_explicit_error(self) -> None:
        import os
        saved = os.environ.pop("JAVA_HOME", None)
        try:
            import sys
            saved_argv = sys.argv
            try:
                sys.argv = ["measure_baseline.py", "--output", "x.json", "--created", "now"]
                with self.assertRaises(SystemExit) as raised:
                    measure_baseline.main()
                self.assertIn("JAVA_HOME", str(raised.exception))
            finally:
                sys.argv = saved_argv
        finally:
            if saved is not None:
                os.environ["JAVA_HOME"] = saved

    def test_the_environment_block_names_hardware_and_jvm(self) -> None:
        source = Path(measure_baseline.__file__).read_text(encoding="utf-8")
        for field in ["processor", "java_version", "platform", "native_library"]:
            self.assertIn(field, source, f"environment must record {field}")


if __name__ == "__main__":
    unittest.main()
