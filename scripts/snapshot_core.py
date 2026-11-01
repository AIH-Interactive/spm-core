"""Create reproducible T00/T01 evidence inventories. Coverage is not origin certification."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import stat
import tomllib
import xml.etree.ElementTree as ET

IGNORED = {'.git', '.gradle', 'build', 'target', '.idea', '__pycache__'}
OHMY = 'd2536542747a1aa5c02594827f579171c525843c'
BRANCHES = [('Fa1.21.1', 'main'), ('Fa26.1.2', 'fa26.1.2'), ('Fa26.2', 'fa26.2'),
            ('Fa26.3', 'fa26.3'), ('Neo1.21.1', 'neo1.21.1'), ('Neo26.1.2', 'neo26.1.2'),
            ('Neo26.2', 'neo26.2'), ('Neo26.3', 'neo26.3')]


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inputs(root: Path):
    files = []
    def fail(error):
        raise error
    for directory, dirs, names in os.walk(root, followlinks=False, onerror=fail):
        dirs[:] = [name for name in dirs if name not in IGNORED]
        for name in dirs + names:
            path = Path(directory) / name
            info = path.lstat()
            if path.is_symlink() or getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                raise ValueError('Unsupported linked/reparse product input: ' + str(path))
        files.extend(Path(directory) / name for name in names if not name.endswith('.pyc'))
    return sorted(files)


def build_input_snapshot(root: Path) -> dict:
    return {p.relative_to(root).as_posix(): sha(p.read_bytes()) for p in inputs(root)
            if p.suffix in {'.gradle', '.kts'} or p.name in {'gradle.properties', 'gradle.lockfile', 'gradlew', 'gradlew.bat'}
            or p.relative_to(root).parts[0] == 'gradle'}


def validate_dependency_inventory(root: Path, inventories: list[dict]) -> list[str]:
    errors = []
    expected = sorted(p.parent.name for p in root.glob('*/build.gradle'))
    observed = sorted(x.get('project', '') for x in inventories)
    if expected != observed:
        errors.append('dependency module coverage mismatch')
    snapshot = build_input_snapshot(root)
    for inventory in inventories:
        if inventory.get('buildInputs') != snapshot:
            errors.append('stale or unattested dependency inventory: ' + inventory.get('project', '<missing>'))
    return errors


def source_inventory(root: Path) -> list[dict]:
    rows = []
    for path in inputs(root):
        rel = path.relative_to(root).as_posix()
        kind = ('fixture' if '/src/test/resources/' in rel else
                'test' if '/src/test/' in rel or path.name.startswith('test_') else
                'source' if path.suffix in {'.java', '.rs', '.h'} else 'build-or-resource')
        rows.append(dict(path=rel, sha256=sha(path.read_bytes()), bytes=path.stat().st_size,
                         category=kind, declared_origin='candidate; see allowed-sources.json and component reports',
                         review_status='Q', evidence='origin-attestations.json'))
    return rows


def verify_snapshot(root: Path, evidence: Path) -> list[str]:
    with (evidence / 'origin-ledger.csv').open(encoding='utf-8', newline='') as source:
        rows = list(csv.DictReader(source))
    errors = []
    expected = {r['path']: r['sha256'] for r in rows}
    actual = {r['path']: r['sha256'] for r in source_inventory(root)}
    if len(expected) != len(rows):
        errors.append('duplicate origin path')
    for path in sorted(expected.keys() - actual.keys()):
        errors.append('missing input: ' + path)
    for path in sorted(actual.keys() - expected.keys()):
        errors.append('unrecorded input: ' + path)
    for path in sorted(actual.keys() & expected.keys()):
        if expected[path] != actual[path]:
            errors.append('changed input: ' + path)
    fixtures = json.loads((evidence / 'fixtures.json').read_text(encoding='utf-8'))
    for fixture in fixtures['fixtures']:
        if actual.get(fixture['path']) != fixture['sha256']:
            errors.append('fixture digest mismatch: ' + fixture['path'])
    return errors


def git(root: Path, *args: str, binary=False):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True, check=True)
    return result.stdout if binary else result.stdout.decode('utf-8').strip()


def write_json(output: Path, name: str, content):
    (output / name).write_text(json.dumps(content, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def write_csv(output: Path, name: str, rows: list[dict]):
    with (output / name).open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)


def capture(root: Path, output: Path, workspace: Path):
    if output == root or root in output.parents:
        raise ValueError('Evidence must be outside the product tree to avoid self-referential digests')
    output.mkdir(parents=True, exist_ok=True)
    rows = source_inventory(root)
    write_csv(output, 'origin-ledger.csv', rows)
    allow = json.loads((root / 'provenance/allowed-sources.json').read_text(encoding='utf-8'))
    write_json(output, 'origin-attestations.json', dict(schema=1, status='INCOMPLETE', references=allow['references'],
        scope='All current Core input paths and exact bytes inventoried; not a certification of algorithm origin.',
        policy='No excluded implementation, binary, expected-output generator or behavior oracle was used.',
        open_items=['Per-file/fragment human source review remains Q; no automatic allowlist promotion.',
                    'Old SPM source certification, rename/token/AST similarity review and full dependency license review remain open.'],
        ledger_sha256=sha((output / 'origin-ledger.csv').read_bytes()), entries=len(rows)))

    fixture_rows = []
    reference = workspace / '.sources/OhMyYesSteveModel-reference'
    reference_pinned = reference.exists() and git(reference, 'rev-parse', 'HEAD') == OHMY
    for path in sorted((root / 'verification/src/test/resources/ysm').rglob('*')):
        if path.suffix not in {'.wire', '.ysm'}:
            continue
        suffix = 'historical/' + path.name if path.suffix == '.wire' else path.name
        source_path = 'runtime/java-v3/src/test/resources/' + suffix
        matches = reference_pinned and git(reference, 'show', OHMY + ':' + source_path, binary=True) == path.read_bytes()
        fixture_rows.append(dict(path=path.relative_to(root).as_posix(), sha256=sha(path.read_bytes()),
            bytes=path.stat().st_size, repository=allow['references'][1]['repository'], commit=OHMY,
            source_path=source_path, exact_upstream_bytes=bool(matches), license='Apache-2.0 (upstream synthetic test data)',
            purpose='synthetic wire/envelope; not artwork or real-model/GPU compatibility',
            replay_test='YsmHistoricalTest/HistoricalExpansionLimitTest' if path.suffix == '.wire' else 'YsmEnvelopeTest'))
    write_json(output, 'fixtures.json', dict(schema=1, fixtures=fixture_rows, status='PINNED_SYNTHETIC_ONLY',
        exclusions=['No real artwork redistributed', 'No OpenYSM oracle', '54-model legacy corpus not licensed or replayed here']))

    inventories = []
    for path in sorted(root.glob('*/build/metadata/dependencies.json')):
        inventories.append(json.loads(path.read_text(encoding='utf-8')))
    cargo = tomllib.loads((root / 'native-rust/Cargo.lock').read_text(encoding='utf-8'))
    locks = [dict(path=p.relative_to(root).as_posix(), sha256=sha(p.read_bytes()))
             for p in inputs(root) if p.name in {'gradle.lockfile', 'Cargo.lock', 'verification-metadata.xml', 'rust-toolchain.toml'}]
    expected_modules = sorted(p.parent.name for p in root.glob('*/build.gradle'))
    observed_modules = sorted(x['project'] for x in inventories)
    dependency_errors = validate_dependency_inventory(root, inventories)
    write_json(output, 'dependency-closure.json', dict(schema=1,
        status='LOCAL_RESOLVED_INVENTORY' if not dependency_errors else 'INCOMPLETE',
        errors=dependency_errors,
        expected_modules=expected_modules, observed_modules=observed_modules, gradle=inventories,
        cargo_lock_packages=cargo['package'], locked_inputs=locks,
        caveats=['Cargo registry checksum describes the package archive; not a built-library digest.',
                 'Target-specific dependencies are listed, not asserted to execute on this host.',
                 'Gradle wrapper distribution is pinned separately; clean anonymous release resolution is unverified.',
                 'Full transitive licensing and SBOM release attestation remain pending.']))

    branch_rows = []
    for suffix, expected in BRANCHES:
        repo = workspace / ('Sparkle-Morpher-' + suffix)
        files = git(repo, 'ls-files').splitlines()
        descriptors = [p for p in files if p.endswith(('.gradle', '.gradle.kts', 'gradle.properties'))]
        branch_rows.append(dict(directory=repo.name, expected_branch=expected, branch=git(repo, 'branch', '--show-current'),
            head=git(repo, 'rev-parse', 'HEAD'), dirty=bool(git(repo, 'status', '--porcelain=v1')),
            core_consumption='NOT_INTEGRATED', task_resolution='NOT_RUN',
            build_inputs=[dict(path=p, sha256=sha((repo / p).read_bytes())) for p in descriptors if (repo / p).is_file()],
            native_and_variant_paths=[p for p in files if any(x in p.lower() for x in ['caustica', '/native', 'distribution.gradle', 'source-set'])]))
    write_json(output, 'branch-task-map.json', dict(schema=1, status='HEAD_AND_INPUT_SNAPSHOT_ONLY', branches=branch_rows,
        pending=['Resolve exact tasks/sourceSets and distribution/native closures per branch without using old algorithms as an oracle.']))

    features = []
    for kernel in ['position_transform', 'hierarchical_bones', 'rigid_vertices_normals_color_visibility',
                   'BDEF', 'SDEF', 'QDEF', 'morph', 'host_gpu_packing']:
        for isa in ['SCALAR', 'SSE2', 'SSE4.1', 'AVX', 'AVX2', 'AVX2_FMA', 'AVX512', 'NEON']:
            implemented = kernel in ['position_transform', 'hierarchical_bones', 'rigid_vertices_normals_color_visibility'] and isa not in ['SSE4.1', 'AVX512']
            implementation = 'PRESENT' if implemented else 'MISSING'
            if implemented and isa == 'AVX2' and kernel in ['position_transform', 'hierarchical_bones']:
                implementation = 'USES_AVX_KERNEL; NO_ADDITIONAL_AVX2_INSTRUCTIONS'
            features.append(dict(kernel=kernel, isa=isa, implementation=implementation,
                execution='SEE_RAW_TEST_LOGS' if implemented and isa != 'NEON' else 'NOT_EXECUTED',
                performance='NOT_MEASURED', scope='kernel presence is not instruction coverage or production default acceptance'))
    write_csv(output, 'native-feature-matrix.csv', features)
    formats = [('YSM V1/V2', 'archive read/write', 'YsmArchiveTest'), ('YSM V3 envelope', 'read/write', 'YsmEnvelopeTest'),
               ('YSM payload 1-32', 'archival read/write; limited runtime projection', 'YsmHistoricalTest'),
               ('V3D', 'bounded logical package capture/restore; not filesystem cache', 'V3dArchiveTest'),
               ('Bedrock', 'single geometry cubes; TRS animation subset', 'BedrockImportTest/AnimationPoseTest'),
               ('BBModel', 'cube geometry; metadata archival', 'BbmodelImportTest'),
               ('glTF/VRM/MMD/Figura', 'UNIMPLEMENTED', '')]
    write_csv(output, 'format-matrix.csv', [dict(format=f, scope=s, tests=t, host_game='NOT_VERIFIED') for f, s, t in formats])
    behavior = [('immutable IR', 'DocumentImmutabilityTest'), ('shared budget', 'SharedBudgetPropagationTest'),
                ('retained assets', 'RetainedAssetBudgetTest/RuntimeGenerationLeaseTest'),
                ('historical expansion', 'HistoricalExpansionLimitTest'), ('transactional scene', 'SceneRuntimeTest'),
                ('worker script budgets', 'ScriptBudgetConcurrencyTest'), ('logical layout', 'LayoutDescriptorTest'),
                ('Shader/OpenGL/Vulkan/GUI/Cloud/P2P', '')]
    write_csv(output, 'host-behavior-matrix.csv', [dict(behavior=b, core_tests=t, host_execution='NOT_VERIFIED') for b, t in behavior])
    write_json(output, 'baseline-metrics.json', dict(schema=1, status='NOT_MEASURED', environment=dict(
        platform=platform.platform(), machine=platform.machine(), processor=platform.processor()),
        measurements=[], required_metrics=['cold/hot load', 'p50/p95/p99', 'throughput', 'memory/GC', 'JNI/copy/upload/queue'],
        excluded_evidence='Existing baseline.json is a source audit, not a timing or stability benchmark.',
        policy='Never sample the excluded implementation as a behavior oracle.'))
    suites = []
    for path in sorted(root.glob('*/build/test-results/test/TEST-*.xml')):
        suite = ET.parse(path).getroot()
        suites.append(dict(path=path.relative_to(root).as_posix(), sha256=sha(path.read_bytes()),
            name=suite.attrib['name'], tests=int(suite.attrib['tests']), failures=int(suite.attrib['failures']),
            errors=int(suite.attrib['errors']), skipped=int(suite.attrib.get('skipped', '0'))))
    write_json(output, 'stability-baseline.json', dict(schema=1, status='UNIT_AND_INTEGRATION_SNAPSHOT_ONLY', suites=suites,
        not_executed=['long-running stress', 'GPU pixels', 'game smoke tests', 'non-Windows execution', 'ARM64/NEON execution', 'multi-client reload/disconnect']))
    write_csv(output, 'implementation-decisions.csv', [
        dict(component='explicit SIMD', decision='Rust only', basis='user requirement', evidence='native-feature-matrix.csv', acceptance='partial; no performance acceptance'),
        dict(component='native-free compute', decision='Java scalar mandatory', basis='Java17 compatibility and fallback', evidence='Java test logs', acceptance='declared rigid subset only'),
        dict(component='codec/JSON', decision='Java with locked reviewed dependencies', basis='existing independent candidate; no comparative measurement', evidence='dependency-closure.json', acceptance='performance decision provisional')])
    write_csv(output, 'extension-bindings.csv', [dict(extension=x, kind=k, state=s, evidence=e) for x, k, s, e in [
        ('query/variable/math', 'numeric computation', 'SUBSET', 'MolangRuntimeTest'),
        ('effect command deduplication', 'Host effect', 'CORE_ONLY', 'SceneRuntimeTest'),
        ('ctrl/ysm/fn/tlm', 'Host extensions', 'NOT_COMPLETE', 'no acceptance evidence'),
        ('Cloud/Peer identity or filesystem access', 'Host policy', 'OUTSIDE_CORE', 'explicit resolver/budget required')]])
    return dict(inputs=len(rows), fixtures=len(fixture_rows), fixtures_matching=sum(f['exact_upstream_bytes'] for f in fixture_rows),
                dependency_modules=len(inventories), branches=len(branch_rows), errors=verify_snapshot(root, output)+dependency_errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--workspace', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    root, output = args.root.resolve(), args.output.resolve()
    result = dict(errors=verify_snapshot(root, output)) if args.check else capture(root, output, args.workspace.resolve())
    print(json.dumps(result, ensure_ascii=False))
    return 1 if result['errors'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
