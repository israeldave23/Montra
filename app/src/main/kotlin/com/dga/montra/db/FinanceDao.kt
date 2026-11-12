package com.dga.montra.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceDao {
    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    fun observeAccounts(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    suspend fun accounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun account(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccount(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccounts(accounts: List<AccountEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransactions(transactions: List<TransactionEntity>)

    @RoomTransaction
    suspend fun importLegacy(accounts: List<AccountEntity>, transactions: List<TransactionEntity>) {
        insertAccounts(accounts)
        if (transactions.isNotEmpty()) insertTransactions(transactions)
    }

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY timestampMillis DESC")
    fun observeTransactions(accountId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestampMillis DESC")
    suspend fun transactions(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY timestampMillis DESC")
    fun observeAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE note LIKE '%' || :query || '%' OR categoryId LIKE '%' || :query || '%' ORDER BY timestampMillis DESC")
    suspend fun searchTransactions(query: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("UPDATE transactions SET categoryId = :categoryId, note = :note, type = :type, timestampMillis = :ts WHERE id = :id")
    suspend fun updateTransaction(id: String, categoryId: String, note: String, type: String, ts: Long): Int

    @Query("SELECT * FROM transactions WHERE categoryId = :categoryId ORDER BY timestampMillis DESC")
    suspend fun transactionsForCategory(categoryId: String): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String): Int

    @Query("UPDATE accounts SET isArchived = :archived WHERE id = :id")
    suspend fun setAccountArchived(id: String, archived: Boolean): Int

    @Query("SELECT * FROM transactions WHERE timestampMillis >= :startMillis AND timestampMillis < :endMillis ORDER BY timestampMillis DESC")
    fun observeTransactionsBetween(startMillis: Long, endMillis: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM budgets WHERE month = :month ORDER BY categoryId")
    suspend fun budgets(month: String): List<BudgetEntity>

    @Query("SELECT * FROM budgets WHERE month = :month ORDER BY categoryId")
    fun observeBudgets(month: String): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    suspend fun budget(id: String): BudgetEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBudget(budget: BudgetEntity)

    @Query("UPDATE budgets SET limitMinorUnits = :limitMinorUnits, currencyCode = :currencyCode WHERE id = :id")
    suspend fun updateBudget(id: String, limitMinorUnits: Long, currencyCode: String): Int

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudget(id: String): Int

    @Query("SELECT * FROM goals ORDER BY name COLLATE NOCASE")
    suspend fun goals(): List<GoalEntity>

    @Query("SELECT * FROM goals ORDER BY name COLLATE NOCASE")
    fun observeGoals(): Flow<List<GoalEntity>>

    @Query("UPDATE goals SET name = :name, targetMinorUnits = :targetMinorUnits, targetDateMillis = :targetDateMillis WHERE id = :id")
    suspend fun updateGoal(id: String, name: String, targetMinorUnits: Long, targetDateMillis: Long?): Int

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoal(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGoal(goal: GoalEntity)

    @Query("SELECT * FROM goals WHERE id = :id LIMIT 1")
    suspend fun goal(id: String): GoalEntity?

    @Query("UPDATE goals SET savedMinorUnits = :saved WHERE id = :id AND savedMinorUnits = :previous")
    suspend fun updateGoalFunds(id: String, previous: Long, saved: Long): Int

    @Query("UPDATE accounts SET name = :name WHERE id = :id")
    suspend fun renameAccount(id: String, name: String): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :id AND type IN ('TRANSFER_IN', 'TRANSFER_OUT')")
    suspend fun transferCount(id: String): Int

    @RoomTransaction
    suspend fun deleteAccountSafely(id: String) {
        require(transferCount(id) == 0) { "Account contains transfers" }
        val account = account(id) ?: error("Account not found")
        deleteAccount(account)
    }

    @RoomTransaction
    suspend fun transfer(from: TransactionEntity, to: TransactionEntity) {
        require(from.type == "TRANSFER_OUT" && to.type == "TRANSFER_IN")
        require(from.id != to.id)
        require(from.minorUnits == to.minorUnits && from.currencyCode == to.currencyCode)
        require(from.accountId != to.accountId)
        insertTransaction(from)
        insertTransaction(to)
    }
}
