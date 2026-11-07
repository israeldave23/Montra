# Development Progress

## Current phase
Phase 3 — Financial Core and Database (incomplete)

## Audit findings
- Project uses one `app` module with Kotlin stdlib only.
- UI uses native Android Views, not Jetpack Compose.
- No Gradle Kotlin DSL/version catalog, Hilt, Room, Navigation Compose, ViewModels, StateFlow, WorkManager, or CI configuration exists.
- Phase 1 and Phase 2 requirements are not complete despite starter dashboard UI.
- Persistence uses encrypted SharedPreferences JSON, not Room.
- Accounts and basic expense/income/refund creation exist.
- No edit/delete/search/filter/splits/transfers/tags/attachments or account management.
- Android backup is disabled.

## Completed features
- Exact money parsing using integer minor units.
- Currency-specific fraction digits for PHP, USD, CAD, EUR, GBP, JPY.
- Basic account creation.
- Basic expense, income, and refund creation.
- AES-GCM local payload encryption with Android Keystore.
- Dashboard totals separated by currency.
- Overflow checks in dashboard aggregation.

## Pending Phase 3
- Room schema, DAO, repositories, migrations, schema export, and migration tests.
- Transaction editing/deletion/details/search/filter/sort/splits/transfers/tags/attachments.
- Account editing/archiving/reconciliation/history.
- StateFlow/ViewModel UI integration.
- Categories and calculation use cases.
- Compose migration and navigation architecture.

## Validation
- `test:app`: passed, but reported `No tests found`.
- Project diagnostics: no compiler errors; seven style warnings.
- `assemble:app:debug`: succeeded after adding Room dependency and database classes.
- Room KSP generation, Kotlin compilation, DEX processing, packaging, and signing succeeded.
- Room SQL query verification disabled on device (SQLite connection unavailable during KSP).

## Known issues
- Current transaction/account repository rewrites whole encrypted JSON documents.
- Account and transaction writes use separate preference files; multi-record operations cannot be atomic.
- No transfer support.
- No executable automated test framework configured.
- Build environment exposes `module.toml`, not requested Gradle files or version catalog.

## Architectural decisions
- Never combine currencies without explicit conversion.
- Use `Long` minor units and checked arithmetic.
- Keep Android automatic backup disabled until encrypted backup/restore exists.
- Do not claim Phase 3 complete until Room, tests, and successful build exist.

## Next steps
1. Establish compatible Room and test dependencies.
2. Replace JSON persistence with versioned Room schema and safe one-time import.
3. Add account/transaction DAOs and financial calculation tests.
4. Implement atomic transfers.
5. Connect dashboard/activity UI to observable database state.
