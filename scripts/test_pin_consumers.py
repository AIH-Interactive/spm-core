"""The pin a consumer reads has to describe exactly one packaged tree."""
import json
import tempfile
import unittest
from pathlib import Path

import pin_consumers as pins


def manifest_bytes(modules, version='1.2.3', revision='a' * 40, group='dev.spm.core',
                   files=None) -> bytes:
    if files is None:
        files = []
        for module in modules:
            path = f'{group.replace(".", "/")}/{module}/{version}/{module}-{version}.jar'
            files.append({'path': path, 'bytes': 1, 'sha256': ('%064x' % len(files))})
    return json.dumps({
        'schema': pins.SCHEMA,
        'scope': 'synthetic',
        'status': 'PREVIEW',
        'source': {'repository': 'https://example.invalid/', 'revision': revision},
        'version': version,
        'group': group,
        'modules': list(modules),
        'files': files,
    }, indent=2).encode('utf-8')


class PinFromManifestTest(unittest.TestCase):
    def test_the_pin_records_every_module_jar_and_the_manifest_digest(self) -> None:
        data = manifest_bytes(['api', 'codec', 'runtime'])
        pin = pins.pin_from_manifest(json.loads(data), data, 'a' * 40, 'b' * 40,
                                     'https://example.invalid/artifact/')
        self.assertEqual(list(pin), ['sourceRevision', 'version', 'repository',
                                     'artifactCommit', 'manifestSha256', 'sha256'])
        self.assertEqual(pin['manifestSha256'], pins.sha256(data))
        self.assertEqual(sorted(pin['sha256']), ['api', 'codec', 'runtime'])
        # The recorded digest is the manifest's own value, not one recomputed from the file on disk.
        self.assertEqual(pin['sha256']['api'], '%064x' % 0)

    def test_a_module_without_a_binary_jar_is_rejected(self) -> None:
        data = manifest_bytes(['api', 'codec'])
        broken = json.loads(data)
        broken['modules'].append('runtime')
        with self.assertRaisesRegex(ValueError, 'no binary jar'):
            pins.pin_from_manifest(broken, data, 'a' * 40, 'b' * 40, 'https://example.invalid/')

    def test_a_manifest_from_another_revision_is_rejected(self) -> None:
        data = manifest_bytes(['api'])
        with self.assertRaisesRegex(ValueError, 'different source revision'):
            pins.pin_from_manifest(json.loads(data), data, 'c' * 40, 'b' * 40,
                                   'https://example.invalid/')

    def test_an_abbreviated_artifact_commit_is_rejected(self) -> None:
        data = manifest_bytes(['api'])
        with self.assertRaisesRegex(ValueError, '40-hex'):
            pins.pin_from_manifest(json.loads(data), data, 'a' * 40, 'b' * 12,
                                   'https://example.invalid/')

    def test_the_rendered_pin_is_stable_and_newline_terminated(self) -> None:
        data = manifest_bytes(['runtime', 'api'])
        pin = pins.pin_from_manifest(json.loads(data), data, 'a' * 40, 'b' * 40,
                                    'https://example.invalid/')
        rendered = pins.render(pin)
        self.assertTrue(rendered.endswith(b'\n'))
        self.assertEqual(json.loads(rendered), pin)
        # Hashes are sorted, so regenerating an unchanged pin is byte-identical.
        self.assertEqual(list(json.loads(rendered)['sha256']), ['api', 'runtime'])

    def test_a_crlf_checkout_does_not_look_like_drift(self) -> None:
        """A Windows checkout stores CRLF; that is not a version change."""
        data = manifest_bytes(['api'])
        pin = pins.pin_from_manifest(json.loads(data), data, 'a' * 40, 'b' * 40,
                                    'https://example.invalid/')
        lf = pins.render(pin)
        crlf = lf.replace(b'\n', b'\r\n')
        self.assertNotEqual(lf, crlf)
        self.assertEqual(pins.semantic(lf), pins.semantic(crlf))

    def test_consumer_discovery_finds_only_checkouts_that_pin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pinned' / 'gradle').mkdir(parents=True)
            (root / 'pinned' / pins.PIN_FILE).write_text('{}', encoding='utf-8')
            (root / 'unrelated').mkdir()
            self.assertEqual([path.name for path in pins.consumer_checkouts(root, [])], ['pinned'])
            with self.assertRaisesRegex(ValueError, 'No consumer checkout'):
                pins.consumer_checkouts(root, ['unrelated'])
            with self.assertRaisesRegex(ValueError, 'not a directory'):
                pins.consumer_checkouts(root / 'missing', [])


if __name__ == '__main__':
    unittest.main()
