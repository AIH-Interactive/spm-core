import copy
import io
import json
from pathlib import Path
import tempfile
import struct
import unittest
from unittest.mock import patch
import zipfile

from package_candidate import (GROUP, SCHEMA, SCOPE, SOURCE, package, safe_path, sha,
                               validate_jar, validate_manifest, validate_module, validate_native, validate_pom, version_for)
from snapshot_core import build_input_snapshot
from validate_candidate_consumer import (consumer_build, inspect_repository, repository_url,
                                         validate_resolved)

REVISION = 'a' * 40
VERSION = version_for(REVISION)
MODULES = ['api', 'codec', 'format-ysm', 'format-bedrock', 'format-bbmodel', 'scene',
           'acceleration-api', 'native-jni', 'render-cpu', 'runtime', 'format-v3d', 'format-common', 'parser']


def jar_bytes(extra=None):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as jar:
        jar.writestr(zipfile.ZipInfo('META-INF/spm-core/LICENSE'), 'MIT')
        jar.writestr(zipfile.ZipInfo('META-INF/spm-core/NOTICE'), 'notice')
        for name, value in (extra or {}).items():
            jar.writestr(name, value)
    return data.getvalue()


def fixture(root):
    """Real temporary Maven/build layout; no remote repository or Gradle execution."""
    for name in ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md', 'verification/src/test/resources/ysm/LICENSE',
                 'gradle/verification-metadata.xml', 'provenance/allowed-sources.json']:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('{}' if name.endswith('.json') else 'synthetic license fixture', encoding='utf-8')
    (root / 'native-rust').mkdir()
    (root / 'native-rust/Cargo.lock').write_text('version = 4\n[[package]]\nname = "fixture"\nversion = "1.0.0"\n', encoding='utf-8')
    (root / 'build.gradle').write_text('// synthetic test', encoding='utf-8')
    for module in MODULES:
        (root / module).mkdir()
        (root / module / 'build.gradle').write_text('// synthetic test', encoding='utf-8')
        (root / module / 'gradle.lockfile').write_text('empty=runtimeClasspath\n', encoding='utf-8')
    snapshot = build_input_snapshot(root)
    repo = root / 'build/candidate-maven'
    for module in MODULES:
        directory = root / module
        libs = directory / 'build/libs'
        publication = directory / 'build/publications/mavenJava'
        metadata = directory / 'build/metadata'
        maven = repo / 'dev/spm/core' / module / VERSION
        for path in [libs, publication, metadata, maven]:
            path.mkdir(parents=True)
        artifacts = {}
        for suffix in ['.jar', '-sources.jar', '-javadoc.jar']:
            name = module + '-' + VERSION + suffix
            artifacts[name] = jar_bytes()
            (libs / name).write_bytes(artifacts[name])
            (maven / name).write_bytes(artifacts[name])
        pom = f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{GROUP}</groupId><artifactId>{module}</artifactId><version>{VERSION}</version></project>'
        (publication / 'pom-default.xml').write_text(pom, encoding='utf-8')
        (maven / (module + '-' + VERSION + '.pom')).write_text(pom, encoding='utf-8')
        module_data = json.dumps(dict(formatVersion='1.1', component=dict(group=GROUP, module=module, version=VERSION, attributes={'org.gradle.status': 'release'}),
                                variants=[dict(attributes={'org.gradle.category': 'library', 'org.gradle.usage': usage, 'org.gradle.jvm.version': 17},
                                               files=[dict(url=name, size=len(data), sha256=sha(data)) for name, data in artifacts.items()])
                                          for usage in ['java-api', 'java-runtime']]))
        (publication / 'module.json').write_text(module_data, encoding='utf-8')
        (maven / (module + '-' + VERSION + '.module')).write_text(module_data, encoding='utf-8')
        (metadata / 'dependencies.json').write_text(json.dumps(dict(project=module, dependencies=[], buildInputs=snapshot)), encoding='utf-8')
    return repo


class CandidatePublicationTest(unittest.TestCase):
    def test_immutable_version_requires_exact_commit(self):
        self.assertEqual('0.1.0-preview.aaaaaaaaaaaa', VERSION)
        for revision in ['main', 'HEAD', 'a' * 12, 'A' * 40, REVISION + '-SNAPSHOT']:
            with self.assertRaises(ValueError):
                version_for(revision)

    def test_repository_paths_reject_traversal_absolute_windows_and_empty_segments(self):
        for path in ['../private', '/private', 'C:/private', 'a\\b', 'a//b', 'a/./b', '', None]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                safe_path(path)

    def test_product_jar_requires_notices_and_keeps_models_and_native_separate(self):
        validate_jar(jar_bytes())
        for asset in ['native.dll', 'model.ysm', 'fixture.wire', 'model.bbmodel']:
            with self.subTest(asset=asset), self.assertRaises(ValueError):
                validate_jar(jar_bytes({asset: b'not distributed'}))
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as jar:
            jar.writestr('example.txt', 'missing notices')
        with self.assertRaises(ValueError):
            validate_jar(data.getvalue())

    def test_pom_rejects_wrong_version_and_unpublished_internal_project(self):
        pom = f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>{GROUP}</groupId><artifactId>api</artifactId><version>{VERSION}</version><dependencies><dependency><groupId>{GROUP}</groupId><artifactId>verification</artifactId><version>{VERSION}</version></dependency></dependencies></project>'
        with self.assertRaisesRegex(ValueError, 'unpublished'):
            validate_pom(pom.encode(), 'api', VERSION, MODULES)
        with self.assertRaisesRegex(ValueError, 'GAV'):
            validate_pom(pom.replace(VERSION, '0.1.0-SNAPSHOT').encode(), 'api', VERSION, MODULES)

    @patch('package_candidate.git', side_effect=lambda root, *args: REVISION if args[0] == 'rev-parse' else '')
    def test_real_package_layout_is_frozen_and_contains_only_allowlisted_products(self, _):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = fixture(root)
            (root / 'private-model.ysm').write_bytes(b'excluded sentinel')
            (repo / 'dev/spm/core/api/maven-metadata.xml').write_text('mutable latest', encoding='utf-8')
            output = root / 'build/candidate-dist'
            result = package(root, repo, output, REVISION, [])
            manifest = json.loads((output / 'manifest.json').read_text(encoding='utf-8'))
            validate_manifest(manifest, REVISION)
            self.assertEqual(set(MODULES), set(manifest['modules']))
            self.assertEqual(sha((output / 'manifest.json').read_bytes()), result['manifest_sha256'])
            self.assertFalse(any('private' in x['path'] or 'maven-metadata.xml' in x['path'] or '/verification/' in x['path'] for x in manifest['files']))
            observed, inventory = inspect_repository(output.as_uri(), REVISION, result['manifest_sha256'])
            self.assertEqual(manifest, observed)
            self.assertEqual('LOCAL_RESOLVED_INVENTORY', inventory['status'])
            records = [dict(group=GROUP, name=module, version=VERSION, sha256=sha(jar_bytes())) for module in MODULES]
            validate_resolved(records, manifest, inventory)
            with self.assertRaisesRegex(ValueError, 'already exists'):
                package(root, repo, output, REVISION, [])
            (output / manifest['files'][0]['path']).write_bytes(b'corruption')
            with self.assertRaisesRegex(ValueError, 'digest/size'):
                inspect_repository(output.as_uri(), REVISION, result['manifest_sha256'])

    @patch('package_candidate.git', side_effect=lambda root, *args: REVISION if args[0] == 'rev-parse' else '')
    def test_stale_publication_and_dependency_inventory_fail_before_output(self, _):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = fixture(root)
            pom = repo / f'dev/spm/core/api/{VERSION}/api-{VERSION}.pom'
            original = pom.read_bytes()
            pom.write_bytes(b'stale')
            with self.assertRaisesRegex(ValueError, 'Stale'):
                package(root, repo, root / 'build/output', REVISION, [])
            self.assertFalse((root / 'build/output').exists())
            pom.write_bytes(original)
            (root / 'build.gradle').write_text('// changed after resolution', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'stale|unattested'):
                package(root, repo, root / 'build/output', REVISION, [])

    def test_module_metadata_rejects_mismatched_artifact_hash(self):
        data = dict(formatVersion='1.1', component=dict(group=GROUP, module='api', version=VERSION, attributes={'org.gradle.status': 'release'}),
                    variants=[dict(attributes={'org.gradle.category': 'library', 'org.gradle.usage': 'java-api', 'org.gradle.jvm.version': 17}, files=[dict(url='api.jar', size=3, sha256='0' * 64)])])
        with self.assertRaisesRegex(ValueError, 'digest'):
            validate_module(json.dumps(data).encode(), 'api', VERSION, {'api.jar': b'jar'})

    def test_consumer_repository_requires_anonymous_transport_and_no_user_gradle_injection(self):
        for url in ['https://user:password@example.com/maven', 'https://example.com/maven?token=secret', 'http://example.com/maven', 'ftp://example.com/maven']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                repository_url(url)
        build = consumer_build('https://example.com/maven', dict(version=VERSION, modules=['api']))
        self.assertIn('exclusiveContent', build)
        self.assertNotIn('metadataSources', build)
        self.assertIn('mavenPom()', consumer_build('https://example.com/maven', dict(version=VERSION, modules=['api']), pom_only=True))
        self.assertNotIn('includeBuild', build)
        self.assertNotIn('mavenLocal', build)
        self.assertIn(f'{GROUP}:api:{VERSION}', build)

    def test_native_binary_container_is_bound_to_target_architecture(self):
        windows = bytearray(128)
        windows[:2] = b'MZ'
        struct.pack_into('<I', windows, 60, 64)
        windows[64:68] = b'PE\0\0'
        struct.pack_into('<H', windows, 68, 0x8664)
        linux = bytearray(64)
        linux[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<HH', linux, 16, 3, 62)
        mac = struct.pack('<IIII', 0xFEEDFACF, 0x0100000C, 0, 6)
        for data, target in [(windows, 'x86_64-pc-windows-msvc'), (linux, 'x86_64-unknown-linux-gnu'), (mac, 'aarch64-apple-darwin')]:
            validate_native(data, target)
            with self.assertRaisesRegex(ValueError, 'architecture'):
                validate_native(b'bad', target)
        with self.assertRaises(ValueError):
            validate_native(windows, 'x86_64-unknown-linux-gnu')

    def test_published_jvm25_variant_is_rejected_despite_java17_bytecode(self):
        data = dict(formatVersion='1.1', component=dict(group=GROUP, module='api', version=VERSION, attributes={'org.gradle.status': 'release'}),
                    variants=[dict(attributes={'org.gradle.category': 'library', 'org.gradle.usage': 'java-runtime', 'org.gradle.jvm.version': 25})])
        with self.assertRaisesRegex(ValueError, 'Java 17'):
            validate_module(json.dumps(data).encode(), 'api', VERSION, {})

    @patch('package_candidate.git', side_effect=lambda root, *args: REVISION if args[0] == 'rev-parse' else '')
    def test_consumer_rejects_manifest_schema_digest_paths_and_gav_closure(self, _):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = fixture(root)
            output = root / 'build/output'
            result = package(root, repo, output, REVISION, [])
            manifest, inventory = inspect_repository(output.as_uri(), REVISION, result['manifest_sha256'])
            for change in [dict(schema=2), dict(version='0.1.0-SNAPSHOT'), dict(status='CERTIFIED')]:
                bad = dict(manifest, **change)
                with self.assertRaises(ValueError):
                    validate_manifest(bad, REVISION)
            for path, digest in [('../secret', '0' * 64), ('valid', 'SHA512:abc')]:
                bad = copy.deepcopy(manifest)
                bad['files'].append(dict(path=path, sha256=digest, bytes=1))
                with self.assertRaises(ValueError):
                    validate_manifest(bad, REVISION)
            with self.assertRaisesRegex(ValueError, 'Manifest digest'):
                inspect_repository(output.as_uri(), REVISION, '0' * 64)
            with self.assertRaisesRegex(ValueError, 'closure'):
                validate_resolved([], manifest, inventory)
            records = [dict(group=GROUP, name=module, version=VERSION, sha256=sha(jar_bytes())) for module in MODULES]
            records[0]['sha256'] = '0' * 64
            with self.assertRaisesRegex(ValueError, 'closure'):
                validate_resolved(records, manifest, inventory)


if __name__ == '__main__':
    unittest.main()
