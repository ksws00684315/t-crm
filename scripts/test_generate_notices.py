"""Offline regression checks for shared license and copyright text."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'scripts/generate-notices.py'
APACHE = (ROOT / 'config/licenses/originals/Apache-2.0.txt').read_text()


class NoticesTest(unittest.TestCase):
    def generate(self, licenses, notices):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            originals = root / 'config/licenses/originals'
            originals.mkdir(parents=True)
            (originals / 'Apache-2.0.txt').write_text(APACHE)
            (originals.parent / 'originals.json').write_text('{}')
            engine = root / 'backend'
            target = engine / 'target/generated-sources/license'
            target.mkdir(parents=True)
            jars = []
            inventory = []
            for index, (license_text, notice) in enumerate(zip(licenses, notices)):
                name = f'example-{index}'
                jar = root / f'{name}-1.0.jar'
                with zipfile.ZipFile(jar, 'w') as archive:
                    archive.writestr('META-INF/LICENSE', license_text)
                    archive.writestr('META-INF/NOTICE', notice)
                jars.append(str(jar))
                inventory.append(f'(Apache-2.0) {name} (example:{name}:1.0 - https://example.invalid)')
            (target / 'THIRD-PARTY.txt').write_text('\n'.join(inventory))
            (engine / 'target/runtime-classpath.txt').write_text(os.pathsep.join(jars))
            subprocess.run([sys.executable, str(SCRIPT), str(engine)], check=True, capture_output=True)
            return (engine / 'THIRD-PARTY-NOTICES').read_text()

    def test_shouldShareLicenseAndNotices_whenArtifactsHaveIdenticalTexts(self):
        output = self.generate([APACHE, APACHE], ['Copyright Shared Example', 'Copyright Shared Example'])
        self.assertEqual(output.count('TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION'), 1)
        self.assertEqual(output.count('Copyright Shared Example'), 1)
        self.assertIn('example:example-0:1.0', output)
        self.assertIn('example:example-1:1.0', output)

    def test_shouldRetainSeparateCopyrights_whenLicenseBodiesAreShared(self):
        output = self.generate(['Copyright Alpha\n\n' + APACHE, 'Copyright Beta\n\n' + APACHE], ['Alpha notice', 'Beta notice'])
        for expected in ['Copyright Alpha', 'Copyright Beta', 'Alpha notice', 'Beta notice']:
            self.assertIn(expected, output)
        self.assertEqual(output.count('TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION'), 1)

    def test_shouldRetainAcknowledgment_whenApache11NoticeIsShared(self):
        acknowledgment = 'This product includes software developed by the Apache Software Foundation (http://www.apache.org/).'
        output = self.generate([APACHE, APACHE], [acknowledgment, acknowledgment])
        self.assertEqual(output.count(acknowledgment), 1)


    def test_shouldReconstructCompleteOriginal_whenParagraphsAreShared(self):
        spec = importlib.util.spec_from_file_location('notices_generator', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        catalog = {}
        original = 'Copyright Alpha\n\nPermission must be retained.\n\nCopyright Alpha'
        references = module.register(original, catalog)
        self.assertEqual(len(catalog), 2)
        self.assertEqual(references[0], references[2])
        reconstructed = '\n\n'.join(catalog[identifier] for identifier in references)
        self.assertEqual(module.normalized_text(original), module.normalized_text(reconstructed))

    def test_shouldShareCommentedOriginal_whenOnlyLayoutDiffers(self):
        spec = importlib.util.spec_from_file_location('notices_generator', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        catalog = {}
        plain = 'Copyright Alpha\n\nPermission must be retained.'
        commented = '/* Copyright Alpha\r\n *\r\n * Permission must be retained.\r\n */'
        plain_ids = module.register(plain, catalog)
        commented_ids = module.register(commented, catalog)
        self.assertEqual(plain_ids, commented_ids)
        self.assertEqual(len(catalog), 2)


if __name__ == '__main__':
    unittest.main()
