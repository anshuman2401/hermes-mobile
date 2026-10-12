#!/usr/bin/env python3
"""Synthetic tests for inventory drift, asset tampering and APK packaging checks."""
import hashlib
import json
from pathlib import Path
import tempfile
import subprocess
import sys
import unittest
import zipfile
from check_release_licenses import verify


class ReleaseLicenseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifact = self.root / 'synthetic.jar'
        self.artifact.write_bytes(b'synthetic artifact')
        self.notice = self.root / 'NOTICE.txt'
        self.notice.write_bytes(b'synthetic notice')
        self.inputs = [{'coordinate': 'test:synthetic:1', 'configuration': 'releaseRuntimeClasspath', 'file': str(self.artifact)}]
        inventory = {'artifacts': [{'coordinate': 'test:synthetic:1', 'configuration': 'releaseRuntimeClasspath',
                                  'sha256': hashlib.sha256(self.artifact.read_bytes()).hexdigest(), 'notice_files': ['NOTICE.txt']}],
                     'texts': [{'file': 'NOTICE.txt', 'sha256': hashlib.sha256(self.notice.read_bytes()).hexdigest()}]}
        (self.root / 'release-inventory.json').write_text(json.dumps(inventory))

    def test_valid_inventory_and_duplicate_resolution(self):
        self.assertEqual(verify(self.inputs * 2, self.root), (1, 1))

    def test_dependency_drift_fails(self):
        for inputs in ([], self.inputs + [{'coordinate': 'test:extra:1', 'configuration': 'releaseRuntimeClasspath', 'file': str(self.artifact)}]):
            with self.assertRaises(ValueError):
                verify(inputs, self.root)
        self.artifact.write_bytes(b'changed artifact')
        with self.assertRaises(ValueError):
            verify(self.inputs, self.root)

    def test_notice_tampering_fails(self):
        self.notice.write_bytes(b'changed notice')
        with self.assertRaises(ValueError):
            verify(self.inputs, self.root)

    def test_apk_assets_checked(self):
        # Keep APK outside the assets directory.
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'synthetic.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                for file in self.root.iterdir():
                    archive.write(file, 'assets/licenses/' + file.name)
            self.assertEqual(verify(self.inputs, self.root, apk), (1, 1))
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('assets/licenses/NOTICE.txt', b'wrong')
            with self.assertRaises((ValueError, KeyError)):
                verify(self.inputs, self.root, apk)


class SourceBundleFailureTest(unittest.TestCase):
    def test_wrong_source_hash_does_not_create_bundle(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            archive = root / 'synthetic-source.tar.gz'
            archive.write_bytes(b'wrong source')
            output = root / 'must-not-exist.zip'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_release_sources.py')),
                                     '--desugar-archive', str(archive), '--output', str(output)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertFalse(output.exists())
            self.assertIn('hash mismatch', result.stderr)
            self.assertNotIn(folder, result.stderr)

    def test_missing_source_does_not_expose_paths(self):
        with tempfile.TemporaryDirectory() as folder:
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_release_sources.py')),
                                     '--desugar-archive', str(Path(folder) / 'missing')],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertNotIn(folder, result.stderr)


if __name__ == '__main__':
    unittest.main()
