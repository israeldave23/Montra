package com.dga.montra.domain

/** Run with assertions enabled in a JVM test environment. */
object MoneySelfCheck {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Money.parse("12.34", "USD").minorUnits == 1234L)
        check(Money.parse("-12.34", "USD").minorUnits == -1234L)
        check(Money.parse("0", "USD").minorUnits == 0L)
        check(Money.parse("1200", "JPY").minorUnits == 1200L)
        check(Money.parse("92233720368547758.07", "USD").minorUnits == Long.MAX_VALUE)
        check(runCatching { Money.parse("92233720368547758.08", "USD") }.isFailure)
        check(runCatching { Money.parse("1.001", "USD") }.isFailure)
        check(runCatching { Money.parse("1.2", "JPY") }.isFailure)
        check(runCatching { Money.parse("1.00", "XYZ") }.isFailure)
        val summary = accountSummary(listOf(
            Account("a", "Cash", Money(1000L, "USD")),
            Account("b", "Bank", Money(250L, "USD")),
            Account("c", "Yen", Money(500L, "JPY"))
        ))
        check(summary.balances == mapOf("USD" to 1250L, "JPY" to 500L))
        check(summary.accountCount == 3)
        check(runCatching { accountSummary(listOf(
            Account("a", "Max", Money(Long.MAX_VALUE, "USD")),
            Account("b", "Overflow", Money(1L, "USD"))
        )) }.isFailure)
        val expense = Transaction(accountId = "a", amount = Money(500L, "USD"), type = TransactionType.EXPENSE, categoryId = "food")
        check(expense.signedMinorUnits() == -500L)
        val income = Transaction(accountId = "a", amount = Money(1500L, "USD"), type = TransactionType.INCOME, categoryId = "salary")
        check(income.signedMinorUnits() == 1500L)
        val refund = Transaction(accountId = "a", amount = Money(200L, "USD"), type = TransactionType.REFUND, categoryId = "returns")
        check(refund.signedMinorUnits() == 200L)
    }
}
