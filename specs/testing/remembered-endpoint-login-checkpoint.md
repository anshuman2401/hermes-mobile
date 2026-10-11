# Remembered endpoint login verification

Implementation contract: [remembered endpoint logins](../architecture/remembered-endpoint-logins.md).

## Selected checks

Focused selectors were chosen from the affected auth, vault, Connect, settings, and navigation consumers before running Gradle:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'com.hermes.bot.data.auth.*' \
  --tests 'com.hermes.bot.data.local.*' \
  --tests 'com.hermes.bot.feature.connect.*' \
  --tests 'com.hermes.bot.feature.settings.*' \
  --tests 'com.hermes.bot.navigation.*' --console=plain
```

Gradle and verifier invocations use the existing shared Gradle lock. Automated verification uses synthetic credentials and endpoints only; JVM encryption uses an explicit software-key test seam. No live host is contacted.

## Development checks

- Initial narrower focused run: 303 tests, 3 failures, 0 errors, 0 skipped. Two failures exposed a new directory sync preceding sign-out authority rotation; rotation ordering was corrected. One fault-injection test still targeted the former profile-local password record; it now targets the endpoint record.
- Expanded focused run: 536 tests, 3 failures, 0 errors, 0 skipped. Findings: sign-out copy expectation needed updating; signed-out startup retained the loading presentation; malformed input waited for preference lookup before showing its error. The copy expectation and both presentation/validation paths were corrected.

- Subsequent expanded run: 539 tests, 0 failures, 0 errors, 0 skipped. Two final profile-ownership/form cases were added afterward.
- Subsequent focused run: PASS — 541 tests, 0 failures, 0 errors, 0 skipped. Gradle reported 5 executed tasks and 22 up-to-date tasks. This includes 16 endpoint auth/persistence tests, 1 recovery-intent concurrency test, and 9 Connect preference/form/profile tests, alongside existing consumer coverage.

- After readiness/profile preparation refinements: expanded focused run PASS — 543 tests, 0 failures, 0 errors, 0 skipped.
- Final expanded focused run after queued-Connect cancellation: PASS — 545 tests, 0 failures, 0 errors, 0 skipped; 5 executed Gradle tasks, 22 up-to-date. Endpoint-specific coverage now includes 16 auth/persistence tests, 1 recovery-intent concurrency test, and 13 preference/form/profile/cancellation tests.
- Pre-refinement full gate: PASS — 2710 tests, 0 failures, 0 errors, 0 skipped, 342 suites; lint 29 warnings and 4 hints, 0 errors; debug APK assembled. Gradle reported 23 executed tasks, 7 from cache, and 26 up-to-date. This receipt is superseded by subsequent executable changes.

- Revised default full gate: PASS — 2714 tests, 0 failures, 0 errors, 0 skipped, 342 suites; lint 29 warnings and 4 hints, 0 errors; debug APK assembled. Gradle reported 12 executed tasks and 44 up-to-date. A final sign-out persistence-failure guard was added afterward, so this receipt is superseded for that change.
- Final focused run including the suppression-write failure guard: PASS — 546 tests, 0 failures, 0 errors, 0 skipped; 5 executed tasks, 22 up-to-date. The endpoint-specific suites now contain 17 auth/persistence tests, 1 recovery-intent concurrency test, and 13 Connect tests.

- First clean committed candidate: final-candidate gate PASS — 2715 tests, 0 failures, 0 errors, 0 skipped, 342 suites; lint 29 warnings and 4 hints, 0 errors; debug APK assembled; 12 executed Gradle tasks, 44 up-to-date. This result is superseded by the encrypted-preference read-failure handling correction.
- Focused Connect/navigation checks after that correction: PASS — 203 tests, 0 failures, 0 errors, 0 skipped, 33 suites; 5 executed Gradle tasks, 22 up-to-date. Endpoint Connect coverage now contains 15 tests, including failed reads during Connect and profile switching.

The final committed candidate runs the full final-candidate verifier after these focused checks. Its authoritative Git revision, actual totals, lint severities, report locations and APK provenance are recorded in the ignored build receipt `app/build/reports/endpoint-login-verification.json`, without runtime logs or machine-specific paths. Earlier development runs are not evidence of the final candidate.

## Evidence boundaries

The required full gate is `./scripts/verify.sh`, followed after a clean candidate commit by `./scripts/verify.sh --final-candidate` in the same worktree. Record actual test totals, lint severity totals and package provenance from the verifier, including reused task outputs. Report locations are `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/test-results/testDebugUnitTest/`, and `app/build/reports/lint-results-debug.html` / `.xml`.

Physical-device/emulator Keystore and UI checks and live-host login are NOT RUN for this candidate. APK assembly and synthetic JVM results do not substitute for these checks. No merge or release is included.
