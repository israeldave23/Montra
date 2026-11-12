package com.dga.montra

import com.dga.montra.domain.TransactionCategories

/**
 * Vector icons for categories, accounts and actions.
 *
 * Every icon is a two-tone 24dp vector tinted at the call site, so the same file works in both themes
 * and on every tonal surface.
 */
internal fun categoryIconRes(id: String): Int = when (id) {
    "dining" -> R.drawable.ic_cat_dining
    "groceries" -> R.drawable.ic_cat_groceries
    "transport" -> R.drawable.ic_cat_transport
    "utilities" -> R.drawable.ic_cat_utilities
    "rent" -> R.drawable.ic_cat_rent
    "health" -> R.drawable.ic_cat_health
    "fun" -> R.drawable.ic_cat_fun
    "salary" -> R.drawable.ic_cat_salary
    "freelance" -> R.drawable.ic_cat_freelance
    "other_income", "income_uncategorized" -> R.drawable.ic_cat_coin
    "transfer" -> R.drawable.ic_cat_transfer
    else -> R.drawable.ic_cat_other
}

/** Human label for a category id, falling back to the raw id so unknown data still renders. */
internal fun categoryLabel(id: String): String =
    TransactionCategories.expense.firstOrNull { it.id == id }?.name
        ?: TransactionCategories.income.firstOrNull { it.id == id }?.name
        ?: id.replaceFirstChar { it.uppercase() }
