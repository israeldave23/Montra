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
3. **Gates**: `tools/check_ui_tokens.py`, `tools/check_resources.py`,
   `tools/check_kotlin_syntax.py`, `tools/check_view_bindings.py`.
4. **Unit tests**: `gradle :app:testDebugUnitTest`.
5. **APK**: `gradle :app:assembleDebug`, uploaded as the `montra-debug-apk` artifact (14 days).
6. **Release**: `gradle :app:assembleRelease` unsigned, so R8 and resource shrinking run; the R8
   mapping is uploaded as `montra-release-mapping`.

The gates run *before* the build on purpose: they are seconds, the build is minutes, and a format-arg
mismatch should not cost a full compile to discover.

## Things that are deliberately off

* **R8 is on for release** (`minifyEnabled` + `shrinkResources`). CI builds the release variant
  unsigned on every push so the rules are exercised, and uploads `mapping.txt`. What is kept and why
  is in `RELEASE.md`; the rules are in `app/proguard-rules.pro`.
* **`buildFeatures.buildConfig = false`.** Nothing reads `BuildConfig`; `AdConfig` detects a debug
  build from `ApplicationInfo.FLAG_DEBUGGABLE` instead, which also works for any build type you add.
* **`buildFeatures.viewBinding = true`** is on, and `tools/check_view_bindings.py` keeps it that way:
  no `findViewById` outside the four helpers that take a root, no adapter inflating a layout by id,
  no `removeAllViews()`, and no `lateinit` field left unassigned. Screen code names a field, and the id is checked by the compiler.

## Release checklist

`RELEASE.md` is the checklist: signing, R8 and its mapping file, `app-ads.txt`, the Play Console
answers, and the first-upload list. The short version:

- [ ] `gradle wrapper --gradle-version 8.11.1` and commit the jar so `./gradlew` works everywhere
- [ ] Keystore created outside the repo, `keystore.properties` filled in
- [ ] `gradle :app:bundleRelease` succeeds and the bundle opens on a clean device
- [ ] `app-ads.txt` served from the listing's developer domain
- [ ] Play data-safety and content-rating answers match `RELEASE.md` §4
