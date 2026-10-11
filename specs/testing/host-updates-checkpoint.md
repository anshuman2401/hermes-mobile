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

## Follow-up: slow forced checks

The owner reported the latest APK still failing after PR #10. The original screenshot and that report do not identify the deployed failing stage. No deployed host request or real credentials are used in this investigation.

Inspection found a separate transport mismatch: the update availability GET forces upstream source probes, but uses the ordinary OkHttp 10-second read timeout and a 15-second total call budget. Compatible host `source_check.py` can perform sequential upstream tip and compare reads (each with its own timeout) plus local checkout/channel probes before sending response headers. The earlier interrupted-receipt fix did not address slow responses.

Focused reproduction selector, chosen before Gradle: `com.hermes.bot.data.update.HostUpdateTransportTest`. It uses a delayed synthetic response and scales the inherited ordinary read timeout down to keep verification short. Follow-up focused selectors: `com.hermes.bot.data.update.*` and `com.hermes.bot.feature.settings.HostUpdateViewModelTest`. Shared Gradle locks apply to all runs.

Reproduction before the transport change: FAIL as expected (exit 1), one test, `slowForcedCheckDoesNotInheritTheOrdinaryReadTimeout`, with the fixed unavailable transport category on delayed headers. The response is synthetic and the read timeout is deliberately scaled; this demonstrates the client failure mechanism, not the deployed host's timing.

Follow-up behavior: only the update-check GET receives a 60-second read and total-call budget; connect timeout, status-read policy and 30-second POST deadline remain unchanged. Header/body timeouts use a fixed TIMEOUT category; POST timeouts remain UNKNOWN without retry. A timed-out status read can still fetch availability with controls disabled. Cancellation and exact-origin/auth/response-size safeguards remain intact. No persistence, migration or host changes.

Follow-up focused run: PASS (exit 0), 23 tests, 0 failures, 0 errors, 0 skips. Includes slow check headers, status header/body timeouts, unknown one-shot POST outcomes and status-timeout fallback.

Follow-up full verifier: PASS (exit 0), 2,702 tests across 342 suites, zero failures/errors/skips. Lint: 33 findings (29 Warning, 4 Hint), no errors. Debug APK assembly and `git diff --check`: PASS. Final clean-candidate gate and Git/APK provenance are recorded in the follow-up PR. Device/emulator and deployed-host checks: NOT RUN.

## Foreground read retries

Owner request: retry failed Host updates reads every five seconds. Add this to the existing timeout follow-up branch/PR; the final PR describes both changes.

Retries wait five seconds after a completed transient read failure (TIMEOUT/UNAVAILABLE), or after a git installation reports its upstream availability unknown. A transient action-status failure also retries when fallback version data succeeded. Attempts run serially within the page-owned job; slow requests never overlap. UI announces the next retry, and manual Check again replaces the pending timer. Success, leaving/backgrounding the page, changed captured owner, auth/origin/stale failures, and unsupported/malformed responses stop retrying. Uncaptured configuration/authority failures are not treated as transient network checks. Running-action status polling remains separate. Update POSTs and ambiguous outcomes are never automatically resubmitted. Exact owner/auth checks, memory-only data, transport security and persistence remain unchanged.

Focused selectors chosen before Gradle: `com.hermes.bot.feature.settings.HostUpdateViewModelTest` and `com.hermes.bot.data.update.*`. Cadence, repeat-until-success, serial requests, fallback status recovery, manual replacement, lifecycle/owner stops, permanent failures, source-unavailable handling, and unknown POST protection use virtual time or explicit suspension; no live host or credentials.

First retry-focused run: PASS (exit 0), 31 tests, zero failures/errors/skips. A review then found the previous status failure could survive into a new thrown read failure; clear it at the start of each owned attempt and add a recovery regression. Rerun focused checks for these changed inputs before the full gate.

Final retry-focused run: PASS (exit 0), 32 tests, 0 failures, 0 errors, 0 skips.

Retry full verifier: PASS (exit 0), 2,711 tests across 342 suites, zero failures/errors/skips; lint 33 findings (29 Warning, 4 Hint), no errors. Debug assembly and `git diff --check`: PASS. Main advanced with endpoint-login changes while verification queued; shared authenticated-exchange signatures remain unchanged, and merge-tree integration is checked after committing. Candidate tests run on this branch, not an executed combined merge tree. Final-candidate revision/APK provenance is recorded in updated PR #12. Device/emulator and deployed-host checks: NOT RUN.
