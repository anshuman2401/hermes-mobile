# Android build toolchain

Repository declarations are authoritative for versions. This reference replaces the old BLD-001 machine snapshot; it does not certify an installed environment or release signing setup.

| Component | Declared version | Source |
|---|---|---|
| Gradle | 9.5.0, pinned distribution checksum | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.3.0 | `gradle/libs.versions.toml` |
| Kotlin / Compose compiler plugin | 2.4.20 | `gradle/libs.versions.toml` |
| Compose BOM | 2026.08.00 | `gradle/libs.versions.toml` |
| Java compatibility | 21 | `app/build.gradle.kts` |
| Compile SDK | 37 | `app/build.gradle.kts` |
| Min / target SDK | 26 / 36 | `app/build.gradle.kts` |

Use JDK 21 and an already-installed compatible Android SDK. Configure SDK discovery locally through ignored `local.properties` or `ANDROID_HOME`/`ANDROID_SDK_ROOT`; keep machine paths out of shared documentation. Do not install SDK components or create signing material as part of verification.

`settings.gradle.kts` declares Google, Maven Central, and the Gradle Plugin Portal, with project repository declarations prohibited. Inspect it and the version catalog before any dependency change; this document does not authorize upgrades.

Documentation-only changes need content/link review and `git diff --check` for text, without tests, lint, or APK assembly unless explicitly requested. For code, test, resource, dependency, build, script, or CI changes, run [the canonical verifier](../testing/verification.md):

```bash
./scripts/verify.sh
```

It runs policy checks, JVM tests, lint, and debug app assembly. The verifier does not package instrumentation tests or execute device/emulator tests; separate device/emulator testing is allowed. Owner physical-phone checks remain a separate evidence tier. Release signing, distribution, and deployment require explicit authorization; debug assembly does not prove release readiness.
