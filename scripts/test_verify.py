#!/usr/bin/env python3
"""Control-flow tests of the real verify.sh with SYNTHETIC subprocess stubs.

No Gradle or Android execution occurs here. Policy/Gradle stubs record argv and
emit explicitly synthetic report/APK fixtures in a disposable local Git repo.
This harness supplements, never replaces, the actual final-candidate verifier.
Run: python3 scripts/test_verify.py

Device/emulator testing is allowed separately. The verifier has no device
surface, so this harness has no device or serial cases.
"""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
DEFAULT = ["testDebugUnitTest", "lintDebug", "assembleDebug", "--stacktrace"]
SCOPE_NOTE = "Device/emulator testing: allowed separately; not run by this verifier."
STUB = '''#!/usr/bin/env python3
# SYNTHETIC policy/Gradle subprocess, NOT build/test evidence.
import json
import os
from pathlib import Path
import sys

kind = "policy" if Path(sys.argv[0]).name == "ci-policy-check.sh" else "gradle"
with Path("build/calls.jsonl").open("a") as log:
    log.write(json.dumps([kind, *sys.argv[1:]]) + "\\n")
if kind == "policy":
    sys.exit(int(os.environ.get("SYNTHETIC_POLICY_EXIT", "0")))
if os.environ.get("SYNTHETIC_FAIL_TASK") in sys.argv[1:]:
    sys.exit(37)
'''


class VerifyControlFlowTest(unittest.TestCase):
    def setUp(self):
        # All filesystem work stays inside this repository's ignored build dir.
        (REPO / "build").mkdir(exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="verify-synthetic-", dir=REPO / "build")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "scripts").mkdir()
        (self.root / "build").mkdir()
        shutil.copy2(REPO / "scripts/verify.sh", self.root / "scripts/verify.sh")
        for path in ["scripts/ci-policy-check.sh", "gradlew"]:
            stub = self.root / path
            stub.write_text(STUB, encoding="utf-8")
            stub.chmod(0o755)
        (self.root / ".gitignore").write_text("build/\napp/build/\n", encoding="utf-8")
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("GIT_", "SYNTHETIC_"))}
        self.env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull)
        self.git("init", "-q")
        self.git("config", "user.name", "Synthetic verifier test")
        self.git("config", "user.email", "synthetic@example.invalid")
        self.git("add", ".")
        self.git("commit", "-qm", "Synthetic verifier fixture")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, env=self.env,
                              text=True, capture_output=True, check=True).stdout.strip()

    def run_verify(self, *args, **env):
        result = subprocess.run(["bash", "scripts/verify.sh", *args], cwd=self.root,
                                env={**self.env, **env}, text=True, capture_output=True)
        log = self.root / "build/calls.jsonl"
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return result, calls

    def test_default_runs_policy_then_one_gradle_with_three_tasks(self):
        result, calls = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [["policy"], ["gradle", *DEFAULT]])
        self.assertIn("Verification PASSED", result.stdout)
        self.assertIn("JUnit summary: unavailable", result.stdout)
        self.assertIn("Lint summary: unavailable", result.stdout)

    def test_default_run_ends_with_scope_note_on_success(self):
        result, calls = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [["policy"], ["gradle", *DEFAULT]])
        stdout_lines = [line for line in result.stdout.splitlines() if line.strip()]
        self.assertEqual(stdout_lines[-1], SCOPE_NOTE)
        self.assertIn("Verification PASSED", result.stdout)
        self.assertNotIn("Connected device tests", result.stdout)

    def assert_failed(self, result, code):
        self.assertEqual(result.returncode, code, result.stdout + result.stderr)
        self.assertNotIn("Verification PASSED", result.stdout)
        self.assertNotIn("Final-candidate debug APK provenance", result.stdout)
        self.assertNotIn(SCOPE_NOTE, result.stdout)

    def test_policy_failure_prevents_gradle(self):
        result, calls = self.run_verify(SYNTHETIC_POLICY_EXIT="23")
        self.assert_failed(result, 23)
        self.assertEqual(calls, [["policy"]])

    def test_each_default_task_failure_stops_before_provenance(self):
        for task in DEFAULT[:-1]:
            with self.subTest(task=task):
                (self.root / "build/calls.jsonl").unlink(missing_ok=True)
                result, calls = self.run_verify(
                    "--final-candidate", SYNTHETIC_FAIL_TASK=task)
                self.assert_failed(result, 37)
                self.assertEqual(calls, [["policy"], ["gradle", *DEFAULT]])

    def test_unknown_argument_is_rejected_before_policy(self):
        # Includes the retired --device flag: the verifier has no device
        # surface, so it is now a plain unknown argument that must not reach
        # policy or Gradle.
        for argument in ["--not-a-mode", "-x", "--final-candidate=yes", "--device"]:
            with self.subTest(argument=argument):
                result, calls = self.run_verify(argument)
                self.assert_failed(result, 2)
                self.assertIn(f"unknown argument: {argument}", result.stderr)
                self.assertIn("Usage:", result.stderr)
                self.assertEqual(calls, [])

    def test_help_does_not_run_gate(self):
        result, calls = self.run_verify("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("Usage:", result.stdout)
        self.assertNotIn("connectedDebugAndroidTest", result.stdout)
        self.assertEqual(calls, [])

    def test_final_candidate_rejects_untracked_file(self):
        (self.root / "synthetic-untracked").touch()
        result, calls = self.run_verify("--final-candidate")
        self.assert_failed(result, 1)
        self.assertIn("rejects untracked files", result.stderr)
        self.assertEqual(calls, [])

    def test_final_candidate_rejects_unstaged_and_staged_changes(self):
        with (self.root / ".gitignore").open("a") as out:
            out.write("# synthetic edit\n")
        for staged in [False, True]:
            with self.subTest(staged=staged):
                if staged:
                    self.git("add", ".gitignore")
                result, calls = self.run_verify("--final-candidate")
                self.assert_failed(result, 1)
                self.assertIn("no staged or unstaged tracked changes", result.stderr)
                self.assertEqual(calls, [])

    def fixture(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        return target

    def test_synthetic_report_summaries_are_preserved(self):
        self.fixture("app/build/test-results/testDebugUnitTest/TEST-synthetic.xml",
                     '<testsuite tests="7" failures="0" errors="0" skipped="2"/>')
        self.fixture("app/build/reports/lint-results-debug.xml",
                     '<issues><issue severity="Warning"/><issue severity="Warning"/></issues>')
        result, _ = self.run_verify()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("JUnit summary: tests=7 failures=0 errors=0 skipped=2 suites=1", result.stdout)
        self.assertIn("Lint summary: total=2 warning=2", result.stdout)
        self.assertIn("JUnit report: app/build/reports/tests/testDebugUnitTest/index.html", result.stdout)
        self.assertIn("Lint reports: app/build/reports/lint-results-debug.xml", result.stdout)

    def test_final_candidate_emits_synthetic_artifact_identity(self):
        apk = self.fixture("app/build/outputs/apk/debug/synthetic.apk", "NOT AN APK: synthetic fixture\n")
        self.fixture("app/build/outputs/apk/debug/output-metadata.json", json.dumps({
            "applicationId": "invalid.synthetic.debug", "variantName": "debug",
            "elements": [{"outputFile": "synthetic.apk", "versionCode": 7,
                          "versionName": "synthetic-only"}]}))
        result, calls = self.run_verify("--final-candidate")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [["policy"], ["gradle", *DEFAULT]])
        for expected in [f"Git HEAD: {self.git('rev-parse', 'HEAD')}",
                         f"Git tree: {self.git('rev-parse', 'HEAD^{tree}')}",
                         f"SHA-256: {hashlib.sha256(apk.read_bytes()).hexdigest()}",
                         f"Size bytes: {apk.stat().st_size}",
                         "Package: invalid.synthetic.debug", "Variant: debug",
                         "Version code: 7", "Version name: synthetic-only"]:
            self.assertIn(expected, result.stdout)
        self.assertIn("Verification PASSED", result.stdout)
        self.assertEqual([line for line in result.stdout.splitlines() if line.strip()][-1],
                         SCOPE_NOTE)

    def test_final_candidate_missing_metadata_fails(self):
        result, calls = self.run_verify("--final-candidate")
        self.assert_failed(result, 1)
        self.assertIn("debug APK metadata not found", result.stderr)
        self.assertEqual(calls, [["policy"], ["gradle", *DEFAULT]])

    def test_verifier_source_has_no_connected_device_wiring(self):
        # Supplementary source-text guard; behavior above is authoritative.
        # Separate device execution is allowed, but is not wired into this gate.
        source = (REPO / "scripts/verify.sh").read_text(encoding="utf-8")
        for forbidden in ["device_mode", "connectedDebugAndroidTest",
                          "assembleDebugAndroidTest", "ANDROID_SERIAL"]:
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, source)
        self.assertIn("testDebugUnitTest lintDebug assembleDebug --stacktrace", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
