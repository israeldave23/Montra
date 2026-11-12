package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneOffset

class InsightsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 12)
    private val month = YearMonth.of(2026, 11)

    private fun tx(
        amount: Long,
        currency: String = "PHP",
        type: TransactionType = TransactionType.EXPENSE,
        category: String = "dining",
        date: LocalDate = LocalDate.of(2026, 11, 5)
    ) = Transaction(
        id = "$category-$date-$amount-$currency-$type",
        accountId = "account",
        amount = Money(amount, currency),
        type = type,
        categoryId = category,
        timestampMillis = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    )

    @Test
    fun `currencies are never added together`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(
                tx(50_000L, "PHP", category = "dining"),
                tx(20_000L, "PHP", category = "rent"),
                tx(7_500L, "USD", category = "fun")
            ),
            today
        )

        assertEquals(listOf("PHP", "USD"), insight.currencies.map { it.currencyCode })
        val php = insight.currencies.first { it.currencyCode == "PHP" }
        assertEquals(70_000L, php.expenseMinorUnits)
        val usd = insight.currencies.first { it.currencyCode == "USD" }
        assertEquals(7_500L, usd.expenseMinorUnits)
    }

    @Test
    fun `income and refunds count in, expenses count out`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(
                tx(100_000L, type = TransactionType.INCOME, category = "salary"),
                tx(5_000L, type = TransactionType.REFUND, category = "dining"),
                tx(30_000L, category = "dining"),
                tx(99_999L, type = TransactionType.TRANSFER_OUT, category = "transfer")
            ),
            today
        ).currencies.single()

        assertEquals(105_000L, insight.incomeMinorUnits)
        assertEquals(30_000L, insight.expenseMinorUnits)
        assertEquals(75_000L, insight.netMinorUnits)
        assertEquals(listOf("dining"), insight.categories.map { it.categoryId })
    }

    @Test
    fun `category shares are floored, ordered and never exceed the whole`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(tx(100L, category = "a"), tx(100L, category = "b"), tx(100L, category = "c")),
            today
        ).currencies.single()

        assertEquals(listOf("a", "b", "c"), insight.categories.map { it.categoryId })
        assertEquals(listOf(33, 33, 33), insight.categories.map { it.sharePercent })
        assertTrue(insight.categories.sumOf { it.sharePercent } <= 100)
    }

    @Test
    fun `previous month comparison is per currency`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(
                tx(120_000L, date = LocalDate.of(2026, 11, 3)),
                tx(100_000L, date = LocalDate.of(2026, 10, 3))
            ),
            today
        ).currencies.single()

        assertEquals(100_000L, insight.previousExpenseMinorUnits)
        assertEquals(20, insight.changePercent)
    }

    @Test
    fun `currency with only last month activity is not shown`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(
                tx(10_000L, "PHP", date = LocalDate.of(2026, 11, 2)),
                tx(90_000L, "USD", date = LocalDate.of(2026, 10, 2))
            ),
            today
        )

        assertEquals(listOf("PHP"), insight.currencies.map { it.currencyCode })
        assertNull(insight.currencies.single().changePercent)
    }

    @Test
    fun `empty month has no currencies`() {
        val insight = InsightCalculator.compute(month, zone, emptyList(), today)
        assertTrue(insight.isEmpty)
    }

    @Test
    fun `projection uses the current day of the month`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(tx(12_000L, date = LocalDate.of(2026, 11, 1))),
            today
        ).currencies.single()

        // 12,000 over 12 elapsed days = 1,000 a day; November has 30 days.
        assertEquals(1_000L, insight.averageDailyExpenseMinorUnits)
        assertEquals(30_000L, insight.projectedExpenseMinorUnits)
    }

    @Test
    fun `past months report the actual total instead of a projection`() {
        val past = YearMonth.of(2026, 10)
        val insight = InsightCalculator.compute(
            past,
            zone,
            listOf(tx(9_000L, date = LocalDate.of(2026, 10, 4))),
            today
        ).currencies.single()

        assertEquals(31, past.lengthOfMonth())
        assertEquals(9_000L, insight.expenseMinorUnits)
        assertEquals(9_000L, insight.projectedExpenseMinorUnits)
        assertEquals(290L, insight.averageDailyExpenseMinorUnits)
    }

    @Test
    fun `top category is the largest expense`() {
        val insight = InsightCalculator.compute(
            month,
            zone,
            listOf(
                tx(2_000L, category = "fun"),
                tx(50_000L, category = "rent"),
                tx(10_000L, category = "dining")
            ),
            today
        ).currencies.single()

        assertEquals("rent", insight.topCategory?.categoryId)
        assertEquals(3, insight.transactionCount)
    }
}
