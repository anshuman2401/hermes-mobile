# Host updates response handling checkpoint

## Behavior and contract decisions

The page previously required a successful action-status GET before reading update availability. Decode exceptions were mapped to a connection failure, including a valid host receipt with the `interrupted` outcome. Compatible host source review covered `hermes_cli/web_routers/actions.py` (`get_action_status`, `check_hermes_update`) and `hermes_cli/update_receipt.py` (interrupted-receipt recovery); this is contract inspection, not deployed-host execution.

Android now admits `interrupted` as a structured outcome. Only a matching action identity can attribute a terminal failure; no success or automatic resend is inferred. Malformed successful GET responses use the fixed unsupported-response category, while malformed POST acknowledgements remain unknown and an explicit refusal remains refused. Body transport failures remain unavailable.

When action status is unavailable, malformed or unsupported and no Android-started action is being reconciled, the page can still fetch version information. A successful version read shows a fixed status-unavailable message and keeps update controls disabled. Status recovery on explicit refresh restores eligibility. Auth, origin and stale-owner failures stop without the secondary read and clear update eligibility. This preserves canonical-origin/auth ownership checks, cancellation, memory-only page data, no automatic POST retries, and repository layering. There is no persistence or migration change and no host infrastructure change.

The screenshot alone cannot establish which deployed response failed. The fixes cover verified Android failure paths; physical-device and deployed-host checks are NOT RUN.

## Verification

Focused selectors (chosen before running Gradle): `com.hermes.bot.data.update.*` and `com.hermes.bot.feature.settings.HostUpdateViewModelTest`, via `./gradlew :app:testDebugUnitTest --tests 'com.hermes.bot.data.update.*' --tests 'com.hermes.bot.feature.settings.HostUpdateViewModelTest' --console=plain`. All Gradle/verifier calls use the repository shared Gradle lock.

Focused run: PASS (exit 0), 20 tests, 0 failures, 0 errors, 0 skipped. Required gates: `./scripts/verify.sh`, then clean committed `./scripts/verify.sh --final-candidate` in the same worktree. Report locations: `app/build/reports/tests/testDebugUnitTest/index.html` and `app/build/reports/lint-results-debug.{xml,html}`.

Full verifier: PASS (exit 0), 2,684 tests across 339 suites, zero failures/errors/skips; lint 33 findings (29 Warning, 4 informational findings), no errors. Debug APK assembly passed. Final-candidate Git/APK provenance and final gate results are recorded in the pull request after the clean candidate run; unchanged Gradle outputs may be reused. `git diff --check`: PASS. Device/emulator and owner/manual: NOT RUN.
