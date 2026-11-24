package com.dga.montra.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import com.dga.montra.db.AccountEntity
import com.dga.montra.db.FinanceDao
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.db.TransactionEntity
import com.dga.montra.db.MIGRATION_1_2
import com.dga.montra.db.MIGRATION_2_3
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.GoalEntity
import com.dga.montra.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map

class RoomFinanceRepository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        FinanceDatabase::class.java,
        "lumen_finance.db"
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    private val dao: FinanceDao = db.financeDao()

    private val migrationMutex = Mutex()

    private suspend fun migrateLegacyIfNeeded() = migrationMutex.withLock {
        if (context.getSharedPreferences("lumen_migration", Context.MODE_PRIVATE)
                .getBoolean("room_v1_done", false)) return@withLock
        if (dao.accounts().isEmpty()) {
            val legacy = AccountRepository(context)
            val legacyAccounts = legacy.all()
            val legacyTxs = legacy.allTransactions()
            val accountIds = legacyAccounts.mapTo(hashSetOf()) { it.id }
            require(legacyTxs.all { it.accountId in accountIds }) { "Legacy transaction references missing account" }
            dao.importLegacy(legacyAccounts.map { it.toEntity() }, legacyTxs.map { it.toEntity() })
        }
        check(context.getSharedPreferences("lumen_migration", Context.MODE_PRIVATE)
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
        require(tx.type != TransactionType.TRANSFER_IN && tx.type != TransactionType.TRANSFER_OUT) { "Use transfer to create both entries" }
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
        require(type != TransactionType.TRANSFER_IN && type != TransactionType.TRANSFER_OUT)
        require(dao.updateTransaction(id, categoryId, note, type.name, ts) == 1) { "Transaction not found or is part of a transfer" }
    }

    suspend fun deleteTransaction(id: String) = withContext(Dispatchers.IO) {
        require(dao.deleteTransaction(id) == 1) { "Transaction not found or is part of a transfer" }
    }

    suspend fun renameAccount(id: String, name: String) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.trim().length <= 100)
        require(dao.renameAccount(id, name.trim()) == 1)
    }

    suspend fun deleteAccount(id: String) = withContext(Dispatchers.IO) { dao.deleteAccountSafely(id) }

    suspend fun backupTo(uri: Uri) = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        FinanceBackup(context, db).exportTo(uri)
    }

    suspend fun restoreFrom(uri: Uri) = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        FinanceBackup(context, db).importFrom(uri)
    }

    suspend fun createBudget(categoryId: String, limit: Money) = withContext(Dispatchers.IO) {
        require(limit.currencyCode in Money.currencies && limit.minorUnits > 0)
        require(TransactionCategories.expense.any { it.id == categoryId })
        dao.insertBudget(BudgetEntity(java.util.UUID.randomUUID().toString(), java.time.YearMonth.now().toString(), categoryId, limit.minorUnits, limit.currencyCode))
    }

    suspend fun budgets(): List<BudgetEntity> = withContext(Dispatchers.IO) {
        dao.budgets(java.time.YearMonth.now().toString())
    }
    suspend fun spentForCategoryMonth(categoryId: String, currencyCode: String, month: String): Long = withContext(Dispatchers.IO) {
        val ym = java.time.YearMonth.parse(month)
        val start = ym.atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = ym.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        dao.spentForCategoryMonth(categoryId, currencyCode, start, end)
    }

    suspend fun goals(): List<GoalEntity> = withContext(Dispatchers.IO) { dao.goals() }

    suspend fun createGoal(name: String, target: Money, saved: Money) = withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name.trim().length <= 100)
        require(target.currencyCode in Money.currencies && saved.currencyCode == target.currencyCode)
        require(target.minorUnits > 0 && saved.minorUnits >= 0)
        dao.insertGoal(GoalEntity(java.util.UUID.randomUUID().toString(), name.trim(), target.minorUnits, saved.minorUnits, target.currencyCode))
    }

    suspend fun addGoalFunds(id: String, amount: Money) = withContext(Dispatchers.IO) {
        require(amount.minorUnits > 0)
        val goal = dao.goal(id) ?: error("Goal not found")
        require(goal.currencyCode == amount.currencyCode)
        val saved = Math.addExact(goal.savedMinorUnits, amount.minorUnits)
        check(dao.updateGoalFunds(id, goal.savedMinorUnits, saved) == 1) { "Goal changed; try again" }
    }

    suspend fun archiveAccount(id: String) = withContext(Dispatchers.IO) {
        require(dao.setAccountArchived(id, true) == 1) { "Account not found" }
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

    fun observeTransactions(): Flow<List<Transaction>> = dao.observeAllTransactions().map { items -> items.map { it.toDomain() } }
    fun observeAccountTransactions(accountId: String): Flow<List<TransactionEntity>> = dao.observeTransactions(accountId)
    suspend fun transactions(): List<TransactionEntity> = withContext(Dispatchers.IO) { dao.transactions() }

    suspend fun prepareForObservation() = withContext(Dispatchers.IO) { migrateLegacyIfNeeded() }

    fun observeDashboard(): Flow<DashboardState> = dao.observeLedger().map { ledger ->
        val totals = mutableMapOf<String, Long>()
        val transactions = ledger.flatMap { row ->
            val account = row.account
            val code = account.currencyCode
            totals[code] = Math.addExact(totals[code] ?: 0L, account.openingBalanceMinorUnits)
            row.transactions.map { entity ->
                val tx = entity.toDomain()
                require(tx.amount.currencyCode == code) { "Transaction currency mismatch" }
                totals[code] = Math.addExact(totals.getValue(code), tx.signedMinorUnits())
                tx
            }
        }.sortedWith(compareByDescending<Transaction> { it.timestampMillis }.thenBy { it.id })
        DashboardState(totals, ledger.size, transactions.take(5),
            FinancialCalculations.monthlySummary(transactions, java.time.YearMonth.now(), java.time.ZoneId.systemDefault()))
    }.flowOn(Dispatchers.Default)

    companion object {
        @Volatile
        private var INSTANCE: RoomFinanceRepository? = null

        fun get(context: Context): RoomFinanceRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: RoomFinanceRepository(context.applicationContext).also { INSTANCE = it }
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
