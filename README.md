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

When running Gradle or the verifier, use the [shared lock procedure](specs/testing/verification.md#shared-gradle-lock) so worktrees cannot build concurrently.

For a documentation-only change, review the text and links and run `git diff --check`; the full verifier is unnecessary unless specifically requested. See [CONTRIBUTING.md](CONTRIBUTING.md) before proposing a change.

This repository has no GitHub Actions verification workflow. Run `./scripts/verify.sh` locally for executable changes and include the results in the PR as described in [CONTRIBUTING.md](CONTRIBUTING.md).

## App identity and versioning

The release application ID is `com.anshuman2401.hermesmobile`. Debug builds use `com.anshuman2401.hermesmobile.debug`; synthetic stream-profile builds use `.streamprofile`. The internal Kotlin namespace remains `com.hermes.bot`. Changing from the old application ID creates a separate installation with fresh app-private storage and Keystore keys; local data is not migrated automatically.

[version.properties](version.properties) is the single source for the Android version and the Settings footer. The initial public version is `0.1.0`, indicating a pre-1.0 app. Use `MAJOR.MINOR.PATCH`: while below 1.0, increment MINOR for features or breaking changes and PATCH for compatible fixes. From 1.0 onward, increment MAJOR for breaking changes, MINOR for compatible features, and PATCH for compatible fixes. Reset lower components when incrementing a higher one. Version numbers have no leading zeros.

For each distributed update, change `VERSION_NAME` as appropriate and increase `VERSION_CODE` above every previously distributed code for that application ID; never reset or derive the code from Git commit counts. The build rejects malformed names and codes outside Android's supported range. Debug builds append `-debug`, and Settings shows the installed build version. Keep the application ID and signing identity stable for future updates. Version edits do not publish a release; releases and tags require maintainer authorization.

## Host compatibility

This repository does not yet document a verified public host distribution/revision and reproducible host installation procedure. The upstream Hermes Agent link identifies the related project; it is not a guarantee that an arbitrary upstream release implements this client's APIs. Before using the app, obtain the compatible host source/version and setup instructions from your host maintainer.

The [fixture manifest](app/src/test/resources/fixtures/v0.21.1/manifest.json) records historical synthetic contract evidence labelled `v0.21.1`, with its source branch and commit. That label is not a current supported-version matrix or a verified upstream installation recipe. The [connection contract](specs/architecture/current-system.md#connection-origin-and-auth-flow) describes status discovery, password-session authentication, one-use WebSocket tickets, and gateway readiness/capability checks. Additional features depend on the APIs described in the same architecture guide. Successful compilation or fixture tests do not establish deployed-host compatibility.

You can build and run the local verifier without a host. Using connected features requires a compatible host you control; this repository supplies neither a host nor a public demo endpoint.

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

Project-authored code and documentation are available under the [MIT License](LICENSE), except where otherwise noted. The [Code of Conduct](CODE_OF_CONDUCT.md) is an adaptation of Contributor Covenant 3.0 under CC BY-SA 4.0. Bundled fonts, third-party libraries, and build tooling retain their own licenses, summarized in [third-party notices](THIRD_PARTY_NOTICES.md).
