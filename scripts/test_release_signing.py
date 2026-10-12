#!/usr/bin/env python3
"""Exercise the release task gate with synthetic settings; never create a key.

Run under the repository's shared Gradle lock with its JDK/SDK prerequisites.
The incomplete release build must stop at its pre-build gate. No key is
created and this does not verify a real signing certificate.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FIELDS = ("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")


class ReleaseSigningGateTest(unittest.TestCase):
    def invoke(self, task, settings):
        # Explicit project properties override any developer environment or
        # user-level signing properties. Output stays in memory, never printed.
        command = [str(ROOT / "gradlew"), task, "--configuration-cache", "--console=plain"]
        command += [f"-P{key}={settings.get(key, '')}" for key in FIELDS]
        try:
            return subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, timeout=300)
        except (subprocess.TimeoutExpired, OSError):
            self.fail("Signing check could not complete; no subprocess details retained")

    def test_cached_release_task_still_rejects_missing_configuration(self):
        self.invoke(":app:assembleRelease", {})
        result = self.invoke(":app:assembleRelease", {})
        self.assertNotEqual(0, result.returncode)
        self.assertTrue("Reusing configuration cache" in result.stdout, "Expected configuration cache reuse")
        self.assertTrue("Release signing requires:" in result.stdout, "Cached gate did not reject release")

    def test_release_rejects_missing_configuration(self):
        result = self.invoke(":app:assembleRelease", {})
        self.assertNotEqual(0, result.returncode)
        self.assertTrue("Release signing requires: RELEASE_STORE_FILE" in result.stdout, "Missing-settings gate did not reject release")

    def test_release_rejects_incomplete_configuration(self):
        result = self.invoke(":app:preReleaseBuild", {"RELEASE_KEY_ALIAS": "synthetic-alias"})
        self.assertNotEqual(0, result.returncode)
        self.assertTrue("Release signing requires:" in result.stdout, "Incomplete-settings gate did not reject release")
        self.assertTrue("RELEASE_STORE_PASSWORD" in result.stdout, "Missing field name not reported")

    def test_release_rejects_nonexistent_keystore(self):
        (ROOT / "build").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / "build", prefix="signing-gate-") as directory:
            result = self.invoke(":app:preReleaseBuild", {
                "RELEASE_STORE_FILE": str(Path(directory) / "missing.jks"),
                "RELEASE_STORE_PASSWORD": "synthetic-password",
                "RELEASE_KEY_ALIAS": "synthetic-alias",
                "RELEASE_KEY_PASSWORD": "synthetic-password",
            })
        self.assertNotEqual(0, result.returncode)
        self.assertTrue("Release signing requires an existing keystore file" in result.stdout, "Missing-keystore gate did not reject release")

    def test_debug_prebuild_needs_no_release_configuration(self):
        result = self.invoke(":app:preDebugBuild", {})
        self.assertEqual(0, result.returncode, "Debug pre-build unexpectedly requires release configuration")


if __name__ == "__main__":
    unittest.main(verbosity=2)
