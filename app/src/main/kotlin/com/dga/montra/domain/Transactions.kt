package com.dga.montra.domain

import java.util.UUID

enum class TransactionType { EXPENSE, INCOME, REFUND, ADJUSTMENT, TRANSFER_OUT, TRANSFER_IN }

/** How the transaction was settled; stored locally, never uploaded. */
enum class PaymentMethod {
    CASH,
    DEBIT_CARD,
    CREDIT_CARD,
    OTHER
}

data class Category(val id: String, val name: String, val isIncome: Boolean = false) {
    override fun toString(): String = name
}

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val accountId: String,
    val amount: Money,
    val type: TransactionType,
    val categoryId: String,
    val note: String = "",
    val timestampMillis: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val billingDateMillis: Long? = null
) {
    init {
        require(amount.minorUnits >= 0) { "Transaction amount must be non-negative" }
        require(categoryId.isNotBlank()) { "Category is required" }
    }
}

object TransactionCategories {
    const val UNCATEGORIZED_EXPENSE = "uncategorized"
    const val UNCATEGORIZED_INCOME = "income_uncategorized"

    val expense = listOf(
        Category("dining", "Dining"),
        Category("groceries", "Groceries"),
        Category("transport", "Transport"),
        Category("utilities", "Utilities"),
        Category("rent", "Rent"),
        Category("health", "Health"),
        Category("fun", "Fun"),
        Category("other_expense", "Other")
    )

    val income = listOf(
        Category("salary", "Salary"),
        Category("freelance", "Freelance"),
        Category("other_income", "Other")
    )
}

fun Transaction.signedMinorUnits(): Long = when (type) {
    TransactionType.INCOME, TransactionType.REFUND, TransactionType.TRANSFER_IN -> amount.minorUnits
    TransactionType.EXPENSE, TransactionType.TRANSFER_OUT -> -amount.minorUnits
    TransactionType.ADJUSTMENT -> amount.minorUnits
}
