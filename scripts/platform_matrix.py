"""Record the native platform matrix: what has actually executed, and what has not.

The plan's T11 requires a per-platform table (Windows x64, Linux x64, macOS x64/ARM64, Android
ARM64/OHOS, and a separate conclusion for old Windows x86) and explicitly forbids treating
cross-compilation as execution evidence.

This module owns that table as *data plus a guard*. A row may only be marked as executed when a real
candidate manifest from that platform is passed in; everything else stays NOT_EXECUTED no matter what
the source or the CI configuration says. That makes it impossible for a future author to upgrade a
row by editing a string.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

# The plan's required platform table. `target` is the Rust triple when one applies.
PLATFORMS = [
    dict(id='windows-x64', os='Windows', arch='x86_64', target='x86_64-pc-windows-msvc'),
    dict(id='linux-x64', os='Linux', arch='x86_64', target='x86_64-unknown-linux-gnu'),
    dict(id='macos-arm64', os='Darwin', arch='aarch64', target='aarch64-apple-darwin'),
    dict(id='macos-x64', os='Darwin', arch='x86_64', target='x86_64-apple-darwin'),
    dict(id='android-arm64', os='Android', arch='aarch64', target='aarch64-linux-android'),
    dict(id='ohos-arm64', os='OHOS', arch='aarch64', target='aarch64-unknown-linux-ohos'),
    # Deliberately separate: the plan requires an explicit conclusion, not silence.
    dict(id='windows-x86', os='Windows', arch='x86', target='i686-pc-windows-msvc'),
]

# Rows whose Java-only path still runs even where no native library is shipped. An x86 Windows host
# can execute the Java scalar path; it simply has no native library. That distinction is recorded
# per row rather than folded into one "supported" flag.
JAVA_ONLY_EXECUTABLE = {'windows-x86'}


def blank_row(platform: dict) -> dict:
    return dict(
        id=platform['id'],
        os=platform['os'],
        arch=platform['arch'],
        target=platform['target'],
        native_execution='NOT_EXECUTED',
        java_scalar_execution='NOT_EXECUTED',
        evidence=None,
        note='no candidate manifest supplied for this platform',
    )


def apply_manifest(rows: dict[str, dict], manifest: dict) -> None:
    """Promote a row only from a real candidate manifest, never from configuration."""
    if manifest.get('status') != 'VERIFIED_CANDIDATE_NOT_RELEASE_CERTIFIED':
        return
    os_name = str(manifest.get('os', ''))
    arch = str(manifest.get('architecture', '')).lower()
    native = manifest.get('native') or {}
    target = native.get('target')
    matched = None
    for row in rows.values():
        if row['os'] != os_name:
            continue
        if arch == row['arch'] or (arch in {'amd64', 'x86_64'} and row['arch'] == 'x86_64') \
                or (arch in {'aarch64', 'arm64'} and row['arch'] == 'aarch64'):
            if target is None or target == row['target']:
                matched = row
                break
    if matched is None:
        return
    checkout = manifest.get('checkout')
    if target:
        matched['native_execution'] = 'EXECUTED'
    matched['java_scalar_execution'] = 'EXECUTED'
    matched['evidence'] = dict(
        checkout=checkout,
        runId=manifest.get('runId'),
        runAttempt=manifest.get('runAttempt'),
        java=manifest.get('java'),
        native_target=target,
    )
    matched['note'] = ('verified by an actual candidate run on this host'
                       if target else 'verified Java-only; no native library for this platform')


def matrix(manifests: list[dict]) -> dict:
    rows = {platform['id']: blank_row(platform) for platform in PLATFORMS}
    for manifest in manifests:
        apply_manifest(rows, manifest)
    for identity in JAVA_ONLY_EXECUTABLE:
        if identity in rows and rows[identity]['native_execution'] == 'NOT_EXECUTED':
            rows[identity]['note'] = (
                'no native library is shipped for 32-bit Windows; the Java scalar path remains the '
                'only executable option and must be verified separately on that host'
            )
    executed = sorted(name for name, row in rows.items() if row['native_execution'] == 'EXECUTED')
    missing = sorted(name for name, row in rows.items() if row['native_execution'] != 'EXECUTED')
    return dict(
        schema=1,
        status='PARTIAL' if missing else 'COMPLETE',
        native_executed=executed,
        native_not_executed=missing,
        policy=('A row is EXECUTED only when a real candidate manifest from that platform is'
                ' supplied. Cross-compilation, source inspection and CI configuration do not'
                ' count and cannot promote a row.'),
        platforms=[rows[key] for key in [p['id'] for p in PLATFORMS]],
    )


def main() -> int:
    parser = argparse.ArgumentParser(description='Record the native platform coverage matrix.')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, action='append', default=[],
                        help='A real candidate.json from an executed run')
    args = parser.parse_args()
    manifests = []
    for path in args.manifest:
        if not path.is_file():
            raise SystemExit(f'Candidate manifest missing: {path}')
        manifests.append(json.loads(path.read_text(encoding='utf-8')))
    result = matrix(manifests)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'status': result['status'], 'executed': result['native_executed'],
                      'not_executed': result['native_not_executed']}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
