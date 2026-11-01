"""Regressions for the native platform coverage matrix.

The whole point of this module is that a row cannot be promoted without real execution evidence, so
most of these tests assert that nothing gets promoted.
"""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from platform_matrix import PLATFORMS, apply_manifest, blank_row, matrix


def manifest(os_name: str, arch: str, target: str | None, checkout: str = 'a' * 40,
             status: str = 'VERIFIED_CANDIDATE_NOT_RELEASE_CERTIFIED') -> dict:
    return dict(status=status, os=os_name, architecture=arch, checkout=checkout,
                runId='123', runAttempt='1', java={'compiler': '25', 'test': '25'},
                native=dict(target=target) if target else None)


class PlatformMatrixTest(unittest.TestCase):
    def test_every_plan_required_platform_has_a_row(self) -> None:
        identities = {p['id'] for p in PLATFORMS}
        for required in ['windows-x64', 'linux-x64', 'macos-arm64', 'macos-x64',
                         'android-arm64', 'ohos-arm64', 'windows-x86']:
            self.assertIn(required, identities)

    def test_without_evidence_every_row_stays_not_executed(self) -> None:
        result = matrix([])
        self.assertEqual('PARTIAL', result['status'])
        for row in result['platforms']:
            self.assertEqual('NOT_EXECUTED', row['native_execution'])
            self.assertEqual('NOT_EXECUTED', row['java_scalar_execution'])
            self.assertIsNone(row['evidence'])

    def test_a_real_native_manifest_promotes_exactly_one_row(self) -> None:
        result = matrix([manifest('Windows', 'AMD64', 'x86_64-pc-windows-msvc')])
        executed = [r['id'] for r in result['platforms'] if r['native_execution'] == 'EXECUTED']
        self.assertEqual(['windows-x64'], executed)
        self.assertEqual('a' * 40, result['platforms'][0]['evidence']['checkout'])

    def test_a_java_only_manifest_does_not_claim_native_execution(self) -> None:
        result = matrix([manifest('Linux', 'x86_64', None)])
        row = next(r for r in result['platforms'] if r['id'] == 'linux-x64')
        self.assertEqual('NOT_EXECUTED', row['native_execution'])
        self.assertEqual('EXECUTED', row['java_scalar_execution'])

    def test_an_unverified_manifest_cannot_promote_anything(self) -> None:
        result = matrix([manifest('Darwin', 'arm64', 'aarch64-apple-darwin', status='FAILED')])
        for row in result['platforms']:
            self.assertEqual('NOT_EXECUTED', row['native_execution'])

    def test_arm64_and_x86_64_macos_rows_are_not_confused(self) -> None:
        result = matrix([manifest('Darwin', 'arm64', 'aarch64-apple-darwin')])
        executed = {r['id'] for r in result['platforms'] if r['native_execution'] == 'EXECUTED'}
        self.assertEqual({'macos-arm64'}, executed)
        self.assertNotIn('macos-x64', executed)

    def test_a_target_that_does_not_match_the_row_is_ignored(self) -> None:
        # A Linux host reporting a macOS target must not promote either row.
        result = matrix([manifest('Linux', 'x86_64', 'aarch64-apple-darwin')])
        for row in result['platforms']:
            self.assertEqual('NOT_EXECUTED', row['native_execution'])

    def test_windows_x86_row_records_an_explicit_conclusion(self) -> None:
        result = matrix([])
        row = next(r for r in result['platforms'] if r['id'] == 'windows-x86')
        self.assertEqual('NOT_EXECUTED', row['native_execution'])
        self.assertIn('Java scalar', row['note'],
                      'the plan requires an explicit conclusion for 32-bit Windows, not silence')

    def test_all_rows_executed_yields_complete(self) -> None:
        manifests = [
            manifest('Windows', 'AMD64', 'x86_64-pc-windows-msvc'),
            manifest('Linux', 'x86_64', 'x86_64-unknown-linux-gnu'),
            manifest('Darwin', 'aarch64', 'aarch64-apple-darwin'),
            manifest('Darwin', 'x86_64', 'x86_64-apple-darwin'),
            manifest('Android', 'aarch64', 'aarch64-linux-android'),
            manifest('OHOS', 'aarch64', 'aarch64-unknown-linux-ohos'),
            manifest('Windows', 'x86', 'i686-pc-windows-msvc'),
        ]
        result = matrix(manifests)
        self.assertEqual('COMPLETE', result['status'])
        self.assertEqual([], result['native_not_executed'])

    def test_the_policy_text_states_cross_compilation_does_not_count(self) -> None:
        result = matrix([])
        self.assertIn('Cross-compilation', result['policy'])
        self.assertIn('cannot promote', result['policy'])

    def test_apply_manifest_is_idempotent(self) -> None:
        rows = {p['id']: blank_row(p) for p in PLATFORMS}
        item = manifest('Windows', 'AMD64', 'x86_64-pc-windows-msvc')
        apply_manifest(rows, item)
        first = json.dumps(rows['windows-x64'], sort_keys=True)
        apply_manifest(rows, item)
        self.assertEqual(first, json.dumps(rows['windows-x64'], sort_keys=True))

    def test_cli_refuses_a_missing_manifest(self) -> None:
        import platform_matrix
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / 'nope.json'
            import sys
            saved = sys.argv
            try:
                sys.argv = ['platform_matrix.py', '--output', str(Path(directory) / 'out.json'),
                            '--manifest', str(missing)]
                with self.assertRaises(SystemExit):
                    platform_matrix.main()
            finally:
                sys.argv = saved


if __name__ == '__main__':
    unittest.main()
