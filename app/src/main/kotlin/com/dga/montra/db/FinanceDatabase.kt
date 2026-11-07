package com.dga.montra.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AccountEntity::class, TransactionEntity::class],
<<<<<<< HEAD
<<<<<<< HEAD
    version = 2,
=======
    version = 1,
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
=======
    version = 2,
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
    exportSchema = false
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
}
