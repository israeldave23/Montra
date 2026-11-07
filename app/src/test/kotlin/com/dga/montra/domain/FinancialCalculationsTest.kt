package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FinancialCalculationsTest {
    @Test
    fun `income and expenses use exact minor units`() {
        val transactions = listOf(
            Transaction("e", "a", Money(1250, "USD"), TransactionType.EXPENSE, "food"),
            Transaction("i", "a", Money(5000, "USD"), TransactionType.INCOME, "salary"),
            Transaction("r", "a", Money(250, "USD"), TransactionType.REFUND, "food")
        )
        assertEquals(1250, FinancialCalculations.total(transactions, TransactionType.EXPENSE, "USD"))
        assertEquals(5000, FinancialCalculations.total(transactions, TransactionType.INCOME, "USD"))
        assertEquals(4000, FinancialCalculations.netCashFlow(transactions, "USD"))
    }

    @Test
    fun `currency totals never mix`() {
        val transactions = listOf(
            Transaction("u", "a", Money(100, "USD"), TransactionType.EXPENSE, "food"),
            Transaction("j", "a", Money(1000, "JPY"), TransactionType.EXPENSE, "food")
        )
        assertEquals(100, FinancialCalculations.total(transactions, TransactionType.EXPENSE, "USD"))
        assertEquals(1000, FinancialCalculations.total(transactions, TransactionType.EXPENSE, "JPY"))
    }
}
