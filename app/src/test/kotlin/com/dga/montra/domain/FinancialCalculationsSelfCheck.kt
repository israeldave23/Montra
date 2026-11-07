package com.dga.montra.domain

import java.time.YearMonth
import java.time.ZoneOffset

object FinancialCalculationsSelfCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        val baseTime = 1_700_000_000_000L
        val expense1 = Transaction("a1", "acc1", Money(1000L, "USD"), TransactionType.EXPENSE, "food", "lunch", baseTime)
        val expense2 = Transaction("a2", "acc1", Money(500L, "USD"), TransactionType.EXPENSE, "dining", "dinner", baseTime + 86_400_000)
        val income = Transaction("a3", "acc1", Money(5000L, "USD"), TransactionType.INCOME, "salary", "", baseTime + 1_000_000)
        val refund = Transaction("a4", "acc1", Money(200L, "USD"), TransactionType.REFUND, "returns", "", baseTime + 2_000_000)
        val transferOut = Transaction("a5", "acc1", Money(300L, "USD"), TransactionType.TRANSFER_OUT, "transfer", "", baseTime + 3_000_000)
        val transferIn = Transaction("a6", "acc2", Money(300L, "USD"), TransactionType.TRANSFER_IN, "transfer", "", baseTime + 3_000_000)
        val all = listOf(expense1, expense2, income, refund, transferOut, transferIn)

        // total EXPENSE = 1000 + 500 = 1500
        check(FinancialCalculations.total(all, TransactionType.EXPENSE, "USD") == 1500L) { "expense total wrong" }
        // total INCOME = 5000
        check(FinancialCalculations.total(all, TransactionType.INCOME, "USD") == 5000L) { "income total wrong" }
        // categorySpending: food=1000, dining=500
        val cat = FinancialCalculations.categorySpending(all, "USD")
        check(cat["food"] == 1000L) { "food wrong" }
        check(cat["dining"] == 500L) { "dining wrong" }

        // netCashFlow: expenses -1500, income +5000, refund +200, transfers excluded
        val ncf = FinancialCalculations.netCashFlow(all, "USD")
        check(ncf == 3700L) { "net cash flow wrong: $ncf" }

        // monthlyComparison for month of baseTime
        val ym = YearMonth.of(2023, 11)
        val zone = ZoneOffset.UTC
        val (cur, prev) = FinancialCalculations.monthlyComparison(all, ym, zone, "USD")
        check(cur == 1500L) { "current month spending wrong: $cur" }
        check(prev == 0L) { "previous month should be 0: $prev" }

        // currency filtering: JPY transactions don't mix with USD
        val jpyExp = Transaction("j1", "acc3", Money(1000L, "JPY"), TransactionType.EXPENSE, "food", "", baseTime)
        val mixed = all + listOf(jpyExp)
        check(FinancialCalculations.total(mixed, TransactionType.EXPENSE, "USD") == 1500L) { "currency filter wrong" }

        println("All FinancialCalculations checks passed.")
    }
}
