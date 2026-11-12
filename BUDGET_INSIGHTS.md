# Budget and Insights screens — purpose, design, and what changed

## Purpose

Two screens, two questions, one rule: **money is never made up.**

**Budget — "what is left this month?"**
Transactions record what already happened. A budget turns that history into a forward-looking plan: a
monthly ceiling per category. The screen exists to show, per category, how much of the ceiling is used
and — the part the old screen never showed — **how much is still available**, or how far over you are.
Budgets are a comparison, not a ledger: setting one never moves money, and spending is always derived
from transactions rather than typed in.

**Insights — "where did it go, and how does it compare?"**
One month at a time: income, expenses, net, the biggest categories with their share of spending, how
this month compares with the previous one, the daily pace, and where that pace leads by month end.
Every number is derived from the same transaction store the rest of the app writes.

**The rule:** amounts are exact minor units, and currencies are *never* added to each other. A peso is
not a dollar, and a screen that adds them is lying.

## What was wrong before

| Problem | Effect |
|---|---|
| `toFloatOrNullOrZero()` then `(value * 100).toLong()` | Cent drift for many inputs; unparseable text silently became `0` |
| `Money(..., "PHP")` hard-coded in `createBudget` | Budgets were peso-only, whatever the app's default currency |
| `Money(spent, "PHP")` in the Insights adapter | **Expenses in USD, EUR and JPY were printed as pesos** — the totals were numerically wrong |
| Insights summed `amount.minorUnits` across all currencies | Mixed-currency totals, the single most misleading number in the app |
| Month range ended at `23:59:59` | The final second of the month was excluded from every total |
| `spentForCategoryMonth` per budget | N+1 queries: a new query for every row on every refresh |
| Two chained dialogs (category list → amount) | Two taps to reach the input, no edit, no delete, no duplicate check |
| `ListView` + `BaseAdapter`, reload in `onResume` | No live updates, no undo, no empty state, no summary |
| Bar width relative to the largest category only | A category at 40% of spending looked identical to one at 90% of its budget |

## Data model

`Budget` (domain, `domain/Budgets.kt`): `id`, `month: YearMonth`, `categoryId`, `limit: Money`.

`BudgetProgress`: a budget plus the spending recorded in **its** category **and** currency.
Everything the UI needs is derived here, so the screen never does arithmetic:

- `remainingMinorUnits` — signed, exact, never clamped (`limit - spent`)
- `usedPercent()` — floored, capped at 100, so the bar cannot overstate
- `state` — `ON_TRACK` (< 80%), `NEAR_LIMIT` (≥ 80%), `AT_LIMIT` (= 100%), `OVER` (> 100%)

`BudgetSummary`: limits and spend **grouped by currency**, plus counts. `BudgetOrdering` sorts the most
at-risk budgets first.

`MonthlyInsight` / `CurrencyInsight` / `CategorySpend` (domain, `domain/Insights.kt`) come from
`InsightCalculator.compute(month, zone, transactions, today)`: income (income + refunds), expenses,
net, per-category totals with floored shares, previous-month expenses, daily average and a
straight-line projection.

No schema change: the existing `budgets` table already stores `month`, `categoryId`,
`limitMinorUnits` and `currencyCode`, so there is no migration and no version bump. One budget per
(month, category) is enforced by the existing unique index and checked up front with a readable error.

## Behaviour

**Budget**

* **Header** — month name and days left ("November 2026 · 18 days left"); the label switches to
  "last day" on the final day of the current month.
* **Summary card** — total limits per currency and "Spent … · N budgets · M over limit". Currencies
  are listed side by side, never summed.
* **Rows** — category, state pill (On track / Nearly spent / Spent / Over), "X of Y spent", a bar in the
  state colour, and "… left" or "… over · 85% used". Most at-risk rows sort first.
* **One dialog** — category spinner (categories that already have a budget are marked
  "already budgeted" and preselecting skips them), exact `Money.parse` amount, inline errors for
  invalid or zero amounts and for duplicates. Editing edits the limit only; the category and currency
  stay fixed, so historical spend cannot be re-attributed by accident.
* **Delete with undo** — undo re-inserts the original row losslessly.
* **Empty state** — explains what a limit is and that it does not move money, with
  "Set your first budget".
* **One budget per category** — the database index and the dialog agree. When every expense
  category is already budgeted, the action explains that instead of opening a dialog that can only
  fail.

**Insights**

* **Month switcher** — `‹ November 2026 ›`; the forward arrow stops at the current month (alpha 0.35
  when disabled).
* **One card per currency** — big expense total, "Income … · Net …", the change against the previous
  month ("₱1,200.00 more than October 2026 (12%)"), the daily average and — for the current month
  only — "Projected … for the month", then "Biggest category: Rent · 12 transactions".
* **Category bars** — amount, share of that currency's spending, and a bar whose width *is* the share,
  so bar length and the printed percentage always agree.
* **Empty state** — "Nothing recorded in November 2026 yet." plus a hint to add a transaction or look
  back a month.
* **Live** — the screen observes the transaction range for the selected and previous months, so a new
  transaction updates it without a resume.

## Money and month math

* Month ranges are `[first day 00:00, first day of next month 00:00)` in the local zone — half-open,
  so no amount can fall in the gap between months and none is excluded.
* Spending is matched on **category + currency**, so a USD expense never counts against a peso budget.
* Percentages are computed with `BigDecimal` and floored (`domain/Percent.kt`), shared with goals:
  `percentOf(part, whole, cap)` clamps *after* the division, so no ratio can overflow an `Int`.
* Projection = `expense / daysElapsed * daysInMonth` for the current month; past months show actuals.
  `daysElapsed` is never 0 for the current month, and the multiplication is overflow-checked.
* Totals use `Math.addExact` everywhere, so a value that would overflow fails loudly instead of wrapping.

## Files

**New**
- `domain/Budgets.kt` — `Budget`, `BudgetProgress`, `BudgetState`, `BudgetSummary`, `BudgetOrdering`, `daysLeft`
- `domain/Insights.kt` — `CategorySpend`, `CurrencyInsight`, `MonthlyInsight`, `InsightCalculator`
- `domain/Percent.kt` — `percentOf`, `percentChange` (shared by goals, budgets and insights)
- `BudgetActivity.kt`, `BudgetAdapter.kt`
- `InsightsActivity.kt`, `InsightAdapter.kt`
- `res/layout/activity_budget.xml`, `item_budget_row.xml`, `dialog_budget_edit.xml`
- `res/layout/activity_insights.xml`, `item_insight_summary.xml`, `item_insight_row.xml`
- `src/test/.../domain/BudgetProgressTest.kt`, `src/test/.../domain/InsightsTest.kt`

**Changed**
- `db/FinanceDao.kt` — `observeBudgets(month)`, `budget(id)`, `updateBudget`, `deleteBudget`,
  `observeTransactionsBetween(start, end)`; removed the per-category `spentForCategoryMonth` query
- `data/RoomFinanceRepository.kt` — `observeBudgets`, `createBudget(month, category, limit)`,
  `updateBudget`, `deleteBudget`, `restoreBudget`, `observeInsight`; removed the PHP-only budget API
- `SectionActivities.kt` — old `BudgetActivity` and `InsightsActivity` removed (same fully qualified
  names, so the manifest and `Navigation` are unchanged); `toFloatOrNullOrZero()` deleted
- `domain/Goals.kt` — `progressPercent()` now uses the shared `percentOf`
- `CategoryIcons.kt` — `categoryLabel(id)` shared by both screens
- `res/values/strings.xml` — budget and insights copy; legacy `insight_*`, `progress_amount`,
  `budget_limit`, `over_budget` and `create_budget` strings removed
- `GOALS_SCREEN.md`-style docs: this file

## Verification

No JDK/Gradle in the review environment, so correctness is shown by static checks rather than a build:

- every `R.*` reference resolves to a declared resource or id (0 missing)
- `getString`/`getQuantityString` argument counts match their format specifiers (paren-aware scan, 0 mismatches)
- layout ids match the ids each activity and adapter looks up (0 missing)
- brace/parenthesis balance is clean in every touched Kotlin file
- `tools/check_ui_tokens.py` passes (no off-scale spacing or text sizes)
- domain behaviour is covered by `BudgetProgressTest` (floored/capped percentages, signed remaining,
  four states, ordering, per-currency summary, day counting) and `InsightsTest` (currency separation,
  refunds as income, shares, month-over-month change, empty month, projection, top category)

## Still out of scope

- **No rollover or carry-over** of unused budget amounts; each month starts from the limit.
- **No budget editing of past months** — the screen always shows the current month, because a budget is
  a plan, not a record.
- **No tap-through to a filtered transaction list** from a category bar; the activity screen does not yet
  accept a category filter, and inventing one was outside this change.
- **No custom categories** — budgets use the built-in expense categories.
- **No multi-currency conversion** — by design. Each currency stands alone.
