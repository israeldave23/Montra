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

    @Query("SELECT * FROM transactions WHERE note LIKE '%' || :query || '%' OR categoryId LIKE '%' || :query || '%' ORDER BY timestampMillis DESC")
    suspend fun searchTransactions(query: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransaction(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun transaction(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE categoryId = :categoryId ORDER BY timestampMillis DESC")
    suspend fun transactionsForCategory(categoryId: String): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: String): Int

    @Query("UPDATE accounts SET isArchived = :archived WHERE id = :id")
    suspend fun setAccountArchived(id: String, archived: Boolean): Int

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
