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
<<<<<<< HEAD
<<<<<<< HEAD
    val openingBalanceMinorUnits: Long,
    val isArchived: Boolean = false
=======
    val openingBalanceMinorUnits: Long
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
=======
    val openingBalanceMinorUnits: Long,
    val isArchived: Boolean = false
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
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
