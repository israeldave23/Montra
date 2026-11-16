# Building Montra

Two build descriptions exist in this repository, on purpose:

| File | Read by | Purpose |
| --- | --- | --- |
| `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties` | Gradle 8.11.1 / AGP 8.9.1 | The real, reproducible build — what CI runs and what produces the APK |
| `app/module.toml` | the in-editor agent toolchain | A declarative mirror of the same module (namespace, SDK levels, dependency list). It exists so that toolchain can reason about the module without Gradle |

They must agree. If you bump a dependency, bump it in `app/build.gradle.kts`; `module.toml` is the
mirror, not the source of truth.

## Toolchain

| Component | Version | Why |
| --- | --- | --- |
| JDK | 17 | AGP 8.x refuses to run on older JDKs |
| Gradle | 8.11.1 | Minimum for AGP 8.9.x |
| AGP | 8.9.1 | First line that supports `compileSdk 36` |
| Kotlin | 2.0.21 | Pairs with KSP 2.0.21-1.0.28 |
| KSP | 2.0.21-1.0.28 | Room's annotation processor |
| compileSdk / minSdk / targetSdk | 36 / 26 / 35 | `targetSdk 35` is what forces the edge-to-edge handling in `AppActivity` |

## Locally

```bash
# One-time: generate the wrapper jar (binary, deliberately not committed)
gradle wrapper --gradle-version 8.11.1

./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JUnit 5 domain tests
```

`gradle/wrapper/gradle-wrapper.properties` is committed; `gradle-wrapper.jar` is not, because the
environment that wrote these files cannot produce a binary. Until you run the command above, use a
locally installed `gradle` instead of `./gradlew`.

## In CI — `.github/workflows/android.yml`

Every push and pull request:

1. JDK 17 (temurin), platform 36 and build-tools 36 installed.
2. Gradle 8.11.1 installed by `gradle/actions/setup-gradle` (no wrapper jar needed).
3. **Design-system gates**: `tools/check_ui_tokens.py`, `tools/check_resources.py`,
   `tools/check_kotlin_syntax.py`.
4. **Unit tests**: `gradle :app:testDebugUnitTest`.
5. **APK**: `gradle :app:assembleDebug`, uploaded as the `montra-debug-apk` artifact (14 days).

The gates run *before* the build on purpose: they are seconds, the build is minutes, and a format-arg
mismatch should not cost a full compile to discover.

## Things that are deliberately off

* **`minifyEnabled = false`** in release. Turning R8 on requires running it against the AdMob and
  Play Services SDKs first — they ship their own consumer rules, but the reflection in Room's
  generated code and the ad adapters needs a real device test. Do that before the first Play upload,
  not after.
* **`buildFeatures.buildConfig = false`.** Nothing reads `BuildConfig`; `AdConfig` detects a debug
  build from `ApplicationInfo.FLAG_DEBUGGABLE` instead, which also works for any build type you add.
* **ViewBinding.** Not enabled. `module.toml` has no switch for it, and enabling it in Gradle while
  the other toolchain does not would split the two builds. When the 189 `findViewById` calls are
  worth removing, enable it *here* and mirror the flag.

## Release checklist

- [ ] `gradle wrapper --gradle-version 8.11.1` and commit the jar so `./gradlew` works everywhere
- [ ] Signing config (`*.jks` is git-ignored — keep it in CI secrets or a keystore file outside git)
- [ ] R8 on, tested on a device: ads load, Room queries return, no `ClassNotFoundException`
- [ ] `app-ads.txt` published (see `ADS.md`)
- [ ] Play data-safety and content-rating answers match the ads integration
