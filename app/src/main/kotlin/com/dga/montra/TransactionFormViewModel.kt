package com.dga.montra

import androidx.lifecycle.ViewModel
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.TransactionType

class TransactionFormViewModel : ViewModel() {
    var type: TransactionType = TransactionType.EXPENSE
        private set
    val categories get() = if (type == TransactionType.INCOME) TransactionCategories.income else TransactionCategories.expense
    var categoryId: String = TransactionCategories.expense.first().id
        private set

    fun selectType(value: TransactionType): Boolean {
        if (value == type) return false
        type = value
        categoryId = categories.first().id
        return true
    }

    fun reset() {
        type = TransactionType.EXPENSE
        categoryId = TransactionCategories.expense.first().id
    }

    fun selectCategory(id: String) {
        require(categories.any { it.id == id }) { "Invalid category for transaction type" }
        categoryId = id
    }
}
