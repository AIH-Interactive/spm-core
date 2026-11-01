import tempfile
import unittest
import zipfile
from pathlib import Path
from audit_core import audit

class ArtifactGateTest(unittest.TestCase):
    def test_rejects_api_leak_and_old_runtime_constant(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            source=root/'api/src/main/java/dev/spm/core/api/Leak.java'
            source.parent.mkdir(parents=True)
            source.write_text('import org.joml.Matrix4f;\nclass Leak {}',encoding='utf-8')
            jar=root/'api/build/libs/api.jar';jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar,'w') as out:
                out.writestr('Leak.class',bytes.fromhex('cafebabe0000003d')+b'com/ysm/parser/Old')
            errors=audit(root)['errors']
            self.assertTrue(any('non-JDK' in x for x in errors))
            self.assertTrue(any('old-runtime constant' in x for x in errors))
            self.assertTrue(any('missing bundled license' in x for x in errors))

    def test_accepts_java17_header_and_notices_but_requires_each_product_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);jar=root/'api/build/libs/api.jar';jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar,'w') as out:
                out.writestr('Okay.class',bytes.fromhex('cafebabe0000003d'))
                out.writestr('META-INF/spm-core/LICENSE','Apache-2.0')
                out.writestr('META-INF/spm-core/NOTICE','SPM Core')
            self.assertEqual([],audit(root)['errors'])
            self.assertTrue(any('runtime jar missing' in x for x in audit(root,True)['errors']))

if __name__=='__main__':unittest.main()
