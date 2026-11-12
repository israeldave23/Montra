# Goals screen — purpose, design, and what changed

## Purpose

Transactions record money that has already moved. **Goals record intent**: money you are deliberately setting aside for something — an emergency fund, a laptop, a trip. The screen exists to answer three questions without tapping through anything:

1. **How far along am I?** A number and a bar that never overstate progress.
2. **Am I on pace?** Status derived from the target date (on track / due soon / overdue / no date) plus a rough monthly amount that would finish on time.
3. **What do I need to do?** One primary action — add funds — reachable from every row and every detail view.

Goals are a *tracker*, not a second ledger. The app deliberately has no transfer between a goal and an account, so the UI says so in the form, the empty state, and the add-funds dialog. Nothing about the screen should imply that money moved.

## What was wrong before

| Problem | Effect |
|---|---|
| `(value * 100).toLong()` on a `Float` | ~4.6% of entered amounts stored one cent low (`0.53` → 52) |
| Hard-coded `PHP` currency | Multi-currency users could not use the feature at all |
| Validation in `.setPositiveButton` | Dialog auto-dismissed before validation; errors were invisible |
| Add funds only via tapping a row | Undiscoverable; no affordance on the row |
| No edit, withdraw, or delete | Any typo was permanent; over-saving was irreversible |
| No target date | Could not answer "am I on pace?" — the most useful question |
| No empty state, no summary, no undo | Screen looked broken on first run and punished mistakes |
| `ListView` + `BaseAdapter`, reload on resume | Flicker, no live updates, weak row design |

## Data model

`Goal` (domain, `domain/Goals.kt`): `id`, `name`, `target: Money`, `saved: Money`, `targetDateMillis: Long?` — all amounts share one currency and stay in exact minor units.

Room `goals` table gains `targetDateMillis INTEGER` (nullable), schema **v3 → v4** via `MIGRATION_3_4`. Existing rows migrate with `NULL` (no date), which reads as "No target date".

## Behaviour

* **List** — sorted active-first by nearest date, then undated, then completed. Each row: name, status pill, `saved of target`, progress bar, percent + date, and a `⋮` menu (add, withdraw, edit, delete).
* **Summary card** — total saved per currency and `N of M complete … X to go`. Currencies are joined, never summed together.
* **Detail dialog** — status, progress, remaining, date, and the pace line ("About ₱300.00 a month to finish by Jan 31, 2027"), with add / withdraw / edit / delete.
* **Create & edit** — exact `Money.parse`, inline errors, optional target date with a clear control, name capped at 100 characters. Editing never changes a goal's currency.
* **Add / withdraw** — quick presets (25%, 50%, everything), live "New total" preview, withdraw capped at what is saved. Both run through optimistic concurrency in the repository, so two quick taps cannot lose an update.
* **Undo** — add, withdraw and delete all show a snackbar with undo (delete restores the original row losslessly).
* **Empty state** — explains the concept and the tracking-only rule, with a "Create your first goal" action.
* **Accessibility** — rows expose a single sentence (`"Emergency fund. ₱12,000.00 of ₱50,000.00 saved, 24 percent. Due Mar 31, 2027"`), the progress bar has a description, the overflow button is labelled, and the funds preview is a polite live region.
* **Currency** — new goals default to the app's *Default currency* setting (Profile → Settings) and keep it for life.

## Pace math

`requiredMonthlyMinorUnits` = remaining ÷ max(1, ceil(daysLeft / 30)), rounded down, and `null` when there is no date or the date has passed. It is deliberately approximate and labelled "About …". Status boundaries: complete → `COMPLETED`; no date → `OPEN_ENDED`; past date → `OVERDUE`; ≤ 14 days → `DUE_SOON`; otherwise `TRACKING`.

## Files

**New**
- `domain/Goals.kt` — `Goal`, `GoalStatus`, `GoalSummary`, `GoalOrdering`
- `GoalsActivity.kt` — screen, detail sheet, editor, funds dialog, undo
- `GoalAdapter.kt` — row binding, status labels/colours, date formatting
- `res/layout/activity_goals.xml`, `item_goal_row.xml`, `dialog_goal_edit.xml`, `dialog_goal_funds.xml`, `dialog_goal_detail.xml`
- `res/drawable/goal_pill_background.xml`
- `src/test/.../domain/GoalProgressTest.kt`

**Changed**
- `domain/Models.kt` — `Money.plainString()` + `fractionDigits()` (round-trips through `parse`)
- `db/Entities.kt`, `db/FinanceDatabase.kt`, `db/Migrations.kt`, `db/FinanceDao.kt`
- `data/RoomFinanceRepository.kt` — goals API, atomic funds adjustment, top-level `GoalEntity` mapping
- `SectionActivities.kt` — old goals screen removed
- `values/strings.xml`, `values{,-night}/colors.xml`

## Still out of scope

Goals are not linked to accounts or transactions, and never affect balances. That is a product decision, not an oversight: linking would need a real earmarking model (reserved balances, transfers, reconciliation) and should not be faked. The next natural step is a `goal_contributions` history table so the screen can show a timeline and per-month savings rate.

Note: the same `Float` money bug still exists on the **Budget** screen; it was left untouched here to keep this change reviewable.
