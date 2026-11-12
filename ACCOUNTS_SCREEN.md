# Accounts — purpose, design, and what changed

## Purpose

An account is **where money actually lives**: cash, a bank, a wallet. Everything else in the app is
derived from accounts and the transactions recorded against them.

The home screen exists to answer two questions in one glance:

1. **How much money do I have?** One total per currency, never mixed.
2. **Where is it?** Each account as a row with its *own* real balance, so the total is explainable
   rather than a number you have to trust.

Tapping an account answers the third question — **what has been happening here?** — with the account's
current balance, its opening balance, the net movement since, and its own transaction history, plus
the actions that belong to that account.

## What was wrong before

| Problem | Effect |
|---|---|
| Home showed only an aggregate balance | Accounts were invisible; the total could not be explained or checked |
| Tapping the balance opened `accounts.firstOrNull()` | You were taken to an **arbitrary** account, not the one you meant |
| Account detail printed `openingBalance` as "balance" | **Wrong number**: every transaction was ignored, so it never matched the dashboard |
| `isArchived` was ignored by the dashboard | Archived accounts still counted in totals and in "N accounts" — a removed account kept inflating the balance |
| Opening balance had to be `> 0` | You could not create an account that starts at zero — the normal case for a new wallet |
| No rename, archive or delete UI | The repository supported all three; the strings `rename_account` / `delete_account*` were dead code |
| Archived accounts appeared in the add-transaction and transfer pickers | New money could be posted to an account you had deliberately retired |
| `ListView` + ad-hoc `BaseAdapter` on detail | No live updates, no empty state, no actions, no accessibility labels |
| Account detail fell back to the first account with no id | Opening it from anywhere else silently showed the wrong account |

## Data model

`AccountBalance` (domain, `domain/Accounts.kt`): an `Account` plus `currentBalance: Money`,
`transactionCount`, `lastActivityMillis`, and the derived `movementMinorUnits` (current − opening).

`accountBalances(accounts, transactions)` is the single place the number is computed:
`current = opening + Σ signedMinorUnits(transaction)`. A transaction pointing at an unknown account, or
one whose currency differs from its account, is a data-integrity error and throws rather than being
silently dropped. All sums use `Math.addExact`.

`AccountSummary.of(balances)` totals **active accounts only**, per currency, and counts archived ones
separately. `AccountOrdering.sorted(...)` puts active accounts first, then case-insensitive name.

`DashboardState` gained `accounts: List<AccountBalance>` and `archivedCount`, so the dashboard, the home
list and the detail screen all read the same computation — they cannot drift apart.

## Behaviour

**Home**

* **Balance card** — total per active currency, hidden-amounts toggle still works. It is informational:
  tapping it no longer jumps to a random account.
* **Accounts section** (new) — header with "Accounts" and a count, then one row per account, active
  first. Each row: initials avatar, name, `CURRENCY · N transactions` (or "No activity yet"), the
  account's current balance, and an "Archived" pill when relevant. Tapping the row opens **that**
  account.
* **Archived accounts** stay listed (their history is real) but are out of the total and the active
  count: "3 accounts · 1 archived".
* **Empty state** — "No accounts yet. Add one to see each balance here."
* **Add account** — opening balance may now be blank (treated as zero) or any non-negative amount,
  with inline errors and a currency picker that remembers the last choice.

**Account detail** (rebuilt, `AccountDetailActivity.kt`)

* **Header** — back, name, archived pill and explanation, and a `⋮` menu (rename, archive/unarchive,
  delete).
* **Hero card** — *current* balance, plus `Opening ₱x · Movement +₱y`, and the transaction count.
  Amounts respect the hide-balances setting.
* **Actions** — "Add transaction" (account preselected) and "Transfer" (this account preselected as
  the source). Both are disabled with an explanation when the account is archived.
* **Activity** — the account's transactions, newest first, with the same row design as the activity
  screen and day headers; empty state when there are none.
* **Rename** — inline validation (required, ≤ 100 characters).
* **Archive** — reversible, with an undo snackbar; the account leaves the total immediately.
* **Delete** — confirmed with a warning that its transactions go too; refused with a clear message for
  accounts that took part in a transfer, because transfer pairs must stay intact.
* **Missing account** — an unknown or deleted id says so and closes instead of showing another account.

**Dialogs** — the add-account, add-transaction and transfer dialogs moved to `FinanceDialogs.kt`, shared
by home and account detail, so validation, defaults and the archived-account rule exist once. Archived
accounts are excluded from both pickers.

## Money and integrity rules

* All account arithmetic is exact minor units; the UI only formats.
* Currencies are never added together — the hero card, the home total and the section count all work
  per currency.
* `TRANSFER_OUT`/`TRANSFER_IN` are signed like any other transaction, so a transfer moves a balance
  correctly on both sides while leaving the currency total unchanged.
* Archiving changes *inclusion*, never history: no rows are deleted, and unarchiving restores the
  balance exactly.

## Files

**New**
- `domain/Accounts.kt` — `AccountBalance`, `AccountSummary`, `AccountOrdering`, `accountBalances`
- `AccountDetailActivity.kt` — account screen, rename/archive/delete, own activity list
- `FinanceDialogs.kt` — add account, add transaction, transfer (shared with home)
- `res/layout/item_account_row.xml`, `res/layout/dialog_rename_account.xml`
- `src/test/.../domain/AccountsTest.kt`

**Changed**
- `MainActivity.kt` — accounts section, real per-account balances, dialog plumbing removed (~180 lines),
  no more arbitrary-account navigation
- `SectionActivities.kt` — old `AccountDetailActivity` removed; the file is now only the `SectionActivity` base
- `res/layout/activity_main.xml` — accounts section + empty state
- `res/layout/activity_account_detail.xml` — rebuilt header, hero card, actions, activity list
- `data/RoomFinanceRepository.kt` — `observeAccountBalances`, `observeAccount`, `unarchiveAccount`;
  `observeAccountTransactions` now returns domain transactions; dashboard uses the shared math and
  excludes archived accounts
- `domain/Models.kt` — `DashboardState.accounts` / `archivedCount`
- `TransactionListAdapter.kt` — callbacks optional, so a list can be read-only
- `res/values/strings.xml` — account copy; dead `account_row` / `account_detail_title` strings removed

## Verification

No JDK/Gradle here, so correctness rests on static checks (all run, all clean):

- every `R.*` reference resolves (0 missing)
- `getString`/`getQuantityString` arity matches format specifiers (0 mismatches)
- ids lookups match their layouts (0 missing)
- brace/parenthesis balance clean in every touched file
- `tools/check_ui_tokens.py` passes
- `AccountsTest` covers: signed balance across all transaction types, zero-activity accounts, per-currency
  summary excluding archived accounts, ordering, unknown-account and currency-mismatch rejection, and
  overflow failing loudly

## Still out of scope

- **No editing past transactions from the account screen** — the activity screen owns that; this list is
  a read-only view of the account.
- **No account reordering or grouping** — sorted active-first, then by name.
- **No cross-currency consolidation** — deliberately; there is no exchange rate in the app.
- **No opening-balance editing** — changing it retroactively would rewrite history; archive and start a
  new account instead.
