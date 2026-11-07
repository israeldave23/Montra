package com.dga.montra.domain

import java.util.UUID

<<<<<<< HEAD
<<<<<<< HEAD
enum class TransactionType { EXPENSE, INCOME, REFUND, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN }

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
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.TRANSFER_IN -> amount.minorUnits
    TransactionType.EXPENSE, TransactionType.TRANSFER_OUT -> -amount.minorUnits
=======
enum class TransactionType { EXPENSE, INCOME, REFUND, ADJUSTMENT }
=======
enum class TransactionType { EXPENSE, INCOME, REFUND, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN }
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git

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
<<<<<<< HEAD
    TransactionType.INCOME, TransactionType.REFUND -> amount.minorUnits
    TransactionType.EXPENSE -> -amount.minorUnits
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
=======
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.TRANSFER_IN -> amount.minorUnits
    TransactionType.EXPENSE, TransactionType.TRANSFER_OUT -> -amount.minorUnits
>>>>>>> branch 'main' of https://github.com/israeldave23/Montra.git
    TransactionType.ADJUSTMENT -> amount.minorUnits
}
