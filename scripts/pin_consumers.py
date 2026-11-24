"""Pin every consumer checkout at one packaged Core manifest.

A consumer does not resolve Core from a moving branch: `gradle/spm-core-artifacts.json` records an
immutable artifact commit, the manifest digest, and the SHA-256 of each module's binary jar, and
`gradle/spm-core-host.gradle` verifies them. Writing that file by hand is where a release goes
wrong — sixteen hashes, one manifest digest, a commit and a URL, all of which have to describe the
same tree. This script derives all of it from the manifest and therefore cannot disagree with it.

The pin is generated, never guessed: every hash is read back out of the manifest that `package_candidate.py`
produced, and `manifestSha256` is the digest of the manifest file itself.

Usage, after the release commit exists and is pushed:

    python scripts/pin_consumers.py \
        --manifest build/candidate-dist/manifest.json \
        --revision <source SHA-40> \
        --artifact-commit <release commit SHA-40> \
        --consumer-root D:/SparkleMorpher            # --apply to write

Without `--apply` this only reports what would change, and `--check` exits non-zero on drift, so a
caller can use it as a verification gate.

The version comes from the manifest, not from a flag: a release, a manifest and a pin that disagree
about the version is exactly the state this script exists to make impossible.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

SCHEMA = 'spm-core-candidate/1'
PIN_FILE = Path('gradle') / 'spm-core-artifacts.json'
SHA40 = re.compile(r'[0-9a-f]{40}')


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def module_jar_path(group: str, module: str, version: str) -> str:
    """The one Maven path a consumer pins: the module's binary jar, not its sources or javadoc."""
    return '/'.join(group.split('.')) + f'/{module}/{version}/{module}-{version}.jar'


def pin_from_manifest(manifest: dict, manifest_bytes: bytes, revision: str,
                      artifact_commit: str, repository: str) -> dict:
    """Builds the pin document. Every value is derived from the manifest or supplied explicitly."""
    if manifest.get('schema') != SCHEMA:
        raise ValueError(f'Unsupported manifest schema: {manifest.get("schema")!r}')
    source = manifest.get('source') or {}
    if source.get('revision') != revision:
        raise ValueError('Manifest was packaged from a different source revision')
    if not SHA40.fullmatch(artifact_commit):
        raise ValueError('The artifact commit must be a full 40-hex revision')
    version = manifest.get('version')
    if not isinstance(version, str) or not version:
        raise ValueError('Manifest carries no version')
    modules = manifest.get('modules')
    if not isinstance(modules, list) or not modules:
        raise ValueError('Manifest lists no modules')
    if len(set(modules)) != len(modules):
        raise ValueError('Manifest repeats a module')
    group = manifest.get('group')
    if not isinstance(group, str) or not group:
        raise ValueError('Manifest carries no group')

    files = {}
    for record in manifest.get('files') or []:
        try:
            files[record['path']] = record
        except (TypeError, KeyError) as error:
            raise ValueError('Manifest file record is malformed') from error

    hashes = {}
    for module in modules:
        path = module_jar_path(group, module, version)
        record = files.get(path)
        if record is None:
            raise ValueError(f'Manifest has no binary jar for module {module}: {path}')
        recorded = record.get('sha256')
        if not isinstance(recorded, str) or not re.fullmatch(r'[0-9a-f]{64}', recorded):
            raise ValueError(f'Module {module} has no recorded SHA-256')
        hashes[module] = recorded

    return {
        'sourceRevision': revision,
        'version': version,
        'repository': repository,
        'artifactCommit': artifact_commit,
        'manifestSha256': sha256(manifest_bytes),
        # Sorted so the file is stable: regenerating an unchanged pin must not produce a diff.
        'sha256': {module: hashes[module] for module in sorted(hashes)},
    }


def render(pin: dict) -> bytes:
    """The exact bytes written into a consumer checkout.

    Always LF: the checkout's line ending is the consumer's business — `core.autocrlf` and
    `.gitattributes` decide how the file lands on disk — and normalising to LF is what makes the
    stored content identical everywhere.
    """
    return (json.dumps(pin, indent=2) + '\n').encode('utf-8')


def semantic(data: bytes) -> bytes:
    """Content with line endings folded, for deciding whether a checkout has actually drifted.

    A Windows checkout stores the file as CRLF while the canonical form is LF, so a byte comparison
    would report drift on every consumer forever and make `--check` useless.
    """
    return data.replace(b'\r\n', b'\n')


def derive_repository(root: Path, artifact_commit: str) -> str:
    """The raw-content base URL a consumer fetches the artifact tree from."""
    remote = subprocess.check_output(
        ['git', '-C', str(root), 'remote', 'get-url', 'origin'], text=True, encoding='utf-8').strip()
    match = re.search(r'github\.com[:/](?P<owner>[^/]+)/(?P<repo>[^/]+?)(?:\.git)?$', remote)
    if not match:
        raise ValueError(f'Cannot derive a raw-content repository from origin {remote!r}')
    return (f'https://raw.githubusercontent.com/{match.group("owner")}/{match.group("repo")}'
            f'/{artifact_commit}/')


def consumer_checkouts(consumer_root: Path, names: list[str]) -> list[Path]:
    """Every checkout under `consumer_root` that already pins a Core artifact."""
    if not consumer_root.is_dir():
        raise ValueError(f'Consumer root is not a directory: {consumer_root}')
    if names:
        candidates = [consumer_root / name for name in names]
    else:
        candidates = sorted(path for path in consumer_root.iterdir() if path.is_dir())
    pinning = [path for path in candidates if (path / PIN_FILE).is_file()]
    if not pinning:
        raise ValueError(f'No consumer checkout under {consumer_root} pins {PIN_FILE}')
    return pinning


def main() -> int:
    parser = argparse.ArgumentParser(description='Pin consumers at one packaged Core manifest.')
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--revision', required=True, help='Source revision the manifest was packaged from')
    parser.add_argument('--artifact-commit', required=True, help='Commit whose tree holds the artifact repository')
    parser.add_argument('--repository', help='Raw-content base URL; derived from origin when omitted')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--consumer-root', type=Path, help='Directory holding the consumer checkouts')
    parser.add_argument('--repo', action='append', default=[], help='Consumer checkout name; repeat as needed')
    parser.add_argument('--apply', action='store_true', help='Write the pin instead of reporting it')
    parser.add_argument('--check', action='store_true', help='Exit non-zero when any consumer has drifted')
    args = parser.parse_args()

    if not SHA40.fullmatch(args.revision):
        raise SystemExit('--revision must be a full 40-hex source revision')
    manifest_bytes = args.manifest.read_bytes()
    manifest = json.loads(manifest_bytes)
    repository = args.repository or derive_repository(args.root, args.artifact_commit)
    if not repository.endswith('/'):
        repository += '/'
    pin = pin_from_manifest(manifest, manifest_bytes, args.revision, args.artifact_commit, repository)
    payload = render(pin)

    consumer_root = args.consumer_root or args.root.parent
    drifted = []
    for checkout in consumer_checkouts(consumer_root, args.repo):
        target = checkout / PIN_FILE
        current = target.read_bytes()
        changed = semantic(current) != semantic(payload)
        if changed:
            drifted.append(checkout.name)
        state = 'updated' if changed and args.apply else ('drifted' if changed else 'current')
        print(f'{checkout.name}: {state} (version {json.loads(current).get("version")} -> {pin["version"]})')
        if changed and args.apply:
            target.write_bytes(payload)

    print(f'version {pin["version"]}, artifact {args.artifact_commit[:12]}, '
          f'manifest {pin["manifestSha256"][:12]}, {len(pin["sha256"])} modules')
    if args.check and drifted and not args.apply:
        print('drift: ' + ', '.join(drifted), file=sys.stderr)
        return 1
    if drifted and not args.apply:
        print('dry run: pass --apply to write')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
