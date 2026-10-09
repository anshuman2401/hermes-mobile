# Hermes Mobile

Hermes Mobile is an independent Kotlin/Jetpack Compose Android client for a compatible, owner-controlled [Hermes Agent](https://github.com/NousResearch/hermes-agent) host. You can browse host sessions and conversations, send prompts, and follow streamed responses and tool activity. The host runs the agent and owns session and transcript history; this repository contains the Android app, not a host. Compatibility depends on the host's available APIs and version. This client is unofficial and is not affiliated with or endorsed by Nous Research.

## Requirements

- Access to this repository, Git, and network access to download the Gradle wrapper distribution and dependencies on the first build.
- JDK 21. The project uses the checked-in Gradle 9.5.0 wrapper; a separate Gradle installation is unnecessary.
- An Android SDK with **SDK Platform 37** and build tools compatible with Android Gradle Plugin 9.3.0. Install these with Android Studio's SDK Manager or the Android command-line tools before building. The project compiles against API 37, targets API 36, and supports devices running **Android 8.0 (API 26) or newer**. Those declarations do not mean the app has been tested on every supported Android version.
- Bash and Python 3 for the repository verification script. A device, emulator, release keystore, Hermes host, and private credentials are unnecessary for the local JVM/lint/debug build.

The declared toolchain versions are listed in the [toolchain reference](specs/build/toolchain.md). The Android SDK location is a local machine setting: set `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) in your shell, or create an ignored `local.properties` containing `sdk.dir=/path/to/android-sdk`. Do not commit that file, SDK paths, signing keys, or credentials. The debug build uses Android's normal debug signing setup; no release signing configuration is required.

## Set up and verify

From a fresh clone, install the prerequisites above and run. The clone command requires repository access; an unauthenticated browser may not be able to open the repository URL.

```bash
git clone https://github.com/anshuman2401/hermes-mobile.git
cd hermes-mobile
java -version                       # confirm JDK 21
./scripts/verify.sh
```

Set the SDK environment variable in your shell before the verifier if Android Studio has not already exposed the SDK, for example `export ANDROID_HOME=/path/to/android-sdk`. No Hermes endpoint or app configuration is needed for this command: the JVM tests use synthetic fixtures. The verifier runs repository policy checks, JVM tests, Android lint, and debug APK assembly. It does not run the app, package instrumentation tests, or test a device or host. Reports and the debug APK are generated under `app/build/`. See the [verification runbook](specs/testing/verification.md) for report paths and the clean final-candidate command. If verification cannot run, report the missing prerequisite or failing stage rather than treating assembly or cached output as device evidence.

The release application ID is `io.github.anshuman2401.hermesmobile`; debug builds use `io.github.anshuman2401.hermesmobile.debug`. These IDs are separate from earlier Android builds, so installing this app does not update those builds or transfer their saved local data.

For a documentation-only change, review the text and links and run `git diff --check`; the full verifier is unnecessary unless specifically requested. See [CONTRIBUTING.md](CONTRIBUTING.md) before proposing a change.

This repository has no GitHub Actions verification workflow. Run `./scripts/verify.sh` locally for executable changes and include the results in the PR as described in [CONTRIBUTING.md](CONTRIBUTING.md).

## First run and connecting

On a fresh install, the app opens its Connect setup. Enter a local connection label, the host or IP address, and port for a **compatible Hermes host you control**. The app checks the host's status and supported authentication before connecting; a compatible password-session host can then prompt for sign-in. A running upstream Hermes Agent alone is not proof that its status, authentication, WebSocket ticket, and gateway capabilities match this client. The [current architecture](specs/architecture/current-system.md) describes the app's protocol boundary.

The current connection form uses HTTP only and displays a cleartext warning. Use an owner-controlled private network or encrypted tunnel you set up; the app cannot detect or guarantee its protection. Do not enter real credentials into a test setup or attach them to issues or build logs. Once connected, the app requests sessions and conversation history from the host. A new install has no local session history; the app does not create or bundle a Hermes host or sample server data. Saved local state may make a returning install open Sessions while reconnecting, and offline rows or snapshots are labelled local caches rather than host truth.

## Known limits

- Host compatibility and optional capabilities vary by deployment; there is no bundled host or public demo endpoint. Live-host sign-in and conversation behavior require separate owner testing.
- The verifier proves static/JVM checks and debug APK assembly only. Android Keystore, UI behavior, network routing, and device/API coverage need separate device or manual evidence; see the [QA matrix](specs/testing/android-qa-matrix.md).
- The app is currently HTTP-only. Cleartext traffic is not protected by the app itself, and it cannot attest to VPN or tunnel protection. See the [connection architecture](specs/architecture/current-system.md#connection-origin-and-auth-flow).
- Local session summaries and offline conversation snapshots are bounded caches. The host remains authoritative for titles, transcripts, and turn outcomes; an ambiguous prompt submission is not automatically retried.

For engineering work, read the repository [rules](AGENTS.md), [documentation guide](specs/README.md), and [current system](specs/architecture/current-system.md).

## License

Project code and documentation are available under the [MIT License](LICENSE). Bundled fonts and third-party libraries retain their own licenses, summarized in [third-party notices](THIRD_PARTY_NOTICES.md).
