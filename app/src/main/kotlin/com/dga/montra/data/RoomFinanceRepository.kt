package com.dga.montra.data

import android.content.Context
import androidx.room.Room
import com.dga.montra.db.AccountEntity
import com.dga.montra.db.FinanceDao
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.db.TransactionEntity
import com.dga.montra.db.MIGRATION_1_2
import com.dga.montra.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomFinanceRepository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        FinanceDatabase::class.java,
        "lumen_finance.db"
    ).addMigrations(MIGRATION_1_2).build()

    private val dao: FinanceDao = db.financeDao()

    private suspend fun migrateLegacyIfNeeded() {
        if (context.getSharedPreferences("lumen_migration", Context.MODE_PRIVATE)
                .getBoolean("room_v1_done", false)) return
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

    suspend fun dashboard(): DashboardState = withContext(Dispatchers.IO) {
        migrateLegacyIfNeeded()
        val accounts = dao.accounts()
        val totals = mutableMapOf<String, Long>()
        accounts.forEach { account ->
            val code = account.currencyCode
            totals[code] = Math.addExact(totals[code] ?: 0L, account.openingBalanceMinorUnits)
        }
        val accountById = accounts.associateBy { it.id }
        dao.transactions().forEach { tx ->
            val account = accountById[tx.accountId]
                ?: throw IllegalStateException("Transaction references missing account")
            require(account.currencyCode == tx.currencyCode) { "Transaction currency mismatch" }
            val signed = when (tx.type) {
                "INCOME", "REFUND", "TRANSFER_IN" -> tx.minorUnits
                "EXPENSE", "TRANSFER_OUT" -> -tx.minorUnits
                else -> tx.minorUnits
            }
            totals[tx.currencyCode] = Math.addExact(totals[tx.currencyCode] ?: 0L, signed)
        }
        DashboardState(totals, accounts.size)
    }

    companion object {
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

private fun TransactionEntity.toDomain() = Transaction(
    id = id,
    accountId = accountId,
    amount = Money(minorUnits, currencyCode),
    type = TransactionType.valueOf(type),
    categoryId = categoryId,
    note = note,
    timestampMillis = timestampMillis
)

private fun Transaction.toEntity() = TransactionEntity(
    id = id,
    accountId = accountId,
    minorUnits = amount.minorUnits,
    currencyCode = amount.currencyCode,
    type = type.name,
    categoryId = categoryId,
    note = note,
    timestampMillis = timestampMillis
)
