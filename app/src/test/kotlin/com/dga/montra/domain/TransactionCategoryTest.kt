package com.dga.montra.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategoryTest {

    @Test
    fun expenseCategoriesExcludeIncome() {
        val ids = TransactionCategories.expense.map { it.id }.toSet()
        assertEquals(setOf("dining", "groceries", "transport", "utilities", "rent", "health", "fun", "other_expense"), ids)
        assertTrue(ids.disjointWith(TransactionCategories.income.map { it.id }.toSet()))
    }

    @Test
    fun incomeCategoriesContainSalary() {
        val ids = TransactionCategories.income.map { it.id }.toSet()
        assertTrue("salary" in ids)
        assertTrue("freelance" in ids)
        assertTrue("other_income" in ids)
    }

    @Test
    fun uncategorizedConstantsMatchLegacyStrings() {
        assertEquals("uncategorized", TransactionCategories.UNCATEGORIZED_EXPENSE)
        assertEquals("income_uncategorized", TransactionCategories.UNCATEGORIZED_INCOME)
    }

    @Test
    fun displayNameMapsToCategoryName() {
        val dining = TransactionCategories.expense.first { it.id == "dining" }
        assertEquals("Dining", dining.name)
    }
}
