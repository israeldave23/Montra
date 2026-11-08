# Development Progress

## Current phase
Phase 3 — Financial Core and Database (incomplete; build gate blocked)

## Completed in current branch
- Room entities, DAO, database version 2 and migration 1→2 for archived-account flag.
- Room repository with encrypted legacy JSON import, account create/update/archive/restore, transactions, same-currency atomic transfer, balances, search and paged account transaction query.
- Transaction activity search, detail display and confirmed deletion; transfer pair deletion prevented to preserve ledger.
- Account management UI: edit, archive, restore.
- Dashboard and transaction screen visual improvements; light/dark Lumen colors.
- Financial calculations use integer minor units and checked sums.

## Validation status
- Project compiler diagnostics: zero errors.
- `assemble:app:debug`: repeated CodeAssist background-process timeout after 120 seconds. Latest APK is stale and cannot validate current sources.
- `test:app`: timed out previously; no test execution confirmed.
- Room KSP cannot verify SQL against SQLite in this environment.
- Layout preview timed out.

## Important limitations and risks
- App remains Android Views, not Compose; no MVVM/StateFlow UI collection.
- Room database is not encrypted at rest. Legacy prefs encryption does not protect newly persisted Room data.
- Migration not instrumented/tested; schema export disabled.
- Whole-history dashboard calculation may be slow for large datasets.
- Transfer pair editing/reversal not implemented; pair deletion is blocked.
- No verified release build or production readiness.

## Next resume actions
1. Restore working CodeAssist build task and run assembleDebug + test task.
2. Add Room migration/integration tests and schema export.
3. Decide/implement supported at-rest Room encryption before storing real financial records.
4. Add account transaction screen and category management.
5. Complete Phase 3 acceptance criteria before starting Phase 4 budgets.
