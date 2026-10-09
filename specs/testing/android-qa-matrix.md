# Android QA matrix

This matrix maps behavior to deterministic JVM/static checks and owner manual acceptance. The [verification runbook](verification.md) and [AGENTS.md](../../AGENTS.md) govern execution. Device/emulator execution is allowed as a separate evidence tier; the verifier has no device mode and does not package instrumentation tests.

Status vocabulary: `PASS`, `FAIL`, `BLOCKED`, `NOT RUN`. APK assembly is never recorded as an owner/manual `PASS`.

## Evidence scope

- Code, test, resource, dependency, build, script, and CI changes run `./scripts/verify.sh`: policy, deterministic JVM tests, lint, and debug application assembly. Documentation-only changes use content/link review and `git diff --check` for text, without tests, lint, or APK assembly unless explicitly requested by the user.
- The source supports API 26+ and targets API 36. These declarations are not execution coverage of the API range; no automated device/API matrix is required or claimed.
- The owner checks the exact hashed APK on his physical phone. Record non-secret model/API/package/version and results, excluding unique serials and private network details.
- JVM tests cannot emulate or certify Android Keystore, physical UI, or deployed-host behavior.

## Behavior-to-evidence matrix

| Area / invariant | JVM/static evidence | Owner/manual evidence | Release expectation |
|---|---|---|---|
| Project policy and fixture integrity | `scripts/ci-policy-check.sh` | N/A | Required |
| UI only calls ViewModels; new data flow remains `UI -> ViewModel -> repository/coordinator -> adapter -> transport` | Architecture/diff review plus focused unit tests | N/A | Required review; current direct-adapter debt must not expand |
| Structured HTTP host/port validation; hostile input rejected | `StructuredEndpointValidatorTest`, `CanonicalOriginTest`, connect integration tests | Confirm warning and owner-entered endpoint behavior | Required for connection changes |
| Saved-login startup opens cached Sessions while connecting; expired cookies silently recover; rejected passwords offer explicit Sign in; rotation retains startup owners | `ConnectStartupTest`, `SessionsStartupTest`, `StartupLandingPolicyTest`, `StartupViewModelFactoryTest`, `StartupVaultReadFailureTest` | Confirm valid-login launch has no host/password flash; offline Retry, silent reauthentication, rejected-password banner, Back and font/width behavior on the exact APK | Required for startup changes; JVM checks do not certify physical UI or Keystore |
| Current HTTP-only UI, exact `Connect` action, no protocol/HTTPS controls | Connect semantics/source tests | Visual check on exact APK | Required |
| Redirects and origin changes fail closed | auth/gateway redirect tests and exact-origin repository tests | No live-host probing required | Required for transport/auth changes |
| Cookie/credential scheme-host-port isolation | auth origin/vault tests | Owner sign-in only; never retain credentials | Required for auth changes |
| Fresh single-use WebSocket ticket per attempt; no persistence/logging | repository/reconnect/protocol tests, storage/redaction scans | Reconnect observation without capturing ticket | Required for gateway/reconnect changes |
| `gateway.ready` and required capabilities gate usability | gateway adapter and controlled-connect integration tests | Owner host compatibility check | Required for protocol changes |
| Server-authoritative session list/title/history | session repository and ViewModel tests | Verify an existing server session rehydrates | Required for session changes |
| Production chat provider reaches the activity-retained keyed owner | `ChatRouteOwnerViewModelTest`, `ChatShellCleanupPolicyTest`, and source review of the shell factory/provider path | Verify an existing session is interactive | Required for production provider changes; source wiring is present, physical behavior remains owner/manual evidence |
| At most one chat gateway collector for the displayed session; zero after route exit; same-session re-entry and rotation retain exactly one valid collector | `ChatRouteOwnerViewModelTest`, `ChatShellCleanupPolicyTest` | Confirm no duplicate/stale streamed messages while switching sessions | Required for chat ownership/lifecycle changes; component tests do not prove physical lifecycle behavior |
| Only events carrying the exact current runtime session ID can mutate the selected transcript | `ChatRuntimeAdmissionTest` | Confirm no cross-session or stale text appears while switching | Required for chat event-admission changes |
| Tool activity correlates only by validated nonblank `tool_id`; same-name tools stay separate and unkeyed events cannot mutate keyed cards | `ToolIdentityCorrelationTest` against the certified lifecycle fixture plus labeled synthetic edge cases | Confirm concurrent tool cards remain distinct and terminal state is not overwritten | Required for tool-event identity/presentation changes |
| Replay truncation/epoch/decode failure falls back to resume/history replacement | replay reducer/adapter tests | Optional observable reconnect | Required for replay changes |
| Ambiguous prompt is never automatically resent | `ReconnectNoDuplicateTest`, ledger/coordinator/chat tests | If exercised, use harmless disposable prompt and inspect history | Required for turn/reconnect changes |
| Explicit retry creates a new pending-turn attempt | ledger/coordinator/chat tests | User-visible retry confirmation | Required for retry UI changes |
| Android Keystore fail-closed; no production software-key fallback | JVM fail-closed/source/composition tests | Physical-device sign-in/persistence validates device-specific Keystore path | Required for vault/security changes |
| Vault survives process restart; runtime session ID is not trusted | vault/session repository tests | Reopen exact APK and resume selected session | Required for persistence/session changes |
| Private diagnostic files are four-level and app-private; the bounded report permits explicit Copy/Share | diagnostics behavior/redaction/storage scans | Inspect only synthetic records; check report, Copy, Share, clipboard behavior and absence of public-path/clear UI | Required for diagnostics changes; owner to confirm Copy/Share for public release |
| Read-only task shelf, revision ordering, runtime isolation, unavailable/offline/terminal states | `TaskSnapshotDecoderTest`, `GatewayTaskProgressTest`, `TaskProgressStateTest`, `ChatTaskProgressTest`, active-projection task integration test | On exact APK with synthetic task data: collapse/expand, long-list scrolling, keyboard, narrow width, large font, themes, Stop and reconnect without false completion | Client deterministic checks passed; visual/device/live-host rows NOT RUN |
| Visible-chat approvals; scoped single-flight decisions, FIFO, cancellation/reconnect and no automatic retry | `GatewayApprovalTest`, `ApprovalRepositoryTest`, `ChatApprovalTest`; shared gateway/session/turn/composer suites | Synthetic peer on exact APK: card above composer, all permitted scopes, permanent confirmation/cancel, queue advancement, Back/re-entry, narrow width/fonts/themes and editable draft/IME | Client gates required; device receipt in session-approval checkpoint; owner/live-host acceptance separate |
| Bot Screen watch and human takeover from Bots or bot chat | `GatewayBotScreenTest`, `RawRfbProtocolTest`, `BotScreenRepositoryTest`, `BackPressPolicyTest`, `BotsRouteWiringTest`; full verifier | Synthetic RFB peer and exact APK: stopped/start, watch, take over, click/right click/scroll/keyboard, hand back, competing viewer, dropped link, Back/background, profile switch, portrait/landscape, narrow width/IME and both themes; confirm owner-initiated screen recording and Google screen search show the viewer rather than black | JVM source/stream evidence cannot certify deployed-host compatibility, real CAPTCHA interaction, capture behavior or device memory/latency; retain no screen frames or secrets |
| Selected-bot screen status and explicit stop | `GatewayBotScreenTest`, `BotsViewModelTest`, `BotsRouteWiringTest`, `BotsWiringTest`; full verifier | Synthetic peer on exact APK: status after viewer close/reopen, 15-second foreground update, confirmation/cancel, human-held refusal, ambiguous stop response, profile switch, narrow width and large font | Status is host-reported; no exact screen CPU/RAM metric; device and deployed-host confirmation remain separate |
| Compose semantics at narrow width and increased font scale | JVM semantics/source tests where applicable | Visual accessibility pass | Required for relevant UI changes |
| No secrets in logs, fixtures, reports, or evidence | policy scan and redaction tests | Do not export owner runtime logs | Required always |
| Debug APK identity/provenance | `./scripts/verify.sh --final-candidate` | Confirm installed package/version against handoff | Required for candidate handoff |

## Instrumentation source coverage

At the 2026-10-08 integration base, `app/src/androidTest` contains `StreamRenderingDeviceTest` (synthetic chat following, keyboard/scroll and prepend behavior), `StreamRenderingProfileTest` (synthetic stream frame profiling), and `StreamProfileRunner`. Both test classes require `-PstreamProfile`, which uses an isolated `.streamprofile` debug package and avoids the production app graph. The source tree does not contain the previously listed Keystore, Connect semantics or general Chat semantics instrumentation tests. The default verifier neither packages nor runs instrumentation. Android Keystore, connection UI, navigation, accessibility beyond these streaming cases, and API-level differences therefore still need separate device/emulator checks with synthetic data.

## Candidate execution record template

| Evidence row | Target | Result | Evidence |
|---|---|---|---|
| Default verifier | JVM/package | `NOT RUN` | Command, exit status, JUnit/lint summaries |
| Final-candidate provenance | JVM/package | `NOT RUN` | HEAD/tree, APK SHA-256/size/package/variant/version |
| Owner manual acceptance | Physical phone/API | `NOT RUN` | Exact APK SHA-256 and checklist result |

Do not prefill results. If the owner has not exercised the checksummed APK, manual acceptance remains `NOT RUN`.

## Owner manual checklist for the exact debug APK

1. Verify the APK SHA-256 matches the final-candidate handoff before installation.
2. Install/update and launch; record device model/API and package/version without sharing a unique serial.
3. Confirm the Connect screen shows the fixed HTTP warning and `Connect`, with no protocol/HTTPS chooser.
4. Exercise password conceal/show semantics using a disposable value; do not capture it in screenshots or evidence.
5. With the owner's own private-network configuration and credentials, connect to the intended Hermes host. Automation must not perform this step.
6. List sessions, resume one, and confirm displayed history comes from the server.
7. Send one harmless prompt. If the connection is deliberately interrupted, confirm the app reconciles or shows unknown status and does not silently duplicate the prompt.
8. With a saved login, force-stop/relaunch and confirm Chats/Sessions opens with Connecting progress rather than host/password forms. Open an existing session and confirm restoration occurs through resume rather than a trusted stale runtime attachment. Separately check unreachable-host Retry and expired-cookie silent login and rejected-password Sign in action; retain no private screenshots or runtime traffic.
9. Open Settings -> Diagnostics; confirm private four-file storage, bounded report, deliberate Copy/Share controls, and no public-file/clear surface. Use synthetic diagnostic values only.
10. Record deviations and failures without attaching runtime logs, host details, credentials, prompts, responses, tickets, or session identifiers.

Destructive clear-data/uninstall testing is optional and must be explicitly authorized because it removes local vault and diagnostic state. Never infer remote session deletion from a local reset.

## Settings host insights acceptance

Deterministic behavior coverage: `HostInsightsRepositoryTest` and `HostInsightsViewModelTest` cover exact named/default profile and origin routing, Analytics-only live roster/default/first-profile selection without global selection writes, auth expiry, cookie receipt carry-forward, redirect rejection, unsupported/malformed/oversized bodies, missing/null metrics, analytics periods/breakdowns, partial Health reads, connection/profile/generation supersession, cancellation, stale UI rejection, and byte-preserving copy projection using synthetic text. Native page/clipboard/lifecycle wiring is reviewed separately; JVM tests do not establish device layout or clipboard behavior.

Owner/manual on the exact final APK: confirm Brain → Health → Analytics → Gateway logs order and working Back navigation; inspect Health unavailable/partial sections and machine-wide labels; select different Analytics profiles and periods, confirm profile-specific values and unchanged app selection, then check null versus zero and cost labels. In Gateway logs, inspect Gateway default, other sources, minimum-level filter, manual snapshot time/Refresh, local search, wrapped rows, full-line detail, original whitespace/newlines, selected-text/line/shown-tail copy, and detail Back. In App logs, inspect saved-event severity filters, current-session separation, report Back, and report Copy/Share equality. Check narrow width, large fonts, themes, background/re-entry and connection/profile switching. Use synthetic data for retained evidence. Do not attach host logs, host identifiers, credentials, or owner content. Device and live-host rows remain NOT RUN until separately executed.

## Composer dictation acceptance

Deterministic: VoiceProtocolTest, VoiceCoordinatorTest, VoiceDictationTest cover WAV/upload/response bounds, auth/cookie succession, profile/origin routing, redirects/no retries, timeout/cancellation, late ownership, permission/repeat/Stop races, two-minute cap, latest draft append/persistence and Send isolation. VoiceWiringTest supplements these with production graph and foreground lifecycle wiring. JVM tests do not certify AudioRecord, runtime permission dialogs or Compose geometry.

Exact-APK manual/device checklist: permission grant/denial/permanent denial/settings; actual microphone capture; Stop/Cancel and two-minute cap; configured host transcription, silence and unavailable/unconfigured host; editable appended draft and explicit Send; attachments preserved; Back cancels before navigating; background/rotation/route exit release microphone and discard work; keyboard, narrow screen, large fonts and themes. Retain only synthetic evidence. Device and deployed-host checks remain NOT RUN until separately executed.

## Work Files path and visibility acceptance

Deterministic: `ManagedFilesViewModelTest`, `ManagedFileListingParserTest`, `ManagedFileProtocolAdapterTest`, Work navigation and rendering-bounds suites cover direct path validation and host capability, locked-root containment, unchanged listing after failed navigation, dot-name filtering without changing the host listing or order, and owner reset.

Exact-APK manual/device checklist (NOT RUN): in Work → Files, enter a permitted absolute folder path with Go and keyboard submit; confirm Up and Back still follow the host parent. Try malformed, missing, denied and locked-root-outside paths and confirm the old list remains with an inline error. Check a host with path changes disabled. Toggle Hide hidden files in folders containing dot folders/files, check visible counts and the filtered-empty message, then switch sections and connection owners. Check loading/offline/error states, long paths, keyboard, narrow width, large font and both themes using synthetic names. Device and deployed-host behavior are separate from the JVM verifier.

## Conversation files acceptance

Deterministic: ConversationFilesTest, FileProtocolTest, ConversationFilesControllerTest, FilePreviewPolicyTest and ChatViewModelTest cover explicit assistant/tool discovery, loaded-history bounds, duplicate reply associations, authenticated session/profile routing, cookie succession, redirects, decompressed download caps, timeout/cancellation, stale owners, export ownership and preview limits.

Exact-APK device checklist (NOT RUN): file cards and Files header on narrow screens/large fonts/both themes; loaded older history and empty/offline/error/retry states; Back from preview/list without changing draft; PDF paging, sampled images, long inert text and unsupported formats; Save picker success/cancel/rotation, Share/Open read grants, cache expiry; background during download and session/profile/connection changes. Deployed-host file access also NOT RUN. Use synthetic files for retained evidence.

## Edit and resend

Behavior suites: `GatewayMessageEditTest`, `MessageEditSubmissionTest`, `MessageEditViewModelTest`, stored-history eligibility and session ownership tests, plus shared gateway/session/ledger/persistence/chat suites. Invariants: exact durable target (including duplicate text), stop then authoritative idle before write, one write per attempt, no automatic resend or ordinary retry after ambiguity, captured ownership, normal draft retention, and removal of stale later history.

Device checklist: historical and live-message Edit, Cancel/Back, retained draft, idle/running resend, restart/reconnect without resend, portrait/landscape/IME scrolling, large font and both themes. Synthetic-peer checks do not certify a deployed host.
