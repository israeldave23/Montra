package com.dga.montra.domain

// Refunds belong with income; adjustments remain visible under All only.
enum class TransactionFilter {
    ALL, INCOME, EXPENSE, TRANSFER;

    fun matches(type: TransactionType): Boolean = when (this) {
        ALL -> true
        INCOME -> type == TransactionType.INCOME || type == TransactionType.REFUND
        EXPENSE -> type == TransactionType.EXPENSE
        TRANSFER -> type == TransactionType.TRANSFER_IN || type == TransactionType.TRANSFER_OUT
    }
}
