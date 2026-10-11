# Connect Atelier styling

The host-entry and credentials pages previously inherited HermesBotTheme, with legacy focus, diagnostic and banner colors. The Connect destination now uses the shared Atelier theme and the persisted light/dark/system preference, including its canvas and system-bar appearance. A connection-scoped color overlay keeps authentication and failure containers and text selection in that palette. Fields, panels and actions use Atelier shapes; the introductory heading uses Instrument Serif and body text uses the shared DM Sans typography.

This is presentation-only: protocol, exact-origin checks, the HTTP disclosure, credential handling, persistence, state ownership, retries and migrations retain their existing behavior. No host infrastructure changes or dependencies are required.

## Verification

- Initial focused invocation could not locate the installed SDK; setting `ANDROID_HOME` to that existing SDK resolved the prerequisite without installing components.
- Focused command: `./gradlew testDebugUnitTest --tests 'com.hermes.bot.design.ThemeAndContrastTest' --tests 'com.hermes.bot.feature.connect.ConnectLayoutPolicyTest' --tests 'com.hermes.bot.feature.connect.ConnectSemanticsAndAccessibilityTest' --console=plain` — PASS: 26 tests, 0 failures, 0 errors, 0 skipped. Test execution ran; some compilation/resource tasks reused Gradle cache outputs.
- The Connect contrast gate covers value/placeholder text, neutral/focus/error field boundaries, cursors and action labels in both Atelier palettes.
- Report: `app/build/reports/tests/testDebugUnitTest/index.html` (subsequently replaced by the full gate).
- Gradle invocations are serialized using the shared locks.
- Device/emulator visual checks and owner/private-host checks: NOT RUN. JVM contrast and layout policy checks do not certify rendered UI or host behavior.
- Full command: `./scripts/verify.sh` — PASS (policy, 2,651 tests across 338 suites with 0 failures/errors/skips, lint, debug assembly). Lint reports contain 33 findings; 29 are warnings. Reports: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.xml`, `app/build/reports/lint-results-debug.html`. Test execution ran; Gradle reused unchanged inputs where applicable.
- `git diff --check` — PASS. Integration recheck against fetched `origin/main` found the task based on the current main revision before committing.
- The committed candidate must pass `./scripts/verify.sh --final-candidate`; its receipt records the candidate Git revision, test/lint summaries and generated APK provenance. This rerun may reuse unchanged reports and outputs.
