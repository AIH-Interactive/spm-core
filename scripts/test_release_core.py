"""A release commit must carry exactly the packaged tree, parented on the previous release."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import release_core


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(['git', '-C', str(root), *args], text=True,
                                   encoding='utf-8').strip()


def init_source_repo(root: Path, version: str = '9.9.9') -> str:
    """A checkout that looks like the Core repository at the exact revision being released."""
    git(root, 'init', '-q', '.')
    git(root, 'config', 'user.email', 'release@example.invalid')
    git(root, 'config', 'user.name', 'Release Test')
    (root / '.gitignore').write_text('build/\n', encoding='utf-8')
    (root / 'gradle.properties').write_text(
        f'spmCoreVersion={version}\nspmCoreReleaseVersions=1.0.0,{version}\n', encoding='utf-8')
    (root / 'Source.java').write_text('// the source tree must never leak into a release commit\n',
                                      encoding='utf-8')
    git(root, 'add', '-A')
    git(root, 'commit', '-qm', 'source commit')
    return git(root, 'rev-parse', 'HEAD')


def make_dist(root: Path, revision: str, version: str = '9.9.9', payload: bytes = bytes(range(256))) -> Path:
    dist = root / 'build' / 'candidate-dist'
    jar = dist / 'dev' / 'spm' / 'core' / 'api' / version / f'api-{version}.jar'
    jar.parent.mkdir(parents=True)
    jar.write_bytes(payload)
    digest = release_core.sha256(payload)
    (dist / 'manifest.json').write_text(json.dumps({
        'schema': release_core.SCHEMA,
        'scope': 'synthetic',
        'status': 'PREVIEW',
        'source': {'repository': 'https://example.invalid/', 'revision': revision},
        'version': version,
        'group': 'dev.spm.core',
        'modules': ['api'],
        'files': [{'path': f'dev/spm/core/api/{version}/api-{version}.jar',
                   'bytes': len(payload), 'sha256': digest}],
    }, indent=2), encoding='utf-8')
    return dist


class VerifyDistTest(unittest.TestCase):
    def test_a_matching_distribution_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            manifest = release_core.verify_dist(dist, revision, '9.9.9')
            self.assertEqual(manifest['version'], '9.9.9')

    def test_a_tampered_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            (dist / 'dev/spm/core/api/9.9.9/api-9.9.9.jar').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'mismatch'):
                release_core.verify_dist(dist, revision, '9.9.9')

    def test_a_file_the_manifest_does_not_list_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            (dist / 'stowaway.txt').write_text('unaccounted', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'does not list'):
                release_core.verify_dist(dist, revision, '9.9.9')

    def test_a_version_that_disagrees_with_gradle_properties_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            with self.assertRaisesRegex(ValueError, 'not the declared'):
                release_core.verify_dist(dist, revision, '1.3.0')

    def test_the_declared_version_is_read_from_the_single_property(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            init_source_repo(root, version='2.0.0')
            self.assertEqual(release_core.version_from_properties(root), '2.0.0')


class ReleaseCommitTest(unittest.TestCase):
    def run_release(self, root: Path, dist: Path, revision: str, *extra: str) -> int:
        saved = sys.argv
        sys.argv = ['release_core.py', '--dist', str(dist), '--revision', revision,
                    '--root', str(root), *extra]
        try:
            return release_core.main()
        finally:
            sys.argv = saved

    def test_apply_creates_an_artifact_only_commit_on_the_previous_release(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            git(root, 'tag', 'maven-release-1.0.0', revision)
            dist = make_dist(root, revision)
            self.assertEqual(self.run_release(root, dist, revision, '--apply'), 0)

            commit = git(root, 'rev-parse', 'maven-release-9.9.9^{commit}')
            parent = git(root, 'rev-parse', 'maven-release-9.9.9^{commit}^')
            self.assertEqual(parent, revision)
            self.assertIn('Release spm-core 9.9.9', git(root, 'log', '-1', '--format=%s', commit))
            # The tree is the artifact repository alone: no source files, plus the byte-exact marker.
            tree = git(root, 'ls-tree', '-r', '--name-only', commit).splitlines()
            self.assertEqual(sorted(tree), ['.gitattributes',
                                            'dev/spm/core/api/9.9.9/api-9.9.9.jar',
                                            'manifest.json'])
            self.assertEqual(git(root, 'cat-file', '-s',
                                 f'{commit}:.gitattributes'), str(len(release_core.GITATTRIBUTES)))

    def test_the_artifact_bytes_survive_staging(self) -> None:
        """Line-ending conversion must not reach the tree, or every consumer hash would change."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            git(root, 'tag', 'maven-release-1.0.0', revision)
            dist = make_dist(root, revision)
            self.run_release(root, dist, revision, '--apply')
            blob = subprocess.check_output(
                ['git', '-C', str(root), 'cat-file', 'blob',
                 'maven-release-9.9.9:dev/spm/core/api/9.9.9/api-9.9.9.jar'])
            self.assertEqual(blob, bytes(range(256)))

    def test_an_existing_tag_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            git(root, 'tag', 'maven-release-9.9.9', revision)
            dist = make_dist(root, revision)
            with self.assertRaisesRegex(SystemExit, 'already exists'):
                self.run_release(root, dist, revision, '--apply')

    def test_a_dirty_checkout_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            (root / 'Source.java').write_text('// edited after the commit\n', encoding='utf-8')
            with self.assertRaisesRegex(SystemExit, 'dirty'):
                self.run_release(root, dist, revision, '--apply')

    def test_a_revision_that_is_not_head_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            revision = init_source_repo(root)
            dist = make_dist(root, revision)
            with self.assertRaisesRegex(SystemExit, 'not the revision being released'):
                self.run_release(root, dist, 'f' * 40, '--apply')


if __name__ == '__main__':
    unittest.main()
