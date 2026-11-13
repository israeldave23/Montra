# Code review — Montra ("Lumen Finance") latest

**Reviewed:** commit `8ee2ce2` *"Fix Crash"* (`main`), working branch `arena/de08cb0a-montra`, tree clean — i.e. the entire current app, since the repo has a single commit.
**Scope:** 82 files, ~3.5k lines: Kotlin sources (`app/src/main/kotlin`), Room layer, domain layer, tests, resources, manifest, `module.toml`, `DEVELOPMENT_PROGRESS.md`.
**Method:** full read of every source file + automated cross-check of every `R.*` reference against `res/` (0 missing app resources, see Appendix) + numeric experiment on the money-parsing path.
**Not verified:** this sandbox has no JDK, no Android SDK and no Gradle, so **I could not compile, run, lint or execute tests**. Anything below about build/runtime behaviour is static analysis; where a claim depends on the toolchain I say so explicitly.

---

## Verdict

The domain core is in good shape — exact minor-unit money, checked arithmetic, strict per-currency separation, encrypted legacy storage with a migration path. The **UI/repository layer around it is pre-alpha** and still contains user-visible dead ends: one feature always fails, one can destroy the database, money entry silently loses cents, and two features aggregate currencies against the project's own stated rule.

| Severity | Count | Meaning |
|---|---|---|
| **P0** | 4 | Broken feature / data-loss risk / build-config blocker |
| **P1** | 5 | Wrong or misleading financial output, dead ends, silent failures |
| **P2** | 12 | Robustness, performance, testability, accessibility |
| **P3** | ~12 | Polish, dead code, naming |

The "Fix Crash" commit is not obviously a crash fix in this snapshot; the app still has several latent crash paths listed below (CSV export, unguarded spinner indexing, malformed/unexpected rows).

---

## P0 — fix before anything else

### P0-1. CSV export always fails: `FileProvider` is used but never declared
`ProfileActivity.kt:177` calls `FileProvider.getUriForFile(this, "com.dga.montra.fileprovider", file)`, and `res/xml/file_provider_paths.xml` exists — but `AndroidManifest.xml` (24 lines, full file) declares **no `<provider>`**. `getUriForFile` therefore throws `IllegalArgumentException: Couldn't find meta-data for provider with authority com.dga.montra.fileprovider`, which is swallowed by the catch at `ProfileActivity.kt:74` and surfaced as the generic *"Could not complete operation."* The export feature cannot work on any device.

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
               android:resource="@xml/file_provider_paths" />
</provider>
```
Also narrow the paths file: `<cache-path path="."/>` currently exposes the **whole** cache directory to any app the user shares to.

### P0-2. Backup/restore can silently corrupt or lose all data
`ProfileActivity.kt:224-240` plus `RoomFinanceRepository.kt:21-25`:

1. **WAL is not handled.** Room enables write-ahead logging by default, so recent commits live in `lumen_finance.db-wal`, not the `.db` file. `backupToUri` copies only the `.db` → **the most recent transactions are missing from the backup**. `restoreFromUri` writes only the `.db` and leaves the old `-wal`/`-shm` behind → a restored file can be reinterpreted with a foreign WAL (corruption / "database disk image is malformed").
2. **The database is copied while Room has it open** (`RoomFinanceRepository` singleton, never closed). Overwriting a file that SQLite has open, without `close()`, is undefined behaviour; at best the app keeps showing the old data, at worst it corrupts.
3. **No validation, no confirmation.** Any file the user picks (`type="*/*"`) is copied over the live DB. There's no SQLite header check, no `PRAGMA integrity_check`, no "this replaces everything" confirmation — the most destructive action in the app is one tap, unattended.
4. **Both copies run on the main thread** (`MainScope.launch` → `backupToUri`/`restoreFromUri` do file IO directly) → ANR on a large DB.
5. **The backup is an unencrypted SQLite file**, despite the app's encryption story and `allowBackup="false"`.

Minimum fix: add `suspend fun checkpoint/close()` to the repository; `PRAGMA wal_checkpoint(TRUNCATE)` before backup; for restore — confirm, validate header/`user_version`, close Room, delete `-wal`/`-shm`, copy on `Dispatchers.IO`, then restart the process (`Intent` to launcher + `exitProcess`). Prefer `VACUUM INTO` for a single consistent file.

### P0-3. Build configuration: no Room compiler, no Gradle build at all
`app/module.toml` is the only build description in the repo — there is **no `settings.gradle(.kts)`, no `build.gradle(.kts)`, no version catalog, no CI**. In `module.toml` the dependencies are `kotlin-stdlib`, `room-runtime`, `core`, `recyclerview`, `material`; there is **no KSP/kapt plugin and no `androidx.room:room-compiler`**. Without the processor there is no generated `FinanceDatabase_Impl`, and `Room.databaseBuilder(...).build()` throws at first use:

> `java.lang.RuntimeException: Cannot find implementation for com.dga.montra.db.FinanceDatabase. FinanceDatabase_Impl does not exist`

`DEVELOPMENT_PROGRESS.md` claims "Room KSP generation … succeeded", so the toolchain may inject the processor — **verify this first**, because if it doesn't, every DB-backed screen fails behind a generic storage toast. Same ambiguity for `kotlinx-coroutines-android`: `MainScope()`/`Dispatchers.Main` need it, and it is not declared (it may arrive transitively via Room 2.8.x — also verify).

### P0-4. Budgets and goals are entered as `Float` — cents are silently lost
`SectionActivities.kt:104,111,215-216,223,266,273,411` do `(value * 100).toLong()` on a `Float`, plus `toFloatOrNullOrZero()` which maps *any* unparseable input to `0f`. This is exactly the failure the rest of the codebase was designed to prevent (`Money.parse` with `RoundingMode.UNNECESSARY`).

Measured (float32 emulation of the Kotlin expression, first 200,000 cent values): **9,173 values truncate one cent low**, e.g.

| Input | Expected minor units | Stored |
|---|---|---|
| `0.53` | 53 | **52** |
| `1.05` | 105 | **104** |
| `2.11` | 211 | **210** |
| `10.10` | 1010 | 1010 (ok) |

Also `"abc"` → `0` (then rejected as ≤ 0 with a misleading "enter an amount" message), and `"1,234.56"` → `0`.

Fix: `Money.parse(text, budgetCurrency)` and reuse `Money.format()`; surface the parse message instead of collapsing to zero.

---

## P1 — wrong output or dead ends

### P1-5. Budgets and Insights mix currencies — violates the project's own rule
* `FinanceDao.kt:95-96` — `SELECT SUM(minorUnits) … WHERE categoryId = :categoryId AND timestampMillis >= :startMillis AND timestampMillis <= :endMillis AND type = 'EXPENSE'` has **no currency filter**. A ₱10,000 "Dining" budget sums PHP **and** USD dining.
* `SectionActivities.kt:379-401` — Insights sums every `EXPENSE` across all currencies and then renders it as `Money(spent, "PHP")`.
* `RoomFinanceRepository.kt:109,128,130` — budgets/goals are hard-coded PHP-only (`require(limit.currencyCode == "PHP")`), so multi-currency users simply cannot use the feature, while `FinancialCalculations.monthlyCategorySpending` (which groups per currency, correctly) is never called.

`DEVELOPMENT_PROGRESS.md` lists "Never combine currencies without explicit conversion" as an architectural decision; these two screens break it. Fix: pass `currency` into the DAO query, make budgets/goals currency-scoped, and have Insights call `FinancialCalculations.monthlyCategorySpending` per currency.

### P1-6. Transfers are a permanent dead end for accounts
* Paired legs carry **no transfer/pair id** (`db/Entities.kt:31-40`), only matching amount+timestamp by convention.
* `TransactionsActivity.kt:55`, `:268-274` blocks editing/removing transfer legs; only `TRANSFER_OUT/IN` rows are non-swipeable.
* `FinanceDao.kt:101-106` — `deleteAccountSafely` refuses any account with `transferCount > 0`.

Net effect: **once an account has received a transfer it can never be deleted**, and no screen can delete the transfer first. (Account deletion/rename/archive have no UI at all — see P1-8 and the P3 dead-code list.) Fix: add `transferId` (+ index), a `@Transaction` "delete pair" DAO method, a reconciliation for existing rows, and give the UI a transfer detail screen.

### P1-7. Budget/Goal dialogs fail silently
`SectionActivities.kt:96-121, 209-227, 265-277` attach validation to `.setPositiveButton`, which **auto-dismisses the dialog before the handler runs**. Every `input.setError(...)`/`return@setPositiveButton` writes to a detached view, so:
* invalid amounts, duplicate budget for the same category (unique index `index_budgets_month_categoryId` → `SQLiteConstraintException`) and repository `require` failures all produce **zero feedback** — the dialog just closes;
* the user's typed values are lost.

The add-account/add-transaction dialogs already do this correctly with `setOnShowListener { getButton(POSITIVE).setOnClickListener { … } }` (`MainActivity.kt:311-330`). Copy that pattern.

### P1-8. Archived accounts are not archived
`FinanceDao.kt:14-15,17-18` select **all** accounts; `RoomFinanceRepository.kt:162-181` includes them in balances, counts and the recent list; transaction/transfer spinners (`MainActivity.kt:190-192`, `:240-256`) offer them as targets; `archiveAccount` (`RoomFinanceRepository.kt:141`) is never called. So the `isArchived` column (schema v2) is inert: no UI, no filtering, no effect. Decide the semantics (excluded from totals + hidden from pickers, with a "show archived" toggle) or drop the column.

### P1-9. Dashboard aggregation runs on the main thread and maps the whole table
`RoomFinanceRepository.kt:162-181` — the `combine { }` transform executes in the **collector's** context, i.e. `Dispatchers.Main` (`MainActivity.kt:92-104`), on **every** DB emission: it converts every `TransactionEntity` to a domain object, sums all of them, computes monthly totals and re-sorts. With a few thousand rows this is measurable jank on every insert. Fix: `flowOn(Dispatchers.Default)` and/or SQL aggregates (`SUM` by currency, `LIMIT 5` for recent) instead of full materialisation. `dashboard()` (`:182`) also queries `dao.transactions()` **twice**, and duplicates the same math a third time.

---

## P2 — robustness, correctness details, testability

1. **Tests almost certainly never run.** `module.toml:52` declares `org.junit.jupiter:junit-jupiter` (JUnit 5) with no JUnit Platform configuration; Android unit tests use the JUnit 4 runner unless the JUnit 5 plugin is applied and `test { useJUnitPlatform() }` is set — and there is no Gradle file to configure it. This matches the documented `"No tests found"`. `MoneySelfCheck`/`FinancialCalculationsSelfCheck` are `main()` self-checks, not tests, and are never executed by a build. `TransactionCategoryTest.uncategorizedConstantsMatchLegacyStrings` is a tautology (asserts constants equal their own literals).
2. **Migrations are unverified.** `FinanceDatabase.kt:9` has `exportSchema = false`, so there are no schema JSONs and Room can't validate `MIGRATION_1_2`/`MIGRATION_2_3` (`Migrations.kt`) against the entities. `MIGRATION_1_2` assumes the v1 `accounts` table lacked `isArchived` **and** had a `name` index matching `index_accounts_name`; if reality differs, Room throws `IllegalStateException: Migration didn't properly handle …` on upgrade. Turn on `exportSchema = true`, check in `schemas/`, add a `MigrationTestHelper` test.
3. **Legacy migration is racy.** `migrateLegacyIfNeeded` (`RoomFinanceRepository.kt:29-42`) is called from ~10 entry points and every `withContext(IO)` path. Two concurrent first calls (e.g. Home + Activity tab on first launch) can both see `accounts().isEmpty()`, both import, and the second dies on the PK constraint. Guard with a `Mutex` (or `@Transaction`-wrapped check-and-import).
4. **Legacy corruption wedges the app.** If the old key is lost (data restored to a new device) or the JSON is malformed, `AccountRepository.all()`/`decrypt` throws on every repository call, so the app shows "Could not load accounts" forever with no recovery path (no "discard legacy data" option, no fallback). Add a bounded try/catch that quarantines `.data` and proceeds with an empty DB.
5. **Account detail shows the wrong number and the wrong sign.** `SectionActivities.kt:307` renders `account.openingBalance` as the account balance (ignores transactions), and `AccountTransactionAdapter` (`:326-341`) marks everything except INCOME/REFUND as negative — so `TRANSFER_IN` and `ADJUSTMENT` render as `-` with the error colour. Use `signedMinorUnits()` and show a computed balance.
6. **Raw category ids leak into the UI.** `TransactionAdapter.kt:21`, `TransactionListAdapter.kt:44`, `AccountDetailActivity` fallback: `tx.categoryId.replaceFirstChar { uppercase }` → users see `Other_expense`, `Income_uncategorized`, `Transfer`. Map to `Category.name` (as Insights/Budgets already do).
7. **CSV export is misleading and injectable.** `ProfileActivity.kt:171` exports `minorUnits` (so ₱12.34 → `1234`, and the currency column is the only clue), and `escapeCsv` doesn't neutralise leading `=`, `+`, `-`, `@` → **formula injection** into Excel/Sheets from a transaction note. Export `BigDecimal.valueOf(minorUnits, digits).toPlainString()` and prefix risky cells with `'`.
8. **Theme "System" is one-way.** *(Fixed: `AppActivity` is now an `AppCompatActivity` and the mode
   goes through `AppCompatDelegate`; the `resources.updateConfiguration` call is gone.)* Both `applyTheme()` implementations mutate `resources.configuration` in place; after choosing Dark, every later activity reads the already-forced configuration as "current", so switching back to System stays dark until the process restarts. `Resources.updateConfiguration` is deprecated. Prefer `AppCompatDelegate.setDefaultNightMode` (and `AppCompatActivity`).
9. **`targetSdk 35` edge-to-edge.** Android 15 draws the window behind system bars; the layouts rely on a single `android:fitsSystemWindows="true"` root and a fixed 76dp bottom bar, with no `WindowInsets` handling. Expect the bottom navigation to sit under the gesture/navigation bar. Verify on an API 35 device and apply insets explicitly.
10. **Dialog/edit edge cases.** `TransactionsActivity.kt:188-193` swallows the **first** type-spinner callback by flag (`initialTypeSelection`); if the framework doesn't emit an initial callback after `setSelection`, the user's first real change is ignored and the category list doesn't update. `:231` indexes `newCats[category.selectedItemPosition]` unguarded (`-1` → `IndexOutOfBoundsException`). Use an explicit "suppress next" that is cleared on the first `onItemSelected`, and `getOrNull`.
11. **Swipe and long-press disagree.** Swipe deletes immediately (`TransactionsActivity.kt:57-60`) with an undo snackbar; long-press shows a confirmation. Pick one. Also `getSwipeDirs` and `onSwiped` rely on `bindingAdapterPosition`, which can be `NO_POSITION` during animations.
12. **Validation is inconsistent between layers.** `addAccount` (`RoomFinanceRepository.kt:49`) accepts any name length/currency; `renameAccount` (`:101-104`) caps at 100 chars and trims; `Transaction.note` is unbounded; `Money(...)` itself has no `init` validation, so any bad row from DB/legacy JSON surfaces as an `IllegalArgumentException` in `format()` or `valueOf`. Add `init` checks in `Money`/`Account` and a `CHECK (type IN …)`/`CHECK (minorUnits >= 0)` on the transaction table.

---

## P3 — polish, dead code, consistency

* **Dead code / dead features:** `RoomFinanceRepository.dashboard()`, `AccountRepository.dashboard()`, `archiveAccount`, `renameAccount`, `deleteAccount`, `FinancialCalculations.total/monthlyComparison/monthlyCategorySpending/netCashFlow` (tests only), `SectionActivity` (adds nothing over `Activity`), `open_add_transaction` extra (nobody sends it), `TransactionType.ADJUSTMENT` and the `TRANSFER` filter path (cannot be created or deleted from the UI). 21 string resources are unreferenced — they describe the missing account-management and insights screens: `accounts_title`, `account_row`, `account_detail_title`, `rename_account`, `delete_account`, `delete_account_message`, `delete_account_error`, `over_budget`, `planning_saved`, `insight_income`, `insight_expenses`, `insight_transactions`, `insight_empty`, `budget_body`, `goals_body`, `insights_body`, `home_description`, `profile_back`, `saved_invalid`, `view_activity`, `transaction_date_default`.
* **Money semantics worth a decision:** `REFUND` is added to *income* in `FinancialCalculations.monthlySummary` (`:16`) and `netCashFlow` (`:39`) rather than netted against expenses, so a fully refunded purchase still shows as full spending in category analytics (`categorySpending` excludes refunds) while also inflating income. `ADJUSTMENT` is treated as `+` by the dashboard and `signedMinorUnits`, but skipped by `monthlySummary` and `netCashFlow` — three different notions of the same transaction.
* **Month window is short by one second:** `spentForCategoryMonth` ends at `23:59:59.000` (`RoomFinanceRepository.kt:117-122`), so a transaction at `23:59:59.4xx` is excluded. Use the exclusive start of the next month.
* **Progress math** uses `minOf(spent, limit) * 100 / limit` in `Long` (`SectionActivities.kt:142, 246`): fine for realistic values, overflows for values > 9.2e16. Clamp before multiplying.
* **`Money.format()`** uses `NumberFormat.getCurrencyInstance()` with the default locale but overrides only the currency; the symbol/placement mix can look odd for PHP/JPY on non-default locales. Consider an explicit locale per currency.
* **Hidden-balance inconsistencies:** masking uses the hardcoded `"₱ ••••"` (`strings.xml:104`) regardless of the real currencies, and while the total and monthly summary are hidden the individual transaction amounts stay visible (`MainActivity.kt:107-140`).
* **Accessibility/i18n:** all copy is English-only (`values/` with no `values-fil/`); clickable `TextView`s (balance, nav items, avatar, back arrows, export/backup/restore) have no role/content description beyond a few labels; emoji glyph icons (`🍴`, `🛒`, `₱`, `↔`) are announced as text; dynamic changes (monthly summary, totals, "transaction deleted") are not announced. Add `contentDescription`, `AccessibilityLiveRegion`, and touch feedback.
* **Branding:** repo/product is "Montra", app label is "Lumen Finance", preferences are `lumen_*`, the DB is `lumen_finance.db`, package is `com.dga.montra`. Pick one namespace before anything is released (it also affects the FileProvider authority in P0-1).
* **`Theme.Material3.*` with `android.app.Activity`** (not `AppCompatActivity`): Material widgets and dynamic theme attrs assume an AppCompat theme/Activity. Migrate to `AppCompatActivity` (also needed for P2-8).
* **Unused import** `kotlin.io.path.copyTo` (`ProfileActivity.kt:30`) — the calls resolve to `kotlin.io.copyTo(File)`; remove it to avoid confusion.
* `DEVELOPMENT_PROGRESS.md` contains duplicated "Validation" blocks and stale claims (build timeout, "No tests found") — worth collapsing before it's used as a status source.

---

## What is genuinely good (keep it)

* Exact money handling in the domain: `Money.parse` with `RoundingMode.UNNECESSARY` + `longValueExact`, integer minor units, `Math.addExact` everywhere, per-currency maps. This is the part the rest of the app should be pulled *towards*, not away from.
* `Transaction` invariants (non-negative amount, category required) and `Money` currency allow-list at the boundary of creation.
* AES-GCM with Android Keystore for the legacy store, one-way migration to Room behind a marker, and `allowBackup="false"`.
* No `INTERNET` permission, no analytics, no network code — the privacy posture is credible for a local-first finance app.
* Optimistic concurrency on goal funding (`UPDATE … WHERE savedMinorUnits = :previous`) is a nice touch.
* Resource discipline: every `R.*` used by code exists (Appendix); design tokens (`dimens`, colours, shapes) are centralised and there is a small token linter.
* Sensible UI copy, empty states, and a filter/search surface that is mostly wired correctly (the stale-request `requestVersion` guard is right).

---

## Suggested order of work

**Day 1 (makes it shippable-as-a-demo)**
1. P0-1 FileProvider declaration.
2. P0-4 + P1-7: route budgets/goals through `Money.parse` and fix the auto-dismiss validation pattern.
3. P0-3: confirm the Room processor is applied; commit a real Gradle build (or document the toolchain).
4. P1-5: currency-scope the budget query and Insights.

**Week 1 (protects data)**
5. P0-2: safe backup/restore (checkpoint, close, validate, confirm, IO off main thread).
6. P2-3/P2-4: migration mutex + legacy failure recovery.
7. P2-2: `exportSchema = true` + migration test; P2-1: make the test task actually execute JUnit 5 (or move tests to JUnit 4).
8. P1-6: `transferId` + paired delete; P1-8: archived semantics.

**Before release**
9. P1-9/P2-10/P2-11/P2-12, then the P3 accessibility, i18n, branding and dead-code sweep.

---

## How to verify (once a real build exists)

```bash
./gradlew :app:assembleDebug          # must pass, not "timed out"
./gradlew :app:lintDebug              # expect P2/P3 warnings to shrink
./gradlew :app:testDebugUnitTest      # must report real test counts, not "No tests found"
```
Then manually: export CSV (must not show the generic error), create a budget with `0.53`, run a transfer and try to delete the account, switch theme to Dark → System, backup → add a transaction → restore, rotate the device while a dialog is open, and run the app with TalkBack on.

---

## Appendix — automated resource cross-check

Script compared every `R.type.name` in Kotlin and every `@type/name` in XML against `res/`:

* **Missing app resources: 0.** (The three apparent "misses" — `android.R.id.content`, `android.R.layout.simple_spinner_dropdown_item`, `android.R.string.ok` — are framework resources.)
* **XML parse errors: 0.**
* **Unused string resources: 21** (listed above) — a direct index of features that are still stubs.

The UI token linter (`tools/check_ui_tokens.py`) passes by inspection, but it is not wired into any build or CI, so it protects nothing today.
