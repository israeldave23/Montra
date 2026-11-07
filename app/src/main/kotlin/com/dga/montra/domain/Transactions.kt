package com.dga.montra.domain

import java.util.UUID

enum class TransactionType { EXPENSE, INCOME, REFUND, ADJUSTMENT }

data class Category(val id: String, val name: String, val isIncome: Boolean = false)

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val amount: Money,
    val type: TransactionType,
    val categoryId: String,
    val note: String = "",
    val timestampMillis: Long = System.currentTimeMillis()
) {
    init {
        require(amount.minorUnits >= 0) { "Transaction amount must be non-negative" }
        require(categoryId.isNotBlank()) { "Category is required" }
    }
}

fun Transaction.signedMinorUnits(): Long = when (type) {
    TransactionType.INCOME, TransactionType.REFUND -> amount.minorUnits
    TransactionType.EXPENSE -> -amount.minorUnits
    TransactionType.ADJUSTMENT -> amount.minorUnits
}
