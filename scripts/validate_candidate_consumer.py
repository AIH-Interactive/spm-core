"""Resolve an anonymous, pinned preview from an ordinary Maven URL in a clean consumer.

The separately supplied manifest SHA-256 is the trust pin. Every repository file
is checked before Gradle resolves exact immutable GAVs without a composite build,
mavenLocal, inherited Gradle init scripts, or the producer's Gradle cache.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import struct
import tempfile
from urllib.parse import urljoin, urlsplit
from urllib.request import build_opener, HTTPRedirectHandler

from package_candidate import GROUP, HASH, sha, validate_manifest, version_for


def repository_url(value: str) -> str:
    parts = urlsplit(value)
    if parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError('Anonymous repository URL cannot include credentials, query or fragment')
    if parts.scheme not in {'https', 'file'} and not (parts.scheme == 'http' and parts.hostname in {'localhost', '127.0.0.1', '::1'}):
        raise ValueError('Use an anonymous HTTPS/file repository URL (HTTP allowed only on loopback)')
    return value.rstrip('/') + '/'


class AnonymousRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        repository_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def fetch(url: str) -> bytes:
    with build_opener(AnonymousRedirects()).open(url, timeout=60) as response:
        return response.read()


def inspect_repository(repository: str, revision: str, manifest_sha256: str, reader=fetch) -> tuple[dict, dict]:
    base = repository_url(repository)
    if not HASH.fullmatch(manifest_sha256):
        raise ValueError('Expected an independently recorded SHA-256 manifest pin')
    data = reader(urljoin(base, 'manifest.json'))
    if sha(data) != manifest_sha256:
        raise ValueError('Manifest digest mismatch')
    manifest = json.loads(data)
    validate_manifest(manifest, revision)
    inventory = None
    for record in manifest['files']:
        data = reader(urljoin(base, record['path']))
        if len(data) != record['bytes'] or sha(data) != record['sha256']:
            raise ValueError('Artifact digest/size mismatch: ' + record['path'])
        if record['path'] == 'inventory.json':
            inventory = json.loads(data)
    if not isinstance(inventory, dict) or inventory.get('schema') != 'spm-core-resolved-inventory/1' or inventory.get('status') != 'LOCAL_RESOLVED_INVENTORY':
        raise ValueError('Missing or incompatible local resolved inventory')
    return manifest, inventory


def groovy_string(value: str) -> str:
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'").replace('\n', '\\n').replace('\r', '\\r') + "'"


def consumer_build(repository: str, manifest: dict, pom_only: bool = False) -> str:
    dependencies = '\n'.join('    implementation ' + groovy_string(f"{GROUP}:{module}:{manifest['version']}") for module in manifest['modules'])
    return '''plugins { id 'java' }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
repositories {
    exclusiveContent {
        forRepository { maven { url = uri(REPOSITORY); METADATA } }
        filter { includeGroup('dev.spm.core') }
    }
    mavenCentral()
}
dependencies {
DEPENDENCIES
}
tasks.register('resolveCandidate') {
    doLast {
        def records = configurations.runtimeClasspath.incoming.artifacts.artifacts.collect { artifact ->
            def id = artifact.id.componentIdentifier
            if (!(id instanceof org.gradle.api.artifacts.component.ModuleComponentIdentifier))
                throw new GradleException('Consumer resolved a project/composite dependency')
            [group:id.group, name:id.module, version:id.version,
             sha256:java.security.MessageDigest.getInstance('SHA-256').digest(artifact.file.bytes).encodeHex().toString()]
        }
        file('resolved.json').text = groovy.json.JsonOutput.toJson(records)
        file('classpath.txt').text = configurations.runtimeClasspath.asPath
    }
}
'''.replace('REPOSITORY', groovy_string(repository_url(repository))).replace('DEPENDENCIES', dependencies).replace('METADATA', 'metadataSources { mavenPom() }' if pom_only else '')


def validate_resolved(records: list[dict], manifest: dict, inventory: dict) -> None:
    version = manifest['version']
    expected = {}
    for module in manifest['modules']:
        path = f'dev/spm/core/{module}/{version}/{module}-{version}.jar'
        expected[(GROUP, module, version)] = next(x['sha256'] for x in manifest['files'] if x['path'] == path)
    for module in inventory['gradle']:
        if module['project'] in manifest['modules']:
            for dep in module['dependencies']:
                if dep['scope'] == 'runtimeClasspath':
                    key = (dep['group'], dep['name'], dep['version'])
                    if key in expected and expected[key] != dep['sha256']:
                        raise ValueError('Inconsistent resolved dependency digest inventory')
                    expected[key] = dep['sha256']
    observed = {}
    for record in records:
        key = (record['group'], record['name'], record['version'])
        if key in observed or not HASH.fullmatch(record['sha256']):
            raise ValueError('Duplicate or incompatible resolved artifact')
        observed[key] = record['sha256']
    if observed != expected:
        raise ValueError('Consumer exact GAV/hash closure differs from published runtime inventory')


CONSUMER_MAIN = '''import dev.spm.core.api.ResourceBudget;
public final class Consumer {
    public static void main(String[] args) {
        ResourceBudget budget = new ResourceBudget(4);
        try (var reservation = budget.reserve(4)) {
            if (reservation.bytes() != 4 || budget.used() != 4) throw new AssertionError("admission");
        }
        if (budget.used() != 0) throw new AssertionError("release");
        System.out.println("SPM_CONSUMER_OK");
    }
}
'''


def java_evidence(home: Path, expected: int, env: dict) -> dict:
    executable = home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
    result = subprocess.run([str(executable), '-XshowSettings:properties', '-version'], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', env=env)
    marker = 'java.specification.version = ' + str(expected)
    if result.returncode or marker not in result.stderr:
        raise ValueError('Explicit Java home does not provide Java ' + str(expected))
    # Keep evidence free of user-specific absolute installation paths.
    lines = [line.strip() for line in result.stderr.splitlines() if 'version "' in line or line.strip().startswith('java.specification.version =')]
    return dict(specification_version=expected, evidence=lines)


def validate(repository: str, revision: str, manifest_sha256: str, gradle: Path, pom_only: bool = False,
             compiler_home: Path | None = None, runtime_home: Path | None = None) -> dict:
    if (compiler_home is None) != (runtime_home is None):
        raise ValueError('Supply both --compiler-home (JDK25) and --runtime-home (JRE17) for compile/run validation')
    manifest, inventory = inspect_repository(repository, revision, manifest_sha256)
    compiler_record = runtime_record = None
    with tempfile.TemporaryDirectory(prefix='spm-standalone-consumer-') as directory:
        root = Path(directory)
        (root / 'settings.gradle').write_text("rootProject.name = 'standalone-candidate-consumer'\n", encoding='utf-8')
        (root / 'build.gradle').write_text(consumer_build(repository, manifest, pom_only), encoding='utf-8')
        env = {key: value for key, value in os.environ.items() if key not in {'GRADLE_OPTS', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'}
               and not key.startswith('ORG_GRADLE_PROJECT_')}
        env['GRADLE_USER_HOME'] = str(root / 'gradle-home')
        command = [str(gradle.resolve()), '--no-daemon', '--no-configuration-cache', '--no-build-cache', 'resolveCandidate']
        if compiler_home is not None:
            compiler_home, runtime_home = compiler_home.resolve(), runtime_home.resolve()
            compiler_record = java_evidence(compiler_home, 25, env)
            runtime_record = java_evidence(runtime_home, 17, env)
            env['JAVA_HOME'] = str(compiler_home)
            with (root / 'build.gradle').open('a', encoding='utf-8') as stream:
                stream.write('''
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType(JavaCompile).configureEach {
    options.release = 17
    doFirst {
        if (javaCompiler.get().metadata.languageVersion.asInt() != 25 || options.release.get() != 17)
            throw new GradleException('Wrong actual consumer compiler/release')
        println('SPM_CONSUMER_COMPILER=25;RELEASE=17')
    }
}
''')
            source = root / 'src/main/java/Consumer.java'
            source.parent.mkdir(parents=True)
            source.write_text(CONSUMER_MAIN, encoding='utf-8')
            command += ['classes', '-Dorg.gradle.java.installations.paths=' + str(compiler_home),
                        '-Dorg.gradle.java.installations.auto-download=false', '-Dorg.gradle.java.installations.auto-detect=false']
        result = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if result.returncode:
            raise ValueError('Clean standalone Gradle consumer failed:\n' + result.stdout + result.stderr)
        records = json.loads((root / 'resolved.json').read_text(encoding='utf-8'))
        validate_resolved(records, manifest, inventory)
        if compiler_home is not None:
            bytecode = (root / 'build/classes/java/main/Consumer.class').read_bytes()
            if 'SPM_CONSUMER_COMPILER=25;RELEASE=17' not in result.stdout or len(bytecode) < 8 or struct.unpack('>H', bytecode[6:8])[0] != 61:
                raise ValueError('Missing actual JDK25/release17 consumer compiler evidence')
            classpath = str(root / 'build/classes/java/main') + os.pathsep + (root / 'classpath.txt').read_text(encoding='utf-8')
            runtime = runtime_home / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
            execution = subprocess.run([str(runtime), '-cp', classpath, 'Consumer'], cwd=root, env=env,
                                       capture_output=True, text=True, encoding='utf-8', errors='replace')
            if execution.returncode or execution.stdout.strip() != 'SPM_CONSUMER_OK':
                raise ValueError('Explicit JRE17 standalone API consumer failed: ' + execution.stderr)
            compiler_record['release'] = 17
            runtime_record['consumer_result'] = execution.stdout.strip()
    return dict(schema=1, status='STANDALONE_MAVEN_RESOLUTION_VERIFIED', source_revision=revision,
                version=version_for(revision), repository=repository_url(repository), manifest_sha256=manifest_sha256,
                metadata='POM-only secondary check' if pom_only else 'Default Gradle module metadata',
                modules=manifest['modules'], resolved=records, native_loading='Not exercised; remains an explicit host operation',
                compiler=compiler_record, runtime=runtime_record,
                scope='Exact anonymous artifact/GAV/hash resolution; not upstream origin, license closure or host compatibility certification')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--gradle', type=Path, required=True, help='Installed Gradle executable or absolute producer wrapper path')
    parser.add_argument('--pom-only', action='store_true', help='Secondary POM-only check; default uses ordinary Gradle module metadata')
    parser.add_argument('--compiler-home', type=Path, help='Optional explicit JDK25 home; requires --runtime-home')
    parser.add_argument('--runtime-home', type=Path, help='Optional explicit JRE17/JDK17 home; requires --compiler-home')
    parser.add_argument('--output', type=Path, required=True, help='Verification JSON artifact path')
    args = parser.parse_args()
    report = validate(args.repository, args.revision, args.manifest_sha256, args.gradle, args.pom_only, args.compiler_home, args.runtime_home)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(json.dumps(dict(status=report['status'], version=report['version'], artifacts=len(report['resolved']))))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
