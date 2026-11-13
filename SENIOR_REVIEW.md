# Senior review — Montra, after the Material 3 pass

_Scope: whole app as of `fef37b6`, read end to end (≈4.9k lines of Kotlin, 7 activities, 1 Room database, 48 unit tests)._
_Environment caveat: this sandbox has no JDK, Gradle or emulator, so everything below is from reading code and resources, not from running the app. Contrast values are computed, not eyeballed._

## Verdict

The **domain layer is genuinely good** — exact minor-unit money, per-currency separation, pure functions with real unit tests, and migrations that exist rather than being skipped. That is the hard part, and it is done.

The **delivery layer is where the app is fragile**: plain `Activity` with 180 `findViewById` calls, no ViewBinding or ViewModel, god classes of 300–500 lines, framework `AlertDialog`s under an M3 theme, a hand-rolled theme switcher using an API deprecated since Android 7.1, and no CI at all. None of that is unusual for an app at this stage — but it is the difference between "demo that looks right" and "app that survives its next 20 features".

Biggest single risk, and it is not UI: **the database has four schema versions and zero migration tests**, in an app whose entire value is the user's financial history.

---

## P0 — fix before the next feature

### 1. No migration tests, and no CI to run anything
`FinanceDatabase` is `version = 4` with `MIGRATION_1_2`, `2_3`, `3_4` (`db/Migrations.kt`), 49 unit tests, and **no `androidTest` source set exists** — so no `MigrationTestHelper`, no Room DAO test, no instrumentation test. There is also no `.github/workflows`, so nothing verifies a push. A broken migration silently destroys the only copy of someone's ledger.
→ Add `androidTest` with `MigrationTestHelper` for 1→2→3→4, a DAO round-trip test on an in-memory DB, and a CI job (`assembleDebug`, `testDebugUnitTest`, `lintDebug`, `connectedCheck` on a managed device for the migration tests). Given `module.toml` is a custom harness rather than Gradle, I'd extract a real Gradle build first — you cannot run lint, R8 or instrumentation without one. Until then, `tools/check_resources.py` and `tools/check_ui_tokens.py` are the only gates, and they are static (see `tools/README.md`).

### 2. Restore overwrites the live database file
`ProfileActivity:294` copies a picked file straight onto `getDatabasePath("lumen_finance.db")` while Room holds it open, with no validation of what was copied. A wrong file, a truncated copy, or a file from a different schema leaves the app in a state it cannot open.
→ Close the database (`RoomDatabase.close()`), open the candidate read-only and check `PRAGMA user_version` + `PRAGMA integrity_check`, copy to a temp path, `File.renameTo` (atomic), then restart the process. Reject anything that is not a Montra DB. Offer the same path for "export" so both directions share one validator.

### 3. Edge-to-edge is unhandled on the target SDK
`targetSdk = 35`, so edge-to-edge is enforced: `android:statusBarColor` / `windowLightStatusBar` in `values/themes.xml:44-48` are **no-ops**, and `android:fitsSystemWindows="true"` on `activity_main.xml:2` is a blunt instrument that will leave the bottom nav under the gesture bar on some devices while double-insetting elsewhere.
→ `WindowCompat.setDecorFitsSystemWindows(window, false)` once in a `BaseActivity`, then `ViewCompat.setOnApplyWindowInsetsListener` applying `systemBars` insets to the scrolling content and the bottom bar; `WindowInsetsControllerCompat.isAppearanceLightStatusBars = !dark`. Drop the status-bar colour items.

### 4. Theme switching uses a deprecated API, four times over
`MainActivity:214` and `ProfileActivity:93` (and their siblings) each copy-paste `resources.updateConfiguration(config, metrics)`, deprecated since API 25, mutating one Activity's resources while the rest of the process keeps the old configuration — which is why it "works" only because every Activity repeats it.
→ One `ThemeController` with `AppCompatDelegate.setDefaultNightMode(...)`, applied in `Application.onCreate` from persisted preference, `AppCompatActivity` everywhere, and `recreate()` after a change. `UiModeManager.setApplicationNightMode()` is the API 31+ equivalent; the delegate handles both.

### 5. Heavy work was running on the main thread — fixed today
`RoomFinanceRepository`'s `observe*` flows did their mapping (`toDomain()` per row, `BigDecimal` totals, budget aggregation) in the **collector's** context, and every screen collects on `MainScope`. Every DB write therefore re-ran the full dashboard aggregation on the UI thread.
→ `.flowOn(Dispatchers.Default)` on all seven observation flows (done; see "Fixed in this commit" below). The remaining piece is the list diffing noted in P1.6.

---

## P1 — architecture and maintainability

### 6. 180 `findViewById` calls, no ViewBinding, views looked up inside `render()`
`MainActivity:131-132` resolves `toggle_balance` twice in consecutive lines; `render()` re-finds `monthly_summary`. There is no type safety if a view is renamed — the resource audit catches missing ids, the compiler does not catch a wrong cast.
→ Enable ViewBinding (one `binding` field per Activity, `null`ed in `onDestroy`), or move screens to Compose where the M3 theme would also give you real components instead of styled `LinearLayout`s.

### 7. God classes
`GoalsActivity` 499 lines, `RoomFinanceRepository` 441, `FinanceDialogs` 410, `AccountDetailActivity` 319, `ProfileActivity` 315. `FinanceDialogs` alone owns add-transaction, transfer, add-account, edit-account and delete flows.
→ Split `FinanceDialogs` into `ui/dialogs/{AddTransactionDialog,TransferDialog,AccountDialogs}.kt`; move screen state into ViewModels (`StateFlow<UiState>`), which also fixes rotation losing scroll position and dialog state.

### 8. Manual `MainScope()` and job bookkeeping
Every Activity creates `MainScope()` (`MainActivity:24`, `GoalsActivity:41`, …), cancels a job in `onStop`, relaunches in `onResume`. It mostly works, but it is exactly the pattern `lifecycleScope` + `repeatOnLifecycle(STARTED)` exists to replace, and `catch { toast(...) }` leaves the flow dead after one transient error.
→ `lifecycleScope.launch { repeatOnLifecycle(STARTED) { repo.observeDashboard().retryWhen { … }.collect(::render) } }`, with an explicit `Error` state in the UI instead of a Toast that disappears.

### 9. SharedPreferences as a global stringly-typed API
Preference file names were duplicated in 6 files (now centralised in `Storage`), but `"display_name"`, `"theme_mode"` and `"balance_hidden"` are still raw string keys at 12 `getSharedPreferences` call sites.
→ A `Settings` facade with typed accessors and one key catalogue (`object Prefs { val themeMode = intPreferencesKey(...) }`), on DataStore so writes are transactional and observable.

### 10. Lists are rebuilt, not diffed
`BudgetAdapter:37`, `GoalAdapter:37`, `InsightAdapter:42` call `notifyDataSetChanged()`. Worse, the home screen's recent activity is a `LinearLayout` that is emptied and re-inflated on every emission (`MainActivity:121-123`, `TransactionAdapter.getView` outside a RecyclerView).
→ `ListAdapter` + `DiffUtil.ItemCallback` everywhere; a nested RecyclerView (or `ConcatAdapter`) for the home feed so emissions only rebind what changed. This is the single biggest scroll-smoothness win available.

### 11. 33 framework `AlertDialog`s, zero Material components
The app's most-used surface — every add/edit flow — is `android.app.AlertDialog`, while `Theme.Material3.*` styles the rest. `MaterialAlertDialogBuilder`, `MaterialButton`, `MaterialCardView` and `AppCompatActivity` appear **zero** times. Your new palette therefore stops at the dialog boundary.
→ `AppCompatActivity` + `MaterialAlertDialogBuilder` (it picks up `colorSurfaceContainerHigh`, M3 shape and typography automatically). Also `MaterialDatePicker` instead of `DatePickerDialog` for the goal date.

### 12. UI strings assembled in code
`MainActivity:196` builds `"${item.currencyCode}  ·  $meta"`; `MainActivity:112` concatenates a quantity string with a separator and another string. Both are untranslatable and break in RTL.
→ Format strings in `strings.xml` (`account_row_meta`, `accounts_summary_separator`) with positional args.

### 13. Branding was stale — done
`app_name` read **"Lumen Finance"** while the package, repo and copy were Montra; the database was
`lumen_finance.db`, preferences `lumen_*`, and every colour had a `lumen_*` alias. All of it is now
Montra, with old files renamed on first launch (`data/Storage.kt`): the DB (plus its `-wal`/`-shm`
sidecars) and five preference files are migrated before anything opens them, and every colour alias is
gone. The only surviving "lumen" strings are deliberate: the legacy file names in `Storage`, and the
KeyStore alias, which cannot be renamed without orphaning existing ciphertext.

---

## P2 — polish, and debt from my own recent work

### 14. Glyph controls still in the UI
I replaced nav, category and quick-action glyphs, but these are still text: `‹` (`activity_account_detail.xml:27`, `activity_insights.xml:55`, `activity_profile.xml:4`, `activity_transactions.xml:4`), `›` (`activity_insights.xml:78`), `⋮` (`activity_account_detail.xml:45`, `item_budget_row.xml:102`, `item_goal_row.xml:85`). They render differently per font, scale badly and are invisible to icon theming.
→ `ic_back`, `ic_chevron_left/right`, `ic_more_vert` (24dp, same two-tone style), swapping the `TextView` for an `ImageView` with `contentDescription` — five files, ~30 minutes. The one `TextView`→`ImageView` change touches three `findViewById<TextView>` casts.

### 15. The night theme duplicates the whole `Theme.App`
`values/themes.xml` and `values-night/themes.xml` both redeclare every role. A style defined in two configurations does not merge, so the only safe edit is "change both" — and that is exactly the kind of parity I had to hand-check today.
→ `Base.Theme.App` holds the shared M3 role map; `Theme.App` in `values/` and `values-night/` only carries the four or five items that actually differ (`windowLightStatusBar`, navbar, hero). Drift becomes impossible rather than detectable.

### 16. Legacy aliases — done
`lumen_*`, `primary`, `on_primary` and `on_balance` were 90+ references across 30 files pointing at the
same roles as `m3_*`. All of them now use the role name, the alias block is deleted, and the hero text
colour became `m3_on_hero` rather than raw white. Remaining hardening: teach `tools/check_ui_tokens.py`
to reject hex literals inside `drawable/`.

### 17. Icon tint footgun
Every vector is `#FF000000` with alpha and relies on the call site for tint. Forget `android:tint` once and the icon is black in dark mode.
→ A `Widget.App.Icon` style with `android:tint=?attr/colorOnSurfaceVariant`, applied via `style=` on list icons and badges, or a small `ImageView.tintWith(role)` helper.

### 18. Accessibility details
`item_budget_row` has a clickable root **and** a clickable overflow child; TalkBack reads the whole card as one blob and the menu never announces itself separately. Buttons use fixed `android:layout_height="@dimen/touch_target"`, which clips at 200 % font scale.
→ `minHeight` + vertical padding for text buttons; put the row description on the title container, give the overflow its own label, and sanity-check with TalkBack once.

### 19. Locale and currency edges
`Money.parse` accepts only `.` as the decimal separator (`domain/Models.kt:31`) while the formatter is locale-aware, so a Spanish-locale user typing `12,50` gets an error. `Currency.getInstance(code)` throws `IllegalArgumentException` for an unknown code stored in a row, and that call sits on the render path. `Money.currencies` is a hardcoded list of six.
→ Normalise `,`→`.` with a `DecimalFormatSymbols` lookup, or restrict input with a locale-aware `InputFilter`; `runCatching { Currency.getInstance(code) }.getOrElse { … }` in the formatter; add tests for JPY (0 digits) and a corrupt code.

### 20. Tests to add (cheap, high value)
`Money` formatting/rounding/JPY, `Money.parse` rejection paths, `DashboardState` aggregation with archived accounts, and the migration tests from P0.1. The two `*SelfCheck.kt` files contain zero `@Test` methods — either convert them or delete them; right now they are ambiguity.

### 21. Minor
`SimpleDateFormat("MMM d, yyyy")` at `FinanceDialogs:147` next to `java.time` everywhere else → `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`. `ProfileActivity` hardcodes the FileProvider authority `com.dga.montra.fileprovider` in a companion → `BuildConfig`/resource. `primary_button_background.xml` is unused. `avatarInitials`/`initials` were three copies of the same code — consolidated in this commit.

---

## Fixed in this commit

1. **`flowOn(Dispatchers.Default)`** on all seven `RoomFinanceRepository` observation flows — mapping and aggregation no longer run on the main thread on every database write (P0.5).
2. **Dead code and a duplicated render path** in `MainActivity.render()`: an unused `balanceHidden` local, and a second `monthly_summary` assignment. The balance is now masked *before* formatting, so a hidden balance can no longer be formatted into a view that is then overwritten.
3. **Initials logic de-duplicated** into `ProfileAvatar.initialsFor(name, fallback)` (was three copies, one returning `"•"`, two returning `"L"`), with the fallback derived from `app_name` instead of a hardcoded letter.
4. **`balance_placeholder` string** replaces the `—` glyph in both the layout and the code; the layout no longer carries placeholder letters.

Verified with the same static sweep as before: XML parse, `R.*` / `@ref` resolution, `getString` arity (depth-aware), duplicate resources, light/night colour parity, brace balance, and `tools/check_ui_tokens.py`.

---

## What I would do next, in order

| # | Work | Why now | Effort |
| --- | --- | --- | --- |
| 1 | Gradle + CI (build, unit tests, lint, managed-device migration tests) | Nothing else is trustworthy until a green build means something | 1 day |
| 2 | Migration tests + hardened backup/restore | Only copy of the user's data | 0.5 day |
| 3 | `BaseActivity` with edge-to-edge insets + `ThemeController` | targetSdk 35 already ships broken system bars | 0.5 day |
| 4 | ViewBinding + `ListAdapter`/`DiffUtil` + home feed as RecyclerView | Every future screen gets cheaper | 1 day |
| 5 | Material dialogs (`MaterialAlertDialogBuilder`, `MaterialDatePicker`) | The M3 pass currently stops at the dialog boundary | 1 day |
| 6 | Finish icons (P2.14), `Base.Theme.App` split, linter rules | Small, mechanical, prevents regression | 0.5 day |

## What I would *not* change

The domain modelling (`Money`, `BudgetProgress`, `InsightCalculator`, `AccountSummary`) is the best part of this codebase: pure, tested, currency-safe, and free of Android types. Keep it exactly where it is — including when the UI moves to Compose or MVVM. The same goes for the token lint script: a small, boring, enforced rule is worth more than a style guide nobody reads.

## Decisions I need from you

1. ~~**Product name.**~~ **Decided: Montra.** Done — label, storage names, export filenames and colour aliases renamed, with old files migrated on first launch.
2. **Android-only, or a Play release?** If Play: edge-to-edge, backup rules, targetSdk cadence and CI are contractual, not nice-to-haves.
3. **Localisation target.** If PH-only, PHP-first defaults are fine; if wider, the currency list and `Money.parse` need the locale work in P2.19.
4. **AppCompat + Views, or Compose for new screens?** The M3 pass would be far cheaper in Compose (`MaterialTheme`, `NavigationBar`, `Card`, dynamic colour). Migrating screen by screen is realistic; a big-bang rewrite is not.
