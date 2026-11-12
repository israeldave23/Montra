package com.dga.montra.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A savings target. Goals record intent only: adding or withdrawing funds here never moves money
 * between accounts. Amounts always share one currency and use exact minor units.
 */
data class Goal(
    val id: String,
    val name: String,
    val target: Money,
    val saved: Money,
    val targetDateMillis: Long? = null
) {
    init {
        require(target.minorUnits > 0L) { "Goal target must be positive" }
        require(saved.minorUnits >= 0L) { "Saved amount must be non-negative" }
        require(target.currencyCode == saved.currencyCode) { "Goal amounts must share a currency" }
    }

    val currencyCode: String get() = target.currencyCode

    val isComplete: Boolean get() = saved.minorUnits >= target.minorUnits

    /** Never negative: an over-funded goal has nothing left to save. */
    val remainingMinorUnits: Long get() = (target.minorUnits - saved.minorUnits).coerceAtLeast(0L)

    /** Whole percent, floored, capped at 100 so progress is never overstated. */
    fun progressPercent(): Int = percentOf(saved.minorUnits, target.minorUnits)

    fun dueDate(zone: ZoneId): LocalDate? = targetDateMillis?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
    }

    fun status(today: LocalDate, zone: ZoneId, dueSoonDays: Long = DUE_SOON_DAYS): GoalStatus {
        if (isComplete) return GoalStatus.COMPLETED
        val due = dueDate(zone) ?: return GoalStatus.OPEN_ENDED
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        return when {
            daysLeft < 0L -> GoalStatus.OVERDUE
            daysLeft <= dueSoonDays -> GoalStatus.DUE_SOON
            else -> GoalStatus.TRACKING
        }
    }

    /**
     * Rough monthly amount that would hit the date. Whole minor units, rounded down so the guidance
     * never overstates how much is already covered. Null when there is no date or the date has passed.
     */
    fun requiredMonthlyMinorUnits(today: LocalDate, zone: ZoneId): Long? {
        if (isComplete) return 0L
        val due = dueDate(zone) ?: return null
        val daysLeft = ChronoUnit.DAYS.between(today, due)
        if (daysLeft < 0L) return null
        val months = ((daysLeft + DAYS_PER_MONTH - 1L) / DAYS_PER_MONTH).coerceAtLeast(1L)
        return remainingMinorUnits / months
    }

    companion object {
        const val DUE_SOON_DAYS = 14L
        private const val DAYS_PER_MONTH = 30L
    }
}

enum class GoalStatus { COMPLETED, OVERDUE, DUE_SOON, TRACKING, OPEN_ENDED }

/** Totals that never combine currencies. */
data class GoalSummary(
    val savedByCurrency: Map<String, Long>,
    val targetByCurrency: Map<String, Long>,
    val goalCount: Int,
    val completedCount: Int
) {
    val remainingByCurrency: Map<String, Long>
        get() = targetByCurrency.mapValues { (currency, target) ->
            (target - (savedByCurrency[currency] ?: 0L)).coerceAtLeast(0L)
        }

    companion object {
        fun of(goals: List<Goal>): GoalSummary {
            val saved = mutableMapOf<String, Long>()
            val target = mutableMapOf<String, Long>()
            goals.forEach { goal ->
                val currency = goal.currencyCode
                saved[currency] = Math.addExact(saved[currency] ?: 0L, goal.saved.minorUnits)
                target[currency] = Math.addExact(target[currency] ?: 0L, goal.target.minorUnits)
            }
            return GoalSummary(
                savedByCurrency = saved,
                targetByCurrency = target,
                goalCount = goals.size,
                completedCount = goals.count { it.isComplete }
            )
        }
    }
}

object GoalOrdering {
    /** Active goals first (soonest date first, then undated), completed last; stable tie-breaks. */
    fun sorted(goals: List<Goal>, today: LocalDate, zone: ZoneId): List<Goal> =
        goals.sortedWith(
            compareBy<Goal> { if (it.isComplete) 1 else 0 }
                .thenBy { dueRank(it, today, zone) }
                .thenByDescending { it.progressPercent() }
                .thenBy { it.name.lowercase() }
        )

    private fun dueRank(goal: Goal, today: LocalDate, zone: ZoneId): Long = when {
        goal.isComplete -> Long.MAX_VALUE
        else -> goal.dueDate(zone)?.let { ChronoUnit.DAYS.between(today, it) } ?: Long.MAX_VALUE
    }
}
