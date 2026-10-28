# Build and check

The project has two Gradle builds. `core/` is a standalone plain Kotlin/JVM build with no Android dependency. The root build adds the Android app (`:app`) and includes `core/` as a composite build.

## Toolchain (pinned in `gradle/libs.versions.toml`)

| Component | Version | Why this version |
| --- | --- | --- |
| JDK | 21 to run Gradle; bytecode target 17 | AGP 8.13 needs JDK 17 or newer |
| Gradle | 8.14.3 (wrapper, distribution SHA-256 pinned) | AGP 8.13 needs Gradle 8.13 or newer; Gradle 9 is out of scope |
| Android Gradle Plugin | 8.13.2 | Newest 8.x. Supports API up to 36.1, default build-tools 35.0.0 ([release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes)) |
| Kotlin (JVM, Android, Compose compiler, serialization plugins) | 2.4.21 | Supports Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1 ([Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html)) |
| Android SDK | `compileSdk`/`targetSdk` 36, `minSdk` 26, `platforms;android-36`, `build-tools;35.0.0` | Within AGP 8.13 limits; `minSdk` 26 is the provisional baseline in `docs/ANDROID_MILESTONE_1.md` |
| Compose BOM | 2026.06.01 (UI 1.11.4, Material 3 1.4.0) | AAR metadata needs compileSdk 35 and AGP 8.6. BOM 2026.09.00 needs AGP 9.1 and compileSdk 37 |
| activity-compose | 1.13.0 | Needs compileSdk 36 and AGP 8.9.1 |
| navigation-compose | 2.9.8 | 2.10.x needs AGP 9.1 and compileSdk 37 |
| lifecycle (viewmodel/runtime compose) | 2.9.4 | Needs compileSdk 34 and AGP 8.1.1 |
| kotlinx-coroutines / kotlinx-serialization-json | 1.11.0 / 1.12.0 | Serialization 1.12.0 is built against Kotlin 2.4.10 |
| Animal Sniffer plugin / `gummy-bears-api-26` signature | 2.0.1 / 0.16.0 | API-26 compatibility check for `:core` |
| JUnit / Robolectric / androidx.test core | 4.13.2 / 4.17 / 1.7.0 | Robolectric runs the app tests on the JVM |

Lint reports newer AGP, Compose BOM, navigation and lifecycle versions. They are held back on purpose because they need AGP 9.

## Dependency licenses

Checked on 2026-10-28 from each pinned artifact's published POM (`<licenses>` element) unless noted. Build and test dependencies are not part of the release APK; `ui-test-manifest` is included in debug builds only.

| Dependency (pinned version) | Used for | License | Source checked |
| --- | --- | --- | --- |
| Gradle wrapper 8.14.3 | build | Apache-2.0 | `LICENSE` at tag `v8.14.3` of gradle/gradle |
| Android Gradle Plugin `com.android.tools.build:gradle` 8.13.2 | build | Apache-2.0 | POM, Google Maven |
| Kotlin Gradle plugin, serialization plugin, Compose compiler plugin 2.4.21 | build | Apache-2.0 | POMs, Maven Central |
| `kotlin-stdlib` 2.4.21 | core and app | Apache-2.0 | POM, Maven Central |
| `kotlinx-coroutines-core` 1.11.0 | core and app | Apache-2.0 | POM, Maven Central |
| `kotlinx-serialization-json` 1.12.0 | core | Apache-2.0 | POM, Maven Central |
| Animal Sniffer Gradle plugin `ru.vyarus.animalsniffer` 2.0.1 | build check | MIT | POM has no license element; `LICENSE` at tag `2.0.1` of xvik/gradle-animalsniffer-plugin |
| `com.toasttab.android:gummy-bears-api-26` 0.16.0 | build check (API signature data) | Apache-2.0 | POM, Maven Central |
| Compose BOM 2026.06.01: `ui` and `ui-tooling-preview` 1.11.4, `material3` 1.4.0, `material-icons-core` 1.7.8 | app | Apache-2.0 | POMs, Google Maven |
| `activity-compose` 1.13.0 | app | Apache-2.0 | POM, Google Maven |
| `lifecycle-viewmodel-compose` and `lifecycle-runtime-compose` 2.9.4 | app | Apache-2.0 | POMs, Google Maven |
| `navigation-compose` 2.9.8 | app | Apache-2.0 | POM, Google Maven |
| `ui-test-junit4` 1.11.4, `ui-test-manifest` 1.11.4 (debug only) | tests | Apache-2.0 | POMs, Google Maven |
| `androidx.test:core` 1.7.0 | tests | Apache-2.0 | POM, Google Maven |
| `kotlinx-coroutines-test` 1.11.0 | tests | Apache-2.0 | POM, Maven Central |
| Robolectric 4.17 | tests | MIT | POM, Maven Central |
| JUnit 4.13.2 | tests | EPL-1.0 | POM, Maven Central |

This covers the directly pinned dependencies. Their transitive dependencies were not inventoried one by one.

## Commands

Core only. This needs no Android SDK and never resolves the Android Gradle Plugin, because `core/settings.gradle.kts` has no `google()` repository:

```sh
./gradlew -p core check
```

It runs the core unit tests, the G1 purity scan (`verifyCorePurity`), the API-26 Animal Sniffer check, and the negative control (`verifyApiNegativeControl`), which must flag a deliberate later-API call.

Android app (needs the SDK, via `ANDROID_HOME` or `local.properties`):

```sh
./gradlew :app:check :app:verifyNoForbiddenPermissions :app:assembleDebug
```

`:app:check` runs lint and the Robolectric unit tests for both variants. `verifyNoForbiddenPermissions` fails if the merged manifest requests any Bluetooth, location or Internet permission (F11). The merged manifest contains only `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, an app-private permission that AndroidX core adds.

## What the app tests do and don't show

The app tests run under Robolectric, which executes Android framework code on the host JVM. They exercise the screens, navigation, the simulation banner and save/reopen. They are **not** a device or emulator run: file I/O uses the host JVM, not Android's runtime, and rendering is not pixel-accurate. The app has not been run on a phone or emulator.

## Environment notes (not part of the repository)

In the cloud environment used to build this milestone, Maven Central returned HTTP 429 through the network proxy. The build there used two settings outside the repository:

- `~/.gradle/init.d/maven-central-mirror.gradle.kts` points `mavenCentral()` at Google's mirror, `https://maven-central.storage-download.googleapis.com/maven2/`.
- `~/.gradle/gradle.properties` sets `robolectricRepoUrl` to the same mirror. `app/build.gradle.kts` passes it to Robolectric's `robolectric.dependency.repo.url` only when set.

Neither is needed where Maven Central is reachable.
