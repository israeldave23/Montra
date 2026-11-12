package com.dga.montra.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE accounts ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id TEXT NOT NULL PRIMARY KEY, month TEXT NOT NULL, categoryId TEXT NOT NULL, limitMinorUnits INTEGER NOT NULL, currencyCode TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_month_categoryId ON budgets(month, categoryId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS goals (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, targetMinorUnits INTEGER NOT NULL, savedMinorUnits INTEGER NOT NULL, currencyCode TEXT NOT NULL)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE goals ADD COLUMN targetDateMillis INTEGER")
    }
}
