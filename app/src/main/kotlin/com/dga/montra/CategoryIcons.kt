package com.dga.montra

import com.dga.montra.domain.TransactionCategories

internal fun categoryIcon(id: String): String = when (id) {
    "dining" -> "🍴"
    "groceries" -> "🛒"
    "transport" -> "🚌"
    "utilities" -> "⚡"
    "rent" -> "⌂"
    "health" -> "+"
    "fun" -> "♪"
    "salary" -> "₱"
    "freelance" -> "✎"
    "transfer" -> "↔"
    else -> "•"
}

/** Human label for a category id, falling back to the raw id so unknown data still renders. */
internal fun categoryLabel(id: String): String =
    TransactionCategories.expense.firstOrNull { it.id == id }?.name
        ?: TransactionCategories.income.firstOrNull { it.id == id }?.name
        ?: id.replaceFirstChar { it.uppercase() }
