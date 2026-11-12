package com.dga.montra.data

import android.content.Context
import androidx.room.Room
import com.dga.montra.db.AccountEntity
import com.dga.montra.db.FinanceDao
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.db.TransactionEntity
import com.dga.montra.db.MIGRATION_1_2
import com.dga.montra.db.MIGRATION_2_3
import com.dga.montra.db.MIGRATION_3_4
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.GoalEntity
import com.dga.montra.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class RoomFinanceRepository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        FinanceDatabase::class.java,
        Storage.DATABASE
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()

    private val dao: FinanceDao = db.financeDao()

    private suspend fun migrateLegacyIfNeeded() {
        if (context.getSharedPreferences(Storage.PREFS_MIGRATION, Context.MODE_PRIVATE)
                .getBoolean("room_v1_done", false)) return
        if (dao.accounts().isEmpty()) {
            val legacy = AccountRepository(context)
            val legacyAccounts = legacy.all()
            val legacyTxs = legacy.allTransactions()
            val accountIds = legacyAccounts.mapTo(hashSetOf()) { it.id }
            require(legacyTxs.all { it.accountId in accountIds }) { "Legacy transaction references missing account" }
            dao.importLegacy(legacyAccounts.map { it.toEntity() }, legacyTxs.map { it.toEntity() })
        }
        check(context.getSharedPreferences(Storage.PREFS_MIGRATION, Context.MODE_PRIVATE)
            .edit().putBoolean("room_v1_done", true).commit()) { "Could not persist migration marker" }
    }

    suspend fun allAccounts(): List<Account> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        dao.accounts().map { it.toDomain() }
    }

    suspend fun addAccount(account: Account) = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        dao.insertAccount(account.toEntity())
    }

    suspend fun addTransaction(tx: Transaction) = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        val account = dao.account(tx.accountId) ?: throw IllegalArgumentException("Unknown account")
        require(account.currencyCode == tx.amount.currencyCode) { "Account currency mismatch" }
        dao.insertTransaction(tx.toEntity())
    }

    suspend fun transfer(fromAccountId: String, toAccountId: String, amount: Money, note: String = "") = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        require(fromAccountId != toAccountId) { "Cannot transfer to same account" }
        require(amount.minorUnits > 0L) { "Transfer amount must be positive" }
        val from = dao.account(fromAccountId) ?: throw IllegalArgumentException("Source account missing")
        val to = dao.account(toAccountId) ?: throw IllegalArgumentException("Target account missing")
        require(from.currencyCode == to.currencyCode) { "Cross-currency transfer requires exchange rate" }
        require(from.currencyCode == amount.currencyCode) { "Amount currency mismatch" }
        val now = System.currentTimeMillis()
        val outTx = TransactionEntity(
            id = java.util.UUID.randomUUID().toString(),
            accountId = fromAccountId,
            minorUnits = amount.minorUnits,
            currencyCode = amount.currencyCode,
            type = TransactionType.TRANSFER_OUT.name,
            categoryId = "transfer",
            note = note,
            timestampMillis = now
        )
        val inTx = TransactionEntity(
            id = java.util.UUID.randomUUID().toString(),
            accountId = toAccountId,
            minorUnits = amount.minorUnits,
            currencyCode = amount.currencyCode,
            type = TransactionType.TRANSFER_IN.name,
            categoryId = "transfer",
            note = note,
            timestampMillis = now
        )
        dao.transfer(outTx, inTx)
    }

    suspend fun updateTransaction(id: String, categoryId: String, note: String, type: TransactionType, ts: Long) = withContext(Dispatchers.IO) {
        require(dao.updateTransaction(id, categoryId, note, type.name, ts) == 1) { "Transaction not found" }
    }

    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.IO) {
        require(dao.deleteTransaction(id) == 1) { "Transaction not found" }
    }

    suspend fun renameAccount(id: String, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.trim().length <= 100)
        require(dao.renameAccount(id, name.trim()) == 1)
    }

    suspend fun deleteAccount(id: String) = withContext(Dispatchers.IO) { dao.deleteAccountSafely(id) }

    /**
     * Budgets for one month, each paired with the spending actually recorded in its category and
     * currency. Spend comes from a single transaction range query, not one query per budget, and
     * currencies are never combined.
     */
    fun observeBudgets(
        month: YearMonth = YearMonth.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Flow<List<BudgetProgress>> {
        val start = monthStartMillis(month, zone)
        val end = monthStartMillis(month.plusMonths(1), zone)
        return combine(
            dao.observeBudgets(month.toString()),
            dao.observeTransactionsBetween(start, end)
        ) { budgetEntities, transactions ->
            val spent = mutableMapOf<Pair<String, String>, Long>()
            transactions.forEach { entity ->
                if (entity.type != TransactionType.EXPENSE.name) return@forEach
                val key = entity.categoryId to entity.currencyCode
                spent[key] = Math.addExact(spent[key] ?: 0L, entity.minorUnits)
            }
            budgetEntities.map { it.toDomain() }.map { budget ->
                BudgetProgress(budget, spent[budget.categoryId to budget.currencyCode] ?: 0L)
            }
        }.flowOn(Dispatchers.Default)
    }

    suspend fun createBudget(month: YearMonth, categoryId: String, limit: Money) = withContext(Dispatchers.IO) {
        requireBudgetInput(categoryId, limit)
        if (dao.budgets(month.toString()).any { it.categoryId == categoryId }) {
            throw IllegalStateException("A budget for this category already exists this month")
        }
        dao.insertBudget(
            BudgetEntity(
                id = java.util.UUID.randomUUID().toString(),
                month = month.toString(),
                categoryId = categoryId,
                limitMinorUnits = limit.minorUnits,
                currencyCode = limit.currencyCode
            )
        )
    }

    suspend fun updateBudget(id: String, limit: Money) = withContext(Dispatchers.IO) {
        require(limit.currencyCode in Money.currencies) { "Unsupported currency" }
        require(limit.minorUnits > 0L) { "Budget limit must be positive" }
        require(dao.updateBudget(id, limit.minorUnits, limit.currencyCode) == 1) { "Budget not found" }
    }

    suspend fun deleteBudget(id: String) = withContext(Dispatchers.IO) {
        require(dao.deleteBudget(id) == 1) { "Budget not found" }
    }

    /** Re-inserts a deleted budget so the undo action is lossless. */
    suspend fun restoreBudget(budget: Budget) = withContext(Dispatchers.IO) {
        dao.insertBudget(budget.toEntity())
    }

    private fun requireBudgetInput(categoryId: String, limit: Money) {
        require(limit.currencyCode in Money.currencies) { "Unsupported currency" }
        require(limit.minorUnits > 0L) { "Budget limit must be positive" }
        require(TransactionCategories.expense.any { it.id == categoryId }) { "Unknown category" }
    }

    /** Reactive monthly insight; includes the previous month so the comparison is live too. */
    fun observeInsight(
        month: YearMonth = YearMonth.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Flow<MonthlyInsight> {
        val start = monthStartMillis(month.minusMonths(1), zone)
        val end = monthStartMillis(month.plusMonths(1), zone)
        return dao.observeTransactionsBetween(start, end).map { entities ->
            InsightCalculator.compute(
                month = month,
                zone = zone,
                transactions = entities.map { it.toDomain() },
                today = LocalDate.now(zone)
            )
        }.flowOn(Dispatchers.Default)
    }

    private fun monthStartMillis(month: YearMonth, zone: ZoneId): Long =
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    suspend fun goals(): List<Goal> = withContext(Dispatchers.IO) {
        dao.goals().map { it.toDomain() }
    }

    /** Goals update reactively, so the screen never needs a manual refresh. */
    fun observeGoals(): Flow<List<Goal>> = dao.observeGoals().map { entities ->
        entities.map { it.toDomain() }
    }.flowOn(Dispatchers.Default)

    suspend fun createGoal(
        name: String,
        target: Money,
        saved: Money = Money(0L, target.currencyCode),
        targetDateMillis: Long? = null
    ) = withContext(Dispatchers.IO) {
        require(target.currencyCode in Money.currencies) { "Unsupported currency" }
        require(saved.currencyCode == target.currencyCode) { "Goal amounts must share a currency" }
        require(target.minorUnits > 0L) { "Target must be positive" }
        require(saved.minorUnits >= 0L) { "Saved amount must be non-negative" }
        val trimmed = validGoalName(name)
        dao.insertGoal(
            GoalEntity(
                id = java.util.UUID.randomUUID().toString(),
                name = trimmed,
                targetMinorUnits = target.minorUnits,
                savedMinorUnits = saved.minorUnits,
                currencyCode = target.currencyCode,
                targetDateMillis = targetDateMillis
            )
        )
    }

    suspend fun updateGoal(id: String, name: String, target: Money, targetDateMillis: Long?) = withContext(Dispatchers.IO) {
        val existing = dao.goal(id) ?: throw IllegalArgumentException("Goal not found")
        require(existing.currencyCode == target.currencyCode) { "Goal currency cannot change" }
        require(target.minorUnits > 0L) { "Target must be positive" }
        val trimmed = validGoalName(name)
        require(dao.updateGoal(id, trimmed, target.minorUnits, targetDateMillis) == 1) { "Goal not found" }
    }

    /** Re-inserts a deleted goal so the undo action is lossless. */
    suspend fun restoreGoal(goal: Goal) = withContext(Dispatchers.IO) {
        dao.insertGoal(goal.toEntity())
    }

    suspend fun deleteGoal(id: String) = withContext(Dispatchers.IO) {
        require(dao.deleteGoal(id) == 1) { "Goal not found" }
    }

    suspend fun addGoalFunds(id: String, amount: Money) = withContext(Dispatchers.IO) {
        require(amount.minorUnits > 0L) { "Amount must be positive" }
        adjustGoalFunds(id, amount, credit = true)
    }

    suspend fun withdrawGoalFunds(id: String, amount: Money) = withContext(Dispatchers.IO) {
        require(amount.minorUnits > 0L) { "Amount must be positive" }
        adjustGoalFunds(id, amount, credit = false)
    }

    /**
     * Optimistic read-modify-write: the update only applies while savedMinorUnits is unchanged,
     * and is retried when another operation wins the race.
     */
    private suspend fun adjustGoalFunds(id: String, amount: Money, credit: Boolean) {
        val currency = dao.goal(id)?.currencyCode ?: throw IllegalArgumentException("Goal not found")
        require(currency == amount.currencyCode) { "Amount currency mismatch" }
        repeat(FUNDS_ATTEMPTS) {
            val current = dao.goal(id) ?: throw IllegalArgumentException("Goal not found")
            val next = Math.addExact(
                current.savedMinorUnits,
                if (credit) amount.minorUnits else -amount.minorUnits
            )
            require(next >= 0L) { "Not enough saved" }
            if (dao.updateGoalFunds(id, current.savedMinorUnits, next) == 1) return
        }
        throw IllegalStateException("Goal changed; try again")
    }

    private fun validGoalName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= GOAL_NAME_LIMIT) { "Goal name is required" }
        return trimmed
    }

    suspend fun archiveAccount(id: String) = withContext(Dispatchers.IO) {
        require(dao.setAccountArchived(id, true) == 1) { "Account not found" }
    }

    /** Archiving is the reversible action, so every archive can be undone. */
    suspend fun unarchiveAccount(id: String) = withContext(Dispatchers.IO) {
        require(dao.setAccountArchived(id, false) == 1) { "Account not found" }
    }

    suspend fun allTransactions(): List<Transaction> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        dao.transactions().map { it.toDomain() }
    }

    suspend fun searchTransactions(query: String): List<Transaction> = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        if (query.isBlank()) dao.transactions().map { it.toDomain() }
        else dao.searchTransactions(query.trim()).map { it.toDomain() }
    }

    fun observeTransactions(): Flow<List<Transaction>> =
        dao.observeAllTransactions().map { items -> items.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** One account's transactions, newest first, ready to render. */
    fun observeAccountTransactions(accountId: String): Flow<List<Transaction>> =
        dao.observeTransactions(accountId).map { items -> items.map { it.toDomain() } }
            .flowOn(Dispatchers.Default)

    /** Every account with its live balance, so a list row and the dashboard can never disagree. */
    fun observeAccountBalances(): Flow<List<AccountBalance>> = combine(
        dao.observeAccounts(), dao.observeAllTransactions()
    ) { accountEntities, transactionEntities ->
        AccountOrdering.sorted(
            accountBalances(
                accountEntities.map { it.toDomain() },
                transactionEntities.map { it.toDomain() }
            )
        )
    }.flowOn(Dispatchers.Default)

    fun observeAccount(accountId: String): Flow<AccountBalance?> =
        observeAccountBalances().map { items -> items.firstOrNull { it.account.id == accountId } }
    suspend fun transactions(): List<TransactionEntity> = withContext(Dispatchers.IO) { dao.transactions() }

    suspend fun prepareForObservation() = withContext(Dispatchers.IO) { migrateLegacyIfNeeded() }

    /**
     * Everything the dashboard draws, from one pass over accounts and transactions. Archived
     * accounts stay in [DashboardState.accounts] but are excluded from the totals and the count.
     */
    fun observeDashboard(): Flow<DashboardState> = combine(
        dao.observeAccounts(), dao.observeAllTransactions()
    ) { accountEntities, transactionEntities ->
        val accounts = accountEntities.map { it.toDomain() }
        val transactions = transactionEntities.map { it.toDomain() }
        dashboardState(accounts, transactions)
    }.flowOn(Dispatchers.Default)

    private fun dashboardState(accounts: List<Account>, transactions: List<Transaction>): DashboardState {
        val balances = accountBalances(accounts, transactions)
        val summary = AccountSummary.of(balances)
        return DashboardState(
            balances = summary.totalsByCurrency,
            accountCount = summary.activeCount,
            recentTransactions = transactions.take(5),
            monthlyTotals = FinancialCalculations.monthlySummary(
                transactions,
                YearMonth.now(),
                ZoneId.systemDefault()
            ),
            accounts = AccountOrdering.sorted(balances),
            archivedCount = summary.archivedCount
        )
    }

    suspend fun dashboard(): DashboardState = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        val accounts = dao.accounts().map { it.toDomain() }
        val transactions = dao.transactions().map { it.toDomain() }
        dashboardState(accounts, transactions)
    }

    companion object {
        private const val FUNDS_ATTEMPTS = 3
        private const val GOAL_NAME_LIMIT = 100

        @Volatile
        private var INSTANCE: RoomFinanceRepository? = null

        fun get(context: Context): RoomFinanceRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RoomFinanceRepository(context).also { INSTANCE = it }
            }
    }
}

private fun AccountEntity.toDomain() = Account(
    id = id,
    name = name,
    openingBalance = Money(openingBalanceMinorUnits, currencyCode),
    isArchived = isArchived
)

private fun Account.toEntity() = AccountEntity(
    id = id,
    name = name,
    currencyCode = openingBalance.currencyCode,
    openingBalanceMinorUnits = openingBalance.minorUnits,
    isArchived = isArchived
)

internal fun TransactionEntity.toDomain() = Transaction(
    id = id,
    accountId = accountId,
    amount = Money(minorUnits, currencyCode),
    type = TransactionType.valueOf(type),
    categoryId = categoryId,
    note = note,
    timestampMillis = timestampMillis
)

internal fun Transaction.toEntity() = TransactionEntity(
    id = id,
    accountId = accountId,
    minorUnits = amount.minorUnits,
    currencyCode = amount.currencyCode,
    type = type.name,
    categoryId = categoryId,
    note = note,
    timestampMillis = timestampMillis
)

internal fun BudgetEntity.toDomain() = Budget(
    id = id,
    month = runCatching { YearMonth.parse(month) }
        .getOrElse { throw IllegalStateException("Invalid budget month: $month") },
    categoryId = categoryId,
    limit = Money(limitMinorUnits, currencyCode)
)

private fun Budget.toEntity() = BudgetEntity(
    id = id,
    month = month.toString(),
    categoryId = categoryId,
    limitMinorUnits = limit.minorUnits,
    currencyCode = limit.currencyCode
)

internal fun GoalEntity.toDomain() = Goal(
    id = id,
    name = name,
    target = Money(targetMinorUnits, currencyCode),
    saved = Money(savedMinorUnits, currencyCode),
    targetDateMillis = targetDateMillis
)

private fun Goal.toEntity() = GoalEntity(
    id = id,
    name = name,
    targetMinorUnits = target.minorUnits,
    savedMinorUnits = saved.minorUnits,
    currencyCode = currencyCode,
    targetDateMillis = targetDateMillis
)
