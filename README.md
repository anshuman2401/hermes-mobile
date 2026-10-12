# Hermes Mobile

**Your Hermes conversations, bots, and work on Android.**

Hermes Mobile is a native Android client for a compatible, owner-controlled [Hermes Agent](https://github.com/NousResearch/hermes-agent) host. Chat with your agent, follow its responses and tool activity, and manage supported host features from your phone. The agent runs on your host; this repository contains the Android app.

Built with Kotlin and Jetpack Compose · Android 8.0+ · Pre-1.0

[Features](#features) · [Getting started](#getting-started) · [Build from source](#build-from-source) · [Contributing](CONTRIBUTING.md)

Hermes Mobile is an independent, unofficial client and is not affiliated with or endorsed by Nous Research.

## Features

Feature availability depends on your host's APIs and permissions. The app includes:

| Area | What you can do |
| --- | --- |
| Conversations | Browse, search, and pin sessions; send prompts; follow streamed responses, Markdown, code blocks, and tool activity. |
| Chat controls | Stop a turn, queue follow-up text prompts, and edit eligible user messages. Review tool approvals and delegated-agent activity when supported. |
| Voice and files | Dictate an editable draft through host transcription; preview and export supported conversation files. Sending a dictated draft remains explicit. |
| Bots and workspaces | Switch context profiles, choose favourite bots, and open their channel conversations. |
| Work | Manage scheduled jobs, work with Kanban tasks, and browse managed files through the host's supported APIs. |
| Brain | Configure the selected profile's main and auxiliary model settings, and browse installed skills. |
| Bot screen | View a bot's host desktop and request control when the host supports screen sharing. |
| Settings and insights | Choose a theme, manage saved connections, and view host health, usage analytics, logs, and update status. |

Saved drafts and eligible conversation previews help you return to your work. Offline previews are labelled caches; the host remains authoritative for conversation history. An uncertain prompt submission is never automatically resent.

## Getting started

You need a **compatible Hermes host you control**. This repository does not yet identify a verified public host distribution/revision with a reproducible installation guide. An arbitrary upstream Hermes Agent installation is not guaranteed to provide this client's APIs; obtain the compatible version and setup instructions from your host maintainer.

1. Build the Android app using the instructions below and install the debug APK on your device.
2. Open the app and add a connection label, host, and port.
3. Sign in when prompted, then select a workspace or conversation.

The current connection form uses **HTTP** and displays a cleartext warning. Use an owner-controlled private network or an encrypted tunnel you configure. The app cannot detect or guarantee tunnel protection. There is no bundled host or public demo endpoint.

For protocol details, see the [connection and authentication contract](specs/architecture/current-system.md#connection-origin-and-auth-flow). The historical [synthetic fixture manifest](app/src/test/resources/fixtures/v0.21.1/manifest.json) is test evidence, not a supported-host version matrix.

## Build from source

Install these prerequisites:

- Git, **JDK 21**, Bash, Python 3, and `flock` for the shared build lock.
- Android SDK **Platform 37** and build tools compatible with Android Gradle Plugin 9.3.0.
- Network access for the first Gradle/dependency download, and repository access while it is private.

The checked-in Gradle wrapper supplies Gradle; a separate installation is unnecessary. Configure SDK discovery with `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) or an ignored `local.properties`. See the [toolchain reference](specs/build/toolchain.md) for exact versions.

```bash
git clone https://github.com/anshuman2401/hermes-mobile.git
cd hermes-mobile
java -version                       # confirm JDK 21
hermes_git_common_dir="$(git rev-parse --path-format=absolute --git-common-dir)"
flock --exclusive "$hermes_git_common_dir/hermes-mobile-gradle.lock" ./scripts/verify.sh
```

The verifier runs policy checks, JVM tests, Android lint, and debug APK assembly. It needs no host, real credentials, device, or release keystore. Use the [shared Gradle lock](specs/testing/verification.md#shared-gradle-lock).

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it with Android's package installer or `adb install app/build/outputs/apk/debug/app-debug.apk` on your chosen device. Test and lint reports are under `app/build/reports/`.

Debug and release installations have separate application IDs and local storage. See [app identity, versioning, and release signing](specs/build/release.md) before distributing a build. Keep signing material, local SDK paths, credentials, and private host data out of Git.

## Privacy and current limits

- Saved connection/auth data and drafts use app-private storage protected by Android Keystore. Android backup is disabled; the app has no telemetry. Host usage analytics are a host feature, not phone telemetry.
- Connected features depend on host compatibility and permissions. Local cache views do not establish current server state.
- The app supports Android API 26+, targets API 36, and compiles against API 37. This does not imply testing on every supported device or Android version.
- The verifier checks code and builds a debug APK; it does not establish physical-device behavior, live-host compatibility, or release readiness. See the [QA coverage matrix](specs/testing/android-qa-matrix.md) for the separate evidence tiers.

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md) and the [contributor rules](AGENTS.md). The [architecture guide](specs/architecture/current-system.md) explains the app's boundaries, and the [documentation index](specs/README.md) links deeper references.

Run the [local verifier](specs/testing/verification.md) for executable changes. GitHub Actions is [manual-only](.github/workflows/verify.yml), so pushes and pull requests do not start hosted runs. Documentation-only changes need content/link review and `git diff --check`.

Report vulnerabilities through the channels in [SECURITY.md](SECURITY.md). Do not post credentials, private host addresses, or owner content in public issues. Participation follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Project-authored code and documentation use the [MIT License](LICENSE), except where otherwise noted. Fonts, libraries, tooling, and the adapted Code of Conduct retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
