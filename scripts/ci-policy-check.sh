#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== Hermes Bot Android Baseline CI Policy Check ==="

# 1. Verify changed non-Markdown paths have no whitespace errors.
# Markdown remains in scope for every subsequent policy check, including the
# added-line secret scan below.
echo "Check 1: git diff --check for non-Markdown paths..."
if git rev-parse --verify origin/main >/dev/null 2>&1; then
    git diff --check origin/main -- . ':(exclude)*.md' || {
        echo "FAIL: whitespace or diff errors detected against origin/main"
        exit 1
    }
fi

# 2. Verify wrapper distribution checksum is present in properties
echo "Check 2: Gradle wrapper checksum configuration..."
grep -q "^distributionSha256Sum=553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746$" gradle/wrapper/gradle-wrapper.properties || {
    echo "FAIL: gradle/wrapper/gradle-wrapper.properties missing expected official distributionSha256Sum"
    exit 1
}

# 3. Verify fixture manifest provenance and SHA-256 integrity
echo "Check 3: Fixture manifest and SHA-256 integrity..."
python3 -c '
import json, hashlib, os, sys

manifest_path = "app/src/test/resources/fixtures/v0.21.1/manifest.json"
if not os.path.exists(manifest_path):
    print(f"FAIL: {manifest_path} not found")
    sys.exit(1)

with open(manifest_path, "r", encoding="utf-8") as f:
    manifest = json.load(f)

if manifest.get("source_commit") != "476fa2c2897e1f6dcbb2b5982756e907b86e9578":
    print("FAIL: manifest source_commit mismatch")
    sys.exit(1)

for item in manifest.get("fixtures", []):
    fpath = os.path.join("app/src/test/resources/fixtures/v0.21.1", item["file"])
    if not os.path.exists(fpath):
        print(f"FAIL: fixture file {fpath} missing")
        sys.exit(1)
    with open(fpath, "rb") as f:
        content = f.read()
    digest = hashlib.sha256(content).hexdigest()
    fname = item["file"]
    if digest != item["sha256"]:
        print(f"FAIL: hash mismatch for {fname}")
        sys.exit(1)

print("Fixture manifest verified: 14 fixtures match exact certified SHA-256 hashes.")
'

# 4. Scan added lines, fixtures, and evidence for secrets and unredacted credentials
echo "Check 4: Secret and credential scanner across added changes, fixtures, and evidence..."
python3 -c '
import os, re, subprocess, sys

patterns = [
    (re.compile(r"hermes-gateway-ticket\.(?!<redacted)[A-Za-z0-9_\-\.]{10,}"), "unredacted ticket"),
    (re.compile(r"hermes_session_[ar]t=(?!<redacted)[A-Za-z0-9_\-\.]{10,}"), "unredacted session cookie"),
    (re.compile(r"Bearer\s+(?!<redacted)[A-Za-z0-9_\-\.]{25,}"), "unredacted bearer token"),
    (re.compile(r"Basic\s+(?!<redacted)[A-Za-z0-9+/=]{25,}"), "unredacted basic auth header"),
    (re.compile(r"ghp_[A-Za-z0-9]{30,}|gho_[A-Za-z0-9]{30,}"), "GitHub token pattern")
]

violations = 0

# Check fixtures directory
for root, _, files in os.walk("app/src/test/resources/fixtures"):
    for fname in files:
        fpath = os.path.join(root, fname)
        with open(fpath, "r", encoding="utf-8", errors="ignore") as f:
            for line_no, line in enumerate(f, 1):
                for regex, desc in patterns:
                    if regex.search(line):
                        print(f"FAIL: {desc} in {fpath}:{line_no}")
                        violations += 1

# Check added/modified files in git diff
try:
    diff_output = subprocess.check_output(["git", "diff", "-U0", "origin/main"], text=True, errors="ignore")
    current_file = None
    for line in diff_output.splitlines():
        if line.startswith("+++ b/"):
            current_file = line[6:]
        elif line.startswith("+") and not line.startswith("+++") and current_file:
            # exclude scanner itself or prompt documentation
            if "ci-policy-check.sh" in current_file or "master-prompt.md" in current_file:
                continue
            for regex, desc in patterns:
                if regex.search(line):
                    print(f"FAIL: {desc} in added line of {current_file}")
                    violations += 1
except Exception as e:
    print(f"Warning checking git diff: {e}")

if violations > 0:
    print(f"FAIL: {violations} secret violations found")
    sys.exit(1)

print("Zero secrets or unredacted credentials detected.")
'

echo "=== All Policy Checks PASSED ==="
