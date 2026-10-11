# Hermes Mobile contributor rules

This file is the repository's single contributor-facing engineering rulebook. Read it before changing the Android app. Requirements and architecture documents describe behavior; dated plans and test reports are historical evidence, not standing instructions.

## Scope and workflow

- Hermes Mobile is the primary app and the default target for future feature work. Hermes Bot Android is reference-only unless the maintainer explicitly requests work there; leave its existing branches and commits unmerged.
- Work on this Android repository only. Inspect compatible host contracts as needed, but do not change host infrastructure as part of an Android contribution. Keep the native app design original and respect third-party licenses and marks.
- Fetch `origin/main`, inspect its current code and this file, and make changes on an isolated task branch or worktree. Do not overwrite another checkout's work. Recheck integration before proposing a merge.
- Keep each change bounded. Inspect definitions, call sites, affected consumers, test fakes, and relevant contracts before editing. Record protocol, security, persistence, ownership, retry, and migration decisions with the change.
- Explain existing behavior, changed behavior, verification, and limitations in the pull request. Do not present a plan, old test result, or compiled APK as proof of current device or host behavior.
- A merge, release, deployment, visibility change, history rewrite, credential change, or external outreach requires explicit maintainer authorization. Preserve unrelated branches, worktrees, and historical evidence.

## Architecture and behavior

- Preserve `Compose UI -> ViewModel -> repository/coordinator -> protocol adapter -> transport`. UI renders immutable state and emits intents; network, persistence, replay, and auth belong behind repository or coordinator interfaces. Do not add direct ViewModel-to-adapter dependencies.
- A multi-step ViewModel operation owns its state transitions until completion or cancellation. Background disconnect observers react from steady state only.
- The host is authoritative for sessions, titles, transcripts, and turn outcomes. Local summaries and snapshots are labelled caches; drafts and pending-turn evidence never become remote history.
- A local pending turn gets at most one automatic `prompt.submit` write. An ambiguous outcome becomes `SUBMISSION_UNKNOWN`; reconnect, restart, and replay reconcile history without automatic resend. An explicit retry is a new attempt.
- Mint a fresh, short-lived, single-use WebSocket ticket for each socket attempt. Keep tickets in memory only.
- Construct REST and WebSocket targets from `CanonicalOrigin`. Reject ambiguous input, origin changes, and redirects. Do not add trust bypasses or automatic scheme changes.
- The current app uses structured HTTP for an owner-controlled private-network endpoint. Preserve its cleartext warning and exact-origin controls. The app cannot attest that a VPN or tunnel protects the route.
- Production vault encryption uses Android Keystore and fails closed with `KeyStoreUnavailableException`. Software or in-memory keys are test seams only.
- Preserve app-private storage, disabled Android backup, no telemetry, and no remote session deletion from local reset or sign-out.

## Privacy and tests

- Never commit or include credentials, cookies, headers, tickets, owner content, session identifiers, private hosts or addresses, local machine paths, or runtime logs in code, fixtures, screenshots, examples, issues, or evidence. Use synthetic data and fixed redacted categories.
- Add behavior tests for stateful, auth, persistence, concurrency, replay, and failure changes. Keep JVM tests deterministic under low-core scheduling; do not make them silently emulate platform security. Do not use a live host or real credentials in automated verification.
- For executable changes, choose the smallest meaningful focused checks first, then run `./scripts/verify.sh` from the repository root. It runs policy checks, JVM tests, lint, and debug APK assembly. It does not run instrumentation or certify device or host behavior.
- After committing an executable candidate with a clean tracked tree and no untracked files, run `./scripts/verify.sh --final-candidate` in the same worktree. Record the actual test and lint summaries, report paths, Git revision, and emitted APK provenance. Rerun applicable checks when inputs change. Serialize Gradle invocations across worktrees using the repository's shared lock.
- Documentation-only changes need content and link review plus `git diff --check`; do not run the full verifier unless requested.
- Device and emulator checks may be run separately with synthetic data. Record what was actually executed and do not infer device behavior from JVM tests or assembly.

See [current architecture](specs/architecture/current-system.md), [verification](specs/testing/verification.md), [QA coverage](specs/testing/android-qa-matrix.md), and [contributing](CONTRIBUTING.md) for implementation detail.
