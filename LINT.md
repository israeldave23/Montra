# Android Lint in this project

Android Lint runs on every push, in the same job as the build:

```
gradle :app:lintDebug            # the gate
python3 tools/annotate_lint.py app/build/reports/lint-results-debug.xml   # read the result
```

It sits after the debug build so compilation is already up to date and the step pays only for analysis.
The HTML and XML reports are uploaded as the `montra-lint-report` artifact on every run, pass or fail.

## Why it exists

Four hand-written gates checked the things a script can see: tokens, resource references, Kotlin syntax,
view binding. None of them understands Android. An API call above `minSdk`, an `ImageView` with no
`contentDescription`, a resource nothing references, a deprecated manifest attribute — all of that
compiled clean and passed every gate for five phases.

The first run found **4 errors and 130 warnings**, and every one of the four errors was a real defect.

## Policy

* **Errors fail the build.** `abortOnError = true` in `app/build.gradle.kts`.
* **There is no `lint-baseline.xml`.** A baseline records today's findings and then suppresses them,
  along with every later occurrence of the same issue — a green build that means nothing. Findings are
  fixed, or suppressed one at a time with a reason next to them.
* **Warnings are findings too.** They do not fail the build, but they are not decoration: the ones
  below are either fixed or listed with the reason they are not.

## Where it stands — run 43963762223 on `b47feef`

| | First run | Now |
| --- | --- | --- |
| Errors | 4 | **0** |
| Warnings | 130 | **34** |
| Information | 1 | 1 |

### Errors, all fixed

| Check | Where | What was actually wrong |
| --- | --- | --- |
| `DiffUtilEquals` | `InsightAdapter` | rows compared as `Any`, so `areContentsTheSame` was `Object.equals`; narrowed to the row types |
| `IncludeLayoutParam` | `activity_main.xml` | margins on `<include>` were ignored without width/height, so the ad slot kept a double gutter |
| `NewApi` ×2 | `values/themes.xml`, `values-night` | `windowLightNavigationBar` is API 27, `minSdk` is 26; moved to `values-v27`, and 26 now gets a dark bar instead of white icons on white |

### Warnings, fixed (96 of 130)

Dead resources (42: 37 strings, 3 dimens, a colour and `activity_section.xml`), `UseKtx`-adjacent
`ApplySharedPref` (2, declined with reasons), `Autofill` (14 fields opted out — none is identity data),
`PluralsCandidate` (real grammar: "1 budgets", "1 transactions", "1 days left"), `Overdraw` (8 root
backgrounds duplicating `windowBackground`), `ContentDescription` (2), `LabelFor` (2), `ExifInterface`,
`StaticFieldLeak` (a genuine Activity leak — see below), `SetTextI18n` (2), `MonochromeLauncherIcon` (2),
`DataExtractionRules`, `ObsoleteSdkInt`, `UseCompatTextViewDrawableXml` (5),
`UseCompatTextViewDrawableApis`, `DisableBaselineAlignment` (2).

The leak is worth naming: `RoomFinanceRepository` is a process-lifetime singleton and seven call sites
built it from an `Activity`. Whichever ran first pinned that Activity, and its views, for the life of the
process. The constructor normalises to the application context now.

### Warnings accepted, with the reason

| Check | Count | Why it stays |
| --- | --- | --- |
| `UseKtx` | 21 | `prefs.edit().put…().apply()` is correct as written; `edit { }` is the same call. Queued as an idiom pass, not a defect |
| `GradleDependency` | 7 | every androidx line is pinned deliberately. Bumping six majors blind, with nothing ever run on a device, trades a known-good build for an unknown one |
| `UselessParent` | 2 | removing a wrapper changes the hierarchy of the home screen, which no device has rendered yet |
| `ButtonStyle` | 2 | the buttons in `activity_account_detail` are filled on purpose; borderless is the convention, not the requirement |
| `MergeRootFrame` | 1 | `view_ad_slot` needs its id and its own layout params; `<merge>` cannot carry either |
| `OldTargetApi` | 1 | **not a style question — see below** |
| `KtxExtensionAvailable` | 1 | information; tied to the `UseKtx` pass |

## The one warning that matters commercially

`OldTargetApi` says `targetSdk` is 35 while 36 is available. Google Play requires new uploads and updates
to target a recent API level, and 36 became the requirement in 2026. Raising it is not a one-line edit:
**targetSdk 36 forces edge-to-edge**, which changes every screen's insets behaviour — and insets here have
never been seen on a device. It is deferred until there is a device pass to verify it against, and it is
recorded in `RELEASE.md` as a blocker for the first upload if Play's console refuses 35.

## Reading a result when the log is unreachable

`tools/annotate_lint.py` turns the XML report into check-run annotations, and prints a grouped table
(severity, id, count, up to three locations, plus **every** unused resource by name) as chunked
`::notice` annotations. That exists because the report artifact itself lives in blob storage that some
environments cannot fetch: the first run reported 136 issues and 96 of them were unreadable.
