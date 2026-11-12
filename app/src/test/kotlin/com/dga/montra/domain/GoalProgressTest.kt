package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class GoalProgressTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 12)

    private fun goal(
        name: String = "Goal",
        target: Long = 100_000L,
        saved: Long = 0L,
        due: LocalDate? = null
    ) = Goal(
        id = name,
        name = name,
        target = Money(target, "PHP"),
        saved = Money(saved, "PHP"),
        targetDateMillis = due?.atTime(LocalTime.NOON)?.atZone(zone)?.toInstant()?.toEpochMilli()
    )

    @Test
    fun `progress floors and never overstates`() {
        assertEquals(0, goal(saved = 0L).progressPercent())
        assertEquals(33, goal(target = 300L, saved = 100L).progressPercent())
        assertEquals(66, goal(target = 300L, saved = 200L).progressPercent())
        assertEquals(99, goal(target = 100_000L, saved = 99_999L).progressPercent())
        assertEquals(100, goal(target = 100_000L, saved = 100_000L).progressPercent())
        assertEquals(100, goal(target = 100_000L, saved = 400_000L).progressPercent())
    }

    @Test
    fun `remaining and completion are consistent`() {
        val partial = goal(target = 100_000L, saved = 40_000L)
        assertEquals(60_000L, partial.remainingMinorUnits)
        assertEquals(false, partial.isComplete)

        val over = goal(target = 100_000L, saved = 120_000L)
        assertEquals(0L, over.remainingMinorUnits)
        assertEquals(true, over.isComplete)
    }

    @Test
    fun `status follows the target date boundaries`() {
        assertEquals(GoalStatus.COMPLETED, goal(saved = 100_000L, due = today).status(today, zone))
        assertEquals(GoalStatus.OVERDUE, goal(due = today.minusDays(1)).status(today, zone))
        assertEquals(GoalStatus.DUE_SOON, goal(due = today.plusDays(14)).status(today, zone))
        assertEquals(GoalStatus.TRACKING, goal(due = today.plusDays(15)).status(today, zone))
        assertEquals(GoalStatus.OPEN_ENDED, goal(due = null).status(today, zone))
    }

    @Test
    fun `monthly pace spreads the remainder over whole months`() {
        // 80 days left => 3 months => 900.00 / 3 = 300.00
        val due = LocalDate.of(2027, 1, 31)
        assertEquals(30_000L, goal(target = 90_000L, saved = 0L, due = due).requiredMonthlyMinorUnits(today, zone))
        assertNull(goal(due = null).requiredMonthlyMinorUnits(today, zone))
        assertNull(goal(due = today.minusDays(3)).requiredMonthlyMinorUnits(today, zone))
        assertEquals(0L, goal(saved = 100_000L, due = due).requiredMonthlyMinorUnits(today, zone))
    }

    @Test
    fun `summary keeps currencies apart and counts completion`() {
        val goals = listOf(
            goal(name = "Peso goal", target = 100_000L, saved = 25_000L),
            goal(name = "Done", target = 50_000L, saved = 50_000L),
            Goal("usd", "Dollar goal", Money(10_000L, "USD"), Money(2_500L, "USD"))
        )
        val summary = GoalSummary.of(goals)

        assertEquals(mapOf("PHP" to 75_000L, "USD" to 2_500L), summary.savedByCurrency)
        assertEquals(mapOf("PHP" to 150_000L, "USD" to 10_000L), summary.targetByCurrency)
        assertEquals(mapOf("PHP" to 75_000L, "USD" to 7_500L), summary.remainingByCurrency)
        assertEquals(3, summary.goalCount)
        assertEquals(1, summary.completedCount)
    }

    @Test
    fun `ordering puts soonest active first and completed last`() {
        val soon = goal(name = "Soon", due = today.plusDays(5))
        val sooner = goal(name = "Sooner", due = today.plusDays(2))
        val undated = goal(name = "Undated", due = null)
        val done = goal(name = "Done", saved = 100_000L, due = today.plusDays(1))

        assertEquals(
            listOf("Sooner", "Soon", "Undated", "Done"),
            GoalOrdering.sorted(listOf(undated, done, soon, sooner), today, zone).map { it.name }
        )
    }

    @Test
    fun `goal rejects invalid amounts`() {
        assertEquals(true, runCatching { goal(target = 0L) }.isFailure)
        assertEquals(true, runCatching { goal(saved = -1L) }.isFailure)
        assertEquals(
            true,
            runCatching { Goal("x", "Mismatch", Money(100L, "PHP"), Money(100L, "USD")) }.isFailure
        )
    }

    @Test
    fun `money plain string round trips through parse`() {
        assertEquals("1234.50", Money(123_450L, "USD").plainString())
        assertEquals("1200", Money(1_200L, "JPY").plainString())
        assertEquals(Money(123_450L, "USD"), Money.parse(Money(123_450L, "USD").plainString(), "USD"))
        assertEquals(Money(1_200L, "JPY"), Money.parse(Money(1_200L, "JPY").plainString(), "JPY"))
    }
}
