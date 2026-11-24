package com.dga.montra.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.dga.montra.db.AccountEntity
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.db.GoalEntity
import com.dga.montra.db.MIGRATION_1_2
import com.dga.montra.db.MIGRATION_2_3
import com.dga.montra.db.TransactionEntity
import com.dga.montra.domain.Money
import java.time.YearMonth
import java.util.UUID

/** Copy consistent snapshots without replacing the database file held open by Room. */
internal class FinanceBackup(private val context: Context, private val live: FinanceDatabase) {
    suspend fun exportTo(uri: Uri) = withTemporaryDatabase { name ->
        val snapshot = live.readSnapshot()
        open(name).useDatabase { it.replaceWith(snapshot) }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open backup destination")
        output.use { destination -> context.getDatabasePath(name).inputStream().use { it.copyTo(destination) } }
    }

    suspend fun importFrom(uri: Uri) = withTemporaryDatabase { name ->
        val staged = context.getDatabasePath(name)
        check(staged.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val input = context.contentResolver.openInputStream(uri) ?: error("Could not open backup source")
        input.use { source -> staged.outputStream().use { source.copyTo(it) } }
        SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY).use { candidate ->
            require(candidate.version > 0 && candidate.isDatabaseIntegrityOk) { "Invalid backup database" }
        }
        val snapshot = open(name).useDatabase { it.readSnapshot() }
        snapshot.validate()
        live.replaceWith(snapshot)
    }

    private fun open(name: String): FinanceDatabase = Room.databaseBuilder(context, FinanceDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()

    private suspend fun <T> withTemporaryDatabase(block: suspend (String) -> T): T {
        val name = "montra-backup-${UUID.randomUUID()}.db"
        try {
            return block(name)
        } finally {
            context.deleteDatabase(name)
        }
    }
}

internal inline fun <T> FinanceDatabase.useDatabase(block: (FinanceDatabase) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}

private data class Snapshot(
    val accounts: List<AccountEntity>,
    val transactions: List<TransactionEntity>,
    val budgets: List<BudgetEntity>,
    val goals: List<GoalEntity>
) {
    fun validate() {
        val accountsById = accounts.associateBy { it.id }
        require(accounts.all { it.currencyCode in Money.currencies })
        transactions.forEach {
            require(accountsById[it.accountId]?.currencyCode == it.currencyCode)
            it.toDomain() // Check transaction type, amount and category invariants.
        }
        budgets.forEach {
            require(it.currencyCode in Money.currencies && it.limitMinorUnits > 0)
            YearMonth.parse(it.month)
        }
        require(goals.all { it.currencyCode in Money.currencies && it.targetMinorUnits > 0 && it.savedMinorUnits >= 0 })
    }
}

private suspend fun FinanceDatabase.readSnapshot() = withTransaction {
    val dao = financeDao()
    Snapshot(dao.accounts(), dao.transactions(), dao.allBudgets(), dao.goals())
}

private suspend fun FinanceDatabase.replaceWith(snapshot: Snapshot) = withTransaction {
    val dao = financeDao()
    dao.clearAccounts() // Deletes dependent transactions by foreign-key cascade.
    dao.clearBudgets()
    dao.clearGoals()
    dao.insertAccounts(snapshot.accounts)
    dao.insertTransactions(snapshot.transactions)
    snapshot.budgets.forEach { dao.insertBudget(it) }
    snapshot.goals.forEach { dao.insertGoal(it) }
}
