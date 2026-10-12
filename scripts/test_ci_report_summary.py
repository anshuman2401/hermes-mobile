#!/usr/bin/env python3
"""Synthetic report tests; no Gradle, host or GitHub calls."""

from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
import ci_report_summary as summary


class ReportSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def junit(self, name, content):
        self.write(f"app/build/test-results/testDebugUnitTest/TEST-{name}.xml", content)

    def test_missing_reports_do_not_claim_pass(self):
        result = summary.render_summary(self.root, "failure", "skipped")
        self.assertIn("Verifier outcome: **failure**", result)
        self.assertIn("JUnit: unavailable (no reports)", result)
        self.assertIn("Lint: unavailable (no report)", result)

    def test_counts_include_failures_without_report_content(self):
        self.junit("one", '<testsuite tests="3" failures="1" errors="0" skipped="1"><testcase name="synthetic-private-marker"><failure>synthetic-private-marker</failure></testcase></testsuite>')
        self.junit("two", '<testsuite tests="2" failures="0" errors="1" skipped="0"/>')
        self.write("app/build/reports/lint-results-debug.xml", '<issues><issue severity="Warning" message="synthetic-private-marker"/><issue severity="synthetic-private-marker"/><issue severity="Error"/></issues>')
        result = summary.render_summary(self.root, "failure", "failure")
        self.assertIn("tests=5, failures=1, errors=1, skipped=1, suites=2", result)
        self.assertIn("error=1, warning=1", result)
        self.assertIn("unknown=1", result)
        self.assertIn("Report retention failed", result)
        self.assertNotIn("synthetic-private-marker", result)

    def test_bad_report_does_not_emit_partial_totals(self):
        self.junit("valid", '<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        for bad in ('<broken', '<testsuite tests="-1" failures="0" errors="0" skipped="0"/>', '<testsuite/>', '<unexpected/>'):
            with self.subTest(report=bad):
                self.junit("bad", bad)
                self.assertEqual("JUnit: unavailable (invalid or unreadable report).", summary.junit_summary(self.root))

    def test_report_size_and_count_limits(self):
        self.junit("one", '<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
        with patch.object(summary, "MAX_REPORT_BYTES", 10):
            self.assertIn("unavailable", summary.junit_summary(self.root))
        with patch.object(summary, "MAX_JUNIT_REPORTS", 0):
            self.assertIn("report limit exceeded", summary.junit_summary(self.root))

    def test_malformed_lint_and_untrusted_outcomes_are_redacted(self):
        self.write("app/build/reports/lint-results-debug.xml", '<issues>synthetic-private-marker')
        result = summary.render_summary(self.root, "synthetic-private-marker", "synthetic-private-marker")
        self.assertIn("Lint: unavailable (invalid or unreadable report)", result)
        self.assertIn("Verifier outcome: **unavailable**", result)
        self.assertNotIn("synthetic-private-marker", result)

    def test_successful_verification_is_independent_of_failed_upload(self):
        result = summary.render_summary(self.root, "success", "failure")
        self.assertIn("Verifier outcome: **success**", result)
        self.assertIn("Optional report upload: **failure**", result)


if __name__ == "__main__":
    unittest.main()
