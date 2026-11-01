import json
from pathlib import Path
import tempfile
import unittest

from ci_core import candidate_inputs, diagnostics, validate_checkout, validate_java_evidence
from snapshot_core import build_input_snapshot


class CandidateChecksTest(unittest.TestCase):
    def test_failure_diagnostics_preserve_failed_audit_without_collecting_private_models(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / '.gradle/core-ci'
            artifact = output / 'artifact'
            artifact.mkdir(parents=True)
            (output / 'logs').mkdir()
            (output / 'logs/core-audit.log').write_text('audit failed', encoding='utf-8')
            audit = dict(errors=['missing product jar'])
            (output / 'core-audit.json').write_text(json.dumps(audit), encoding='utf-8')
            (root / 'private').mkdir()
            (root / 'private/model.ysm').write_bytes(b'hand-authored exclusion sentinel')
            diagnostics(root, output, artifact)
            self.assertTrue((artifact / 'core-audit.json').is_file(), 'failed audit report must remain reviewable')
            self.assertEqual(audit, json.loads((artifact / 'core-audit.json').read_text(encoding='utf-8')))
            self.assertEqual('audit failed', (artifact / 'logs/core-audit.log').read_text(encoding='utf-8'))
            self.assertFalse((artifact / 'private').exists())

    def test_exact_checkout_rejects_wrong_revision_dirty_source_and_non_sha_input(self):
        revision = 'a' * 40
        validate_checkout(revision, revision, '')
        for expected, actual, dirty in [
                ('b' * 40, revision, ''), (revision, revision, ' M api/A.java'),
                ('main', revision, ''), (revision, 'HEAD', '')]:
            with self.assertRaises(ValueError):
                validate_checkout(expected, actual, dirty)

    def test_stale_missing_or_incomplete_candidate_products_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / 'api'
            module.mkdir()
            (module / 'build.gradle').write_text('dependencies {}', encoding='utf-8')
            (root / 'build.gradle').write_text('allprojects {}', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'binary|jar'):
                candidate_inputs(root)
            libs = module / 'build/libs'
            libs.mkdir(parents=True)
            for name in ['api-1.jar', 'api-1-sources.jar', 'api-1-javadoc.jar']:
                (libs / name).write_bytes(b'checked by the separate mandatory ZIP/bytecode audit')
            pom = module / 'build/publications/mavenJava/pom-default.xml'
            pom.parent.mkdir(parents=True)
            pom.write_text('<project/>', encoding='utf-8')
            metadata = module / 'build/metadata/dependencies.json'
            metadata.parent.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, 'inventory|metadata'):
                candidate_inputs(root)
            inventory = dict(project='api', dependencies=[], buildInputs=build_input_snapshot(root))
            metadata.write_text(json.dumps(inventory), encoding='utf-8')
            self.assertEqual(5, len(candidate_inputs(root)))
            (root / 'build.gradle').write_text('changed', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'stale|unattested'):
                candidate_inputs(root)

    def test_actual_launcher_compiler_and_reports_are_required_not_only_bytecode_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'api/src/test/java/example/ActualTest.java'
            source.parent.mkdir(parents=True)
            source.write_text('package example; class ActualTest {}', encoding='utf-8')
            report = root / 'api/build/test-results/test/TEST-example.ActualTest.xml'
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite name="example.ActualTest" tests="1" failures="0" errors="0" skipped="0">'
                              '<testcase name="runs"/></testsuite>', encoding='utf-8')
            compile_record = dict(module='api', task='compileTestJava', java=25, release=17, werror=True, xlint=True)
            test_record = dict(module='api', java=17)
            log = 'SPM_CI_COMPILE=' + json.dumps(compile_record) + '\nSPM_CI_TEST=' + json.dumps(test_record)
            result = validate_java_evidence(root, log, 17, False)
            self.assertEqual(1, result['passed'])
            for broken in ['', log.replace('"java": 17', '"java": 25'),
                           log.replace('"werror": true', '"werror": false')]:
                with self.assertRaises(ValueError):
                    validate_java_evidence(root, broken, 17, False)
            report.unlink()
            with self.assertRaisesRegex(ValueError, 'report'):
                validate_java_evidence(root, log, 17, False)

    def test_native_skips_failures_and_missing_suite_cases_cannot_masquerade_as_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'integration-tests/src/test/java/example/NativeComputeProviderTest.java'
            source.parent.mkdir(parents=True)
            source.write_text('package example; class NativeComputeProviderTest {}', encoding='utf-8')
            report = root / 'integration-tests/build/test-results/test/TEST-example.NativeComputeProviderTest.xml'
            report.parent.mkdir(parents=True)
            compiler = dict(module='integration-tests', task='compileTestJava', java=25, release=17, werror=True, xlint=True)
            log = 'SPM_CI_COMPILE=' + json.dumps(compiler) + '\nSPM_CI_TEST=' + json.dumps(dict(module='integration-tests', java=25))
            for contents in [
                    '<testsuite name="example.NativeComputeProviderTest" tests="1" failures="0" errors="0" skipped="1"><testcase><skipped/></testcase></testsuite>',
                    '<testsuite name="example.NativeComputeProviderTest" tests="1" failures="1" errors="0" skipped="0"><testcase><failure/></testcase></testsuite>',
                    '<testsuite name="example.NativeComputeProviderTest" tests="1" failures="0" errors="0" skipped="0"/>']:
                report.write_text(contents, encoding='utf-8')
                with self.assertRaises(ValueError):
                    validate_java_evidence(root, log, 25, True)


if __name__ == '__main__':
    unittest.main()
