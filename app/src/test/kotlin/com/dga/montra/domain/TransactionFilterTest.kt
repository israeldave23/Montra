package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class TransactionFilterTest {
    @Test
    fun `type filters include refunds with income and transfers together`() {
        assertEquals(true, TransactionFilter.INCOME.matches(TransactionType.REFUND))
        assertEquals(false, TransactionFilter.EXPENSE.matches(TransactionType.REFUND))
        assertEquals(true, TransactionFilter.TRANSFER.matches(TransactionType.TRANSFER_OUT))
        assertEquals(true, TransactionFilter.ALL.matches(TransactionType.ADJUSTMENT))
    }

    @Test
    fun `monthly category spending groups currencies and sorts largest first`() {
        val current = Instant.parse("2026-05-11T10:00:00Z").toEpochMilli()
        val old = Instant.parse("2026-04-30T23:59:00Z").toEpochMilli()
        val values = listOf(
            Transaction("a", "a", Money(500, "PHP"), TransactionType.EXPENSE, "dining", timestampMillis = current),
            Transaction("b", "a", Money(900, "PHP"), TransactionType.EXPENSE, "transport", timestampMillis = current),
            Transaction("c", "a", Money(700, "PHP"), TransactionType.EXPENSE, "rent", timestampMillis = old)
        )
        assertEquals(listOf("transport", "dining"), FinancialCalculations.monthlyCategorySpending(values, YearMonth.of(2026, 5), ZoneOffset.UTC)["PHP"]?.keys?.toList())
    }

    @Test
    fun `monthly summary excludes transfers and other months`() {
        val current = Instant.parse("2026-05-11T10:00:00Z").toEpochMilli()
        val old = Instant.parse("2026-04-30T23:59:00Z").toEpochMilli()
        val values = listOf(
            Transaction("i", "a", Money(10000, "PHP"), TransactionType.INCOME, "salary", timestampMillis = current),
            Transaction("e", "a", Money(2500, "PHP"), TransactionType.EXPENSE, "dining", timestampMillis = current),
            Transaction("t", "a", Money(900, "PHP"), TransactionType.TRANSFER_IN, "transfer", timestampMillis = current),
            Transaction("o", "a", Money(1, "PHP"), TransactionType.EXPENSE, "dining", timestampMillis = old)
        )
        assertEquals(10000L to 2500L, FinancialCalculations.monthlySummary(values, YearMonth.of(2026, 5), ZoneOffset.UTC)["PHP"])
    }
}
