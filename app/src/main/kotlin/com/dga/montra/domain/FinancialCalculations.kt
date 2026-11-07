package com.dga.montra.domain

import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

/** Deterministic totals. Transfers excluded from income/expense; all inputs must share currency. */
object FinancialCalculations {
    fun total(transactions: Iterable<Transaction>, type: TransactionType, currency: String): Long {
        var sum = 0L
        for (transaction in transactions) {
            if (transaction.type == type && transaction.amount.currencyCode == currency) {
                sum = Math.addExact(sum, transaction.amount.minorUnits)
            }
        }
        return sum
    }

    fun netCashFlow(transactions: Iterable<Transaction>, currency: String): Long {
        var sum = 0L
        for (transaction in transactions) {
            if (transaction.amount.currencyCode == currency &&
                transaction.type in setOf(TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.REFUND)) {
                sum = Math.addExact(sum, transaction.signedMinorUnits())
            }
        }
        return sum
    }

    fun categorySpending(transactions: Iterable<Transaction>, currency: String): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        for (transaction in transactions) {
            if (transaction.type == TransactionType.EXPENSE && transaction.amount.currencyCode == currency) {
                result[transaction.categoryId] = Math.addExact(
                    result[transaction.categoryId] ?: 0L,
                    transaction.amount.minorUnits
                )
            }
        }
        return result
    }

    fun monthlyComparison(
        transactions: Iterable<Transaction>,
        month: YearMonth,
        zone: ZoneId,
        currency: String
    ): Pair<Long, Long> {
        val previous = month.minusMonths(1)
        var currentTotal = 0L
        var previousTotal = 0L
        for (transaction in transactions) {
            if (transaction.type != TransactionType.EXPENSE || transaction.amount.currencyCode != currency) continue
            val txMonth = Instant.ofEpochMilli(transaction.timestampMillis).atZone(zone).let { YearMonth.from(it) }
            if (txMonth == month) currentTotal = Math.addExact(currentTotal, transaction.amount.minorUnits)
            if (txMonth == previous) previousTotal = Math.addExact(previousTotal, transaction.amount.minorUnits)
        }
        return currentTotal to previousTotal
    }
}
