package com.dga.montra.data

import android.net.Uri
import androidx.room.Room
import com.dga.montra.db.AccountEntity
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.FinanceDatabase
import com.dga.montra.db.GoalEntity
import com.dga.montra.db.TransactionEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FinanceBackupTest {
    @Test fun `backup restores all four tables and rejects an invalid file without data loss`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java).allowMainThreadQueries().build().useDatabase { db ->
            val dao = db.financeDao()
            val account = AccountEntity("cash", "Cash", "USD", 2_000)
            val expense = TransactionEntity("purchase", account.id, 100, "USD", "EXPENSE", "dining", "Lunch", 10)
            val budget = BudgetEntity("budget", "2026-11", "dining", 1_000, "USD")
            val goal = GoalEntity("goal", "Trip", 10_000, 3_000, "USD")
            dao.insertAccount(account)
            dao.insertTransaction(expense)
            dao.insertBudget(budget)
            dao.insertGoal(goal)
            val file = File.createTempFile("backup", ".db", context.cacheDir)
            try {
                val backup = FinanceBackup(context, db)
                val uri = Uri.fromFile(file)
                backup.exportTo(uri)
                dao.clearAccounts()
                dao.clearBudgets()
                dao.clearGoals()
                dao.insertAccount(account.copy(id = "replacement"))
                backup.importFrom(uri)
                assertEquals(listOf(account), dao.accounts())
                assertEquals(listOf(expense), dao.transactions())
                assertEquals(listOf(budget), dao.allBudgets())
                assertEquals(listOf(goal), dao.goals())
                file.writeText("invalid backup")
                assertTrue(runCatching { backup.importFrom(uri) }.isFailure)
                assertEquals(listOf(account), dao.accounts())
                assertEquals(listOf(expense), dao.transactions())
            } finally {
                file.delete()
            }
        }
    }

    @Test fun `individual transfer legs cannot be changed or deleted and month end includes the final milliseconds`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Room.inMemoryDatabaseBuilder(context, FinanceDatabase::class.java).allowMainThreadQueries().build().useDatabase { db ->
            val dao = db.financeDao()
            dao.insertAccounts(listOf(AccountEntity("a", "Cash", "USD", 0), AccountEntity("b", "Bank", "USD", 0)))
            val out = TransactionEntity("out", "a", 100, "USD", "TRANSFER_OUT", "transfer", "", 9_999)
            dao.transfer(out, out.copy(id = "in", accountId = "b", type = "TRANSFER_IN"))
            assertEquals(0, dao.deleteTransaction("out"))
            assertEquals(0, dao.updateTransaction("in", "dining", "", "EXPENSE", 9_999))
            dao.insertTransaction(out.copy(id = "expense", type = "EXPENSE", categoryId = "dining"))
            dao.insertTransaction(out.copy(id = "next", type = "EXPENSE", categoryId = "dining", timestampMillis = 10_000))
            assertEquals(100L, dao.spentForCategoryMonth("dining", "USD", 0, 10_000))
        }
    }
}
