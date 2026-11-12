package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth

class BudgetProgressTest {
    private val month = YearMonth.of(2026, 11)

    private fun budget(
        id: String = "b",
        category: String = "dining",
        limit: Long = 100_000L,
        spent: Long = 0L,
        currency: String = "PHP"
    ) = BudgetProgress(
        Budget(id = id, month = month, categoryId = category, limit = Money(limit, currency)),
        spent
    )

    @Test
    fun `used percent floors and caps at one hundred`() {
        assertEquals(0, budget(spent = 0L).usedPercent())
        assertEquals(33, budget(limit = 300L, spent = 100L).usedPercent())
        assertEquals(99, budget(limit = 100_000L, spent = 99_999L).usedPercent())
        assertEquals(100, budget(limit = 100_000L, spent = 100_000L).usedPercent())
        assertEquals(100, budget(limit = 100_000L, spent = 400_000L).usedPercent())
    }

    @Test
    fun `remaining is exact and never clamped`() {
        assertEquals(60_000L, budget(spent = 40_000L).remainingMinorUnits)
        assertEquals(0L, budget(spent = 100_000L).remainingMinorUnits)
        assertEquals(-25_000L, budget(spent = 125_000L).remainingMinorUnits)
        assertFalse(budget(spent = 40_000L).isOver)
        assertTrue(budget(spent = 125_000L).isOver)
    }

    @Test
    fun `state distinguishes on track, near, spent and over`() {
        assertEquals(BudgetState.ON_TRACK, budget(spent = 0L).state)
        assertEquals(BudgetState.ON_TRACK, budget(limit = 100_000L, spent = 79_999L).state)
        assertEquals(BudgetState.NEAR_LIMIT, budget(limit = 100_000L, spent = 80_000L).state)
        assertEquals(BudgetState.AT_LIMIT, budget(limit = 100_000L, spent = 100_000L).state)
        assertEquals(BudgetState.OVER, budget(limit = 100_000L, spent = 100_001L).state)
    }

    @Test
    fun `ordering puts the most at risk first`() {
        val calm = budget(id = "calm", category = "rent", spent = 10_000L)
        val near = budget(id = "near", category = "fun", spent = 90_000L)
        val over = budget(id = "over", category = "dining", spent = 150_000L)
        val spent = budget(id = "spent", category = "health", spent = 100_000L)

        assertEquals(
            listOf("over", "spent", "near", "calm"),
            BudgetOrdering.sorted(listOf(calm, near, over, spent)).map { it.budget.id }
        )
    }

    @Test
    fun `summary keeps currencies apart and counts overruns`() {
        val items = listOf(
            budget(id = "1", category = "dining", limit = 100_000L, spent = 120_000L),
            budget(id = "2", category = "rent", limit = 500_000L, spent = 100_000L),
            budget(id = "3", category = "fun", limit = 5_000L, spent = 1_000L, currency = "USD")
        )
        val summary = BudgetSummary.of(items)

        assertEquals(mapOf("PHP" to 600_000L, "USD" to 5_000L), summary.limitByCurrency)
        assertEquals(mapOf("PHP" to 220_000L, "USD" to 1_000L), summary.spentByCurrency)
        assertEquals(3, summary.count)
        assertEquals(1, summary.overCount)
    }

    @Test
    fun `budget rejects invalid input`() {
        assertTrue(runCatching { budget(limit = 0L) }.isFailure)
        assertTrue(runCatching { budget(category = "  ") }.isFailure)
        assertTrue(runCatching { budget(spent = -1L) }.isFailure)
    }

    @Test
    fun `days left counts the rest of the month`() {
        val today = LocalDate.of(2026, 11, 12)
        assertEquals(18, month.daysLeft(today))
        assertEquals(30, YearMonth.of(2026, 12).daysLeft(today))
        assertEquals(0, YearMonth.of(2026, 10).daysLeft(today))
        assertEquals(0, month.daysLeft(LocalDate.of(2026, 11, 30)))
    }
}
