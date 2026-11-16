package com.dga.montra.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, TransactionEntity::class, BudgetEntity::class, GoalEntity::class],
    version = FinanceDatabase.VERSION,
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao

    companion object {
        /**
         * The schema version, named so a backup manifest can record it and a restore can refuse a
         * file written by a newer build. Room reads the annotation value, so this stays a constant.
         */
        const val VERSION = 4
    }
}
