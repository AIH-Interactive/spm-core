"""Freeze a local Maven preview. No network publication or upstream-origin certification.

Build from a clean exact checkout with -PspmCoreVersion=0.1.0-preview.<sha12>
publishCandidate, then run this script with --revision <full SHA>. The output is
a new Maven repository directory. Existing output is never overwritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import struct
import tempfile
import tomllib
import xml.etree.ElementTree as ET

from audit_core import audit
from ci_core import NATIVE_TARGETS, candidate_inputs, validate_checkout

GROUP = 'dev.spm.core'
SOURCE = 'https://github.com/AIH-Interactive/spm-core'
SCHEMA = 'spm-core-candidate/1'
SCOPE = 'LOCAL_RESOLVED_INVENTORY; preview only; full dependency licensing and source origin certification remain pending'
HASH = re.compile(r'[0-9a-f]{64}')


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_for(revision: str) -> str:
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('An exact 40-hex source revision is required')
    return '0.1.0-preview.' + revision[:12]


def safe_path(value: str) -> str:
    if not isinstance(value, str):
        raise ValueError('Repository path must be a string')
    path = PurePosixPath(value)
    if not value or '\\' in value or ':' in value or path.is_absolute() or any(p in {'', '.', '..'} for p in value.split('/')):
        raise ValueError('Unsafe repository path: ' + value)
    return value


def validate_manifest(manifest: dict, revision: str) -> None:
    if manifest.get('schema') != SCHEMA or manifest.get('source') != {'repository': SOURCE, 'revision': revision}:
        raise ValueError('Unsupported schema or incompatible source revision')
    version = version_for(revision)
    if manifest.get('version') != version or manifest.get('group') != GROUP:
        raise ValueError('Immutable candidate GAV mismatch')
    if manifest.get('status') != 'PREVIEW' or manifest.get('scope') != SCOPE:
        raise ValueError('Missing preview evidence limitations')
    modules, files = manifest.get('modules'), manifest.get('files')
    if not isinstance(modules, list) or not modules or any(
            not isinstance(m, str) or not re.fullmatch(r'[a-z][a-z0-9-]*', m) or m in {'verification', 'integration-tests'} for m in modules) or len(set(modules)) != len(modules):
        raise ValueError('Invalid product module inventory')
    if not isinstance(files, list) or not files:
        raise ValueError('Missing artifact inventory')
    seen = set()
    for record in files:
        if not isinstance(record, dict) or set(record) != {'path', 'sha256', 'bytes'}:
            raise ValueError('Invalid artifact record schema')
        path = safe_path(record['path'])
        if path == 'manifest.json' or path in seen or not HASH.fullmatch(str(record['sha256'])) or type(record['bytes']) is not int or record['bytes'] < 0:
            raise ValueError('Duplicate path, incompatible digest or invalid artifact size')
        seen.add(path)
    required = {'inventory.json', 'licenses/LICENSE', 'licenses/NOTICE', 'licenses/THIRD_PARTY_NOTICES.md',
                'licenses/upstream-fixtures-Apache-2.0.txt', 'provenance/allowed-sources.json',
                'locks/native-rust/Cargo.lock', 'locks/gradle/verification-metadata.xml'}
    for module in modules:
        prefix = f'dev/spm/core/{module}/{version}/{module}-{version}'
        required |= {prefix + suffix for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module')}
        required.add('locks/' + module + '/gradle.lockfile')
    if not required <= seen:
        raise ValueError('Missing required product artifacts or metadata: ' + ', '.join(sorted(required - seen)))


def validate_pom(data: bytes, module: str, version: str, modules: list[str]) -> None:
    pom = ET.fromstring(data)
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    for key, expected in [('groupId', GROUP), ('artifactId', module), ('version', version)]:
        if pom.findtext('m:' + key, namespaces=ns) != expected:
            raise ValueError(module + ': wrong publication GAV')
    for dep in pom.findall('m:dependencies/m:dependency', ns):
        if dep.findtext('m:groupId', namespaces=ns) == GROUP:
            if dep.findtext('m:artifactId', namespaces=ns) not in modules or dep.findtext('m:version', namespaces=ns) != version:
                raise ValueError(module + ': stale or unpublished internal dependency')


def validate_jar(data: bytes, require_notices: bool = True) -> None:
    import io
    import zipfile
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        if require_notices and not {'META-INF/spm-core/LICENSE', 'META-INF/spm-core/NOTICE'} <= set(jar.namelist()):
            raise ValueError('Missing license or notice in product JAR')
        if any(name.lower().endswith(('.dll', '.so', '.dylib', '.ysm', '.wire', '.v3d', '.bbmodel')) for name in jar.namelist()):
            raise ValueError('Unexpected native binary or model asset in product JAR')


def validate_module(data: bytes, module: str, version: str, artifacts: dict[str, bytes]) -> None:
    metadata = json.loads(data)
    if metadata.get('formatVersion') != '1.1' or metadata.get('component') != {'group': GROUP, 'module': module, 'version': version, 'attributes': {'org.gradle.status': 'release'}}:
        raise ValueError(module + ': incompatible Gradle module coordinates/schema')
    variants = metadata.get('variants')
    if not isinstance(variants, list) or not variants:
        raise ValueError(module + ': missing Gradle publication variants')
    usages = set()
    for variant in variants:
        attributes = variant.get('attributes', {})
        usage = attributes.get('org.gradle.usage')
        if attributes.get('org.gradle.category') == 'library' and usage in {'java-api', 'java-runtime'}:
            usages.add(usage)
            if attributes.get('org.gradle.jvm.version') != 17:
                raise ValueError(module + ': published JVM variant must target Java 17')
        for record in variant.get('files', []):
            name = safe_path(record['url'])
            if '/' in name or name not in artifacts or record.get('size') != len(artifacts[name]) or record.get('sha256') != sha(artifacts[name]):
                raise ValueError(module + ': stale Gradle module artifact digest')
    if usages != {'java-api', 'java-runtime'}:
        raise ValueError(module + ': missing API/runtime Gradle variants')


def validate_native(data: bytes, target: str) -> None:
    """Check container architecture, not execution or instruction-set compatibility."""
    valid = False
    if target == 'x86_64-pc-windows-msvc' and len(data) >= 64 and data[:2] == b'MZ':
        offset = struct.unpack_from('<I', data, 60)[0]
        valid = len(data) >= offset + 24 and data[offset:offset + 4] == b'PE\0\0' and struct.unpack_from('<H', data, offset + 4)[0] == 0x8664
    elif target == 'x86_64-unknown-linux-gnu' and len(data) >= 20:
        valid = data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<HH', data, 16) == (3, 62)
    elif target == 'aarch64-apple-darwin' and len(data) >= 16:
        valid = struct.unpack_from('<IIII', data)[:2] == (0xFEEDFACF, 0x0100000C) and struct.unpack_from('<I', data, 12)[0] == 6
    if not valid:
        raise ValueError('Native container/architecture mismatch: ' + target)


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(['git', '-C', str(root), *args], text=True, encoding='utf-8').strip()


def package(root: Path, repository: Path, output: Path, revision: str, natives: list[str]) -> dict:
    version = version_for(revision)
    validate_checkout(revision, git(root, 'rev-parse', 'HEAD'), git(root, 'status', '--porcelain=v1'))
    if output.exists():
        raise ValueError('Immutable candidate output already exists; choose a new output directory')
    if repository == output or repository in output.parents:
        raise ValueError('Output must be separate from Gradle publication repository')
    candidate_inputs(root)  # Mandatory current dependency-inventory and product completeness checks.
    errors = audit(root, require_jars=True)['errors']
    if errors:
        raise ValueError('; '.join(errors))
    modules = sorted(p.parent.name for p in root.glob('*/build.gradle') if p.parent.name not in {'verification', 'integration-tests'})
    staged: dict[str, bytes] = {}
    def add(path: str, data: bytes):
        safe_path(path)
        if path in staged:
            raise ValueError('Duplicate staged artifact: ' + path)
        staged[path] = data
    for module in modules:
        prefix = f'dev/spm/core/{module}/{version}/{module}-{version}'
        artifacts = {}
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module'):
            relative = prefix + suffix
            source = repository / relative
            if not source.is_file() or source.is_symlink():
                raise ValueError('Missing publication: ' + relative)
            data = source.read_bytes()
            # A stale Maven repository must never pass because current build/libs exists.
            local = root / module / ('build/publications/mavenJava/pom-default.xml' if suffix == '.pom' else
                                     'build/publications/mavenJava/module.json' if suffix == '.module' else
                                     'build/libs/' + module + '-' + version + suffix)
            if not local.is_file() or data != local.read_bytes():
                raise ValueError('Stale or incompatible publication: ' + relative)
            if suffix == '.pom':
                validate_pom(data, module, version, modules)
            if suffix.endswith('.jar'):
                validate_jar(data, require_notices=suffix == '.jar')
            artifacts[source.name] = data
            add(relative, data)
            add(relative + '.sha256', (sha(data) + '\n').encode())
        validate_module(artifacts[module + '-' + version + '.module'], module, version, artifacts)
    for source, destination in [('LICENSE', 'licenses/LICENSE'), ('NOTICE', 'licenses/NOTICE'),
            ('THIRD_PARTY_NOTICES.md', 'licenses/THIRD_PARTY_NOTICES.md'),
            ('verification/src/test/resources/ysm/LICENSE', 'licenses/upstream-fixtures-Apache-2.0.txt'),
            ('provenance/allowed-sources.json', 'provenance/allowed-sources.json')]:
        add(destination, (root / source).read_bytes())
    inventories = [json.loads(p.read_text(encoding='utf-8')) for p in sorted(root.glob('*/build/metadata/dependencies.json'))]
    for inventory in inventories:
        for dep in inventory['dependencies']:
            if not HASH.fullmatch(dep['sha256']) or any('/' in dep[key] or '\\' in dep[key] for key in ['group', 'name', 'version', 'file']):
                raise ValueError('Unsafe or invalid resolved dependency inventory')
    lock_paths = sorted(root.glob('*/gradle.lockfile')) + [root / 'native-rust/Cargo.lock', root / 'gradle/verification-metadata.xml']
    for path in lock_paths:
        add('locks/' + path.relative_to(root).as_posix(), path.read_bytes())
    native_inventory = []
    for entry in natives:
        target, separator, filename = entry.partition('=')
        if not separator or target not in NATIVE_TARGETS:
            raise ValueError('Expected --native <supported-target>=<built-library>')
        path = Path(filename)
        data = path.read_bytes()
        expected = NATIVE_TARGETS[target][2]
        if path.name != expected:
            raise ValueError('Native target/name mismatch: ' + target)
        validate_native(data, target)
        relative = 'native/' + target + '/' + expected
        add(relative, data)
        native_inventory.append(dict(target=target, path=relative, sha256=sha(data), source_revision=revision,
                                     loading='Explicit host path only; Core never downloads a library', execution='Not asserted by packaging',
                                     build_provenance='Explicitly supplied bytes; source_revision identifies the packaged checkout, not a compiler-input attestation'))
    inventory = dict(schema='spm-core-resolved-inventory/1', status='LOCAL_RESOLVED_INVENTORY', scope=SCOPE,
                     format='Project-specific inventory, not a validated SPDX or CycloneDX document',
                     gradle=inventories, cargo_lock_packages=tomllib.loads((root / 'native-rust/Cargo.lock').read_text(encoding='utf-8'))['package'],
                     native=native_inventory, license_conclusion='NOASSERTION; see bundled component notices and pending full license closure')
    add('inventory.json', (json.dumps(inventory, indent=2, sort_keys=True) + '\n').encode())
    manifest = dict(schema=SCHEMA, status='PREVIEW', scope=SCOPE, source=dict(repository=SOURCE, revision=revision),
                    group=GROUP, version=version, modules=modules,
                    files=[dict(path=path, sha256=sha(data), bytes=len(data)) for path, data in sorted(staged.items())])
    validate_manifest(manifest, revision)
    validate_checkout(revision, git(root, 'rev-parse', 'HEAD'), git(root, 'status', '--porcelain=v1'))
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='candidate-', dir=output.parent) as directory:
        stage = Path(directory) / 'repository'
        stage.mkdir()
        for path, data in staged.items():
            destination = stage / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n', encoding='utf-8')
        stage.rename(output)
    return dict(version=version, files=len(staged), manifest_sha256=sha((output / 'manifest.json').read_bytes()), native_targets=[x['target'] for x in native_inventory])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--repository', type=Path, help='Default: <root>/build/candidate-maven')
    parser.add_argument('--output', type=Path, help='Default: <root>/build/candidate-dist')
    parser.add_argument('--revision', required=True)
    parser.add_argument('--native', action='append', default=[], help='Explicit supported-target=library path; repeat for multiple targets')
    args = parser.parse_args()
    root = args.root.resolve()
    result = package(root, (args.repository or root / 'build/candidate-maven').resolve(),
                     (args.output or root / 'build/candidate-dist').resolve(), args.revision, args.native)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
