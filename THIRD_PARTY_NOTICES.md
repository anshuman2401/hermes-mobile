# Third-party notices

The root [MIT License](LICENSE) covers project-authored code and documentation, except where otherwise noted. It does not replace the licenses of bundled fonts, resolved libraries, or build tooling. The [reviewed release inventory](app/src/main/assets/licenses/release-inventory.json) records the resolved runtime and core-desugaring artifacts, their SHA-256 hashes, and the corresponding bundled license/notice texts. It is an auditable notice inventory, not a legal certification.

## Code of Conduct

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) adapts Contributor Covenant 3.0, stewarded by the Organization for Ethical Source, under [Creative Commons Attribution-ShareAlike 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Its attribution and license remain in that document; the root MIT License does not replace them.

## Bundled fonts

The Android app bundles these font files. Each family is distributed under the SIL Open Font License 1.1; the corresponding license text is retained in `app/src/main/assets/licenses/`.

| Family | Bundled file(s) | Source and provenance | License text |
| --- | --- | --- | --- |
| Albert Sans | `albert_sans_regular.ttf`, `albert_sans_medium.ttf`, `albert_sans_semibold.ttf`, `albert_sans_bold.ttf` | [Albert Sans v1.31 release](https://github.com/usted/Albert-Sans/releases/tag/v1.31); the bundled files and license were checked against that release. | [Albert Sans OFL](app/src/main/assets/licenses/AlbertSans-OFL.txt) |
| DM Sans | `settings_dm_sans.ttf` | Exact SHA-256 match to [`DMSans[opsz,wght].ttf` at Google Fonts commit `5e8a3ba` ](https://github.com/google/fonts/blob/5e8a3ba899557829a76cfdac30fa512bda91d7ca/ofl/dmsans/DMSans%5Bopsz,wght%5D.ttf). Bundled SHA-256: `8cd08d97e89c24d0aa92edd2f0f4c8ee6195eee9b7c9f154865a58b02f0c1c0d`. | [DM Sans OFL](app/src/main/assets/licenses/DMSans-OFL.txt) |
| Instrument Serif | `settings_instrument_serif.ttf` | Exact SHA-256 match to [`InstrumentSerif-Regular.ttf` at Google Fonts commit `5e8a3ba`](https://github.com/google/fonts/blob/5e8a3ba899557829a76cfdac30fa512bda91d7ca/ofl/instrumentserif/InstrumentSerif-Regular.ttf). Bundled SHA-256: `498efd461f6ddfcb7a111bf9a565709d2085d48201d501ead960d93e84ffbb88`. | [Instrument Serif OFL](app/src/main/assets/licenses/InstrumentSerif-OFL.txt) |

The bundled OFL texts have the same legal wording as their upstream notices. Albert Sans has its release-specific copyright year; the remaining differences from the current upstream copies are whitespace.

## Resolved runtime libraries and notices

The inventory covers 78 distinct artifacts from `releaseRuntimeClasspath` and `coreLibraryDesugaring`, including transitive artifacts and the desugaring configuration artifact. BOM-only constraints have no runtime artifact. Identical repeated resolution entries are deduplicated. Library versions can differ from declared versions because Gradle resolves transitive constraints: for example, the current graph resolves Lifecycle 2.9.4 and Kotlin standard library 2.4.20.

| Components | Retained material |
| --- | --- |
| AndroidX/Compose, JSpecify, Guava ListenableFuture, JetBrains annotations | Apache 2.0 texts; embedded artifact notices where present. |
| Kotlin standard library and JDK compatibility artifacts | Apache 2.0 and JetBrains copyright texts; standard-library third-party GWT, Guava, ThreeTen and Boost notices. |
| Kotlin coroutines and serialization | Version-specific Apache 2.0 and upstream NOTICE files. |
| OkHttp and Okio | Version-specific Apache 2.0 texts. OkHttp's public-suffix resource additionally retains its embedded NOTICE, MPL 2.0, and readable source rule tables reconstructed from the exact bundled resource. |
| CommonMark core and GFM extensions | Their embedded BSD 2-Clause license and copyright text. |
| autolink-java | Version-specific MIT license and copyright text. |
| desugar_jdk_libs 2.1.5 | Complete GPL 2 with Classpath Exception, assembly exception, additional license information, and a conservative superset of upstream runtime-source copyright/license headers. The configuration artifact's embedded BSD license is also retained. |

All listed texts and the inventory are app assets under `assets/licenses/`. The inventory records each original text's source and hash; `Embedded-*` files are texts extracted from resolved archives, including nested JARs. Imported texts may have trailing line whitespace and terminal blank lines removed without changing legal wording. For normalized files, the inventory retains both `original_source_sha256` and the packaged `sha256`; other texts retain their original bytes. The source-header collection deliberately retains more upstream notices than a particular APK may need; it does not imply every upstream class is packaged. These are third-party notices, not grants of the Hermes Mobile name or icon.

### Recheck dependencies and a distribution artifact

Use the [shared Gradle lock](specs/testing/verification.md#shared-gradle-lock). After defining that helper and before distributing an APK:

```bash
with_gradle_lock ./gradlew --no-configuration-cache -I scripts/license-inputs.init.gradle :app:exportReleaseLicenseInputs
python3 scripts/check_release_licenses.py
python3 scripts/check_release_licenses.py --apk app/build/outputs/apk/release/app-release.apk
python3 scripts/prepare_release_sources.py
```

The Gradle export writes local artifact paths only to ignored `build/license-inputs.json`; do not publish that file. The checker fails if resolved coordinates/artifact bytes or reviewed notice bytes change, and its optional APK check verifies that the exact notice assets are packaged. It does not decide legal applicability or inspect final DEX classes. The export uses `--no-configuration-cache` because this standalone audit task resolves project configurations during execution.

Distribute the generated `build/release-sources.zip` **alongside the matching APK**, retaining the notices in both. The source ZIP contains the hash-checked desugared-library upstream source archive (including its build files), the public-suffix source tables, all retained notices, and the inventory. The exact desugar source is the upstream [2.1.5 preparation commit](https://github.com/google/desugar_jdk_libs/tree/73170c345e6a762fc6a1f0301bb15218850023ef); the archive URL and SHA-256 are recorded in the inventory. `prepare_release_sources.py` downloads that archive unless `--desugar-archive` supplies an already-downloaded copy, and refuses a hash mismatch. Source and notice packaging does not sign or publish anything. Record hashes for both the final APK and source ZIP in the release handoff.

When dependencies change, export the new graph, review each changed artifact's published metadata, embedded notices and upstream version-specific license/source material, then update the checked-in inventory and texts. Do not merely replace hashes to make the check pass. Kotlin's standard-library third-party material and OkHttp's public-suffix data need review separately from their primary Apache licenses. Regenerate the source ZIP for the reviewed dependency set. Android build transformations do not replace the original component license grants.

## Build tooling checked into this repository

The checked-in `gradle/wrapper/gradle-wrapper.jar` is part of the Gradle wrapper, not the Android app. [Gradle is licensed under Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).

The Hermes Mobile name and project icon are separate from these software and font license grants. Hermes Mobile is an independent, unofficial client for Hermes Agent and is not affiliated with or endorsed by Nous Research.
