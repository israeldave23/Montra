package com.dga.montra.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * A monthly spending plan for one category in one currency. Budgets are one per
 * (month, category, currency); spending is always measured from transactions, never entered by hand.
 */
data class Budget(
    val id: String,
    val month: YearMonth,
    val categoryId: String,
    val limit: Money
) {
    init {
        require(limit.minorUnits > 0L) { "Budget limit must be positive" }
        require(categoryId.isNotBlank()) { "Category is required" }
    }

    val currencyCode: String get() = limit.currencyCode
}

/** A budget paired with what has actually been spent in its category and currency. */
data class BudgetProgress(val budget: Budget, val spentMinorUnits: Long) {
    init {
        require(spentMinorUnits >= 0L) { "Spent amount must be non-negative" }
    }

    val spent: Money get() = Money(spentMinorUnits, budget.currencyCode)
    val limit: Money get() = budget.limit

    /** Negative when the budget is exceeded; never clamped, so "over by" stays truthful. */
    val remainingMinorUnits: Long get() = budget.limit.minorUnits - spentMinorUnits
    val isOver: Boolean get() = remainingMinorUnits < 0L

    /** Bar value, capped at 100 so the bar cannot overflow. */
    fun usedPercent(): Int = percentOf(spentMinorUnits, budget.limit.minorUnits)

    /** Uncapped ratio used for ordering only (over-budget rows sort first). */
    fun usedPercentUncapped(): Int =
        percentOf(spentMinorUnits, budget.limit.minorUnits, cap = MAX_ORDER_PERCENT)

    val state: BudgetState
        get() = when {
            isOver -> BudgetState.OVER
            spentMinorUnits >= budget.limit.minorUnits -> BudgetState.AT_LIMIT
            usedPercent() >= NEAR_LIMIT_PERCENT -> BudgetState.NEAR_LIMIT
            else -> BudgetState.ON_TRACK
        }

    companion object {
        private const val MAX_ORDER_PERCENT = 9_999
        const val NEAR_LIMIT_PERCENT = 80
    }
}

/** How the month is going for one budget; drives the pill label, colour and bar tint. */
enum class BudgetState { ON_TRACK, NEAR_LIMIT, AT_LIMIT, OVER }

/** Totals per currency; currencies are never combined. */
data class BudgetSummary(
    val limitByCurrency: Map<String, Long>,
    val spentByCurrency: Map<String, Long>,
    val count: Int,
    val overCount: Int
) {
    companion object {
        fun of(items: List<BudgetProgress>): BudgetSummary {
            val limits = mutableMapOf<String, Long>()
            val spent = mutableMapOf<String, Long>()
            items.forEach { item ->
                val currency = item.budget.currencyCode
                limits[currency] = Math.addExact(limits[currency] ?: 0L, item.budget.limit.minorUnits)
                spent[currency] = Math.addExact(spent[currency] ?: 0L, item.spentMinorUnits)
            }
            return BudgetSummary(
                limitByCurrency = limits,
                spentByCurrency = spent,
                count = items.size,
                overCount = items.count { it.isOver }
            )
        }
    }
}

object BudgetOrdering {
    /** Most at-risk first: highest share of the limit already spent, then category id. */
    fun sorted(items: List<BudgetProgress>): List<BudgetProgress> =
        items.sortedWith(
            compareByDescending<BudgetProgress> { it.usedPercentUncapped() }
                .thenBy { it.budget.categoryId }
        )
}

/** Days still available in the month, from [today]; past months have none. */
fun YearMonth.daysLeft(today: LocalDate = LocalDate.now()): Int {
    val current = YearMonth.from(today)
    return when {
        this < current -> 0
        this > current -> lengthOfMonth()
        else -> lengthOfMonth() - today.dayOfMonth
    }
}
