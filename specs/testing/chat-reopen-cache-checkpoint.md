# Chat reopen cache correction

The owner reported five consecutive open / Back / reopen attempts remaining empty during a four-to-five-second host sync, after installing the previous verified cache-preview build. The previous test suite proved previews with unchanged credentials, but did not exercise an authenticated history read renewing its cookie before local publication. Those results did not establish the reported phone behavior.

## Cause and behavior

The selected-workspace preview used a separate stored-history budget and the original credential lease. The concurrent REST read can publish a successor cookie, invalidating that lease even though the owner, origin, workspace and encrypted cache remain the same. Its valid cache preview was silently rejected while the host body was pending. A synthetic regression reproduced this failure with both host reads held.

Connected preview now captures an opaque local cache read owner atomically in the vault. Ownership binds the exact cache partition and authoritative workspace selection plus explicit-login and cache-reset generations. Normal cookie renewal preserves this local authority; manual login, sign-out, profile/origin changes, unreadable selection, local cache clear/purge, disposal and newer history publication reject old reads. Snapshot authorization and IO share the vault mutex. One final owner check precedes UI publication; the transient runtime, send and edit gates remain closed. Host reads continue concurrently and replace preview rows wholesale. No network calls or prompt submissions come from the preview.

Protocol, persistence schema, expiry/caps, Keystore, backup, retry and migration behavior are unchanged. Owner receipts and invalidation generations are process-local and never serialized. Unsupported storage seams fail closed. JVM keys are test seams; the separate device check uses Android Keystore with an isolated synthetic folder and temporary test alias, never the app graph, owner vault or a live host.

## Focused verification

Commands run under the shared Gradle lock. Reproduction before the fix: `./gradlew :app:testDebugUnitTest --tests 'com.hermes.bot.feature.chat.ChatCachedHistoryPreviewTest.storedReadCookieRenewalDoesNotSuppressCachedPreviewWhileHostHistoryWaits' --console=plain --stacktrace` FAIL, one test timing out before cached preview. Repeated with local login authority enabled to match production: same failure; the test did not observe cache publication while host reads waited.

After the correction: `./gradlew :app:testDebugUnitTest --tests 'com.hermes.bot.feature.chat.ChatCachedHistoryPreviewTest' --tests 'com.hermes.bot.feature.chat.ChatSnapshotReconnectTest' --tests 'com.hermes.bot.feature.chat.ChatStoredHistoryTest' --tests 'com.hermes.bot.bootstrap.ConversationCacheWiringTest' --tests 'com.hermes.bot.data.local.ConversationCacheReadOwnerTest' --tests 'com.hermes.bot.data.local.EncryptedVaultCacheResetTest' --tests 'com.hermes.bot.data.local.EncryptedVaultSignOutCacheChoiceTest' --tests 'com.hermes.bot.data.session.SessionHistoryScopeTest' --console=plain --stacktrace` PASS, exit 0: 65 tests in 8 suites, zero failures/errors/skips. 34s; 5 tasks executed / 22 up-to-date. Covers five consecutive reopens with renewed cookies before each preview publication, slow-cache / late-host replacement, draft retention, owner revocation, local-reset residue and scoped auth behavior. Reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/index.html`.

Full gate, committed-clean final-candidate receipt and separately executed device evidence are recorded below once completed. Build and JVM results do not certify owner-host or actual chat UI behavior.

## Synthetic device execution

`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --console=plain --stacktrace` PASS; 38s, 20 tasks executed / 15 from cache / 39 up-to-date. Replacement installation of both APKs via the user-authorized wireless ADB connection PASS.

Executed `adb -s <wireless-device> shell am instrument -w -r -e class com.hermes.bot.data.local.ConversationCacheReadDeviceTest com.hermes.bot.debug.test/com.hermes.bot.feature.chat.StreamProfileRunner` PASS: 1 test on Android API 36, model SM-S928B. Tested application APK SHA-256: `fa63b482a5907de31d14e15c690d0388103c9f9a239085949e6c197f1c477bc9`. This execution used the working candidate before its commit; final-candidate provenance is recorded separately.

Five synthetic 100-row cache cycles with real Android Keystore, including local owner acquisition, credential lease read, a compared cookie-successor write, snapshot read and final owner validation: 766, 567, 513, 708, 497 ms. These are storage-and-renewal measurements, not UI-open latency. A fresh test-only folder/key alias was deleted afterward. The runner instantiates a plain Application and never opens the production app graph, owner vault or live host. Physical chat UI and owner-host behavior: NOT RUN by this test.

## Canonical verification

`./scripts/verify.sh` PASS, exit 0: policy checks, 2,683 JVM tests in 340 suites / zero failures, errors or skips; lint 29 warnings and 4 hints / zero errors or fatal findings; debug assembly PASS. 4m15s; 9 tasks executed / 47 up-to-date. Reports: `app/build/test-results/testDebugUnitTest/`, `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/lint-results-debug.xml` / `.html`. Content/link review and `git diff --check` PASS.

Integration recheck: origin/main at 3fbda21 contains only disjoint host-update error handling changes after this branch's 43fcd4d base. No overlapping chat/cache/vault/owner paths; no merge/release/host change performed. The committed-clean final-candidate gate and safe APK provenance are retained under ignored `build/verification/` and reported in the handoff. Final-candidate reuse is recorded honestly; the verifier does not run device tests.
