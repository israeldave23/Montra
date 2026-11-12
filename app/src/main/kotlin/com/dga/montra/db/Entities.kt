package com.dga.montra.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts", indices = [Index("name")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currencyCode: String,
    val openingBalanceMinorUnits: Long,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "transactions",
    foreignKeys = [ForeignKey(
        entity = AccountEntity::class,
        parentColumns = ["id"],
        childColumns = ["accountId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("accountId"),
        Index("timestampMillis"),
        Index("categoryId")
    ]
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val minorUnits: Long,
    val currencyCode: String,
    val type: String,
    val categoryId: String,
    val note: String,
    val timestampMillis: Long
)

@Entity(tableName = "budgets", indices = [Index(value = ["month", "categoryId"], unique = true)])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val month: String,
    val categoryId: String,
    val limitMinorUnits: Long,
    val currencyCode: String
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val targetMinorUnits: Long,
    val savedMinorUnits: Long,
    val currencyCode: String,
    val targetDateMillis: Long? = null
)
