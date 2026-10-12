# Release preparation

Publishing source and distributing an APK are separate actions. The [verification runbook](../testing/verification.md) covers policy, JVM tests, lint and debug assembly. None of those checks certify a release signing identity, Android device behavior or a deployed host.

## Signing

Debug builds use Android's normal debug signing setup. Release builds require all four explicit settings, supplied through local Gradle properties or environment variables: `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. Project properties take precedence over environment variables. Empty values count as missing. Keep values outside Git, reports, screenshots, command history and public CI.

The release pre-build gate rejects missing fields and a nonexistent/non-file keystore path. Release-variant lint/test tasks that share this pre-build step also require these settings; ordinary contribution checks use the debug variant. There is no fallback to the debug key or an unsigned release. AGP's signing tasks then validate the actual keystore, alias and passwords while producing the APK; passing the pre-build gate alone does not prove valid signing material. Do not point release settings at a debug keystore. Keep the chosen application ID and signing certificate stable across distributed updates. Creating or changing release credentials requires explicit maintainer authorization.

The regression harness uses synthetic settings and never creates a key. Run it under the shared lock with the existing JDK/SDK:

```bash
with_gradle_lock python3 scripts/test_release_signing.py
```

It verifies that an actual release build rejects absent settings, partial settings and nonexistent files are rejected, and debug preparation does not require release credentials. See the [shared-lock setup](../testing/verification.md#shared-gradle-lock) before using this shell helper.

## Candidate checks

1. Update `version.properties` when preparing a distributed update, preserving increasing version codes and stable app identity.
2. Run focused checks and the full local verifier, commit the candidate, then run `--final-candidate` in the same clean worktree. These receipts describe the debug artifact.
3. Review the exact dependency graph and [third-party notices](../../THIRD_PARTY_NOTICES.md), including required source material, before distribution.
4. With maintainer-provided signing settings, run `with_gradle_lock ./gradlew :app:assembleRelease`. No real signing credentials are needed for ordinary contribution or public CI.
5. Inspect the emitted release APK using Android SDK `apksigner verify --print-certs` and package inspection tools. Record its package, version, certificate SHA-256, APK SHA-256 and size; compare the certificate to the intended stable release identity. Do not publish private-key material or machine paths.
6. Exercise the exact release APK using the [QA matrix](../testing/android-qa-matrix.md). Synthetic instrumentation, debug testing, release assembly and owner-host acceptance are distinct evidence. Host sign-in remains an owner-performed check; automation uses synthetic peers only.
7. Obtain explicit maintainer authorization before creating a release/tag or distributing the APK. Include applicable notices/source material, supported-host information, known limitations and actual check results.

Do not mark an unexecuted tier as passed. A missing signing identity or unavailable device/host evidence means no verified binary release is ready, even when the source repository can be published.
