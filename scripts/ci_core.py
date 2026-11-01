"""Build and inspect one exact-checkout CI candidate; never publish or certify upstream origin."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

from snapshot_core import validate_dependency_inventory

RUST_VERSION = '1.98.1'
NATIVE_TARGETS = {
    'x86_64-unknown-linux-gnu': ('Linux', {'x86_64', 'amd64'}, 'libspm_core_native.so'),
    'x86_64-pc-windows-msvc': ('Windows', {'x86_64', 'amd64'}, 'spm_core_native.dll'),
    'aarch64-apple-darwin': ('Darwin', {'aarch64', 'arm64'}, 'libspm_core_native.dylib'),
}


def validate_checkout(expected: str, actual: str, dirty: str) -> None:
    if not re.fullmatch(r'[0-9a-f]{40}', expected) or actual != expected:
        raise ValueError('Exact checkout SHA mismatch or invalid expected SHA')
    if dirty.strip():
        raise ValueError('Candidate source checkout is dirty: ' + dirty)


def candidate_inputs(root: Path) -> list[Path]:
    """Require every configured module's current inventory and every product's publication files."""
    files, inventories = [], []
    modules = sorted(path.parent for path in root.glob('*/build.gradle'))
    if not modules:
        raise ValueError('No product modules found')
    for module in modules:
        if module.name not in {'verification', 'integration-tests'}:
            jars = sorted((module / 'build/libs').glob('*.jar'))
            groups = [
                [path for path in jars if not path.name.endswith(('-sources.jar', '-javadoc.jar'))],
                [path for path in jars if path.name.endswith('-sources.jar')],
                [path for path in jars if path.name.endswith('-javadoc.jar')],
            ]
            if any(len(group) != 1 for group in groups):
                raise ValueError(module.name + ': exactly one binary, source and javadoc jar required')
            files.extend(jars)
            pom = module / 'build/publications/mavenJava/pom-default.xml'
            if not pom.is_file():
                raise ValueError(module.name + ': publication POM missing')
            files.append(pom)
        metadata = module / 'build/metadata/dependencies.json'
        if not metadata.is_file():
            raise ValueError(module.name + ': dependency inventory metadata missing')
        inventories.append(json.loads(metadata.read_text(encoding='utf-8')))
        files.append(metadata)
    errors = validate_dependency_inventory(root, inventories)
    if errors:
        raise ValueError('; '.join(errors))
    return files


def validate_java_evidence(root: Path, log: str, test_java: int, native: bool) -> dict:
    compiles, launchers = [], {}
    for line in log.splitlines():
        if line.startswith('SPM_CI_COMPILE='):
            compiles.append(json.loads(line.removeprefix('SPM_CI_COMPILE=')))
        elif line.startswith('SPM_CI_TEST='):
            entry = json.loads(line.removeprefix('SPM_CI_TEST='))
            launchers[entry['module']] = entry['java']
    if not compiles or any(record['java'] != 25 or record['release'] != 17
                           or not record['werror'] or not record['xlint'] for record in compiles):
        raise ValueError('Missing actual JDK25/release17 compiler evidence or disabled warning gate')
    expected_compiles = {(source.relative_to(root).parts[0], 'compileJava')
                         for source in root.glob('*/src/main/java/**/*.java')}
    expected_compiles |= {(source.relative_to(root).parts[0], 'compileTestJava')
                          for source in root.glob('*/src/test/java/**/*.java')}
    if not expected_compiles <= {(record['module'], record['task']) for record in compiles}:
        raise ValueError('Missing actual compiler execution for a source module')
    expected = {}
    for source in root.glob('*/src/test/java/**/*Test.java'):
        package = re.search(r'^package\s+([\w.]+)\s*;', source.read_text(encoding='utf-8-sig'), re.MULTILINE)
        if not package:
            raise ValueError('Test source has no package: ' + source.relative_to(root).as_posix())
        expected[package[1] + '.' + source.stem] = source.relative_to(root).parts[0]
    suites, passed, seen = [], 0, set()
    for path in sorted(root.glob('*/build/test-results/test/TEST-*.xml')):
        suite = ET.parse(path).getroot()
        name = suite.attrib['name']
        module = path.relative_to(root).parts[0]
        cases = list(suite.findall('testcase'))
        count, skipped = int(suite.attrib['tests']), int(suite.attrib.get('skipped', 0))
        if count != len(cases) or count <= 0 or skipped != sum(case.find('skipped') is not None for case in cases):
            raise ValueError('Incomplete test report: ' + name)
        if int(suite.attrib.get('failures', 0)) or int(suite.attrib.get('errors', 0)) \
                or any(case.find('failure') is not None or case.find('error') is not None for case in cases):
            raise ValueError('Failing test report: ' + name)
        if launchers.get(module) != test_java:
            raise ValueError('Missing or wrong actual test JVM launcher: ' + module)
        if native and module == 'integration-tests' and skipped:
            raise ValueError('Native integration test was skipped: ' + name)
        passed += count - skipped
        seen.add(name)
        suites.append(dict(module=module, name=name, tests=count, skipped=skipped))
    if not expected or expected.keys() - seen:
        raise ValueError('Expected test report missing: ' + ', '.join(sorted(expected.keys() - seen)))
    if passed == 0:
        raise ValueError('No actual passing test execution')
    if native and not any(name.endswith('.NativeComputeProviderTest') for name in seen):
        raise ValueError('Native CPU provider test report missing')
    return dict(testJava=test_java, compilers=compiles, suites=suites, passed=passed)


# These markers are emitted only when the real compile/test task executes. They are not a
# replacement for the XML reports, and no task-output/configuration cache is used in this job.
INIT_SCRIPT = '''
allprojects {
    tasks.withType(JavaCompile).configureEach {
        doFirst {
            def record = [module: project.name, task: name,
                java: javaCompiler.get().metadata.languageVersion.asInt(), release: options.release.get(),
                werror: options.compilerArgs.contains('-Werror'), xlint: options.compilerArgs.contains('-Xlint:all')]
            println('SPM_CI_COMPILE=' + groovy.json.JsonOutput.toJson(record))
            if (record.java != 25 || record.release != 17 || !record.werror || !record.xlint)
                throw new GradleException('Core compiler/release/warning gate changed')
        }
    }
    tasks.withType(Test).configureEach {
        doFirst {
            println('SPM_CI_TEST=' + groovy.json.JsonOutput.toJson([
                module: project.name, java: javaLauncher.get().metadata.languageVersion.asInt()]))
        }
    }
}
'''


def run(root: Path, output: Path, name: str, args: list[str], env: dict | None = None) -> str:
    print('+ ' + json.dumps(args), flush=True)
    with (output / 'logs' / (name + '.log')).open('w', encoding='utf-8') as log:
        log.write('COMMAND ' + json.dumps(args) + '\n')
        log.flush()
        process = subprocess.Popen(args, cwd=root, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace')
        lines = []
        assert process.stdout is not None
        for line in process.stdout:
            lines.append(line)
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
        code = process.wait()
        log.write('\nEXIT_CODE ' + str(code) + '\n')
    if code:
        raise RuntimeError(name + ' failed with exit code ' + str(code))
    return ''.join(lines)


def checkout(root: Path, expected: str) -> str:
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    dirty = subprocess.check_output(['git', 'status', '--porcelain=v1', '--untracked-files=all'], cwd=root, text=True)
    validate_checkout(expected, actual, dirty)
    return actual


def copy_file(root: Path, destination: Path, path: Path) -> None:
    path.resolve().relative_to(root.resolve())
    if path.is_symlink():
        raise ValueError('Linked candidate input: ' + str(path))
    target = destination / path.relative_to(root)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)


def diagnostics(root: Path, output: Path, artifact: Path) -> None:
    audit = output / 'core-audit.json'
    if audit.is_file():
        shutil.copyfile(audit, artifact / 'core-audit.json')
    for log in (output / 'logs').glob('*.log'):
        target = artifact / 'logs' / log.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(log, target)
    for pattern in ['*/build/test-results/test/TEST-*.xml', '*/build/reports/tests/test/**/*']:
        for path in root.glob(pattern):
            if path.is_file():
                copy_file(root, artifact, path)


def build(args: argparse.Namespace) -> None:
    root, output = args.root.resolve(), args.output.resolve()
    output.relative_to(root / '.gradle')  # keep root/subproject clean tasks from deleting live logs
    output.mkdir(parents=True, exist_ok=True)
    (output / 'logs').mkdir(exist_ok=True)
    artifact = output / 'artifact'
    artifact.mkdir()  # refuse a stale candidate directory instead of mixing attempts
    status = dict(schema=1, status='FAILED', expectedCheckout=args.expected_sha,
                  sourceCertification='Q', licenseClosureCertification='Q')
    try:
        revision = checkout(root, args.expected_sha)
        run(root, output, 'python-tests', [sys.executable, '-m', 'unittest', 'discover', '-s', 'scripts', '-p', 'test_*.py'])
        compiler = Path(os.environ['JAVA_HOME'])
        test_home = Path(os.environ['SPM_TEST_JAVA_HOME'])
        suffix = '.exe' if os.name == 'nt' else ''
        run(root, output, 'compiler-java', [str(compiler / 'bin' / ('java' + suffix)), '-version'])
        run(root, output, 'test-java', [str(test_home / 'bin' / ('java' + suffix)), '-version'])
        init = output / 'evidence.init.gradle'
        init.write_text(INIT_SCRIPT, encoding='utf-8')
        native, native_record = None, None
        if args.native_target:
            system, machines, library = NATIVE_TARGETS[args.native_target]
            if platform.system() != system or platform.machine().lower() not in machines:
                raise ValueError('Native target is not this execution host')
            toolchain = tomllib.loads((root / 'native-rust/rust-toolchain.toml').read_text(encoding='utf-8'))
            if toolchain['toolchain']['channel'] != RUST_VERSION:
                raise ValueError('Rust toolchain pin changed')
            flags = os.environ.get('RUSTFLAGS', '') + ' ' + os.environ.get('CARGO_ENCODED_RUSTFLAGS', '')
            if re.search(r'target-cpu(?:=|\s+)native', flags.replace('\x1f', ' ')):
                raise ValueError('Development-machine native CPU target is forbidden')
            run(root, output, 'rust-install', ['rustup', 'toolchain', 'install', RUST_VERSION, '--profile', 'minimal',
                                             '--component', 'rustfmt', '--component', 'clippy', '--target', args.native_target])
            version = run(root, output, 'rust-version', ['rustc', '+' + RUST_VERSION, '-vV'])
            if 'host: ' + args.native_target not in version:
                raise ValueError('Rust host does not match the executed native target')
            cargo = ['cargo', '+' + RUST_VERSION]
            manifest = ['--manifest-path', str(root / 'native-rust/Cargo.toml')]
            target = ['--target', args.native_target]
            native_env = dict(os.environ, CARGO_TARGET_DIR=str(root / 'native-rust/target'))
            run(root, output, 'rust-clean', cargo + ['clean'] + manifest, native_env)
            run(root, output, 'rust-fmt', cargo + ['fmt'] + manifest + ['--all', '--check'], native_env)
            run(root, output, 'rust-clippy', cargo + ['clippy'] + manifest + ['--workspace', '--all-targets', '--locked']
                + target + ['--', '-D', 'warnings'], native_env)
            rust_tests = run(root, output, 'rust-tests', cargo + ['test'] + manifest + ['--workspace', '--locked'] + target, native_env)
            if not re.search(r'test result: ok\. [1-9]\d* passed', rust_tests):
                raise ValueError('No actual passing Rust test execution')
            run(root, output, 'rust-release', cargo + ['build'] + manifest + ['--release', '--locked'] + target, native_env)
            native = root / 'native-rust/target' / args.native_target / 'release' / library
            if not native.is_file():
                raise ValueError('Native release library missing')
            native_record = dict(target=args.native_target, rust=RUST_VERSION, host=platform.machine())
        wrapper = [str(root / 'gradlew.bat')] if os.name == 'nt' else ['bash', str(root / 'gradlew')]
        gradle = wrapper + ['clean', 'check', 'bundleArtifacts', '--console=plain', '--info', '--no-build-cache',
                            '--no-configuration-cache', '--rerun-tasks', '--dependency-verification', 'strict',
                            '-PspmTestJavaVersion=' + str(args.test_java),
                            '-Dorg.gradle.java.installations.paths=' + str(compiler) + ',' + str(test_home),
                            '--init-script', str(init)]
        if native:
            gradle.append('-PspmNativeLibrary=' + str(native))
        log = run(root, output, 'gradle', gradle)
        evidence = validate_java_evidence(root, log, args.test_java, native is not None)
        audit_path = output / 'core-audit.json'
        run(root, output, 'core-audit', [sys.executable, 'scripts/audit_core.py', '--root', str(root),
                                       '--require-jars', '--output', str(audit_path)])
        audit_report = json.loads(audit_path.read_text(encoding='utf-8'))
        if audit_report['errors']:
            raise ValueError('Mandatory Core audit reported errors')
        files = candidate_inputs(root)
        files += [root / 'LICENSE', root / 'NOTICE', root / 'THIRD_PARTY_NOTICES.md']
        files += [root / 'verification/src/test/resources/ysm/LICENSE']
        files += [path for path in (root / 'provenance').rglob('*') if path.is_file()]
        if not (root / 'provenance/allowed-sources.json').is_file():
            raise ValueError('Allowed source provenance manifest missing')
        tracked = subprocess.check_output(['git', 'ls-files', '-z', '--', 'native-rust'], cwd=root).decode().split('\0')
        # Only native product source/build inputs, never target outputs or private model corpora.
        files += [root / name for name in tracked if name and Path(name).suffix in {'.rs', '.h', '.toml', '.lock'}
                  and 'target' not in Path(name).parts]
        if native:
            files.append(native)
        for path in sorted(set(files)):
            copy_file(root, artifact, path)
        shutil.copyfile(audit_path, artifact / 'core-audit.json')
        checkout(root, revision)
        diagnostics(root, output, artifact)
        records = [dict(path=path.relative_to(artifact).as_posix(), bytes=path.stat().st_size,
                        sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                   for path in sorted(artifact.rglob('*')) if path.is_file()]
        manifest_record = dict(schema=1, status='VERIFIED_CANDIDATE_NOT_RELEASE_CERTIFIED', checkout=revision,
                              expectedCheckout=args.expected_sha, runId=os.environ.get('GITHUB_RUN_ID'),
                              runAttempt=os.environ.get('GITHUB_RUN_ATTEMPT'), os=platform.system(),
                              architecture=platform.machine(), native=native_record, java=evidence,
                              sourceCertification='Q', licenseClosureCertification='Q',
                              dependencyInventoryScope='Resolved dependency hashes and build inputs; not a certified SBOM', files=records)
        (artifact / 'candidate.json').write_text(json.dumps(manifest_record, indent=2) + '\n', encoding='utf-8')
        status.update(status='PASSED', checkout=revision)
    except Exception as failure:
        status['failure'] = str(failure)
        raise
    finally:
        diagnostics(root, output, artifact)
        (artifact / 'result.json').write_text(json.dumps(status, indent=2) + '\n', encoding='utf-8')
        hashes = [hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.relative_to(artifact).as_posix()
                  for path in sorted(artifact.rglob('*')) if path.is_file() and path.name != 'SHA256SUMS']
        (artifact / 'SHA256SUMS').write_text('\n'.join(hashes) + '\n', encoding='utf-8')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument('--root', type=Path, default=root)
    parser.add_argument('--output', type=Path, default=root / '.gradle/core-ci')
    parser.add_argument('--expected-sha', required=True)
    parser.add_argument('--test-java', required=True, type=int, choices=[17, 21, 25])
    parser.add_argument('--native-target', choices=NATIVE_TARGETS)
    args = parser.parse_args()
    try:
        build(args)
        return 0
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, KeyError) as failure:
        print('Core CI candidate failed: ' + str(failure), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
