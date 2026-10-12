#!/usr/bin/env python3
"""Write fixed-category CI evidence without copying report messages or paths."""

import os
from pathlib import Path
import re
from xml.etree import ElementTree

MAX_REPORT_BYTES = 16 * 1024 * 1024
MAX_JUNIT_REPORTS = 2000
COUNT_KEYS = ("tests", "failures", "errors", "skipped")


def read_xml(path):
    with path.open("rb") as source:
        data = source.read(MAX_REPORT_BYTES + 1)
    if len(data) > MAX_REPORT_BYTES:
        raise ValueError("report exceeds limit")
    return ElementTree.fromstring(data)


def junit_summary(root):
    reports = sorted((root / "app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    if not reports:
        return "JUnit: unavailable (no reports)."
    if len(reports) > MAX_JUNIT_REPORTS:
        return "JUnit: unavailable (report limit exceeded)."
    totals = dict.fromkeys(COUNT_KEYS, 0)
    try:
        for report in reports:
            suite = read_xml(report)
            if suite.tag != "testsuite":
                raise ValueError("unexpected report root")
            for key in COUNT_KEYS:
                value = suite.attrib[key]
                if not re.fullmatch(r"[0-9]{1,9}", value):
                    raise ValueError("invalid count")
                totals[key] += int(value)
    except (OSError, ElementTree.ParseError, ValueError, KeyError):
        return "JUnit: unavailable (invalid or unreadable report)."
    return "JUnit: " + ", ".join(f"{key}={totals[key]}" for key in COUNT_KEYS) + f", suites={len(reports)}."


def lint_summary(root):
    report = root / "app/build/reports/lint-results-debug.xml"
    if not report.exists():
        return "Lint: unavailable (no report)."
    try:
        issues = read_xml(report)
        if issues.tag != "issues":
            raise ValueError("unexpected report root")
        counts = dict.fromkeys(("Fatal", "Error", "Warning", "Information", "Ignore", "Unknown"), 0)
        for issue in issues.findall("issue"):
            severity = issue.attrib.get("severity")
            counts[severity if severity in counts else "Unknown"] += 1
    except (OSError, ElementTree.ParseError, ValueError):
        return "Lint: unavailable (invalid or unreadable report)."
    return "Lint: " + ", ".join(f"{key.lower()}={value}" for key, value in counts.items()) + "."


def render_summary(root, verifier_outcome, upload_outcome):
    allowed = {"success", "failure", "cancelled", "skipped"}
    verifier = verifier_outcome if verifier_outcome in allowed else "unavailable"
    upload = upload_outcome if upload_outcome in allowed else "unavailable"
    lines = ["## Repository verification", "", f"Verifier outcome: **{verifier}**.", "", junit_summary(root), "", lint_summary(root), "", f"Optional report upload: **{upload}**."]
    if upload == "failure":
        lines += ["", "Report retention failed; verification outcome is unchanged. Consult the job log for diagnostics."]
    lines += ["", "Counts describe available reports only; missing reports do not imply a pass. Device and live-host checks were not run.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    summary = render_summary(Path.cwd(), os.environ.get("VERIFIER_OUTCOME"), os.environ.get("UPLOAD_OUTCOME"))
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as destination:
        destination.write(summary)
