# Third-party notices

The root [MIT License](LICENSE) covers project-authored code and documentation. It does not replace the licenses of bundled fonts, resolved libraries, or build tooling. This file identifies known direct dependencies from `app/build.gradle.kts` and `gradle/libs.versions.toml`; it is not a complete resolved release-APK license inventory.

## Bundled fonts

The Android app bundles these font files. Each family is distributed under the SIL Open Font License 1.1; the corresponding license text is retained in `app/src/main/assets/licenses/`.

| Family | Bundled file(s) | Source and provenance | License text |
| --- | --- | --- | --- |
| Albert Sans | `albert_sans_regular.ttf`, `albert_sans_medium.ttf`, `albert_sans_semibold.ttf`, `albert_sans_bold.ttf` | [Albert Sans v1.31 release](https://github.com/usted/Albert-Sans/releases/tag/v1.31); the bundled files and license were checked against that release. | [Albert Sans OFL](app/src/main/assets/licenses/AlbertSans-OFL.txt) |
| DM Sans | `settings_dm_sans.ttf` | Exact SHA-256 match to [`DMSans[opsz,wght].ttf` at Google Fonts commit `5e8a3ba` ](https://github.com/google/fonts/blob/5e8a3ba899557829a76cfdac30fa512bda91d7ca/ofl/dmsans/DMSans%5Bopsz,wght%5D.ttf). Bundled SHA-256: `8cd08d97e89c24d0aa92edd2f0f4c8ee6195eee9b7c9f154865a58b02f0c1c0d`. | [DM Sans OFL](app/src/main/assets/licenses/DMSans-OFL.txt) |
| Instrument Serif | `settings_instrument_serif.ttf` | Exact SHA-256 match to [`InstrumentSerif-Regular.ttf` at Google Fonts commit `5e8a3ba`](https://github.com/google/fonts/blob/5e8a3ba899557829a76cfdac30fa512bda91d7ca/ofl/instrumentserif/InstrumentSerif-Regular.ttf). Bundled SHA-256: `498efd461f6ddfcb7a111bf9a565709d2085d48201d501ead960d93e84ffbb88`. | [Instrument Serif OFL](app/src/main/assets/licenses/InstrumentSerif-OFL.txt) |

The bundled OFL texts have the same legal wording as their upstream notices. Albert Sans has its release-specific copyright year; the remaining differences from the current upstream copies are whitespace.

## Declared runtime libraries

| Component | Declared version | Upstream license source |
| --- | --- | --- |
| AndroidX and Jetpack Compose modules | Versions in `gradle/libs.versions.toml`, including Compose BOM `2026.08.00` | [AndroidX Apache License 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| Kotlin coroutines | `1.10.2` | [kotlinx.coroutines Apache License 2.0](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt) |
| OkHttp | `4.12.0` | [OkHttp Apache License 2.0](https://github.com/square/okhttp/blob/master/LICENSE.txt) |
| CommonMark Java core, GFM tables, and GFM strikethrough | `0.30.0` | [CommonMark BSD 2-Clause License](https://github.com/commonmark/commonmark-java/blob/main/LICENSE.txt) |
| autolink-java | `0.12.0` | [autolink-java MIT License](https://github.com/robinst/autolink-java/blob/main/LICENSE) |
| desugar_jdk_libs (core library desugaring) | `2.1.5` | [Upstream GPL 2 with Classpath exception and additional license material](https://github.com/google/desugar_jdk_libs/blob/master/LICENSE) |

The app also uses transitive libraries resolved by Gradle. Before distributing an APK, audit the exact release runtime graph and packaged artifact, retain each dependency's required copyright, license, and notice text, and make the applicable notices available with the distributed app. The desugaring library's exception and additional license material need particular attention. This table alone is not a binary-distribution notice bundle.

## Build tooling checked into this repository

The checked-in `gradle/wrapper/gradle-wrapper.jar` is part of the Gradle wrapper, not the Android app. [Gradle is licensed under Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).

The Hermes Mobile name and project icon are separate from these software and font license grants. Hermes Mobile is an independent, unofficial client for Hermes Agent and is not affiliated with or endorsed by Nous Research.
