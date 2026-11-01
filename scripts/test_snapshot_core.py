import csv
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from snapshot_core import source_inventory, verify_snapshot


class SnapshotIntegrityTest(unittest.TestCase):
    def test_all_inputs_are_covered_and_build_outputs_are_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'api/src/main').mkdir(parents=True)
            (root / 'api/src/main/A.java').write_text('class A {}', encoding='utf-8')
            (root / 'api/build').mkdir()
            (root / 'api/build/A.class').write_bytes(b'generated')
            (root / 'gradle/wrapper').mkdir(parents=True)
            (root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'wrapper')
            rows = source_inventory(root)
            self.assertEqual(['api/src/main/A.java', 'gradle/wrapper/gradle-wrapper.jar'], [r['path'] for r in rows])
            self.assertTrue(all(r['review_status'] == 'Q' for r in rows))

    def test_missing_changed_and_new_files_cannot_pass_a_frozen_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'core'; root.mkdir()
            evidence = Path(directory) / 'evidence'; evidence.mkdir()
            (root / 'a.txt').write_text('first', encoding='utf-8')
            rows = source_inventory(root)
            with (evidence / 'origin-ledger.csv').open('w', newline='', encoding='utf-8') as output:
                writer = csv.DictWriter(output, fieldnames=list(rows[0])); writer.writeheader(); writer.writerows(rows)
            (evidence / 'fixtures.json').write_text(json.dumps({'fixtures': []}), encoding='utf-8')
            self.assertEqual([], verify_snapshot(root, evidence))
            (root / 'a.txt').write_text('changed', encoding='utf-8')
            self.assertTrue(any('changed' in e for e in verify_snapshot(root, evidence)))
            (root / 'a.txt').unlink(); (root / 'b.txt').write_text('new', encoding='utf-8')
            errors = verify_snapshot(root, evidence)
            self.assertTrue(any('missing' in e for e in errors))
            self.assertTrue(any('unrecorded' in e for e in errors))

    def test_linked_source_directory_is_rejected_instead_of_silently_omitted(self):
        with tempfile.TemporaryDirectory() as directory:
            container = Path(directory); root = container / 'core'; root.mkdir()
            target = container / 'outside'; target.mkdir()
            (target / 'Hidden.java').write_text('class Hidden {}', encoding='utf-8')
            link = root / 'source-link'
            if os.name == 'nt':
                # Directory junctions exercise the actual Windows reparse path without admin rights.
                subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(target)], check=True, capture_output=True)
            else:
                os.symlink(target, link, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'linked|reparse'):
                source_inventory(root)

    def test_dependency_inventory_is_bound_to_current_build_inputs(self):
        from snapshot_core import build_input_snapshot, validate_dependency_inventory
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'api').mkdir(); (root / 'api/build.gradle').write_text('java {}', encoding='utf-8')
            build = root / 'build.gradle'; build.write_text('version=1', encoding='utf-8')
            inventory = [dict(project='api', dependencies=[], buildInputs=build_input_snapshot(root))]
            self.assertEqual([], validate_dependency_inventory(root, inventory))
            build.write_text('version=2', encoding='utf-8')
            self.assertTrue(any('stale' in e for e in validate_dependency_inventory(root, inventory)))
            inventory[0]['buildInputs'] = build_input_snapshot(root)
            (root / 'new').mkdir(); (root / 'new/build.gradle').write_text('java {}', encoding='utf-8')
            self.assertTrue(any('coverage' in e for e in validate_dependency_inventory(root, inventory)))


if __name__ == '__main__':
    unittest.main()
