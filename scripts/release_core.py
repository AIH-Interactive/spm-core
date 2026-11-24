"""Freeze a packaged candidate distribution into a tagged, immutable release commit.

A Core release is not a branch: it is one commit whose tree is *only* the artifact repository, whose
parent is the previous release commit, and which is reachable through a `maven-release-<version>`
tag. The `1.0.0` and `1.1.0` releases are exactly that — `56c4ff5` and then `0e7b189` on top of it —
and consumers pin the release commit by SHA, fetching the tree over raw content.

That shape is easy to get subtly wrong: a release commit that accidentally inherits the source tree,
a tree staged with the wrong line endings so every jar's hash changes, a version that disagrees with
`gradle.properties`. So this script verifies the distribution against the manifest it ships before
it writes anything, and refuses to run on a checkout that is not the exact revision being released.

It does **not** build or package. The steps before it are the existing ones:

    ./gradlew clean bundleArtifacts publishCandidate -PspmCoreVersion=<version>
    python scripts/package_candidate.py --revision <sha> --version <version> --native <target>=<path> ...

and the step after it is `scripts/pin_consumers.py`, which moves the consumers onto the new release.

Usage:

    python scripts/release_core.py --dist build/candidate-dist --revision <sha>          # plan
    python scripts/release_core.py --dist build/candidate-dist --revision <sha> --apply  # commit + tag

`--apply` never pushes. Publishing a tag is the human gate, and the script prints the exact command.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCHEMA = 'spm-core-candidate/1'
TAG_PREFIX = 'maven-release-'
SHA40 = re.compile(r'[0-9a-f]{40}')
SHA256 = re.compile(r'[0-9a-f]{64}')

# Files the release tree carries that the manifest deliberately does not list: the manifest cannot
# record its own digest, and this attribute file is what makes the stored bytes exact.
GITATTRIBUTES = (
    b'# The release tree is a byte-exact artifact repository: every file must be stored exactly as\n'
    b'# published, so a consumer\'s SHA-256 and the manifest\'s recorded hashes agree.\n'
    b'* -text'
)
UNLISTED = {'manifest.json', '.gitattributes'}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(root: Path, *args: str, env: dict[str, str] | None = None,
        config: tuple[str, ...] = ()) -> str:
    """Runs git in `root`.

    `config` is for the staging step only: disabling line-ending conversion while building the tree
    object is right, but applying it to `status` makes a checkout whose blobs are LF and whose files
    are CRLF look modified, which would refuse every release on Windows.
    """
    command = ['git', '-C', str(root)]
    for setting in config:
        command += ['-c', setting]
    command += list(args)
    return subprocess.check_output(command, text=True, encoding='utf-8', env=env).strip()


def version_from_properties(root: Path) -> str:
    """The one declared version, so a release cannot disagree with `gradle.properties`."""
    text = (root / 'gradle.properties').read_text(encoding='utf-8')
    match = re.search(r'^spmCoreVersion[ \t]*=[ \t]*(\S+)[ \t]*$', text, re.MULTILINE)
    if not match:
        raise ValueError('gradle.properties must declare spmCoreVersion')
    return match.group(1)


def verify_dist(dist: Path, revision: str, version: str) -> dict:
    """Checks the packaged tree against its own manifest, and refuses anything undocumented."""
    manifest_path = dist / 'manifest.json'
    if not manifest_path.is_file():
        raise ValueError(f'No packaged manifest at {manifest_path}')
    manifest = json.loads(manifest_path.read_bytes())
    if manifest.get('schema') != SCHEMA:
        raise ValueError(f'Unsupported manifest schema: {manifest.get("schema")!r}')
    if (manifest.get('source') or {}).get('revision') != revision:
        raise ValueError('The distribution was packaged from a different source revision')
    if manifest.get('version') != version:
        raise ValueError(f'Distribution version {manifest.get("version")!r} is not the declared {version!r}')

    records = manifest.get('files')
    if not isinstance(records, list) or not records:
        raise ValueError('Manifest lists no files')

    listed = set()
    for record in records:
        try:
            relative = Path(record['path'])
            expected_bytes = int(record['bytes'])
            expected_sha = record['sha256']
        except (TypeError, KeyError, ValueError) as error:
            raise ValueError('Malformed manifest file record') from error
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError(f'Unsafe manifest path: {record["path"]}')
        if not SHA256.fullmatch(str(expected_sha)):
            raise ValueError(f'Malformed digest for {record["path"]}')
        listed.add(relative.as_posix())
        target = dist / relative
        if not target.is_file():
            raise ValueError(f'Manifest lists a missing file: {record["path"]}')
        data = target.read_bytes()
        if len(data) != expected_bytes:
            raise ValueError(f'Size mismatch for {record["path"]}')
        if sha256(data) != expected_sha:
            raise ValueError(f'Digest mismatch for {record["path"]}')

    extra = sorted(
        path.relative_to(dist).as_posix()
        for path in dist.rglob('*')
        if path.is_file()
        and path.relative_to(dist).as_posix() not in listed
        and path.relative_to(dist).as_posix() not in UNLISTED
    )
    if extra:
        raise ValueError('Distribution carries files the manifest does not list: ' + ', '.join(extra[:5]))
    return manifest


def latest_release(root: Path) -> tuple[str, str]:
    """The previous release commit and its tag, which the new release commit is parented on."""
    tags = [line for line in git(root, 'tag', '-l', TAG_PREFIX + '*').splitlines() if line]
    if not tags:
        raise ValueError(f'No {TAG_PREFIX}* tag to continue the release chain from')

    def version_key(tag: str) -> tuple[int, ...]:
        parts = tag[len(TAG_PREFIX):].split('.')
        try:
            return tuple(int(part) for part in parts)
        except ValueError as error:
            raise ValueError(f'Release tag is not a numeric version: {tag}') from error

    tag = max(tags, key=version_key)
    return git(root, 'rev-parse', f'{tag}^{{commit}}'), tag


def stage_tree(root: Path, dist: Path) -> str:
    """Writes the distribution into a fresh tree object, byte for byte."""
    attributes = dist / '.gitattributes'
    if attributes.is_file():
        if attributes.read_bytes() != GITATTRIBUTES:
            raise ValueError('The distribution carries a .gitattributes that is not the release one')
    else:
        attributes.write_bytes(GITATTRIBUTES)

    handle, index = tempfile.mkstemp(suffix='.index')
    os.close(handle)
    os.unlink(index)  # git creates the index; a zero-length file would be rejected
    env = dict(os.environ, GIT_INDEX_FILE=index, GIT_WORK_TREE=str(dist))
    # Belt and braces: the distribution carries `.gitattributes: * -text` so nothing should be
    # converted, and these settings make sure of it even if that file is ever lost.
    no_conversion = ('core.autocrlf=false', 'core.safecrlf=false')
    try:
        git(root, 'add', '-A', '-f', env=env, config=no_conversion)
        return git(root, 'write-tree', env=env, config=no_conversion)
    finally:
        Path(index).unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description='Tag a packaged Core distribution as a release commit.')
    parser.add_argument('--dist', type=Path, default=None, help='Packaged tree; defaults to build/candidate-dist')
    parser.add_argument('--revision', required=True, help='Exact 40-hex source revision being released')
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--parent', help='Release commit to build on; defaults to the newest release tag')
    parser.add_argument('--tag', help='Tag name; defaults to maven-release-<version>')
    parser.add_argument('--apply', action='store_true', help='Create the commit and tag instead of planning')
    args = parser.parse_args()

    root = args.root.resolve()
    dist = (args.dist or root / 'build' / 'candidate-dist').resolve()
    if not SHA40.fullmatch(args.revision):
        raise SystemExit('--revision must be a full 40-hex source revision')

    head = git(root, 'rev-parse', 'HEAD')
    if head != args.revision:
        raise SystemExit(f'HEAD is {head}, which is not the revision being released ({args.revision})')
    if git(root, 'status', '--porcelain=v1'):
        raise SystemExit('The checkout is dirty; a release tree must come from an exact revision')

    version = version_from_properties(root)
    manifest = verify_dist(dist, args.revision, version)
    tag = args.tag or TAG_PREFIX + version
    if git(root, 'tag', '-l', tag):
        raise SystemExit(f'Tag {tag} already exists')
    parent, parent_tag = (args.parent, args.parent) if args.parent else latest_release(root)
    parent = git(root, 'rev-parse', f'{parent}^{{commit}}')

    files = len(manifest['files'])
    print(f'version {version}, source {args.revision[:12]}, {files} files verified against the manifest')
    print(f'parent {parent[:12]} ({parent_tag}), tag {tag}')
    if not args.apply:
        print('dry run: pass --apply to create the release commit and tag')
        return 0

    tree = stage_tree(root, dist)
    message = f'Release spm-core {version} (source {args.revision}) [skip ci]'
    commit = git(root, 'commit-tree', tree, '-p', parent, '-m', message)
    git(root, 'tag', tag, commit)
    print(f'created {commit[:12]} on {tree[:12]}')
    print('next:')
    print(f'  git push origin {tag}')
    print(f'  python scripts/pin_consumers.py --manifest {dist / "manifest.json"} '
          f'--revision {args.revision} --artifact-commit {commit} --apply')
    return 0


if __name__ == '__main__':
    sys.exit(main())
