# Development Progress

Everything below is either verified by a green GitHub Actions run or explicitly marked unverified.
The run is the source of truth: `Android / Build debug APK` on `arena/de08cb0a-montra`.

## Phase status

| Phase | State | Evidence |
| --- | --- | --- |
| 0 — Build system and CI | **Done** | `.github/workflows/android.yml` compiles, tests and packages on every push; artifact `montra-debug-apk` |
| 1 — Material dialogs | **Done** | 17 `AlertDialog.Builder` sites are `MaterialAlertDialogBuilder` |
| 2 — View binding and RecyclerView feeds | **Done** | 0 `findViewById` outside four root-taking helpers; home feed submits to `ListAdapter`s |
| 3 — ViewModel and StateFlow | **Done** | Six screen view models; screens render `UiState`; `GoalsActivity` is 136 lines, down from 505 |
| 4 — Release readiness | **Mostly done** | R8 + bundle verified in CI, `app-ads.txt`, Play answers and a privacy-policy draft; the keystore and the upload need a human (see `RELEASE.md`) |

## What the build proves

- `:app:assembleDebug` succeeds: Kotlin compilation, Room/KSP code generation, resource linking,
  DEX and packaging. The debug APK is ~10.1 MB.
- `:app:testDebugUnitTest` runs **50 tests in 10 classes, all passing**.
- `tools/check_ui_tokens.py`, `tools/check_resources.py`, `tools/check_kotlin_syntax.py` and
  `tools/check_view_bindings.py` pass.

## What the build does not prove

- **Nothing has run on a device or emulator.** Compilation is not correctness: layout behaviour,
  ad fills, insets, animations and the Room queries at runtime are untested.
- **Ads are untested end to end.** Debug builds use Google's test unit IDs; consent flow, banner
  fill, interstitial caps and the rewarded window need a real device.
- **R8 is off** in release, so nothing is known about shrinking the ad SDK or Room.

## Completed features

- Exact money handling: integer minor units, checked arithmetic, per-currency fraction digits.
- Room persistence with migrations; AES-GCM payload encryption with the Android Keystore.
- Accounts: create, rename, archive, delete, per-account history and running balance.
- Transactions: expense, income, refund, transfer (atomic), edit, delete with undo, search, filter.
- Budgets: per-category monthly limits with derived spend and status colours.
- Goals: savings targets, funding/withdrawal, pace, status.
- Insights: per-currency monthly cards; currencies are never summed together.
- Profile: display name, avatar image, theme, base currency, export, backup, restore.
- Design system enforced by linters (`DESIGN_SYSTEM.md`).

## How a screen is built

- A view model owns the query and exposes `StateFlow<UiState<T>>` — `Loading`, `Ready`, `Failed`.
  `WhileSubscribed(5_000)` stops the query when the screen is not visible and keeps a rotation from
  re-running it.
- `UiState.Failed` is a value, not a thrown exception with a toast in the middle of the stream.
- The activity collects once with `repeatOnLifecycle(STARTED)` and renders; it owns no query, no
  cancellation and no job.
- Dialog surfaces live next to what they act on: `FinanceDialogs` for accounts and transactions,
  `GoalDialogs` for goals (extensions on the screen, so call sites stay `showGoalEditor()`).

## Architecture

- One `app` module, Kotlin DSL, AGP 8.9.1, Kotlin 2.0.21, minSdk 26, compileSdk 36.
- Native views with **view binding**; `findViewById` is banned by a gate.
- Lists are `RecyclerView` + `ListAdapter`; the day headings on the activity screen are a row type,
  not a view built in code.
- Activities bind repository flows in `lifecycleScope` and cancel their own jobs in `onStop`.
  This is the part Phase 3 replaces with `ViewModel` + `StateFlow`.

## Known issues

- **Theme parent is Material 2.** `Base.Theme.App` derives from
  `Theme.MaterialComponents.DayNight.NoActionBar` after a launch crash with the Material 3 parent.
  The chips still use `Widget.Material3.*` styles, which is a mixed family. The crash stack is
  still wanted: with it the M3 theme can be restored instead of worked around.
- **Rewarded-ad window, consent and interstitial caps are unverified** on a device.
- **No signing config**, so release APKs are unsigned and cannot go to Play.
- **`gradle-wrapper.jar` is absent.** CI installs Gradle itself; `./gradlew` needs one
  `gradle wrapper --gradle-version 8.11.1` locally (see `BUILDING.md`).

## Next steps

1. Record the launch crash trace and restore the Material 3 theme parent.
2. Device pass over ads, insets, night mode, backup and restore.
3. Phase 4: signing, R8 with the ad SDK, `app-ads.txt`, Play data-safety answers.
