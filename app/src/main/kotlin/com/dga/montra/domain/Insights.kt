package com.dga.montra.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Spending inside one category for one currency, with its share of that currency's expenses. */
data class CategorySpend(
    val categoryId: String,
    val minorUnits: Long,
    val transactionCount: Int,
    val sharePercent: Int
)

/** Everything the Insights screen needs for one currency in one month. */
data class CurrencyInsight(
    val currencyCode: String,
    val incomeMinorUnits: Long,
    val expenseMinorUnits: Long,
    val previousExpenseMinorUnits: Long,
    val averageDailyExpenseMinorUnits: Long,
    val projectedExpenseMinorUnits: Long,
    val transactionCount: Int,
    val categories: List<CategorySpend>
) {
    /** Refunds count as money in, matching the dashboard's definition of income. */
    val netMinorUnits: Long get() = incomeMinorUnits - expenseMinorUnits
    val changePercent: Int? get() = percentChange(expenseMinorUnits, previousExpenseMinorUnits)
    val topCategory: CategorySpend? get() = categories.firstOrNull()
}

data class MonthlyInsight(val month: YearMonth, val currencies: List<CurrencyInsight>) {
    val isEmpty: Boolean get() = currencies.isEmpty()
}

/**
 * Deterministic monthly insight: income, expenses, net, per-category shares, comparison with the
 * previous month and a straight-line projection of the current month. Currencies never mix.
 */
object InsightCalculator {
    fun compute(
        month: YearMonth,
        zone: ZoneId,
        transactions: Iterable<Transaction>,
        today: LocalDate
    ): MonthlyInsight {
        val previousMonth = month.minusMonths(1)
        val daysInMonth = month.lengthOfMonth()
        val daysElapsed = when {
            month < YearMonth.from(today) -> daysInMonth
            month > YearMonth.from(today) -> 0
            else -> today.dayOfMonth.coerceIn(1, daysInMonth)
        }

        val buckets = linkedMapOf<String, Bucket>()
        transactions.forEach { transaction ->
            val transactionMonth = YearMonth.from(
                Instant.ofEpochMilli(transaction.timestampMillis).atZone(zone)
            )
            val bucket = buckets.getOrPut(transaction.amount.currencyCode) { Bucket() }
            if (transactionMonth == month) {
                bucket.count++
                when (transaction.type) {
                    TransactionType.INCOME, TransactionType.REFUND ->
                        bucket.income = Math.addExact(bucket.income, transaction.amount.minorUnits)

                    TransactionType.EXPENSE -> {
                        bucket.expense = Math.addExact(bucket.expense, transaction.amount.minorUnits)
                        bucket.categoryTotals[transaction.categoryId] = Math.addExact(
                            bucket.categoryTotals[transaction.categoryId] ?: 0L,
                            transaction.amount.minorUnits
                        )
                        bucket.categoryCounts[transaction.categoryId] =
                            (bucket.categoryCounts[transaction.categoryId] ?: 0) + 1
                    }

                    else -> Unit
                }
            } else if (transactionMonth == previousMonth && transaction.type == TransactionType.EXPENSE) {
                bucket.previousExpense = Math.addExact(bucket.previousExpense, transaction.amount.minorUnits)
            }
        }

        val currencies = buckets.mapNotNull { (currency, bucket) ->
            if (bucket.count == 0) return@mapNotNull null
            val categories = bucket.categoryTotals.entries
                .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                .map { (categoryId, total) ->
                    CategorySpend(
                        categoryId = categoryId,
                        minorUnits = total,
                        transactionCount = bucket.categoryCounts[categoryId] ?: 0,
                        sharePercent = percentOf(total, bucket.expense)
                    )
                }
            val averageDaily = if (daysElapsed > 0) bucket.expense / daysElapsed else 0L
            val projected = when {
                month != YearMonth.from(today) -> bucket.expense
                daysInMonth <= 0 -> bucket.expense
                averageDaily > Long.MAX_VALUE / daysInMonth -> Long.MAX_VALUE
                else -> averageDaily * daysInMonth
            }
            CurrencyInsight(
                currencyCode = currency,
                incomeMinorUnits = bucket.income,
                expenseMinorUnits = bucket.expense,
                previousExpenseMinorUnits = bucket.previousExpense,
                averageDailyExpenseMinorUnits = averageDaily,
                projectedExpenseMinorUnits = projected,
                transactionCount = bucket.count,
                categories = categories
            )
        }.sortedWith(
            compareByDescending<CurrencyInsight> { it.expenseMinorUnits }.thenBy { it.currencyCode }
        )

        return MonthlyInsight(month, currencies)
    }

    private class Bucket {
        var income = 0L
        var expense = 0L
        var previousExpense = 0L
        var count = 0
        val categoryTotals = linkedMapOf<String, Long>()
        val categoryCounts = linkedMapOf<String, Int>()
    }
}
