# Current Android system architecture

Status: architectural summary of the implementation. Check the current source and tests before relying on a feature claim; this document does not certify a live host, physical device, or release.

Documentation correction (2026-10-03): the chat-owner provider description below was rechecked against the current shell, owner, and component tests. Other sections retain their recorded baseline; this correction does not certify device or live-host behavior. See the [documentation guide](../README.md) for current authority.

## Product and platform boundary

Hermes Mobile is a single-module native Kotlin/Jetpack Compose client (`:app`, namespace `com.hermes.bot`). It targets Android 36, supports API 26+, and compiles with Java 21. The host owns agent execution, tools, credentials, durable sessions, and transcript truth. The phone owns presentation state, encrypted connection/auth/session-selection data, drafts, pending-turn evidence, and private redacted diagnostics.

The production graph is assembled in `bootstrap/AppContainer.kt`:

```text
MainActivity / Compose navigation
  -> feature ViewModels
     -> AuthRepository / SessionRepository / TurnCoordinator
        -> GatewayProtocolAdapter / AuthHttpTransport
           -> OkHttp HTTP and WebSocket transports
     -> VaultStorage
        -> EncryptedVault -> Android Keystore AES-GCM
     -> Diagnostics
        -> app-private four-file diagnostic store
```

The required direction for new work is `UI -> ViewModel -> repository/coordinator -> adapter -> transport`. Two current implementation exceptions are visible in the composition root: `ConnectViewModel` calls `GatewayProtocolAdapter` for connection setup, and `ChatViewModel` subscribes directly to adapter event/fallback flows. They are existing architecture debt, not precedent. Do not add new direct ViewModel-to-adapter calls; move them behind a bounded repository/coordinator change when that surface is next refactored.

## UI and state ownership

- `MainActivity` obtains `DefaultAppContainer`, validates the production graph, and hosts the Compose root. Connect and Sessions startup ViewModels use an explicit Activity-retained factory with saved-state handles; rotation reuses them instead of starting another connection attempt.
- `HermesNavShell` owns screen-level navigation among connect, sessions, chat, settings, and private debug logs. Its activity-retained `ChatRouteOwnerViewModel` owns a keyed child `ChatViewModel` for each displayed durable session, clears the previous child on a session switch or route exit, retains the displayed child across rotation, and clears children with the Activity. The shell now creates the owner with an explicit `viewModelFactory` that forwards `chatViewModelProvider` from `MainActivity`; the older missing-provider description is stale. `ChatRouteOwnerViewModelTest` and `ChatShellCleanupPolicyTest` exercise child creation/disposal and the missing-provider fallback. Source wiring and component tests do not certify physical-phone navigation, rendering, or live-host operation.
- `ConnectViewModel` owns the structured connection/auth UI state. A generation gate prevents stale non-cooperative work from publishing state or atomically replacing the saved profile after disconnect/supersession. Its gateway-status observer only turns steady `CONNECTED_COMPATIBLE` into a drop state; it does not race intermediate preflight/auth/connect states.
- `SessionsViewModel` delegates list/create/resume to `SessionRepository`.
- When constructed with production dependencies, `ChatViewModel` renders authoritative resume history, streamed gateway events, tool cards, drafts, and explicit ambiguous-send recovery. It admits a gateway event only when the event carries the exact current runtime session ID; durable-only, stale-runtime, other-session, null, and blank IDs cannot mutate the timeline. Tool start/completion events correlate only by a trimmed nonblank `tool_id`; name and adjacency are never identity fallbacks. Concurrent same-name tools therefore remain separate, blank IDs render bounded generic rows, completion-before-start creates one terminal keyed card, and `tool.generating`/uncertified progress events remain independent unkeyed rows. `TurnCoordinator` and `PendingTurnLedger` own the write-attempt lifecycle.
- `DebugLogsViewModel` reads bounded, already-redacted app-private diagnostics. Settings → App logs shows saved records newest first with a severity filter and separately labels the current-process ring. View report retains explicit Copy, Share and text selection of the bounded report; its privacy impact needs review before a public release.

UI state is exposed with `StateFlow`; feature coroutines are scoped to their ViewModels. `ChatScreen` uses lifecycle-aware collection, so UI state collection stops with the host lifecycle and when the composable leaves. The separate gateway collectors launched by `ChatViewModel` stop only when that child ViewModel is cleared, which is why keyed owner disposal and production provider wiring are required together. The provider is now forwarded in source; physical lifecycle behavior remains owner/manual evidence. App-scoped gateway reconnect work uses a `SupervisorJob` plus `Dispatchers.IO`.

## Context profiles and workspace selection

The app distinguishes a **local saved connection profile** from a **remote Hermes context profile**. Switching a local connection profile changes the encrypted local connection selection, reconnects to the selected origin, and does not call the remote profile-activation endpoint.

The remote workspace selector stores the selected context-profile name locally through the existing encrypted storage abstraction. Normal confirmation never calls `POST /api/profiles/active` and therefore does not change the Hermes host sticky/default profile. After a successful local selection, `HermesNavShell` closes the sheet immediately and refreshes sessions; the sessions repository requests `GET /api/profiles/sessions?profile=<selected>&limit=100`. Local persistence failure restores the prior selection and rolls back the optimistic row state without any host mutation. A profile-not-found-shaped response clears the stale local selection and falls back to the ordinary unscoped session list. The roster and host metadata come from the read-only `GET /api/profiles` and `GET /api/profiles/active` endpoints. After a successful authenticated fetch pair, the parsed roster is cached encrypted under the signed-in access partition and — when the live fetch fails — served from that cache labelled with its source and capture time; loading it never mutates the local selection or any host state.

The selected local profile is also supplied by the already-merged routing path to `session.create` and `session.resume`. Any future host-default administration action must be separate and explicitly labelled; no such UI exists in this app.

## Unified Bot board (BOT-003)

The Bot tab embeds the existing Discord-style channel feed below an ordered favourites strip and selected bot heading. All bots opens a roster picker with independent selection and star actions. Bot selection reuses the local workspace coordinator; it does not activate the host default, resume a session, or submit a prompt. Both chat Back paths return to the root Bot tab. The legacy channel route is retained only for restored-route normalization. Per-bot channel drafts and scroll offsets remain in the connection-lifetime presentation ViewModel.

`BotsViewModel -> BotRepository -> VaultStorage` owns favourites reads/edits. Encrypted schema-1 name preferences are keyed/AAD-bound to saved connection and exact origin, independent of workspace/session pins and cache expiry. Authenticated lease admission and serialized stale-owner rejection protect storage operations; ViewModel scope admission protects late results. This describes implementation, not physical-device validation.

## Connection, origin, and auth flow

The current user flow is intentionally fixed to structured HTTP:

1. The Connect form accepts a local display label, host/IP, and port. It has no protocol picker and retains a cleartext/private-network warning.
2. `StructuredEndpointValidator` produces a root `CanonicalOrigin`; userinfo, query, fragment, path prefix, control/bidirectional characters, malformed authority, and invalid ports are rejected.
3. `DefaultAuthRepository` performs `GET /api/status` before exposing credentials to a login request.
4. The certified password-session path posts to `/auth/password-login`, accepts only a host-advertised provider, validates origin-bound cookies, and rejects a successful status without a valid session cookie.
5. Each socket attempt posts to `/api/auth/ws-ticket` using the exact-origin credential and receives a fresh short-lived `GatewayTicket`. Tickets are returned directly and are not persisted or cached.
6. `DefaultGatewayProtocolAdapter` opens `/api/ws` through `OkHttpGatewayTransport`, waits for `gateway.ready`, and checks `gateway.capabilities` and announces optional `client.capabilities {server_requests:true}` before declaring the connection usable. An unavailable optional approval capability does not prevent ordinary chat.
7. HTTP and WebSocket clients disable HTTP and TLS redirects. WebSocket handshake redirects are converted to policy failures; no automatic cross-origin or scheme-changing request is allowed.

`AndroidManifest.xml` intentionally has `android:usesCleartextTraffic="true"` because arbitrary owner-entered private-network HTTP hosts cannot be represented by a static network-security allowlist. The app constrains its own URLs, but process-wide cleartext capability remains a residual risk and the app cannot attest that Tailscale/VPN protects the route.

### Saved-login startup

Startup presentation is separate from connection progress. Encrypted local state loads behind a neutral loading surface; an eligible exact-origin saved login opens Chats/Sessions before ticket acquisition or socket negotiation finishes. A one-time ViewModel-owned route admission ignores restored screen authority on a cold process launch while preserving deliberate navigation across rotation. Returning users stay on Sessions after cookie expiry or rejection. Successful password logins retain exact-origin/profile-bound credentials in the Keystore-backed vault. One app-scoped connection recovery coordinator coalesces startup, foreground reconnect and current-session rejection, tries the cookie first, and permits one saved-password attempt per recovery cycle with fresh single-use socket tickets. Definite password rejection disables automatic password use until manual correction. Sign in required is an explicit action on cached Sessions/chat, not an automatic route change; network failures offer Retry. Auth HTTP calls have finite call/read timeouts and no transport-internal retries. Existing installs have no recoverable password: their first successful manual correction enables remembering.

Production Sessions startup defers network reads until connection readiness, and may first read existing eligible summary cache through a network-free repository operation. Local read-only cache owners are distinct from renewable network auth epochs; same-account recovery preserves local ownership, while explicit sign-out/account/origin replacement revokes it. Legacy owner migration requires an existing authorized partition and never resurrects signed-out records. Cache partitions, workspace binding and freshness remain authoritative; absent cache is not a server-empty list. Live connection completion supersedes pending cache work and fetches server truth even without cached rows. Online session actions require a compatible connection; eligible offline rows retain the existing read-only conversation snapshot path. Connected presentation follows successful handshake and completion of the profile save attempt, preserving a local-save error if that save fails. A socket lost during persistence cannot publish ready.

Profile/index/auth and summary reads propagate `KeyStoreUnavailableException` while retaining ciphertext for a later retry; unavailable keys are not treated as record corruption. Corrupt or unauthentic ciphertext still fails closed under the existing deletion/quarantine rules. This describes source behavior; device startup, layout and platform-key recovery require separate evidence.

### Current network behavior

The executable Connect form uses fixed HTTP with a cleartext warning and no visible HTTPS option. Exact-origin, credential, storage, privacy, redaction, and no-bypass controls remain required. The app cannot verify that an owner-configured private network or tunnel protects the route.

## Gateway, sessions, and server authority

`GatewayProtocolAdapter` is the wire-protocol boundary. Its implementation owns JSON-RPC IDs, raw frames, ready/capability negotiation, event decoding, replay sequence/epoch handling, and protocol failures. Repositories and feature code consume typed models.

- `session.list` is the authoritative list and title source. `SessionSummaryCache` may be shown only as a fresh, explicitly offline cache for the signed-in workspace: reads require a resolved cache-access partition (active profile, `sha256Hex` of its normalized origin, the minted cache epoch, and the current workspace-scope tag), a partition-matching record inside its 7-day freshness window, and are refused outright whenever any resolver check fails. Network auth revisions rotate before guarded credential mutations; local cache-owner epochs survive cookie refresh/expiry but rotate on explicit sign-out/account/origin replacement, records written before the epoch they belong to are purged, and any legacy pre-partition cache file is discarded without being read.
- `session.create` returns durable and runtime IDs. A blank local draft is not treated as persisted remote history.
- `session.resume` is the authoritative history hydration operation. `DefaultSessionRepository` replaces active messages from the returned server snapshot and marks history rehydrated before replay continues. When a successful resume lands, PR-3's offline conversation snapshot for that session is atomically replaced by the returned history — Hermes stays authoritative and the cached copy is never merged into it.
- Only the durable stored session ID is restored after process death. A runtime session ID is never trusted across launch; the client resumes to obtain a current runtime attachment. The offline snapshot path likewise never restores a cached runtime session ID (it stays null) and never arms `NO_RUNTIME`.
- Replay is an optimization. Truncation, epoch change, invalid ordering/decoding, or process restart clears replay confidence and triggers `session.resume` history replacement.

No full remote transcript is durably owned by the app. What may exist locally is a bounded, encrypted, per-partition resume-window snapshot (the offline conversation snapshot): a labelled `Saved snapshot — offline` copy of a recently opened conversation, served cache-first while `connectionStatus != CONNECTED`, with zero adapter, prompt, or replay calls, the composer disabled, and `loadEarlier` read-only while it is shown. Alongside it may exist the phone-captured tool trace: a bounded list of redacted tool/terminal observations (name, status, observed timing, duration, and the host's own bounded context label only — never raw terminal output, payload, args, result, or summary bytes) captured on this device. Following the owner decision on 2026-10-07, these local observations are no longer rendered in chat in any state; normal server/live tool Actions remain visible. The trace never authorizes a read or write, is never restored as server history, and survives replace-on-resume: a successful resume replaces rows and snapshot wholesale while the observed trace is carried forward, not deleted (it is dropped only with the record itself — rotation, purge, or record loss). Local drafts and pending-turn records remain separate from server transcript state.

Connected chat opening also reads this existing snapshot concurrently with scoped host hydration when no transcript is displayed yet. Following the owner's preference for a quiet refresh, this temporary preview suppresses cache, syncing, refresh-progress and restore-session notices; the header continues to report the actual connection. The cache capture marker and initial-load action gates remain set internally: no runtime attachment, sending, editing, attachment, or pagination authority comes from preview rows. Reads and publication retain the captured profile, origin, credential lease, workspace and operation generation; a disposed/superseded owner or late cache result cannot replace a host publication. Host history replaces the preview without merging cache rows into it. A reconnect refresh of an already displayed snapshot is similarly quiet while its owned read runs. At completion, unsuccessful hydration retains the existing labelled offline fallback. No protocol, cache schema, retention, key storage, retry or migration policy changes; drafts and pending turns stay separate, and refresh never resends a prompt.

## Prompt submission and reconnect safety

`TurnCoordinator` serializes prompt submissions with a mutex and records `PREPARED` then `WRITE_ATTEMPTED` before calling `prompt.submit`.

- A matching success produces `CONFIRMED_ADMITTED`.
- Any write/result ambiguity becomes `UNKNOWN`.
- Reconnect and process restoration reconcile against server history; they do not call `prompt.submit` for the old pending-turn ID.
- A user-selected retry creates a new pending-turn record and ID before a new write.
- `GatewayReconnectManager` implements and tests a bounded foreground retry budget,
  suspension of scheduled retry/heartbeat in background, terminal auth/policy stops,
  and fresh-ticket acquisition per attempt. The production composition wires those
  guarantees: `ConnectViewModel` arms the manager's configured origin on a successful
  gateway connect and clears it on explicit disconnect, `MainActivity` forwards app
  foreground/background, and the manager schedules its bounded retry when the
  connection reports FAILED/OFFLINE while foregrounded with a configured origin
  (re-connecting immediately on foreground if the socket is down). Reconnect is
  therefore a user-reachable composition path, still gated by foreground,
  configured-origin, and terminal-failure conditions.

PR-7 reconnect gating: on each hard edge into connected the chat status collector's second consumer issues exactly one `loadSession(reconnectReplace = true)` while PR-3's labelled offline snapshot is presented — remote history always replaces the cached rows (a failed replace retains the `Saved snapshot — offline` strip with the composer still gated, and `NO_RUNTIME` is never armed by this lane) — while the shell's reconnect `LaunchedEffect` keys `isOfflineCache`/`hasLocalCacheError` as composition-observed StateFlow reads (fixing the read-once stale-key gap) and an open CACHED Workspaces sheet reloads through the pure, GET-only `CacheReconnectGatePolicy` gate (edge + open + CACHED + no in-flight optimistic switch, retain-on-error).

What that reconnect path now does for chat-stop (merged PRs #125/#126):
`DefaultAppContainer` arms a composition-time `PendingInterruptFlushObserver` that,
on each observed CONNECTED transition, flushes vault-held pending `session.interrupt`
stops — delivered only against a live runtime session id resolved from current
session state (never the persisted snapshot), within the 60 s hold TTL, at most once
per connection generation. The flush delivers interrupts only; it never writes
`prompt.submit`.

`GatewayProtocolAdapter.replayEventsSince` has exactly one production caller: the
post-resume replay-watermark seed inside `DefaultSessionRepository.resumeSession`
(it seeds live-event sequencing; it does not drive recovery on its own). Current
production recovery must not be described as automatic replay/reconciliation
until composition-level behavior proves the full origin, lifecycle, reconnect,
replay, and history-replacement path.

This preserves the core invariant: no automatic prompt resend, even when delivery status is uncertain.

## Persistence, Keystore, and diagnostics

`EncryptedVault` stores encrypted records under `noBackupFilesDir/vault`. Its production default is `AesGcmCipherEngine(AndroidKeyStoreMasterKeyProvider())` with AES-256-GCM and associated data. Android Keystore errors surface as `KeyStoreUnavailableException`; there is no production software-key fallback. `InMemoryMasterKeyProvider` exists for tests only. Android backup is disabled in the manifest.

Persisted records include the connection profile, origin-bound auth material, selected durable session, bounded session summaries (schema V3, partitioned per active profile, origin hash, cache epoch, and workspace scope under `noBackupFilesDir/vault/cache/`, with a durable cache-access rotation state written before each guarded credential or origin mutation), offline conversation snapshots (schema V2 conversation records under the `cache/<sha256(profile)>/<sha256(profile:origin)>/` subtree, each partition's `conversation-index:<P>:<O>:<E>:<W>` as the sole existence authority, and an opaque global conversation catalog as maintenance metadata rebuilt by recovery), drafts, pending turns, and relevant local metadata. Conversation snapshot records also carry an optional bounded `localTrace` list — the phone-captured tool-trace events (schema V2 optional field: records written before it parse as an empty list; each row holds only a local event id, source seq/type, tool id, name, redacted context label, status, duration, observed time, and admitted runtime id) capped at 128 events and 256 KiB with oldest-whole-event eviction. A legacy unpartitioned summary cache file from an earlier schema is renamed to a discard artifact on first encounter and never surfaced. WebSocket tickets are never persisted. Local sign-out/reset does not remotely delete Hermes sessions. On sign-out a single confirmation dialog (hosted at the shell's shared sign-out funnel) offers keep-vs-clear of this device's encrypted cache with keep as the default; either choice durably rotates the cache epoch to `SIGNED_OUT` before the credential file is removed, and clear additionally purges the profile's cache subtree plus its validated conversation-index envelopes with a global-catalog reconcile — always local, never remotely destructive. Settings exposes two confirmation-gated local reset actions: **Clear offline cache** runs a mutex-held seven-phase deletion of cached conversation content, per-partition conversation indexes, the global conversation catalog, roster records, and session summaries for every partition (prefix-scoped inside `cache/` so the durable cache-access rotation state, credentials, connection profiles, session selection, drafts, pending turns, and diagnostics all survive), and **Clear all local app data** runs the local sign-out teardown first and then the existing full vault wipe — and neither action performs any remote call or deletes a Hermes session on the server.

`PrivateDiagnosticsRepository` owns four fixed severity files in app-private storage. Diagnostic events use a typed catalog, bounded fields, sink/display redaction, and no raw credentials, headers, cookies, tickets, host/IP values, session identifiers, prompt/response text, or tool payloads. There is no client telemetry, analytics SDK, or remote crash-reporting path in the current dependency graph.

## Verification seams and evidence limits

- JVM tests cover repositories, protocol parsing/replay, redirect rejection, origin/cookie boundaries, no-duplicate turn handling, redaction, encrypted-vault behavior with test keys, ViewModels, keyed chat-owner disposal, shell cleanup policy, exact-runtime-ID event admission, tool identity correlation, the offline conversation snapshot cache (store semantics, crash-window recovery, catalog rebuild, caps/eviction, purge/reset, offline serve, and composition wiring), the phone-captured tool trace (append store semantics, exact-tuple dedupe, count/byte caps with whole-event eviction, TTL non-extension, CON-01 carry-forward, zero-payload sentinel, pure projection policy, chat hydration/admission, and byte-exact provenance copy), the offline/reconnect gate (pure edge/gate matrix in `CacheReconnectGatePolicyTest`, shell and label-source contracts in `CacheReconnectGateSourceContractTest`, reconnect-triggered snapshot replacement in `ChatSnapshotReconnectTest`, and the extended `SessionsReconnectPolicyTest` truth table), and source/manifest policies. The owner tests inject their own provider and therefore do not prove the production composition wiring described above.
- `src/androidTest` contains Android Keystore vault and Compose semantics tests. `assembleDebugAndroidTest` only proves that these tests package.
- Actual Android Keystore behavior, Compose behavior on a device, cleartext networking on packaged Android, owner-host interoperability, and production reconnect/replay composition require explicitly selected device/manual or composition-level execution. They are not established by JVM/component tests or APK assembly.
- The canonical commands and evidence labels are in [`../testing/verification.md`](../testing/verification.md) and [`../testing/android-qa-matrix.md`](../testing/android-qa-matrix.md).

## Change rules

Preserve these boundaries unless a current decision explicitly changes them:

1. `UI -> ViewModel -> repository/coordinator -> adapter -> transport` for new dependencies.
2. Server-authoritative session/history and title state.
3. No automatic prompt resend after any ambiguity.
4. One fresh, memory-only WebSocket ticket per socket attempt.
5. Exact canonical origin, disabled redirects, no trust bypass or silent scheme change.
6. Fail-closed Android Keystore with no production software fallback.
7. No secrets or private content in logs, fixtures, evidence, or generated reports.
8. Fixed HTTP is the current executable transport; historical HTTPS-only text must be labeled as such.

## Settings host insights (2026-10-04)

Settings retains the implemented Brain entry and places Health, Analytics, and Gateway logs in the host group. New dependencies follow `Compose -> HostInsightsViewModel -> HostInsightsRepository -> HostInsightsAdapter -> HostInsightsTransport`, using existing header-first AuthRepository exchanges and captured exact connection/auth/gateway ownership. Health separates selected-context gateway status from machine-wide system metrics; Analytics shows host usage for a page-local choice from the live profile roster, defaulting to `default` or the first listed profile. That choice does not change the app's selected context profile or persist across page visits. Analytics is not client telemetry. Missing metrics remain unavailable and partial Health sections remain independent.

The Gateway logs viewer defaults to Gateway and retains Agent, Errors and Desktop in a source picker. It shows a manually fetched 200-line snapshot in compact wrapping rows; selected rows expose complete text. It supports deliberate line, selection, and filtered-tail copy. Raw host text stays in page memory; it never enters phone diagnostics, disk caches, or retained evidence. Both native copy actions and Compose selection clipboard paths mark content sensitive. Screen/profile/connection changes and backgrounding clear page data and cancel requests. There are no host mutations or background network polls. Installed source availability does not certify deployed-host compatibility.

## Work: Crons

Work now hosts the Crons feature, with room for a future Kanban page. Profile
chips default to All and filter the host-wide job list locally, independently of
workspace selection. The lifecycle-owned WorkCronsViewModel delegates management
to CronRepository -> CronHttpAdapter -> AuthHttpTransport. The adapter binds
management to the credential/connection partition used for the last successful
list; uncertain writes require an explicit read reconciliation and are never
retried automatically. Jobs, run observations and editor drafts are memory-only.
Wall-clock schedules use the owning Hermes profile timezone, since the reviewed
API has no per-job timezone field.
This describes source integration, not owner-host or device execution evidence.

## Delegated agents in chat

`ChatViewModel` forwards admitted child lifecycle events from its existing exact-runtime collector to `SubagentChatController`, which owns visible-screen polling and sheet state. `DefaultSubagentRepository` owns bounded roster reduction, stale-response rejection and control admission through captured session authority. Optional `subagent.list`, `subagent.tail`, `subagent.steer` and `subagent.interrupt` RPCs use the adapter's origin/generation-scoped single-send correlation; missing optional support does not alter chat readiness.

Delegation tool fields produce bounded task descriptions. Only observed native identities authorize controls. Historical rows associate with the roster through explicit child or delegation/task identity; an unambiguous current-turn goal can associate a live tool description. Native terminal outcomes remain settled; missing or unknown outcomes never imply success. Live roster/activity, selected transcript tails and steering drafts stay in memory and do not enter conversation snapshots or tool-trace caches. Background observation stops polling and clears sheet payloads. Steer acknowledgement means queued; interrupt acknowledgement means requested. Ambiguous control outcomes are displayed without automatic resend.

## Bot Screen viewer (implementation candidate, 2026-10-08)

The selected bot's Bots header and its chat header open one foreground-only, dedicated Android screen route. `BotScreenScreen -> BotScreenViewModel -> BotScreenRepository -> BotScreenProtocolAdapter/GatewayProtocolAdapter -> RfbScreenStream` keeps RPC, tickets, viewer identities and RFB wire details below presentation. Hermes supplies the per-profile Xvnc screen and authoritative lease; Android starts a stopped screen only on tap, observes it with one-use display tickets, watches by default, and sends pointer/keyboard input only after an acknowledged takeover. The native RFB client requests bounded raw 32-bit pixels; it does not use WebView, clipboard sync, screen storage or background streaming. The viewer does not set `FLAG_SECURE`, allowing owner-initiated Android screen recording and screen search. Back/background closes the stream and checks whether its viewer still owns the lease before release; an unconfirmed handback is not presented as bot control. Synthetic JVM/packaging checks do not certify a deployed host or physical-device touch and performance.

The selected bot's Bots header also reads profile-scoped screen status while foregrounded. Its running/stopped/unknown label comes from Hermes, not the prior Android viewer state. Confirmed **Stop screen** sends one non-force `display.stop` through `BotsViewModel -> ScreenOverviewRepository -> GatewayProtocolAdapter`, then reconciles status; human-held screens cannot be force-stopped from Android. Closing the viewer remains a disconnect, separate from stopping the host desktop. Hermes does not provide per-screen CPU/RAM usage to this client.

## Foreground composer dictation

The main conversation composer supports explicit microphone recording → Stop → host transcription → editable draft. VoiceDictationController owns permission/operation generations and presentation; ChatViewModel appends to the latest draft through existing encrypted draft persistence. Send remains explicit and blocked during dictation. Production direction is UI → ViewModel → VoiceDictationCoordinator/VoiceRepository → VoiceProtocolAdapter → VoiceHttpTransport. Existing adapter debt is not expanded.

AndroidVoiceRecorder captures at most 120 seconds of mono 16 kHz PCM16 and packages WAV entirely in bounded memory. Background, rotation, Back, route exit, connection/owner invalidation and ViewModel clear cancel work; audio is discarded after every terminal path. No audio files, background service, provider keys, client-direct transcription or speech output are introduced. HTTP POST `/api/audio/transcribe` uses captured profile (explicit default otherwise), canonical origin, existing authenticated exchange with successor cookie receipt admission, disabled redirects/retries and bounded response parsing. Deployed-host and physical-device evidence remain separate.
## Session approvals in visible chat (2026-10-07)

`ChatScreen -> ChatViewModel -> ApprovalChatController -> DefaultApprovalRepository -> GatewayProtocolAdapter` renders a memory-only FIFO card directly above the composer. Reject, Run once, This session and Always allow are direct actions when the host permits each scope; permanent allowance requires confirmation. Pending decisions gate send while preserving editable drafts and the existing Stop action. Backgrounding clears presentation and stops polling; returning or reconnecting reconciles the exact current runtime. There are no session badges or Android notifications.

Captured session activity authority binds reads and decisions to the saved connection, canonical origin, selected profile, auth authority and socket generation. Live server requests use their original socket response ID; restored requests use the exact queue ID with `all:false`. Decisions are single-flight and never retried automatically. Unknown outcomes stay disabled until a successful pending-state read; receipt means sent, not command completion. Bounded strict parsing and snapshot revision guards reject malformed/foreign/stale updates. Approval commands and IDs are not persisted or added to diagnostics/transcripts.

## Editing a user message

The bottom composer has a memory-only edit mode independent of the encrypted normal draft. Complete raw plain-text user rows and confirmed local plain-text submissions carry durable edit targets; compact display-only text and cached snapshots do not authorize an edit. Attachments, skill invocations and truncated/transformed content are excluded.

`ChatViewModel -> TurnCoordinator -> SessionRepository` captures authenticated ownership, reads authoritative history, interrupts a running turn and waits for typed idle evidence before one scoped rewind-aware submission. An edit never falls back to an appended prompt. Unknown edit attempts retain encrypted operation/target/owner evidence and require explicit history review; ordinary text-matching reconciliation and retry cannot consume them. The host owns truncation, archives and subsequent generation; prior external actions are not undone.

## Local chat FIFO

Production chat injects a vault-backed queue coordinator through the ViewModel. An idle text prompt with no retained queue work uses the normal direct-send path. Prompts entered while chat is busy or earlier queue work exists, and parsed queued commands, are local intent, never remote transcript rows. Ten waiting entries and one active entry run in order. Enqueue and waiting prompt edits/cancellations are accepted only after encrypted, partition/workspace/conversation-bound persistence; compared writes reject stale owners and reset residue. Existing installations have no queue record and start empty; no Bot Android data migration occurs.

Prompt dispatch reuses TurnCoordinator and its single-write pending ledger, waiting for attributed terminal completion and ledger settlement, including when queued work follows a direct first prompt. Queued writes require repository-captured PromptSubmissionAccess; queue/owner authority is rechecked after ledger IO and exact origin/socket generation are checked under the adapter connection lock before transport. Unsupported authority fails closed. A terminal observed before acknowledgement is held in memory until admission settles; unknown admission never releases dependent work. Commands reuse the slash/picker/compression repositories and confirmation contracts. YOLO waits for freshly confirmed state. Only waiting entries appear as compact, right-aligned pills directly above the composer, including after a stream stops or saved waiting intent is restored. There is no inline heading, count, numbering, active-turn status or pause caption; long previews ellipsize on one line. Paused waiting intent has a compact explicit Resume queue control; normal idle progression can be paused inline. Failed, uncertain, confirmation-required or storage-error outcomes retain a separate unnumbered review dialog. Showing pills never resumes progression or retries a write. Waiting prompts have distinct edit and cross-shaped cancel touch targets on the right; editing in a dialog preserves identity and position. Commands allow cancellation only. Stop, approvals and navigation remain immediate. Attachments and dictation require an idle chat and an empty queue.

Background/route leave, disconnect, Stop, restore and failures pause progression. Rotation retains the keyed chat owner. Restored active dispatch becomes unknown and never automatically replays. Status review, dismissal and explicit Resume require authoritative idle evidence after recovery. An explicit failed-item retry creates a new attempt. Channel-first-prompt handoff waits for initial hydration and queue restoration without retrying failed, superseded or cancelled intent. Production encryption stays Android-Keystore-backed and fails closed. Current Mobile verification is recorded in the [queue checkpoint](../design/chat-queue/checkpoint.md).
