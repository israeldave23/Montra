package com.dga.montra.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
}
