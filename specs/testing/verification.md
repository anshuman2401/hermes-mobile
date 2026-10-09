# Android verification runbook

`./scripts/verify.sh` is the canonical repository verification entry point. Run it locally; this repository has no GitHub Actions verification workflow.

## Evidence tiers

| Tier | Meaning | Canonical evidence |
|---|---|---|
| Static/JVM | Policy, local deterministic behavior, compilation, and lint | policy script, `testDebugUnitTest`, `lintDebug` |
| Package | The debug application APK can be assembled | `assembleDebug` |
| Device/emulator | Android platform and UI behavior from separately executed tests | Actual execution report tied to tested APK identity and non-secret API/model |
| Owner/manual | Real UI, network, auth, and private host behavior on the exact APK | Manual record tied to debug APK SHA-256 |

A higher tier is never implied by a lower one. Package evidence is not execution evidence.

Device/emulator testing is allowed. This supersedes the former 2026-09-18 prohibition; historical plans and evidence retain the policy that applied to their recorded runs. Device execution is separate from the default verifier, which has no `--device` mode.

## Prerequisites

- Run from any location inside a Git checkout; the script resolves the repository root itself.
- Use JDK 21 and an already-installed Android SDK compatible with this project.
- Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` when the environment does not already expose the SDK.
- Do not install SDK components, create keystores, authenticate, or contact a live/private Hermes host as part of this runbook.
- Keep real credentials, cookies, tickets, host/IP values, owner content, and private filesystem paths out of command output and retained evidence.

## Documentation-only changes

Changes confined to documentation (including `.md`, documentation `.txt`, `.doc`, `.docx`, and `.pdf` files) do not require JVM tests, lint, APK assembly, or the final-candidate gate. Review content and links, and run `git diff --check` for text changes. Do not run the full verifier unless the user explicitly requests it.

This exemption applies to documentation content, not executable/build inputs based solely on their extension. Changes to code, tests, resources, dependencies, build configuration, executable scripts, or CI workflows require the full gate below. Mixed changes require the full gate. Existing unrelated workspace changes do not expand the verification scope of a documentation-only task.

## Default local gate

```bash
./scripts/verify.sh
```

The script prints Git HEAD, tree, branch/status, then runs in order:

1. `./scripts/ci-policy-check.sh`
2. One default Gradle invocation: `./gradlew testDebugUnitTest lintDebug assembleDebug --stacktrace`

There is exactly one default Gradle call and it runs exactly those three tasks. Instrumentation packaging is not part of this gate. An unknown argument still exits `2` before any gate step. Failure propagation is unchanged: the invocation stops on the first failed task with a nonzero exit and the script never prints `PASS` after a failure.

After the combined Gradle invocation succeeds, it summarizes generated JUnit XML and lint XML when those reports are present and prints their paths. Preserve the actual counts; do not rewrite a warning count as “clean” or claim zero warnings without the report. On failure, inspect the available reports directly.

## Final-candidate gate and APK provenance

For changes requiring the full verifier, run only after the candidate is committed and the tracked worktree is clean:

```bash
./scripts/verify.sh --final-candidate
```

Before building, this mode rejects:

- any untracked, non-ignored file;
- staged changes; and
- unstaged tracked changes.

After a successful gate, it reads AGP's generated debug `output-metadata.json`, verifies the declared APK exists, and emits:

- candidate Git HEAD and tree;
- APK path;
- SHA-256;
- byte size;
- application package ID;
- build variant;
- version code and version name.

Copy these values directly into a handoff. The debug APK package is expected to include the debug application-ID suffix, but the generated metadata—not a hand-written value—is the evidence source. Any source change invalidates previous build and artifact evidence.

## Connected/device evidence

There is no connected-device gate, no `--device` flag, and no `ANDROID_SERIAL` handling anywhere in the verifier. Device/emulator execution is allowed separately when the required build configuration and device/emulator are available. Record the actual command, result, non-secret API/model, and tested APK identity; mark unavailable or unexecuted checks `BLOCKED` or `NOT RUN`. Real UI, network, auth, and private-host behavior is recorded under the owner/manual tier as a manual record tied to the debug APK SHA-256 — never as a verifier result, and never inferred from an assembled APK.

## Reading failures

- **Policy failure:** fix the policy violation; do not bypass `ci-policy-check.sh`.
- **JVM failure:** use `app/build/reports/tests/testDebugUnitTest/index.html` and XML under `app/build/test-results/testDebugUnitTest/`.
- **Lint failure or warnings:** use `app/build/reports/lint-results-debug.html` and `lint-results-debug.xml`; retain severity counts.
- **Assembly failure:** report which APK task failed. Do not claim package evidence.
- **Unknown argument:** the script prints the usage text and exits `2` before any gate step.
- **Missing SDK:** mark the affected tier `NOT RUN` or `BLOCKED`; do not substitute assembly for execution.

## Focused development checks

The sequencing policy lives in [repository AGENTS.md](../../AGENTS.md#privacy-and-tests). This section supplies technical commands, not another approval or delivery policy.

Choose selectors from the actual implementation, tests and directly affected consumers before the first development run. Record them in the existing feature checkpoint. A focused command limits **test execution**; it does not promise that Gradle will compile only those test files or avoid necessary module/dependency compilation. No time saving is guaranteed without measuring the task results.

A bounded turn-coordination change can start with:

```bash
./gradlew testDebugUnitTest --tests 'io.github.anshuman2401.hermesmobile.data.turn.*' --stacktrace
```

For assistant Markdown changes, this command includes formatting, interaction policy, structural consumers, copying, Unicode/bounds and the performance gate. Keep or extend the applicable selectors when new tests/consumers are added; an unrelated change does not need this suite.

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.MarkdownParserTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.MarkdownRenderingRegressionTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.MarkdownStructureTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.CodeBlockBudgetTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.ExternalLinkPolicyTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.TranscriptCopyPolicyTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.markdown.ReplyFormatStyleContractTest' \
  --tests 'io.github.anshuman2401.hermesmobile.hardening.RenderingPerformanceAndBoundsTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.ChatUiPresentationSourceContractTest' \
  --tests 'io.github.anshuman2401.hermesmobile.feature.chat.components.CopyIconOnlySourceContractTest' \
  --tests 'io.github.anshuman2401.hermesmobile.design.ThemeAndContrastTest' \
  --console=plain --stacktrace
```

Use the established shared Gradle lock for these commands as required by AGENTS.md. Inspect the complete available JUnit failure list, not only the first console failure. Preserve the focused aggregate, failed test names and concise result in existing feature evidence before a full run replaces the reports under `app/build/test-results/testDebugUnitTest` and `app/build/reports/tests/testDebugUnitTest`.

For executable changes, after focused checks pass, the required full gate remains:

```bash
./scripts/verify.sh
```

After committing the ready candidate, keep the same worktree and valid outputs and run:

```bash
./scripts/verify.sh --final-candidate
```

Let Gradle decide whether unchanged tasks are `UP-TO-DATE` or `FROM-CACHE`; record that status honestly. Do not describe reused reports as newly executed tests. Do not delete outputs or force task execution merely to obtain a fresh-looking receipt. A diagnosed cache/output problem, relevant environment/input change, or explicit request can require fresh execution. A full-gate failure still requires diagnosis, repair and a passing full gate; repeating focused checks alone is insufficient.

Stateful/auth/protocol/security fixes need behavior-level regressions. Source assertions, a successful compile and synthetic harnesses do not establish device behavior. Documentation-only changes retain their exemption and do not run these Gradle commands by default.

## Verifier control-flow harness

```bash
python3 scripts/test_verify.py
bash -n scripts/verify.sh
```

The harness executes the real `verify.sh` in disposable Git fixtures under ignored `build/`, with explicitly synthetic policy/Gradle subprocess stubs and report/APK fixtures. It checks: policy-first invocation order and the exact three-task default argv; per-task failure exits without `PASS` or provenance; unknown-argument exit `2` before policy and `--help` with no gate step; final-candidate untracked/staged/unstaged guards; JUnit/lint summary parsing; synthetic APK provenance formatting including the closing scope note; and missing-metadata failure. There are no device/serial cases because the verifier has no device surface. Fixture counts and artifacts are synthetic, not JVM/package evidence. This supplements, never replaces, the actual verifier.

## Evidence checklist

Record only non-secret facts:

- exact Git HEAD and tree;
- `git status --short --branch` result;
- verifier mode and exact exit status;
- JUnit aggregate and report path;
- lint aggregate and report path;
- assembled artifact paths;
- final-candidate APK provenance when applicable;
- device/emulator and owner/manual rows explicitly marked `PASS`, `FAIL`, `BLOCKED`, or `NOT RUN` (never a verifier result).

Never attach raw runtime traffic, credentials, cookies, ticket values, headers, private origins, prompts/responses, tool payloads, app-private logs, or owner data.
